package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Immutable read-only measurement. No path choice, probing or send permission.
 * Missing/contended/stale samples are unknown, never invented zero latency. */
public record PeerTelemetry(AgentReply evidence, RuntimeView runtime, String peer, String generation,
        String session, boolean online, String path, String label, String virtualIp, Long latencyMs,
        Long jitterMs, Long lastSuccessAgeMs, Long bytesSent, Long bytesReceived, Integer overlayBudget, LatencyStatistics statistics, long observedAt) {
    public static final long FRESH_NANOS = 5_000_000_000L;
    private static final Set<String> VIEW_FIELDS = Set.of("network_generation", "peer_session", "remote_candidate_epoch",
            "path_revision", "udp_publication_owner", "online", "path", "direct_validation_owned", "direct_validation_request_bound",
            "direct_pair_present", "direct_pair_local_bound", "direct_pair_epoch_current", "direct_pair_local_matches_primary",
            "direct_pair_local_matches_ipv6", "direct_outer_ipv6", "direct_validation_epoch_current", "direct_budget_ready", "mtu", "metrics");
    private static final Set<String> MTU_FIELDS = Set.of("network_generation", "peer_session", "remote_candidate_epoch", "state",
            "revision", "base_confirmed", "overlay_budget", "probes", "successes", "timeouts", "send_failures", "live_worker");
    public static PeerTelemetry parse(AgentReply reply, RuntimeView runtime, String peer, long now) throws AgentException {
        if (reply == null || runtime == null || !GameService.validPeer(peer) || !reply.owner().equals(runtime.owner())) throw invalid();
        JsonObject data = reply.data();
        if (!data.keySet().equals(Set.of("runtime", "peer", "view")) || !runtime.runtime().equals(RuntimeView.text(data, "runtime"))
                || !peer.equals(RuntimeView.text(data, "peer")) || !data.get("view").isJsonObject()) throw invalid();
        JsonObject view = data.getAsJsonObject("view");
        if (!view.keySet().equals(VIEW_FIELDS)) throw invalid();
        String generation = decimal(view, "network_generation"), session = decimal(view, "peer_session");
        for (String key : Set.of("remote_candidate_epoch", "path_revision", "udp_publication_owner")) {
            if (!view.get(key).isJsonNull()) decimal(view, key);
        }
        for (String key : Set.of("direct_validation_owned", "direct_validation_request_bound", "direct_pair_present",
                "direct_pair_local_bound", "direct_pair_epoch_current", "direct_pair_local_matches_primary",
                "direct_pair_local_matches_ipv6", "direct_outer_ipv6", "direct_validation_epoch_current")) bool(view, key);
        if (!view.get("direct_budget_ready").isJsonNull()) bool(view, "direct_budget_ready");
        JsonElement live = view.get("online");
        if (live == null || !live.isJsonPrimitive() || !live.getAsJsonPrimitive().isBoolean()) throw invalid();
        boolean online = live.getAsBoolean(); String path = RuntimeView.text(view, "path");
        if (!view.get("path").isJsonNull() && (path == null || !Set.of("direct", "relay").contains(path))) throw invalid();
        String label = "", ip = ""; Long latency = null, jitter = null, age = null, tx = null, rx = null;
        LatencyStatistics statistics = null;
        JsonElement raw = view.get("metrics");
        if (raw != null && !raw.isJsonNull()) {
            if (!raw.isJsonObject()) throw invalid();
            JsonObject metrics = raw.getAsJsonObject();
            if (!metrics.keySet().equals(Set.of("label", "virtual_ip", "latency_ms", "jitter_ms", "last_success_age_ms", "bytes_sent", "bytes_received", "latency_stats"))) throw invalid();
            label = text(metrics, "label", 128, true); ip = text(metrics, "virtual_ip", 15, false);
            if (!ipv4(ip)) throw invalid();
            latency = number(metrics, "latency_ms"); jitter = number(metrics, "jitter_ms"); age = number(metrics, "last_success_age_ms");
            tx = number(metrics, "bytes_sent"); rx = number(metrics, "bytes_received");
            if (tx == null || rx == null) throw invalid();
            var stats = metrics.get("latency_stats");
            if (!stats.isJsonNull()) {
                if (!stats.isJsonObject()) throw invalid();
                statistics = LatencyStatistics.parse(stats.getAsJsonObject());
            }
        }
        Integer budget = null;
        raw = view.get("mtu");
        if (raw != null && !raw.isJsonNull()) {
            if (!raw.isJsonObject()) throw invalid();
            JsonObject mtu = raw.getAsJsonObject();
            if (!mtu.keySet().equals(MTU_FIELDS)) throw invalid();
            for (String key : Set.of("network_generation", "peer_session", "remote_candidate_epoch")) {
                if (!mtu.get(key).isJsonNull()) decimal(mtu, key);
            }
            decimal(mtu, "revision"); bool(mtu, "base_confirmed"); bool(mtu, "live_worker");
            String state = RuntimeView.text(mtu, "state");
            if (state == null || !Set.of("disabled", "unsupported", "base", "searching", "search_complete", "error").contains(state)) throw invalid();
            for (String key : Set.of("probes", "successes", "timeouts", "send_failures")) if (number(mtu, key) == null) throw invalid();
            Long value = number(mtu, "overlay_budget");
            if (value != null && value > 65535) throw invalid();
            String candidate = RuntimeView.text(view, "remote_candidate_epoch");
            if ("direct".equals(path) && bool(mtu, "base_confirmed")
                    && !view.get("direct_budget_ready").isJsonNull() && bool(view, "direct_budget_ready")
                    && generation.equals(RuntimeView.text(mtu, "network_generation")) && session.equals(RuntimeView.text(mtu, "peer_session"))
                    && candidate != null && candidate.equals(RuntimeView.text(mtu, "remote_candidate_epoch"))) {
                budget = value == null ? null : value.intValue();
            }
        }
        if (!online || path == null || statistics == null || statistics.samples() == 0 || statistics.lastSampleAgeMs() == null || statistics.lastSampleAgeMs() > 15_000) { latency = null; jitter = null; }
        return new PeerTelemetry(reply, runtime, peer, generation, session, online, path, label, ip, latency, jitter, age, tx, rx, budget, statistics, now);
    }
    private static boolean bool(JsonObject value, String key) throws AgentException {
        JsonElement raw = value.get(key);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) throw invalid();
        return raw.getAsBoolean();
    }
    private static String decimal(JsonObject value, String key) throws AgentException {
        String result = RuntimeView.text(value, key);
        if (result == null || !result.matches("0|[1-9][0-9]{0,19}") || new BigInteger(result).bitLength() > 64) throw invalid();
        return result;
    }
    private static String text(JsonObject value, String key, int maximum, boolean empty) throws AgentException {
        String result = RuntimeView.text(value, key);
        if (result == null || !empty && result.isEmpty() || result.getBytes(StandardCharsets.UTF_8).length > maximum
                || result.indexOf('§') >= 0 || result.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return result;
    }
    static Long number(JsonObject object, String key) throws AgentException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value.getAsString()); } catch (NumberFormatException invalid) { throw invalid(); }
    }
    private static boolean ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        return true;
    }
    public boolean fresh(long now) { return now >= observedAt && now - observedAt <= FRESH_NANOS; }
    public String name() { return label.isBlank() || label.startsWith("node-") ? "Minecraft 设备" : label; }
    public String pathLabel() { return !online ? "离线" : path == null ? "连接中" : path.equals("direct") ? "直连" : "中继"; }
    public boolean recentRtt(long now) {
        return fresh(now) && latencyMs != null && statistics != null && statistics.lastSampleAgeMs() != null && statistics.lastSampleAgeMs() <= 15_000
                && (now - observedAt) / 1_000_000 <= 15_000 - statistics.lastSampleAgeMs();
    }
    public String latencyLabel(long now) { return !fresh(now) ? "待更新" : !recentRtt(now) ? "未测得" : latencyMs == 0 ? "<1 ms" : latencyMs + " ms"; }
    public boolean statisticsCurrent(long now) {
        return fresh(now) && online && path != null && statistics != null
                && (statistics.samples() == 0 || statistics.lastSampleAgeMs() != null
                && (now - observedAt) / 1_000_000 <= LatencyStatistics.WINDOW_MS - statistics.lastSampleAgeMs());
    }
    public String tooltip(long now) {
        return name() + "\n虚拟地址：" + (virtualIp.isEmpty() ? "待分配" : virtualIp)
                + "\n链路：" + (fresh(now) ? pathLabel() : "待更新") + " · 端到端 RTT：" + latencyLabel(now)
                + "\n抖动（平滑 RTT 偏差）：" + (recentRtt(now) && jitterMs != null ? jitterMs + " ms" : "未测得")
                + "\n路径载荷 MTU：" + (fresh(now) && overlayBudget != null ? overlayBudget + " B" : "未确认")
                + "\n发送 / 接收（累计）：" + bytes(bytesSent) + " / " + bytes(bytesReceived)
                + (statisticsCurrent(now) ? "\n" + statistics.tooltip() + "\n最新计时样本：" + (statistics.lastSampleAgeMs() == null ? "未测得" : (statistics.lastSampleAgeMs() + (now - observedAt) / 1_000_000) + " ms 前") : "\n窗口统计：待测量 / 待更新")
                + "\nMTU 为原生路径测量值。\nRTT 为 P2WLAN 路径的实测往返时间。\n直连与加密中继均可承载游戏流量。";
    }
    public static String bytes(Long value) {
        if (value == null) return "—";
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return value / 1024 + " KiB";
        if (value < 1024L * 1024 * 1024) return value / (1024 * 1024) + " MiB";
        return value / (1024L * 1024 * 1024) + " GiB";
    }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
}
