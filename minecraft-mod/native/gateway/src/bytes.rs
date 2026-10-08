use crate::GatewayError;
use p2wlan_mc_runtime_port::FramePermit;
use subtle::ConstantTimeEq;
use tokio::net::TcpStream;

/// No raw-byte accessor: the final local socket handoff must retain and check
/// every original receive admission, including the authenticated crypto key.
pub struct ReceivedBytes {
    bytes: Vec<u8>,
    offset: usize,
    permits: Vec<FramePermit>,
}
impl ReceivedBytes {
    pub(crate) fn new(bytes: Vec<u8>, permits: Vec<FramePermit>) -> Self {
        Self {
            bytes,
            offset: 0,
            permits,
        }
    }
    pub fn remaining(&self) -> usize {
        self.bytes.len() - self.offset
    }
    pub fn is_empty(&self) -> bool {
        self.remaining() == 0
    }
    pub fn validate(&self) -> Result<(), GatewayError> {
        for permit in &self.permits {
            permit.validate()?;
        }
        Ok(())
    }
    /// Used only to consume a fixed remote-service prelude, never game data.
    pub(crate) fn checked_matches(&self, expected: &[u8]) -> Result<bool, GatewayError> {
        self.validate()?;
        Ok(bool::from(self.bytes[self.offset..].ct_eq(expected)))
    }
    /// There is no await between validation and the actual socket syscall.
    /// Readiness waits belong to the bounded connection driver, not this buffer.
    pub fn try_write(&mut self, socket: &TcpStream) -> Result<usize, GatewayError> {
        if self.is_empty() {
            return Ok(0);
        }
        self.validate()?;
        let count = socket
            .try_write(&self.bytes[self.offset..])
            .map_err(|error| {
                if error.kind() == std::io::ErrorKind::WouldBlock {
                    GatewayError::WouldBlock
                } else {
                    GatewayError::Io(error)
                }
            })?;
        if count == 0 {
            return Err(GatewayError::Closed);
        }
        self.offset += count;
        Ok(count)
    }
}
