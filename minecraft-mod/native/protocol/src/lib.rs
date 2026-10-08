//! Bounded Java/native IPC. Frame progress belongs to the codec, not a future.
#![forbid(unsafe_code)]

mod frames;
mod json;

pub use frames::{encode_frame, FrameReader, FrameWriter, FRAME_TIMEOUT};
pub use json::parse_object;

pub const VERSION: u32 = 1;
pub const MAX_FRAME_BYTES: usize = 64 * 1024;
pub const MAX_DEPTH: usize = 16;
pub const MAX_ITEMS: usize = 128;
pub const MAX_NODES: usize = 4096;

#[derive(Debug, Clone, Copy, thiserror::Error, PartialEq, Eq)]
pub enum ProtocolError {
    #[error("FRAME_SIZE")]
    FrameSize,
    #[error("TRUNCATED_FRAME")]
    TruncatedFrame,
    #[error("INVALID_UTF8")]
    InvalidUtf8,
    #[error("INVALID_JSON")]
    InvalidJson,
    #[error("DEPTH")]
    Depth,
    #[error("CAPACITY")]
    Capacity,
    #[error("FRAME_TIMEOUT")]
    Timeout,
    #[error("IO")]
    Io,
    #[error("STREAM_POISONED")]
    Poisoned,
}
