package io.p2wlan.mc.common;

import java.util.ArrayList;
import java.util.List;

/** Pure formatting of fenced display snapshots; never starts probes or samples. */
public final class TelemetryPresentation {
    private TelemetryPresentation() {}
    public static List<String> hud(DisplayPreferences settings, PeerTelemetry peer, long now) {
        if (!settings.hudVisible()) return List.of();
        boolean fresh = peer != null && peer.fresh(now);
        var lines = new ArrayList<String>();
        lines.add("P2WLAN · RTT " + (peer == null ? "待测量" : peer.latencyLabel(now)) + " · " + (fresh ? peer.pathLabel() : "待更新"));
        if (settings.detail() == DisplayPreferences.Detail.BASIC) return List.copyOf(lines);
        LatencyStatistics stats = peer != null && peer.statisticsCurrent(now) ? peer.statistics() : null;
        lines.add("Min / Avg / Max " + (stats == null ? "— / — / —" : LatencyStatistics.ms(stats.minUs()) + " / " + LatencyStatistics.ms(stats.avgUs()) + " / " + LatencyStatistics.ms(stats.maxUs())) + " ms");
        if (settings.detail() == DisplayPreferences.Detail.ADVANCED) {
            lines.add("P50 / P90 " + (stats == null ? "— / —" : LatencyStatistics.ms(stats.p50Us()) + " / " + LatencyStatistics.ms(stats.p90Us())) + " ms");
            lines.add("P95 / P99 " + (stats == null ? "— / —" : LatencyStatistics.ms(stats.p95Us()) + " / " + LatencyStatistics.ms(stats.p99Us())) + " ms");
            lines.add("MTU " + (fresh && peer.overlayBudget() != null ? peer.overlayBudget() + " B" : "—") + " · ↑" + (fresh ? PeerTelemetry.bytes(peer.bytesSent()) : "—") + " ↓" + (fresh ? PeerTelemetry.bytes(peer.bytesReceived()) : "—"));
        }
        lines.add("Jitter " + (peer != null && peer.recentRtt(now) && peer.jitterMs() != null ? peer.jitterMs() + " ms" : "—")
                + " · 60s n=" + (stats == null ? "—" : stats.samples() + (stats.capacityLimited() ? " (截断)" : "")));
        return List.copyOf(lines);
    }
}
