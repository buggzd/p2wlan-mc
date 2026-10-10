//! Read-only, try-lock telemetry from the existing connection owner. No cache,
//! probe, writer queue or path decision; contention is represented by None.
use super::{NetworkPath, PeerManager};
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct PeerMetrics {
    pub label: String,
    pub virtual_ip: String,
    pub latency_ms: Option<u64>,
    pub jitter_ms: Option<u64>,
    pub last_success_age_ms: Option<u64>,
    pub bytes_sent: u64,
    pub bytes_received: u64,
}
impl PeerManager {
    pub(crate) fn mc_peer_metrics(
        &self,
        peer: &str,
        virtual_ip: &str,
        path: Option<NetworkPath>,
    ) -> Option<PeerMetrics> {
        let connections = self.connections.try_read().ok()?;
        let connection = connections.get(peer)?;
        if connection.virtual_ip != virtual_ip || connection.active_path() != path {
            return None;
        }
        let health = match path {
            Some(NetworkPath::Direct) => Some(&connection.direct_health),
            Some(NetworkPath::Relay) => Some(&connection.relay_health),
            None => None,
        };
        let age = health
            .and_then(|value| value.last_success_at)
            .map(|value| value.elapsed().as_millis().min(i64::MAX as u128) as u64);
        let fresh = age.is_some_and(|value| value <= 15_000);
        // The registration's explicit display label is UI metadata, not a host
        // lookup. No physical endpoint, secret, credential or candidate is copied.
        let mut label = String::new();
        for value in connection
            .device_name
            .chars()
            .filter(|value| !value.is_control() && *value != '§')
        {
            if label.len() + value.len_utf8() > 128 {
                break;
            }
            label.push(value);
        }
        Some(PeerMetrics {
            label,
            virtual_ip: connection.virtual_ip.clone(),
            latency_ms: health.filter(|_| fresh).and_then(|value| value.latency_ms),
            jitter_ms: health.filter(|_| fresh).and_then(|value| value.jitter_ms),
            last_success_age_ms: age,
            bytes_sent: connection.bytes_sent.min(i64::MAX as u64),
            bytes_received: connection.bytes_received.min(i64::MAX as u64),
        })
    }
}
