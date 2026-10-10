package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.stream.IntStream;

/** Deterministic display-cache tests; not game/network authorization tests. */
final class NetworkObservationTests {
    private static int checks;
    private static final RuntimeView RUNTIME = new RuntimeView("a".repeat(32), "b".repeat(32));
    private static final RuntimeView OTHER = new RuntimeView("c".repeat(32), "d".repeat(32));
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("observation " + checks); }
    private static void reject(Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected SCOPE_CHANGED"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.SCOPE_CHANGED); }
    }
    private static AgentReply reply(RuntimeView runtime) { return new AgentReply(runtime.owner(), 1, new JsonObject()); }
    private static RoomPeers roster(RuntimeView runtime, String generation, List<String> peers) {
        return new RoomPeers(reply(runtime), runtime, generation, peers);
    }
    private static PeerTelemetry peer(RuntimeView runtime, String id, String generation, String session, boolean online, long at) {
        return new PeerTelemetry(reply(runtime), runtime, id, generation, session, online, online ? "direct" : null,
                "Minecraft", "10.77.0.2", 17L, 2L, 100L, 2048L, 4096L, 1168, new LatencyStatistics(1,false,100L,17000L,17000L,17000L,17000L,17000L,17000L,17000L), at);
    }
    private static ServiceCatalog catalog(RuntimeView runtime, String peer, int count) {
        return new ServiceCatalog(reply(runtime), runtime, peer, IntStream.range(0, count)
                .mapToObj(i -> new GameService(runtime, peer, 20000 + i, String.format("%064x", i + 1))).toList());
    }
    static int run() throws Exception {
        checks = 0;
        var cache = new NetworkObservations(RUNTIME);
        List<String> ids = IntStream.range(0, 17).mapToObj(i -> String.format("peer%03d", i)).toList();
        check(cache.services(0).isEmpty() && !cache.authorized(0));
        reject(() -> cache.roster(roster(OTHER, "1", ids), 0));
        cache.roster(roster(RUNTIME, "1", ids), 1);
        check(cache.reason().equals("VIEW_CAPACITY_16") && cache.pollingPeers().size() == 16);
        reject(() -> cache.peer(peer(RUNTIME, ids.get(16), "1", "1", true, 2)));
        reject(() -> cache.peer(peer(OTHER, ids.get(0), "1", "1", true, 2)));
        reject(() -> cache.peer(peer(RUNTIME, ids.get(0), "2", "1", true, 2)));
        for (String id : cache.pollingPeers()) cache.peer(peer(RUNTIME, id, "1", "1", true, 2));
        check(cache.peers().size() == 16 && cache.authorized(2) && !cache.authorized(0));
        for (int i = 0; i < 5; i++) cache.catalog(catalog(RUNTIME, ids.get(i), 8), cache.peer(ids.get(i)), 3);
        var rows = cache.services(3);
        check(rows.size() == 32 && cache.reason().equals("SERVICE_CAPACITY_32"));
        check(cache.services(4) == rows); // No pointless Vanilla selection refresh.
        try { rows.clear(); throw new AssertionError("mutable services"); }
        catch (UnsupportedOperationException expected) { check(true); }
        var old = cache.peer(ids.get(0));
        cache.peer(peer(RUNTIME, ids.get(0), "1", "2", true, 4));
        check(cache.services(4).size() == 24);
        reject(() -> cache.catalog(catalog(RUNTIME, ids.get(0), 8), old, 5));
        reject(() -> cache.catalog(catalog(OTHER, ids.get(0), 8), cache.peer(ids.get(0)), 5));
        cache.catalog(catalog(RUNTIME, ids.get(0), 1), cache.peer(ids.get(0)), 5);
        check(cache.services(5).size() == 25);
        cache.peer(peer(RUNTIME, ids.get(0), "1", "2", false, 6));
        check(cache.services(6).size() == 24);
        reject(() -> cache.catalog(catalog(RUNTIME, ids.get(0), 1), cache.peer(ids.get(0)), 7));
        cache.fail(ids.get(1), "PEER_CHANGED");
        check(cache.peer(ids.get(1)) == null && cache.services(7).size() == 16 && cache.reason().equals("PEER_CHANGED"));
        cache.roster(roster(RUNTIME, "1", List.of(ids.get(2))), 8);
        check(cache.peers().size() == 1 && cache.services(8).size() == 8);
        cache.clearServices();
        check(cache.services(8).isEmpty() && cache.peers().size() == 1); // Browser close leaves room intact.
        cache.catalog(catalog(RUNTIME, ids.get(2), 1), cache.peer(ids.get(2)), 9);
        check(cache.services(9).size() == 1);
        check(!cache.authorized(PeerTelemetry.FRESH_NANOS + 9) && cache.services(PeerTelemetry.FRESH_NANOS + 9).isEmpty());
        reject(() -> cache.catalog(catalog(RUNTIME, ids.get(2), 1), cache.peer(ids.get(2)), PeerTelemetry.FRESH_NANOS + 9));
        cache.roster(roster(RUNTIME, "1", List.of(ids.get(2))), NetworkObservations.CATALOG_NANOS + 8);
        check(cache.services(NetworkObservations.CATALOG_NANOS + 8).size() == 1);
        check(cache.services(NetworkObservations.CATALOG_NANOS + 10).isEmpty());
        cache.peer(peer(RUNTIME, ids.get(2), "1", "1", true, NetworkObservations.CATALOG_NANOS + 10));
        cache.catalog(catalog(RUNTIME, ids.get(2), 1), cache.peer(ids.get(2)), NetworkObservations.CATALOG_NANOS + 11);
        cache.roster(roster(RUNTIME, "2", ids), NetworkObservations.CATALOG_NANOS + 12);
        check(cache.peers().isEmpty() && cache.services(NetworkObservations.CATALOG_NANOS + 12).isEmpty());
        reject(() -> cache.catalog(catalog(RUNTIME, ids.get(0), 1), old, NetworkObservations.CATALOG_NANOS + 13));
        cache.fail(null, "ROOM_REVOKED");
        check(cache.roster() == null && !cache.authorized(20) && cache.pollingPeers().isEmpty() && cache.reason().equals("ROOM_REVOKED"));
        cache.clear();
        check(cache.reason().isEmpty());
        return checks;
    }
}
