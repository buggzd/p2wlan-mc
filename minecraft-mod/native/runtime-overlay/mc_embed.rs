//! Build-only packet port. All network ownership stays in the upstream Daemon.
use async_trait::async_trait;
use p2pnet_tun::{Error, Result as TunResult, TunDevice, VirtualInterface};
use tokio::sync::mpsc;
use tokio::time::{timeout, Duration};

use crate::Config;

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
            Backend::Userspace { .. } => "p2wlan-mc",
        }
    }
    fn mtu(&self) -> u32 {
        match &self.backend {
            Backend::System(device) => device.mtu(),
            Backend::Userspace { .. } => PACKET_MTU as u32,
        }
    }
    fn address(&self) -> &str {
        match &self.backend {
            Backend::System(device) => device.address(),
            Backend::Userspace { address, .. } => address,
        }
    }
    fn is_up(&self) -> bool {
        match &self.backend {
            Backend::System(device) => device.is_up(),
            Backend::Userspace { outgoing, .. } => !outgoing.is_closed(),
        }
    }
}
