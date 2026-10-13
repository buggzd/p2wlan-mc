package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;

/** A native-owner aggregate, never a Java measurement/sampling cache. Microsecond
 * units preserve sub-ms samples; the display uses milliseconds. */
public record LatencyStatistics(int samples, boolean capacityLimited, Long lastSampleAgeMs,
        Long minUs, Long avgUs, Long maxUs, Long p50Us, Long p90Us, Long p95Us, Long p99Us) {
    public static final long WINDOW_MS = 60_000;
    public static final int CAPACITY = 256;
    private static final Set<String> FIELDS = Set.of("window_ms", "capacity", "samples", "capacity_limited", "last_sample_age_ms",
            "min_us", "avg_us", "max_us", "p50_us", "p90_us", "p95_us", "p99_us");
    static LatencyStatistics parse(JsonObject value) throws AgentException {
        if (!JsonTrees.fields(value, FIELDS) || !Long.valueOf(WINDOW_MS).equals(PeerTelemetry.number(value,"window_ms"))
                || !Long.valueOf(CAPACITY).equals(PeerTelemetry.number(value,"capacity"))) throw invalid();
        Long count = PeerTelemetry.number(value,"samples"), age = PeerTelemetry.number(value,"last_sample_age_ms");
        var limited = value.get("capacity_limited");
        if (count == null || count > CAPACITY || !limited.isJsonPrimitive() || !limited.getAsJsonPrimitive().isBoolean()) throw invalid();
        Long min = PeerTelemetry.number(value,"min_us"), avg = PeerTelemetry.number(value,"avg_us"), max = PeerTelemetry.number(value,"max_us");
        Long p50 = PeerTelemetry.number(value,"p50_us"), p90 = PeerTelemetry.number(value,"p90_us"), p95 = PeerTelemetry.number(value,"p95_us"), p99 = PeerTelemetry.number(value,"p99_us");
        Long[] numbers = {min,avg,max,p50,p90,p95,p99};
        for (Long number : numbers) if (count == 0 ? number != null : number == null) throw invalid();
        if (count > 0 && (age == null || age > WINDOW_MS || min > avg || avg > max || min > p50 || p50 > p90 || p90 > p95 || p95 > p99 || p99 > max)) throw invalid();
        return new LatencyStatistics(count.intValue(),limited.getAsBoolean(),age,min,avg,max,p50,p90,p95,p99);
    }
    public static String ms(Long microseconds) {
        if (microseconds == null) return "—";
        if (microseconds == 0) return "<0.001";
        if (microseconds < 1000) return "0." + String.format(java.util.Locale.ROOT,"%03d",microseconds).replaceFirst("0+$", "");
        long whole = microseconds / 1000, tenth = microseconds % 1000 / 100;
        return tenth == 0 ? Long.toString(whole) : whole + "." + tenth;
    }
    public String windowLabel() { return "最近 60 秒 · n=" + samples + (capacityLimited ? " · 仅最近 256 个样本" : ""); }
    public List<String> summaryLines() {
        return List.of("Min / Avg / Max：" + ms(minUs) + " / " + ms(avgUs) + " / " + ms(maxUs) + " ms",
                "P50 / P90：" + ms(p50Us) + " / " + ms(p90Us) + " ms",
                "P95 / P99：" + ms(p95Us) + " / " + ms(p99Us) + " ms");
    }
    public String tooltip() {
        return String.join("\n", summaryLines()) + "\n" + windowLabel()
                + "\nPp 取升序样本的第 ceil(p × n / 100) 位（从 1 开始）。"
                + (samples < 100 ? "\n样本不足 100 个；P99 按当前窗口计算。" : "")
                + "\n样本范围：最近 60 秒内实际计时成功的 RTT，最多 256 个。";
    }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
}
