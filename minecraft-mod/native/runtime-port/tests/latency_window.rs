// Exercise the exact pure overlay used by the generated runtime owner.
#[path = "../../runtime-overlay/mc_latency.rs"]
mod mc_latency;
use mc_latency::{LatencyWindow, CAPACITY, WINDOW_MS};
use std::time::{Duration, Instant};

#[test]
fn nearest_rank_and_mean_use_each_timed_sample_once() {
    let at = Instant::now();
    let mut window = LatencyWindow::default();
    assert_eq!(window.summary(at).min_us, None);
    for ms in 1..=100 {
        window.record(Duration::from_millis(ms), at);
    }
    let summary = window.summary(at);
    assert_eq!(
        (
            summary.samples,
            summary.min_us,
            summary.avg_us,
            summary.max_us
        ),
        (100, Some(1000), Some(50500), Some(100000))
    );
    assert_eq!(
        (
            summary.p50_us,
            summary.p90_us,
            summary.p95_us,
            summary.p99_us
        ),
        (Some(50000), Some(90000), Some(95000), Some(99000))
    );
    for _ in 0..100 {
        assert_eq!(window.summary(at), summary);
    } // polling is read-only
    window.record(Duration::from_millis(100), at);
    assert_eq!(window.summary(at).samples, 101); // equal RTT != duplicate
}
#[test]
fn expiry_capacity_and_lifecycle_are_bounded_and_explicit() {
    let at = Instant::now();
    let mut window = LatencyWindow::default();
    window.record(Duration::ZERO, at);
    assert_eq!(window.summary(at).avg_us, Some(0));
    assert_eq!(
        window
            .summary(at + Duration::from_millis(WINDOW_MS))
            .samples,
        1
    );
    assert_eq!(
        window
            .summary(at + Duration::from_millis(WINDOW_MS + 1))
            .samples,
        0
    );
    for n in 0..CAPACITY + 8 {
        window.record(Duration::from_micros(n as u64), at);
    }
    let summary = window.summary(at);
    assert_eq!(summary.samples, CAPACITY);
    assert!(summary.capacity_limited);
    assert_eq!(summary.min_us, Some(8));
    assert!(
        !window
            .summary(at + Duration::from_millis(WINDOW_MS + 1))
            .capacity_limited
    );
    window.clear();
    assert_eq!(window.summary(at).last_sample_age_ms, None);
}
#[test]
fn singleton_unsorted_submillisecond_and_extreme_samples() {
    let at = Instant::now();
    let mut window = LatencyWindow::default();
    window.record(Duration::from_micros(123), at);
    assert_eq!(window.summary(at).p99_us, Some(123));
    window.record(Duration::from_micros(2), at);
    window.record(Duration::from_micros(20), at);
    assert_eq!(window.summary(at).p50_us, Some(20));
    window.clear();
    for _ in 0..CAPACITY {
        window.record(Duration::MAX, at);
    }
    assert_eq!(window.summary(at).avg_us, Some(i64::MAX as u64));
    window.record(Duration::from_millis(1), at - Duration::from_millis(1));
    assert_eq!(window.summary(at).samples, 1); // backwards clock cannot retain future entries
}
