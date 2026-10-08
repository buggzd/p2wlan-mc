use std::time::Duration;

use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::time::{timeout_at, Instant};

use crate::{parse_object, ProtocolError, MAX_FRAME_BYTES};

pub const FRAME_TIMEOUT: Duration = Duration::from_secs(2);

/// A dropped `next` future retains header/body progress and the original deadline.
/// Invalid, timed-out or truncated streams are terminal; never resynchronize them.
pub struct FrameReader<R> {
    input: R,
    header: [u8; 4],
    header_used: usize,
    body: Vec<u8>,
    body_used: usize,
    deadline: Option<Instant>,
    poisoned: bool,
}

impl<R: AsyncRead + Unpin> FrameReader<R> {
    pub fn new(input: R) -> Self {
        Self {
            input,
            header: [0; 4],
            header_used: 0,
            body: Vec::new(),
            body_used: 0,
            deadline: None,
            poisoned: false,
        }
    }

    pub async fn next(&mut self) -> Result<Option<Value>, ProtocolError> {
        if self.poisoned {
            return Err(ProtocolError::Poisoned);
        }
        let result = self.next_inner().await;
        if result.is_err() || result.as_ref().is_ok_and(Option::is_none) {
            self.poisoned = true;
        }
        result
    }

    async fn next_inner(&mut self) -> Result<Option<Value>, ProtocolError> {
        // Waiting for a new frame is idle, not a partial-frame timeout. The
        // process/session owner supplies its own handshake and heartbeat limit.
        if self.header_used == 0 {
            let count = self
                .input
                .read(&mut self.header[..1])
                .await
                .map_err(|_| ProtocolError::Io)?;
            if count == 0 {
                return Ok(None);
            }
            self.header_used = count;
            self.deadline = Some(Instant::now() + FRAME_TIMEOUT);
        }
        let deadline = self.deadline.ok_or(ProtocolError::Poisoned)?;
        while self.header_used < 4 {
            let count = timeout_at(
                deadline,
                self.input.read(&mut self.header[self.header_used..]),
            )
            .await
            .map_err(|_| ProtocolError::Timeout)?
            .map_err(|_| ProtocolError::Io)?;
            if count == 0 {
                return Err(ProtocolError::TruncatedFrame);
            }
            self.header_used += count;
        }
        if self.body.is_empty() {
            let length = u32::from_be_bytes(self.header) as usize;
            if length == 0 || length > MAX_FRAME_BYTES {
                return Err(ProtocolError::FrameSize);
            }
            self.body.resize(length, 0);
        }
        while self.body_used < self.body.len() {
            let count = timeout_at(deadline, self.input.read(&mut self.body[self.body_used..]))
                .await
                .map_err(|_| ProtocolError::Timeout)?
                .map_err(|_| ProtocolError::Io)?;
            if count == 0 {
                return Err(ProtocolError::TruncatedFrame);
            }
            self.body_used += count;
        }
        let object = parse_object(&self.body)?;
        self.header_used = 0;
        self.body.clear();
        self.body_used = 0;
        self.deadline = None;
        Ok(Some(object))
    }
}

pub fn encode_frame(object: &Value) -> Result<Vec<u8>, ProtocolError> {
    crate::json::validate_value(object)?;
    let body = serde_json::to_vec(object).map_err(|_| ProtocolError::InvalidJson)?;
    if body.is_empty() || body.len() > MAX_FRAME_BYTES {
        return Err(ProtocolError::FrameSize);
    }
    let mut frame = Vec::with_capacity(body.len() + 4);
    frame.extend_from_slice(&(body.len() as u32).to_be_bytes());
    frame.extend_from_slice(&body);
    Ok(frame)
}

/// A cancelled/failed write leaves this writer poisoned. Appending another
/// frame to a partially written one is forbidden, not retried as a new request.
pub struct FrameWriter<W> {
    output: W,
    poisoned: bool,
}

impl<W: AsyncWrite + Unpin> FrameWriter<W> {
    pub fn new(output: W) -> Self {
        Self {
            output,
            poisoned: false,
        }
    }

    pub async fn write(&mut self, object: &Value) -> Result<(), ProtocolError> {
        if self.poisoned {
            return Err(ProtocolError::Poisoned);
        }
        let frame = encode_frame(object)?;
        self.poisoned = true;
        timeout_at(Instant::now() + FRAME_TIMEOUT, async {
            self.output.write_all(&frame).await?;
            self.output.flush().await
        })
        .await
        .map_err(|_| ProtocolError::Timeout)?
        .map_err(|_| ProtocolError::Io)?;
        self.poisoned = false;
        Ok(())
    }
}
