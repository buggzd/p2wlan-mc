//! Build-only packet port. All network ownership stays in the upstream Daemon.
use async_trait::async_trait;
use p2pnet_tun::{Error, Result as TunResult, TunDevice, VirtualInterface};
use tokio::sync::mpsc;
use tokio::time::{timeout, Duration};

use crate::Config;

#[path = "mc_admission.rs"]
mod admission;
#[path = "mc_port.rs"]
mod port;
pub use admission::{
    AdmissionError, ApplicationAuthority, AuthorizedPeers, FramePermit, MtuView, PeerGrant,
    PeerView, ScopeOwner, ScopeToken, MAX_ROOM_PEERS, MAX_SCOPES,
};
pub use port::{FencedPacket, FencedPacketEndpoint, PacketGate};

pub struct PacketContext {
    pub config: Config,
    pub authority: ApplicationAuthority,
}
pub type AuthorizedPacketFactory =
    Box<dyn FnOnce(PacketContext) -> crate::Result<PacketInterface> + Send>;
pub(crate) enum RuntimePacketFactory {
    Raw(PacketFactory),
    Authorized(AuthorizedPacketFactory),
}

pub use p2wlan_mc_bridge::{PACKET_CAPACITY, PACKET_MTU};
pub type PacketFactory = Box<dyn FnOnce(&Config) -> crate::Result<PacketInterface> + Send>;

/// The application side of a bounded raw-IP interface, not a second transport.
pub struct PacketEndpoint {
    pub incoming: mpsc::Receiver<Vec<u8>>,
    pub outgoing: mpsc::Sender<Vec<u8>>,
    pub address: String,
}

pub struct PacketInterface {
    backend: Backend,
}

enum Backend {
    System(TunDevice),
    Authorized {
        incoming: mpsc::Receiver<FencedPacket>,
        gate: std::sync::Arc<PacketGate>,
        address: String,
        _lifetime: ScopeOwner,
    },
    Userspace {
        incoming: mpsc::Receiver<Vec<u8>>,
        outgoing: mpsc::Sender<Vec<u8>>,
        address: String,
    },
}

impl PacketInterface {
    pub fn system(device: TunDevice) -> Self {
        Self {
            backend: Backend::System(device),
        }
    }

    pub fn authorized_pair(context: PacketContext) -> crate::Result<(Self, FencedPacketEndpoint)> {
        if context.config.network.mtu != PACKET_MTU as u32 {
            return Err(crate::DaemonError::Config(
                "packet_interface_mtu_mismatch".into(),
            ));
        }
        if context.config.network.virtual_ip != context.authority.address().to_string() {
            return Err(crate::DaemonError::Config(
                "packet_interface_address_mismatch".into(),
            ));
        }
        let (incoming, gate, endpoint, lifetime) = PacketGate::pair(context.authority);
        Ok((
            Self {
                backend: Backend::Authorized {
                    incoming,
                    gate,
                    address: context.config.network.virtual_ip,
                    _lifetime: lifetime,
                },
            },
            endpoint,
        ))
    }

    pub fn application_gate(&self) -> Option<std::sync::Arc<PacketGate>> {
        match &self.backend {
            Backend::Authorized { gate, .. } => Some(gate.clone()),
            _ => None,
        }
    }

    pub fn pair(config: &Config) -> crate::Result<(Self, PacketEndpoint)> {
        if config.network.mtu != PACKET_MTU as u32 {
            return Err(crate::DaemonError::Config(
                "packet_interface_mtu_mismatch".into(),
            ));
        }
        let (to_core, core_read) = mpsc::channel(PACKET_CAPACITY);
        let (to_stack, stack_read) = mpsc::channel(PACKET_CAPACITY);
        Ok((
            Self {
                backend: Backend::Userspace {
                    incoming: core_read,
                    outgoing: to_stack,
                    address: config.network.virtual_ip.clone(),
                },
            },
            PacketEndpoint {
                incoming: stack_read,
                outgoing: to_core,
                address: config.network.virtual_ip.clone(),
            },
        ))
    }
}

#[async_trait]
impl VirtualInterface for PacketInterface {
    async fn read(&mut self, buffer: &mut [u8]) -> TunResult<usize> {
        match &mut self.backend {
            Backend::System(device) => device.read(buffer).await,
            Backend::Authorized { incoming, gate, .. } => {
                let packet = incoming.recv().await.ok_or(Error::DeviceClosed)?;
                if let Err(reason) = packet.validate() {
                    gate.record_drop(reason);
                    return Ok(0);
                }
                let length = packet.bytes.len();
                if length > buffer.len() {
                    return Err(Error::PacketTooShort(buffer.len(), length));
                }
                if let Err(reason) = gate.capture_outbound(packet.permit) {
                    gate.record_drop(reason);
                    return Ok(0);
                }
                buffer[..length].copy_from_slice(&packet.bytes);
                Ok(length)
            }
            Backend::Userspace { incoming, .. } => {
                let packet = incoming.recv().await.ok_or(Error::DeviceClosed)?;
                if packet.len() > PACKET_MTU || packet.len() > buffer.len() {
                    return Err(Error::PacketTooShort(buffer.len(), packet.len()));
                }
                let length = packet.len();
                buffer[..length].copy_from_slice(&packet);
                Ok(length)
            }
        }
    }

    async fn write(&mut self, buffer: &[u8]) -> TunResult<usize> {
        match &mut self.backend {
            Backend::System(device) => device.write(buffer).await,
            Backend::Authorized { gate, .. } => {
                // Business receive uses the authenticated original envelope in
                // deliver_inbound, never a generic raw-IP interface write.
                gate.record_drop(AdmissionError::UnsupportedLocalFeedback);
                Err(Error::SendBufferFull)
            }
            Backend::Userspace { outgoing, .. } => {
                if buffer.len() > PACKET_MTU {
                    return Err(Error::PacketTooShort(PACKET_MTU, buffer.len()));
                }
                timeout(Duration::from_millis(250), outgoing.send(buffer.to_vec()))
                    .await
                    .map_err(|_| Error::SendBufferFull)?
                    .map_err(|_| Error::DeviceClosed)?;
                Ok(buffer.len())
            }
        }
    }

    fn name(&self) -> &str {
        match &self.backend {
            Backend::System(device) => device.name(),
            Backend::Userspace { .. } | Backend::Authorized { .. } => "p2wlan-mc",
        }
    }
    fn mtu(&self) -> u32 {
        match &self.backend {
            Backend::System(device) => device.mtu(),
            Backend::Userspace { .. } | Backend::Authorized { .. } => PACKET_MTU as u32,
        }
    }
    fn address(&self) -> &str {
        match &self.backend {
            Backend::System(device) => device.address(),
            Backend::Userspace { address, .. } | Backend::Authorized { address, .. } => address,
        }
    }
    fn is_up(&self) -> bool {
        match &self.backend {
            Backend::System(device) => device.is_up(),
            Backend::Userspace { outgoing, .. } => !outgoing.is_closed(),
            Backend::Authorized { gate, .. } => gate.is_up(),
        }
    }
}
