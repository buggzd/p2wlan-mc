//! Bounded local/game TCP gateway; networking identities remain upstream-owned.
#![forbid(unsafe_code)]
mod bytes;
mod connection;
mod receive;
mod wire;

pub use bytes::ReceivedBytes;
pub use connection::{BridgeProgress, ConnectionBridge, BRIDGE_IDLE_TIMEOUT};
pub use receive::{RangeAdmission, ReceiveWindow, MAX_RECEIVE_SPANS};
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
