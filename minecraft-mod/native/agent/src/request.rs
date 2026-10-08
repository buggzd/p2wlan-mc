use crate::{AgentError, Identity};
use serde_json::{Map, Value};

pub struct Request {
    pub id: i64,
    pub operation: String,
    pub arguments: Map<String, Value>,
}
impl Request {
    pub fn parse(value: Value, owner: &Identity) -> Result<Self, AgentError> {
        let Value::Object(mut object) = value else {
            return Err(AgentError::InvalidRequest);
        };
        if object.len() != 5
            || object.get("version").and_then(Value::as_u64) != Some(1)
            || object.get("owner").and_then(Value::as_str) != Some(owner.as_str())
        {
            return Err(AgentError::InvalidRequest);
        }
        let id = object
            .get("id")
            .and_then(Value::as_i64)
            .filter(|id| *id > 0)
            .ok_or(AgentError::InvalidRequest)?;
        let operation = object
            .get("op")
            .and_then(Value::as_str)
            .filter(|op| {
                !op.is_empty()
                    && op.len() <= 48
                    && op
                        .bytes()
                        .all(|byte| byte.is_ascii_lowercase() || byte == b'_')
            })
            .ok_or(AgentError::InvalidRequest)?
            .to_string();
        let Some(Value::Object(arguments)) = object.remove("args") else {
            return Err(AgentError::InvalidRequest);
        };
        Ok(Self {
            id,
            operation,
            arguments,
        })
    }
}

/// Completed/requested IDs compact into non-evicting intervals. Encoding may
/// reorder Java submissions or skip an invalid request ID, so numeric ordering
/// is not assumed. At most 128 disjoint ranges; capacity is terminal, not replay.
#[derive(Default)]
pub struct RequestLedger {
    ranges: Vec<(i64, i64)>,
}
impl RequestLedger {
    pub fn admit(&mut self, id: i64) -> Result<(), AgentError> {
        if id <= 0 {
            return Err(AgentError::InvalidRequest);
        }
        let at = self.ranges.partition_point(|(_, end)| *end < id);
        if self.ranges.get(at).is_some_and(|(start, _)| *start <= id) {
            return Err(AgentError::DuplicateRequest);
        }
        let previous = at > 0 && self.ranges[at - 1].1.checked_add(1) == Some(id);
        let next = self
            .ranges
            .get(at)
            .is_some_and(|(start, _)| id.checked_add(1) == Some(*start));
        match (previous, next) {
            (true, true) => {
                let end = self.ranges.remove(at).1;
                self.ranges[at - 1].1 = end;
            }
            (true, false) => self.ranges[at - 1].1 = id,
            (false, true) => self.ranges[at].0 = id,
            (false, false) => {
                if self.ranges.len() >= 128 {
                    return Err(AgentError::RequestHistoryCapacity);
                }
                self.ranges.insert(at, (id, id));
            }
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unordered_ids_compact_without_replay_or_eviction() {
        let mut ledger = RequestLedger::default();
        for id in [4, 2, 1, 3, 7, 6, 5, i64::MAX, i64::MAX - 1] {
            ledger.admit(id).unwrap();
        }
        assert_eq!(ledger.ranges, [(1, 7), (i64::MAX - 1, i64::MAX)]);
        assert_eq!(ledger.admit(4).unwrap_err(), AgentError::DuplicateRequest);
        assert_eq!(ledger.admit(0).unwrap_err(), AgentError::InvalidRequest);
        let mut bounded = RequestLedger::default();
        for id in 0..128 {
            bounded.admit(id * 2 + 1).unwrap();
        }
        assert_eq!(
            bounded.admit(257).unwrap_err(),
            AgentError::RequestHistoryCapacity
        );
        assert_eq!(bounded.admit(1).unwrap_err(), AgentError::DuplicateRequest);
        for id in 1..128 {
            bounded.admit(id * 2).unwrap();
        }
        bounded.admit(257).unwrap();
        assert_eq!(bounded.ranges, [(1, 255), (257, 257)]);
    }
}
