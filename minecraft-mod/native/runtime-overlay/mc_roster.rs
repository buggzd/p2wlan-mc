//! Read-only bounded projection inside the original room owner. No await or
//! independent membership store; the generated module alone sees private data.
use super::RoomAuthorization;
use crate::mc_embed::{AdmissionError, MAX_ROOM_PEERS};
use std::sync::atomic::Ordering;
use std::time::Instant;

impl RoomAuthorization {
    pub(crate) fn mc_authorized_peers(
        &self,
        local_ip: &str,
    ) -> Result<Vec<String>, AdmissionError> {
        if !self.enabled {
            return Err(AdmissionError::RoomRequired);
        }
        // A UI request must not wait on a poisoned/contended room owner.
        let locked = self
            .snapshot
            .try_lock()
            .map_err(|_| AdmissionError::Contended)?;
        let snapshot = locked.as_ref().ok_or(AdmissionError::RoomRevoked)?;
        if snapshot.local_ip != local_ip || !snapshot.live.load(Ordering::Acquire) {
            return Err(AdmissionError::RoomRevoked);
        }
        if snapshot.expires_at <= Instant::now() {
            return Err(AdmissionError::RoomLeaseExpired);
        }
        if snapshot.peers.len() > MAX_ROOM_PEERS {
            return Err(AdmissionError::RosterCapacity);
        }
        let mut peers = Vec::with_capacity(snapshot.peers.len());
        for id in snapshot.peers.keys() {
            if id.is_empty() || id.len() > 256 || id.chars().any(char::is_control) {
                return Err(AdmissionError::RosterInvalid);
            }
            peers.push(id.clone());
        }
        peers.sort_unstable();
        // Copying selection metadata must not extend an expired lease.
        if snapshot.expires_at <= Instant::now() {
            return Err(AdmissionError::RoomLeaseExpired);
        }
        Ok(peers)
    }
}
