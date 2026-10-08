//! Bounded, privilege-free TCP/IP endpoint for the P2WLAN IP dataplane.
//!
//! A single caller owns the stack and drives time and packet handoff. This
//! module does not select paths, create OS interfaces, or interpret MC packets.
#![forbid(unsafe_code)]

mod device;
mod stack;

pub use device::{PacketError, PACKET_CAPACITY, PACKET_MTU};
pub use stack::{ConnectionId, ReadResult, StackError, TcpStack, BUFFER_BYTES, MAX_CONNECTIONS};
