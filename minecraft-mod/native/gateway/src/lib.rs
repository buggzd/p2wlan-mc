//! Bounded local/game TCP gateway; networking identities remain upstream-owned.
#![forbid(unsafe_code)]
mod bytes;
mod connection;
mod local;
mod owner;
mod receive;
mod registry;
mod service;
mod wire;

pub use bytes::ReceivedBytes;
pub use connection::{BridgeProgress, ConnectionBridge, BRIDGE_IDLE_TIMEOUT};
pub use local::{LocalKey, LocalTarget, LOCAL_HANDSHAKE_TIMEOUT, LOCAL_PRELUDE_BYTES};
pub use owner::{GatewayFailure, GatewayStatus, TcpGateway, PACKET_WAIT_TIMEOUT};
pub use receive::{RangeAdmission, ReceiveWindow, MAX_RECEIVE_SPANS};
pub use registry::{HostedService, JoinProxy, RegistrationId, MAX_REGISTRATIONS};
pub use service::{ServiceIdentity, SERVICE_HANDSHAKE_BYTES, SERVICE_HANDSHAKE_TIMEOUT};
pub use wire::TcpFrame;

pub const CHUNK_BYTES: usize = 8 * 1024;
pub const RECEIVE_WINDOW: usize = p2wlan_mc_bridge::BUFFER_BYTES;

#[derive(Debug, thiserror::Error)]
pub enum GatewayError {
    #[error(transparent)]
    Admission(#[from] p2wlan_mc_runtime_port::AdmissionError),
    #[error(transparent)]
    Stack(#[from] p2wlan_mc_bridge::StackError),
    #[error(transparent)]
    Packet(#[from] p2wlan_mc_bridge::PacketError),
    #[error("GATEWAY_ENTROPY_UNAVAILABLE")]
    Entropy,
    #[error("GATEWAY_LOCAL_TIMEOUT")]
    LocalTimeout,
    #[error("GATEWAY_LOCAL_KEY_MISMATCH")]
    LocalKeyMismatch,
    #[error("GATEWAY_IDENTITY_EXHAUSTED")]
    IdentityExhausted,
    #[error("GATEWAY_INVALID_SERVICE_IDENTITY")]
    InvalidServiceIdentity,
    #[error("GATEWAY_SERVICE_IDENTITY_MISMATCH")]
    ServiceIdentityMismatch,
    #[error("GATEWAY_SERVICE_TIMEOUT")]
    ServiceTimeout,
    #[error("GATEWAY_STALE_REGISTRATION")]
    StaleRegistration,
    #[error("GATEWAY_TASK_FAILED")]
    TaskFailed,
    #[error("GATEWAY_WRONG_TARGET")]
    WrongTarget,
    #[error("GATEWAY_IDLE_TIMEOUT")]
    IdleTimeout,
    #[error("GATEWAY_INVALID_PACKET")]
    InvalidPacket,
    #[error("GATEWAY_UNSUPPORTED_PACKET")]
    UnsupportedPacket,
    #[error("GATEWAY_BAD_CHECKSUM")]
    BadChecksum,
    #[error("GATEWAY_BUFFER_CAPACITY")]
    Capacity,
    #[error("GATEWAY_MISSING_RECEIVE_ADMISSION")]
    MissingAdmission,
    #[error("GATEWAY_RECEIVE_WINDOW_POISONED")]
    Poisoned,
    #[error("GATEWAY_WOULD_BLOCK")]
    WouldBlock,
    #[error("GATEWAY_SOCKET_CLOSED")]
    Closed,
    #[error("GATEWAY_IO")]
    Io(#[source] std::io::Error),
}

#[cfg(test)]
#[allow(dead_code)]
#[path = "../tests/common/mod.rs"]
mod test_support;
