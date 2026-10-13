package io.p2wlan.mc.common;

import com.google.gson.JsonParser;
import java.util.List;

public final class NetworkCatalogTests {
    private static int checks;
    private static final RuntimeView RUNTIME = new RuntimeView("a".repeat(32), "b".repeat(32));
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected INVALID_REPLY"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.INVALID_REPLY); }
    }
    private static AgentReply reply(String json) { return new AgentReply(RUNTIME.owner(), 1, JsonParser.parseString(json).getAsJsonObject()); }
    private static String peers(String generation, String ids) {
        return "{\"runtime\":\"" + RUNTIME.runtime() + "\",\"view\":{\"network_generation\":" + generation + ",\"peers\":" + ids + "}}";
    }
    private static String services(String entries) {
        return "{\"runtime\":\"" + RUNTIME.runtime() + "\",\"peer\":\"peer\",\"services\":" + entries + "}";
    }
    private static String service(String port, String identity) { return "{\"port\":" + port + ",\"service\":\"" + identity + "\"}"; }
    public static void main(String[] args) throws Exception {
        RoomPeers roster = RoomPeers.parse(reply(peers("\"18446744073709551615\"", "[\"a\",\"z\"]")), RUNTIME);
        check(roster.peers().equals(List.of("a", "z")) && roster.generation().equals("18446744073709551615"));
        check(RoomPeers.parse(reply(peers("\"0\"", "[]")), RUNTIME).peers().isEmpty());
        // Rust sorts UTF-8 bytes, not Java's UTF-16 code units.
        check(RoomPeers.parse(reply(peers("\"1\"", "[\"\ue000\",\"\ud800\udc00\"]")), RUNTIME).peers().size() == 2);
        try { roster.peers().add("foreign"); throw new AssertionError("mutable roster"); } catch (UnsupportedOperationException expected) { check(true); }
        for (String generation : new String[]{"1", "\"01\"", "\"-1\"", "\"18446744073709551616\"", "null"}) {
            reject(() -> RoomPeers.parse(reply(peers(generation, "[]")), RUNTIME));
        }
        for (String ids : new String[]{"[\"z\",\"a\"]", "[\"a\",\"a\"]", "[\"\"]", "[\"bad\\npeer\"]", "[1]", "null",
                "[\"" + "中".repeat(86) + "\"]"}) {
            reject(() -> RoomPeers.parse(reply(peers("\"1\"", ids)), RUNTIME));
        }
        String maximumPeers = "[" + String.join(",", java.util.stream.IntStream.range(0, 128).mapToObj(n -> String.format("\"p%03d\"", n)).toList()) + "]";
        check(RoomPeers.parse(reply(peers("\"1\"", maximumPeers)), RUNTIME).peers().size() == 128);
        // Oversized trees are rejected at the bounded immutable snapshot boundary.
        String excessPeers = "[" + String.join(",", java.util.stream.IntStream.range(0, 129).mapToObj(n -> String.format("\"p%03d\"", n)).toList()) + "]";
        try { reply(peers("\"1\"", excessPeers)); throw new AssertionError("oversized snapshot admitted"); }
        catch (IllegalArgumentException invalid) { check(invalid.getMessage().equals("INVALID_JSON_SNAPSHOT")); }
        reject(() -> RoomPeers.parse(reply(peers("\"1\"", "[]").replace(RUNTIME.runtime(), "c".repeat(32))), RUNTIME));
        reject(() -> RoomPeers.parse(new AgentReply("c".repeat(32), 1, roster.evidence().data()), RUNTIME));
        String first = service("1024", "d".repeat(64)), last = service("65535", "e".repeat(64));
        ServiceCatalog catalog = ServiceCatalog.parse(reply(services("[" + first + "," + last + "]")), RUNTIME, "peer");
        check(catalog.services().get(0).equals(new GameService(RUNTIME, "peer", 1024, "d".repeat(64))));
        try { catalog.services().clear(); throw new AssertionError("mutable services"); } catch (UnsupportedOperationException expected) { check(true); }
        check(ServiceCatalog.parse(reply(services("[]")), RUNTIME, "peer").services().isEmpty());
        for (String entries : new String[]{"null", "[0]", "[" + last + "," + first + "]", "[" + first + "," + first + "]",
                "[" + first + "," + service("1025", "d".repeat(64)) + "]", "[" + service("0", "d".repeat(64)) + "]",
                "[" + service("65536", "d".repeat(64)) + "]", "[" + service("1024.0", "d".repeat(64)) + "]",
                "[" + service("\"1024\"", "d".repeat(64)) + "]", "[" + service("1024", "X".repeat(64)) + "]"}) {
            reject(() -> ServiceCatalog.parse(reply(services(entries)), RUNTIME, "peer"));
        }
        String eight = String.join(",", java.util.stream.IntStream.range(0, 8).mapToObj(n -> service(Integer.toString(n + 1), String.format("%064x", n))).toList());
        check(ServiceCatalog.parse(reply(services("[" + eight + "]")), RUNTIME, "peer").services().size() == 8);
        reject(() -> ServiceCatalog.parse(reply(services("[" + eight + "," + service("9", "f".repeat(64)) + "]")), RUNTIME, "peer"));
        reject(() -> ServiceCatalog.parse(reply(services("[]").replace("\"peer\":\"peer\"", "\"peer\":\"peer\",\"extra\":true")), RUNTIME, "peer"));
        reject(() -> RoomPeers.parse(reply(peers("\"1\"", "[]").replace("\"peers\":[]", "\"peers\":[],\"extra\":true")), RUNTIME));
        reject(() -> ServiceCatalog.parse(reply(services("[]")), RUNTIME, "foreign"));
        reject(() -> ServiceCatalog.parse(new AgentReply("c".repeat(32), 1, catalog.evidence().data()), RUNTIME, "peer"));
        com.google.gson.JsonObject metric = JsonParser.parseString("{\"label\":\"Minecraft server\",\"virtual_ip\":\"10.77.0.2\",\"latency_ms\":17,\"jitter_ms\":2,\"last_success_age_ms\":100,\"bytes_sent\":2048,\"bytes_received\":4096}").getAsJsonObject();
        metric.add("latency_stats", JsonParser.parseString("{\"window_ms\":60000,\"capacity\":256,\"samples\":1,\"capacity_limited\":false,\"last_sample_age_ms\":100,\"min_us\":17000,\"avg_us\":17000,\"max_us\":17000,\"p50_us\":17000,\"p90_us\":17000,\"p95_us\":17000,\"p99_us\":17000}"));
        com.google.gson.JsonObject view = new com.google.gson.JsonObject(); view.addProperty("network_generation","1");view.addProperty("peer_session","2");view.addProperty("online",true);view.addProperty("path","relay");view.add("metrics",metric);
        for (String key : List.of("remote_candidate_epoch", "path_revision", "udp_publication_owner", "mtu", "direct_budget_ready")) view.add(key, com.google.gson.JsonNull.INSTANCE);
        for (String key : List.of("direct_validation_owned", "direct_validation_request_bound", "direct_pair_present", "direct_pair_local_bound",
                "direct_pair_epoch_current", "direct_pair_local_matches_primary", "direct_pair_local_matches_ipv6", "direct_outer_ipv6", "direct_validation_epoch_current")) view.addProperty(key, false);
        com.google.gson.JsonObject packet = new com.google.gson.JsonObject(); packet.addProperty("runtime",RUNTIME.runtime());packet.addProperty("peer","peer");packet.add("view",view);
        PeerTelemetry measured=PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1);
        check(measured.latencyLabel(2).equals("17 ms") && measured.pathLabel().equals("中继"));
        check(measured.latencyLabel(PeerTelemetry.FRESH_NANOS+2).equals("待更新"));
        check(measured.tooltip(2).contains("P2WLAN 路径的实测往返时间") && measured.tooltip(2).contains("2 ms"));
        check(measured.tooltip(2).contains("最近 60 秒") && measured.tooltip(2).contains("最多 256 个"));
        check(measured.tooltip(2).contains("ceil(p × n / 100)") && measured.tooltip(2).contains("从 1 开始"));
        metric.add("latency_ms",com.google.gson.JsonNull.INSTANCE);
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).latencyLabel(2).equals("未测得"));
        metric.addProperty("latency_ms",17);metric.addProperty("last_success_age_ms",15001);metric.getAsJsonObject("latency_stats").addProperty("last_sample_age_ms",15001);
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).latencyMs()==null);
        metric.addProperty("last_success_age_ms",100);metric.getAsJsonObject("latency_stats").addProperty("last_sample_age_ms",100);
        view.addProperty("online",false);
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).latencyMs()==null);
        view.addProperty("online",true);
        for (String ip : new String[]{"127.0.0.01","10.1.1.256","host","10.1.1"}) {
            metric.addProperty("virtual_ip",ip);reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));
        }
        metric.addProperty("virtual_ip","10.77.0.2");
        for (String name : new String[]{"bad\nlabel","界".repeat(43)}) {
            metric.addProperty("label",name);reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));
        }
        metric.addProperty("label","Minecraft");metric.addProperty("token","never-display");
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));metric.remove("token");
        reject(()->PeerTelemetry.parse(new AgentReply("c".repeat(32),1,packet),RUNTIME,"peer",1));
        view.addProperty("secret", "never-display");
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1)); view.remove("secret");
        for (String value : new String[]{"-1", "1.5", "\"17\"", "9223372036854775808"}) {
            metric.add("latency_ms", JsonParser.parseString(value));
            reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));
        }
        metric.addProperty("latency_ms", 17);
        view.addProperty("path", 1);
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1)); view.addProperty("path", "direct");
        view.addProperty("mtu", 1);
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1)); view.add("mtu", com.google.gson.JsonNull.INSTANCE);
        metric.addProperty("last_success_age_ms", 14_999);
        metric.getAsJsonObject("latency_stats").addProperty("last_sample_age_ms",14_999);
        measured = PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1);
        check(measured.latencyLabel(2_000_001).equals("未测得"));
        check(!measured.fresh(0));
        metric.addProperty("last_success_age_ms", 100);
        metric.getAsJsonObject("latency_stats").addProperty("last_sample_age_ms",100);
        metric.addProperty("latency_ms", 0);
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).latencyLabel(2).equals("<1 ms"));
        metric.addProperty("latency_ms", 17);
        var mtu = JsonParser.parseString("{\"network_generation\":\"1\",\"peer_session\":\"2\",\"remote_candidate_epoch\":\"3\",\"state\":\"search_complete\",\"revision\":\"7\",\"base_confirmed\":true,\"overlay_budget\":1168,\"probes\":2,\"successes\":2,\"timeouts\":0,\"send_failures\":0,\"live_worker\":false}").getAsJsonObject();
        view.add("mtu", mtu); view.addProperty("remote_candidate_epoch", "3"); view.addProperty("direct_budget_ready", true);
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).overlayBudget() == 1168);
        mtu.addProperty("remote_candidate_epoch", "4");
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).overlayBudget() == null);
        mtu.addProperty("remote_candidate_epoch", "3"); mtu.addProperty("base_confirmed", false);
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).overlayBudget() == null);
        mtu.addProperty("base_confirmed", true); view.addProperty("path", "relay");
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).overlayBudget() == null);
        view.addProperty("path", "direct");
        mtu.addProperty("peer_session", "3");
        check(PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1).overlayBudget() == null);
        mtu.addProperty("peer_session", "2"); mtu.addProperty("overlay_budget", 65536);
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));
        mtu.addProperty("overlay_budget", 1168); mtu.addProperty("token", "never-display");
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1)); mtu.remove("token");
        mtu.add("state", com.google.gson.JsonNull.INSTANCE);
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));
        view.add("mtu", com.google.gson.JsonNull.INSTANCE);
        view.addProperty("network_generation","18446744073709551616");
        reject(()->PeerTelemetry.parse(new AgentReply(RUNTIME.owner(),1,packet),RUNTIME,"peer",1));
        checks += NetworkObservationTests.run();
        System.out.println("PASS native network catalogs: " + checks + " explicit checks (not discovery/game acceptance)");
    }
}
