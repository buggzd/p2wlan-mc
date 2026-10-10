//! Bounded RTT display history, embedded in the existing PathHealth owner.
//! No probes, worker, timer, lock, path choice or Java-poll-derived samples.
use std::collections::VecDeque;
use std::time::{Duration, Instant};

pub const WINDOW_MS: u64 = 60_000;
pub const CAPACITY: usize = 256;

#[derive(Debug, Clone, Default)]
pub struct LatencyWindow {
    samples: VecDeque<(Instant, u64)>,
    last_sample: Option<Instant>,
    last_capacity_drop: Option<Instant>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LatencySummary {
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
impl LatencyWindow {
    pub fn clear(&mut self) {
        *self = Self::default();
    }
    pub fn record(&mut self, latency: Duration, now: Instant) {
        // Monotonic owner clock. A backwards test clock starts a new window.
        if self.last_sample.is_some_and(|last| now < last) {
            self.clear();
        }
        self.samples.retain(|(at, _)| {
            now.saturating_duration_since(*at) <= Duration::from_millis(WINDOW_MS)
        });
        if self.samples.len() == CAPACITY {
            if let Some((at, _)) = self.samples.pop_front() {
                self.last_capacity_drop = Some(at);
            }
        }
        self.samples
            .push_back((now, latency.as_micros().min(i64::MAX as u128) as u64));
        self.last_sample = Some(now);
    }
    pub fn summary(&self, now: Instant) -> LatencySummary {
        let mut values: Vec<_> = self
            .samples
            .iter()
            .filter(|(at, _)| {
                now >= *at && now.duration_since(*at) <= Duration::from_millis(WINDOW_MS)
            })
            .map(|(_, value)| *value)
            .collect();
        values.sort_unstable();
        let samples = values.len();
        // Nearest rank (inverse empirical CDF): sorted[ceil(p * n) - 1].
        // Empty populations remain unknown; identical RTTs are distinct samples.
        let percentile = |p: usize| {
            if samples == 0 {
                None
            } else {
                values.get((p * samples).div_ceil(100) - 1).copied()
            }
        };
        LatencySummary {
            samples,
            capacity_limited: self.last_capacity_drop.is_some_and(|at| {
                now >= at && now.duration_since(at) <= Duration::from_millis(WINDOW_MS)
            }),
            last_sample_age_ms: self
                .last_sample
                .filter(|at| now >= *at)
                .map(|at| now.duration_since(at).as_millis().min(i64::MAX as u128) as u64),
            min_us: values.first().copied(),
            avg_us: if samples == 0 {
                None
            } else {
                Some((values.iter().map(|v| *v as u128).sum::<u128>() / samples as u128) as u64)
            },
            max_us: values.last().copied(),
            p50_us: percentile(50),
            p90_us: percentile(90),
            p95_us: percentile(95),
            p99_us: percentile(99),
        }
    }
}
