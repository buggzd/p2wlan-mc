package io.p2wlan.mc.fabric.client.lan;

import io.p2wlan.mc.common.*;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import io.p2wlan.mc.fabric.client.screen.AccountScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;

/** Game-thread-owned read scheduler, never a network state machine. One IPC in
 * flight, 5s deadline, <=16 peers/32 services, 1s between observation cycles.
 * Closing the browser cancels discovery; replacement/shutdown retires all views. */
public final class FabricNetworkView {
    private record Pending(AgentClient agent, RuntimeView runtime, String operation, String peer,
            PeerTelemetry captured, CompletableFuture<AgentReply> future) {}
    private static RuntimeView runtime;
    private static NetworkObservations observations;
    private static List<String> cycle = List.of();
    private static Pending pending;
    private static AtomicBoolean lifetime = new AtomicBoolean(true), browserLifetime = new AtomicBoolean(false);
    private static int index;
    private static boolean discover;
    private static long nextPoll;
    private FabricNetworkView() {}

    public static List<GameService> services() { return current() ? observations.services(System.nanoTime()) : List.of(); }
    public static List<PeerTelemetry> peers() { return current() ? observations.peers() : List.of(); }
    public static PeerTelemetry peer(String id) { return current() ? observations.peer(id) : null; }
    public static boolean authorized() { return current() && observations.authorized(System.nanoTime()); }
    public static String reason() { return current() ? observations.reason() : ""; }
    public static String roomName() {
        var agent = FabricP2wlan.AGENT.current().orElse(null);
        if (!current() || agent == null) return "虚拟局域网";
        var data = agent.snapshot().map(StatusSnapshot::data).orElse(null);
        var room = data == null ? null : data.get("room");
        String name = room != null && room.isJsonObject() ? RuntimeView.text(room.getAsJsonObject(), "name") : null;
        return name == null || name.isBlank() ? "虚拟局域网" : name.replaceAll("[\\p{Cntrl}§]", "");
    }
    private static boolean current() {
        var agent = FabricP2wlan.AGENT.current().orElse(null);
        return runtime != null && runtime.isCurrent(agent) && FabricP2wlan.AGENT.isCurrent(agent);
    }
    public static boolean contains(GameService service) { return services().contains(service); }
    public static void retire() {
        lifetime.set(false); lifetime = new AtomicBoolean(true);
        browserLifetime.set(false); browserLifetime = new AtomicBoolean(false);
        if (pending != null) pending.future().cancel(false);
        pending = null; runtime = null; observations = null; cycle = List.of();
        index = 0; discover = false; nextPoll = 0;
    }
    public static void tick(MinecraftClient client) {
        var agent = FabricP2wlan.AGENT.current().orElse(null);
        RuntimeView selected = RuntimeView.ready(agent).orElse(null);
        if (!java.util.Objects.equals(selected, runtime)) {
            retire(); runtime = selected;
            if (selected != null) observations = new NetworkObservations(selected);
        }
        if (!current()) return;
        long now = System.nanoTime();
        boolean browser = client.currentScreen instanceof MultiplayerScreen;
        if (browser != browserLifetime.get()) {
            browserLifetime.set(false); browserLifetime = new AtomicBoolean(browser);
            if (!browser) {
                observations.clearServices();
                if (pending != null && pending.operation().equals("discover")) {
                    pending.future().cancel(false); pending = null; index++; discover = false;
                }
            }
        }
        if (pending != null && pending.future().isDone()) complete(now, browser, client);
        // Account mutations have priority; never queue background read work.
        if (pending != null || AccountScreen.requestsBusy() || now < nextPoll) return;
        if (cycle.isEmpty()) { submit(agent, "room_peers", null); return; }
        if (index >= cycle.size()) { cycle = List.of(); index = 0; discover = false; nextPoll = now + 1_000_000_000L; return; }
        String peer = cycle.get(index);
        if (discover) {
            discover = false;
            var observation = observations.peer(peer);
            if (browser && observation != null && observation.online()) { submit(agent, "discover", peer); return; }
            index++;
        } else submit(agent, "peer_status", peer);
    }
    private static void submit(AgentClient agent, String operation, String peer) {
        RuntimeView scope = runtime; AtomicBoolean live = lifetime, browser = browserLifetime;
        var args = scope.arguments(); if (peer != null) args.addProperty("peer", peer);
        var captured = peer == null ? null : observations.peer(peer);
        var future = agent.request(operation, args, () -> live.get() && FabricP2wlan.AGENT.isCurrent(agent)
                && scope.isCurrent(agent) && (!operation.equals("discover") || browser.get()));
        pending = new Pending(agent, scope, operation, peer, captured, future);
    }
    private static void complete(long now, boolean browser, MinecraftClient client) {
        Pending completed = pending; pending = null;
        if (!current() || !completed.runtime().equals(runtime) || !FabricP2wlan.AGENT.isCurrent(completed.agent())) return;
        try {
            AgentReply reply = completed.future().join();
            if (!completed.agent().isCurrent(reply) || !runtime.isCurrent(completed.agent())) return;
            switch (completed.operation()) {
                case "room_peers" -> {
                    observations.roster(RoomPeers.parse(reply, runtime), now);
                    var ordered = new ArrayList<>(observations.pollingPeers());
                    var playing = FabricGameJoins.playingService(client);
                    if (playing != null && ordered.remove(playing.peer())) ordered.addFirst(playing.peer());
                    cycle = List.copyOf(ordered); index = 0;
                    if (cycle.isEmpty()) nextPoll = now + 1_000_000_000L;
                }
                case "peer_status" -> {
                    observations.peer(PeerTelemetry.parse(reply, runtime, completed.peer(), now));
                    discover = true;
                }
                case "discover" -> {
                    if (browser) observations.catalog(ServiceCatalog.parse(reply, runtime, completed.peer()), completed.captured(), now);
                    index++;
                }
                default -> throw new AgentException(AgentException.Code.INVALID_REPLY);
            }
        } catch (Exception error) {
            observations.fail(completed.peer(), UiCompletionGate.reason(error));
            if (completed.peer() == null) { cycle = List.of(); nextPoll = now + 1_000_000_000L; }
            else { index++; discover = false; }
        }
    }
}
