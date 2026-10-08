use crate::{GatewayError, ReceivedBytes, CHUNK_BYTES, RECEIVE_WINDOW};
use p2wlan_mc_runtime_port::{FramePermit, PeerGrant};

pub const MAX_RECEIVE_SPANS: usize = 128;
#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum RangeAdmission {
    Recorded,
    Obsolete,
    OutsideWindow,
    Empty,
}

struct Span {
    start: i32,
    end: i32,
    permit: FramePermit,
}

/// Owns only the admission metadata of unread stream bytes, not a second TCP
/// state machine. The single stack owner supplies the SYN-established sequence
/// origin and each actual read count. Out-of-order and overlapping payloads
/// conservatively retain all original capabilities that could cover a read.
pub struct ReceiveWindow {
    connection: PeerGrant,
    next_sequence: u32,
    spans: Vec<Span>,
    poisoned: bool,
}
impl ReceiveWindow {
    pub fn new(connection: PeerGrant, first_sequence: u32) -> Result<Self, GatewayError> {
        connection.validate()?;
        Ok(Self {
            connection,
            next_sequence: first_sequence,
            spans: Vec::with_capacity(MAX_RECEIVE_SPANS),
            poisoned: false,
        })
    }
    pub fn retained_spans(&self) -> usize {
        self.spans.len()
    }
    pub fn record(
        &mut self,
        sequence: u32,
        bytes: usize,
        permit: &FramePermit,
    ) -> Result<RangeAdmission, GatewayError> {
        if self.poisoned {
            return Err(GatewayError::Poisoned);
        }
        if bytes == 0 {
            return Ok(RangeAdmission::Empty);
        }
        if bytes > RECEIVE_WINDOW {
            return Err(GatewayError::Capacity);
        }
        // Only a 32 KiB receive window is admitted, so signed modular distance
        // is unambiguous even when the 32-bit TCP sequence space wraps.
        let start = sequence.wrapping_sub(self.next_sequence) as i32 as i64;
        let end = start + bytes as i64;
        if end <= 0 {
            return Ok(RangeAdmission::Obsolete);
        }
        if start >= RECEIVE_WINDOW as i64 || end > RECEIVE_WINDOW as i64 {
            return Ok(RangeAdmission::OutsideWindow);
        }
        let mut start = start.max(0) as i32;
        let mut end = end as i32;
        let permit = permit.bind_received_connection(&self.connection)?;
        let mut merged = [false; MAX_RECEIVE_SPANS];
        let mut count = 0;
        // spans remain sorted and equal-capability intervals are already
        // coalesced; expanding this one interval needs no unbounded retry.
        for (index, span) in self.spans.iter().enumerate() {
            if span.permit == permit && span.start <= end && span.end >= start {
                start = start.min(span.start);
                end = end.max(span.end);
                merged[index] = true;
                count += 1;
            }
        }
        if self.spans.len() - count >= MAX_RECEIVE_SPANS {
            return Err(GatewayError::Capacity);
        }
        let mut index = 0;
        self.spans.retain(|_| {
            let keep = !merged[index];
            index += 1;
            keep
        });
        self.spans.push(Span { start, end, permit });
        self.spans.sort_by_key(|span| span.start);
        Ok(RangeAdmission::Recorded)
    }
    /// Call only with bytes actually removed from the owned TCP stack. A failed
    /// seal is terminal: those bytes cannot be read again or newly authorized.
    pub fn seal(&mut self, bytes: Vec<u8>) -> Result<ReceivedBytes, GatewayError> {
        if self.poisoned {
            return Err(GatewayError::Poisoned);
        }
        self.poisoned = true;
        if bytes.is_empty() || bytes.len() > CHUNK_BYTES {
            return Err(GatewayError::Capacity);
        }
        self.connection.validate()?;
        let count = bytes.len() as i32;
        let mut covered = 0;
        let mut permits = Vec::with_capacity(self.spans.len());
        for span in self
            .spans
            .iter()
            .filter(|span| span.start < count && span.end > 0)
        {
            if span.start > covered {
                return Err(GatewayError::MissingAdmission);
            }
            covered = covered.max(span.end.min(count));
            if !permits.contains(&span.permit) {
                permits.push(span.permit.clone());
            }
        }
        if covered < count {
            return Err(GatewayError::MissingAdmission);
        }
        self.next_sequence = self.next_sequence.wrapping_add(bytes.len() as u32);
        self.spans.retain_mut(|span| {
            span.end -= count;
            span.start = (span.start - count).max(0);
            span.end > 0
        });
        self.poisoned = false;
        Ok(ReceivedBytes::new(bytes, permits))
    }
}
