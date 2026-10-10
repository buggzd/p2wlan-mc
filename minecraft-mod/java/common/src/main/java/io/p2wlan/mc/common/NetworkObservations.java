package io.p2wlan.mc.common;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, game-thread-owned display snapshots only. These cannot authorize a
 * join or a send. Native remains the sole membership/path/session authority. */
public final class NetworkObservations {
    public static final int MAX_PEERS = 16, MAX_SERVICES = 32;
    public static final long CATALOG_NANOS = 30_000_000_000L;
    private record Observed(GameService service, String generation, String session, long at) {}
    private final RuntimeView runtime;
    private final Map<String, PeerTelemetry> peers = new LinkedHashMap<>();
    private final Map<String, Observed> services = new LinkedHashMap<>();
    private RoomPeers roster;
    private long rosterAt;
    private List<GameService> visible = List.of();
    private String reason = "";

    public NetworkObservations(RuntimeView runtime) { this.runtime = runtime; }
    public RoomPeers roster() { return roster; }
    public String reason() { return reason; }
    public List<PeerTelemetry> peers() { return List.copyOf(peers.values()); }
    public PeerTelemetry peer(String id) { return peers.get(id); }
    public boolean authorized(long now) {
        return roster != null && now >= rosterAt && now - rosterAt <= PeerTelemetry.FRESH_NANOS;
    }
    public List<String> pollingPeers() {
        return roster == null ? List.of() : roster.peers().stream().limit(MAX_PEERS).toList();
    }
    public void clear() {
        roster = null; rosterAt = 0; peers.clear(); clearServices(); reason = "";
    }
    public void clearServices() { services.clear(); visible = List.of(); }
    public void fail(String peer, String failure) {
        if (peer == null) clear();
        else {
            peers.remove(peer);
            services.values().removeIf(value -> value.service().peer().equals(peer));
        }
        reason = failure;
    }
    public void roster(RoomPeers next, long now) throws AgentException {
        if (!runtime.equals(next.runtime())) throw changed();
        if (roster != null && !roster.generation().equals(next.generation())) {
            peers.clear(); clearServices();
        }
        peers.keySet().retainAll(next.peers());
        services.values().removeIf(value -> !next.peers().contains(value.service().peer()));
        roster = next; rosterAt = now;
        reason = next.peers().size() > MAX_PEERS ? "VIEW_CAPACITY_16" : "";
    }
    public void peer(PeerTelemetry next) throws AgentException {
        if (!runtime.equals(next.runtime()) || roster == null || !roster.generation().equals(next.generation())
                || !pollingPeers().contains(next.peer())) throw changed();
        PeerTelemetry before = peers.put(next.peer(), next);
        if (!next.online() || before != null && !before.session().equals(next.session())) {
            services.values().removeIf(value -> value.service().peer().equals(next.peer()));
        }
    }
    public void catalog(ServiceCatalog next, PeerTelemetry captured, long now) throws AgentException {
        // A late discovery result cannot replace a newer peer observation or a
        // different generation/session, even when the service's port is reused.
        if (captured == null || peers.get(next.peer()) != captured || !runtime.equals(next.runtime())
                || !captured.peer().equals(next.peer()) || !captured.online() || !captured.fresh(now)
                || !authorized(now) || !roster.generation().equals(captured.generation())) throw changed();
        services.values().removeIf(value -> value.service().peer().equals(next.peer()));
        for (GameService service : next.services()) {
            if (services.size() == MAX_SERVICES) { reason = "SERVICE_CAPACITY_32"; break; }
            services.put(service.peer() + ":" + service.identity(),
                    new Observed(service, captured.generation(), captured.session(), now));
        }
    }
    public List<GameService> services(long now) {
        services.values().removeIf(value -> now < value.at() || now - value.at() > CATALOG_NANOS
                || roster == null || !value.generation().equals(roster.generation())
                || !peers.containsKey(value.service().peer())
                || !value.session().equals(peers.get(value.service().peer()).session()));
        List<GameService> next = authorized(now) ? services.values().stream().map(Observed::service)
                .sorted(Comparator.comparing(GameService::peer).thenComparingInt(GameService::port)).toList() : List.of();
        if (!next.equals(visible)) visible = next;
        return visible;
    }
    private static AgentException changed() { return new AgentException(AgentException.Code.SCOPE_CHANGED); }
}
