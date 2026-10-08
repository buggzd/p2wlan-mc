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
                "[\"" + "中".repeat(86) + "\"]", "[" + String.join(",", java.util.stream.IntStream.range(0, 129).mapToObj(n -> String.format("\"p%03d\"", n)).toList()) + "]"}) {
            reject(() -> RoomPeers.parse(reply(peers("\"1\"", ids)), RUNTIME));
        }
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
        System.out.println("PASS native network catalogs: " + checks + " explicit checks (not discovery/game acceptance)");
    }
}
