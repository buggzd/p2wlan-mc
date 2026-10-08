use crate::{ConnectionBridge, GatewayError, LocalKey, LocalTarget};
use p2wlan_mc_bridge::ConnectionId;
use p2wlan_mc_runtime_port::{PeerGrant, ScopeOwner};
use std::net::SocketAddrV4;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::AbortHandle;

static NEXT_REGISTRATION: AtomicU64 = AtomicU64::new(1);
pub const MAX_REGISTRATIONS: usize = 8;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct RegistrationId(u64);
impl RegistrationId {
    pub(crate) fn allocate() -> Result<Self, GatewayError> {
        NEXT_REGISTRATION
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| {
                value.checked_add(1)
            })
            .map(Self)
            .map_err(|_| GatewayError::IdentityExhausted)
    }
}

pub struct HostedService {
    pub id: RegistrationId,
    pub virtual_address: SocketAddrV4,
}
/// Immutable endpoint capability. Stopping this registration makes the old
/// snapshot unusable, even if another listener obtains the same OS port.
pub struct JoinProxy {
    pub id: RegistrationId,
    pub local_address: SocketAddrV4,
    pub key: LocalKey,
}

pub(crate) struct Registration {
    pub(crate) lifetime: ScopeOwner,
    pub(crate) kind: RegistrationKind,
}
pub(crate) enum RegistrationKind {
    Host {
        target: LocalTarget,
        port: u16,
        listener: Option<ConnectionId>,
    },
    Proxy {
        listener: TcpListener,
        key: LocalKey,
        remote: SocketAddrV4,
        grant: Box<PeerGrant>,
    },
}
pub(crate) struct Flow {
    pub(crate) registration: RegistrationId,
    pub(crate) local: SocketAddrV4,
    pub(crate) remote: SocketAddrV4,
    pub(crate) bridge: ConnectionBridge,
    pub(crate) connect: Option<AbortHandle>,
}
pub(crate) struct PendingLocal {
    pub(crate) registration: RegistrationId,
    pub(crate) socket: TcpStream,
    pub(crate) prelude: crate::local::LocalPrelude,
}
