//! Read-only, try-lock telemetry from the existing connection owner. No cache,
//! probe, writer queue or path decision; contention is represented by None.
use super::{CommittedBusinessPathSnapshot, NetworkPath, PeerManager};
use std::time::Instant;

#[derive(Debug, Serialize)]
pub struct LatencyStatistics {
    pub window_ms: u64,
    pub capacity: usize,
    pub samples: usize,
    pub capacity_limited: bool,
    pub last_sample_age_ms: Option<u64>,
    pub min_us: Option<u64>,
    pub avg_us: Option<u64>,
    pub max_us: Option<u64>,
    pub p50_us: Option<u64>,
    pub p90_us: Option<u64>,
    pub p95_us: Option<u64>,
    pub p99_us: Option<u64>,
}
impl From<super::mc_latency::LatencySummary> for LatencyStatistics {
    fn from(value: super::mc_latency::LatencySummary) -> Self {
        Self {
            window_ms: super::mc_latency::WINDOW_MS,
            capacity: super::mc_latency::CAPACITY,
            samples: value.samples,
            capacity_limited: value.capacity_limited,
            last_sample_age_ms: value.last_sample_age_ms,
            min_us: value.min_us,
            avg_us: value.avg_us,
            max_us: value.max_us,
            p50_us: value.p50_us,
            p90_us: value.p90_us,
            p95_us: value.p95_us,
            p99_us: value.p99_us,
        }
    }
}
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct PeerMetrics {
    pub label: String,
    pub virtual_ip: String,
    pub latency_ms: Option<u64>,
    pub jitter_ms: Option<u64>,
    pub latency_stats: Option<LatencyStatistics>,
    pub last_success_age_ms: Option<u64>,
    pub bytes_sent: u64,
    pub bytes_received: u64,
}
impl PeerManager {
    pub(crate) fn mc_peer_metrics(
        &self,
        peer: &str,
        expected: &CommittedBusinessPathSnapshot,
    ) -> Option<PeerMetrics> {
        let connections = self.connections.try_read().ok()?;
        let connection = connections.get(peer)?;
        let state = connection.path_state_snapshot().state;
        if connection.virtual_ip != expected.virtual_ip
            || state.epoch != expected.epoch
            || state.active != expected.active
            || state.lifecycle != expected.lifecycle
        {
            return None;
        }
        let health = match expected.active_path() {
            Some(NetworkPath::Direct) => Some(&connection.direct_health),
            Some(NetworkPath::Relay) => Some(&connection.relay_health),
            None => None,
        };
        let age = health
            .and_then(|value| value.last_success_at)
            .map(|value| value.elapsed().as_millis().min(i64::MAX as u128) as u64);
        let statistics =
            health.map(|value| LatencyStatistics::from(value.mc_latency.summary(Instant::now())));
        let fresh = statistics
            .as_ref()
            .and_then(|value| value.last_sample_age_ms)
            .is_some_and(|value| value <= 15_000);
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
            latency_stats: statistics,
            last_success_age_ms: age,
            bytes_sent: connection.bytes_sent.min(i64::MAX as u64),
            bytes_received: connection.bytes_received.min(i64::MAX as u64),
        })
    }
}
