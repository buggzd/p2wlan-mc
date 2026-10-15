package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Deterministic restart, delayed status and lifecycle fencing checks. */
public final class DedicatedServerControllerTests {
    private static final String OWNER = "a".repeat(32), RUNTIME = "b".repeat(32);
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("DEDICATED_" + checks); }
    private static final class Native implements DedicatedServerController.Session {
        boolean live = true;
        JsonObject state = json("{\"phase\":\"idle\",\"account\":{\"authenticated\":true,\"server\":\"https://example.com\",\"user_id\":\"user-one\"}}");
        JsonObject freshStatus;
        final List<String> operations = new ArrayList<>();
        CompletableFuture<AgentReply> future;
        BooleanSupplier fence;
        public boolean current() { return live; }
        public boolean owns(AgentReply reply) { return live && OWNER.equals(reply.owner()); }
        public JsonObject snapshot() { return state.deepCopy(); }
        public CompletableFuture<AgentReply> request(String operation, JsonObject arguments, BooleanSupplier fence) {
            check(fence.getAsBoolean());
            if (operation.equals("status")) return CompletableFuture.completedFuture(new AgentReply(OWNER, operations.size() + 1, (freshStatus == null ? state : freshStatus).deepCopy()));
            operations.add(operation); this.fence = fence;
            future = new CompletableFuture<>(); return future;
        }
        void reply(JsonObject data) { future.complete(new AgentReply(OWNER, operations.size(), data)); }
        void ready() { state.addProperty("phase", "ready"); state.addProperty("runtime", RUNTIME); }
    }
    private static final class Host implements DedicatedServerController.Host {
        boolean live = true; boolean available = true; int capacityRejections; int hosted; int revoked; WorldHost.Status status;
        public boolean current() { return live; }
        public void host(RuntimeView runtime) throws WorldHostException {
            check(live && runtime.runtime().equals(RUNTIME));
            if (capacityRejections-- > 0) throw new WorldHostException(WorldHostException.Code.CAPACITY);
            hosted++; status = new WorldHost.Status(WorldHost.Phase.HOSTED, null);
        }
        public void unhost() { revoked++; status = null; }
        public WorldHost.Status status() { return status; }
        public boolean publicationAvailable() { return available; }
    }
    private static JsonObject json(String value) {
        try { return FrameCodec.parse(value); } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    private static JsonObject room() { return json("{\"room\":{\"id\":\"room-one\",\"code\":\"12345678\",\"name\":\"World\",\"cidr\":\"10.0.0.0/24\",\"role\":\"owner\"},\"runtime\":\"" + RUNTIME + "\",\"phase\":\"starting\"}"); }
    private static DedicatedServerSettings.Value plan(boolean share) { return new DedicatedServerSettings.Value("https://example.com", "user-one", "room-one", share); }
    private static DedicatedServerController controller(Host host, Native nativeSession, DedicatedServerSettings store, List<String> output, AtomicLong clock) {
        return new DedicatedServerController(host, () -> nativeSession, () -> {}, () -> true, store, output::add, clock::get);
    }
    private static void pump(DedicatedServerController controller) { for (int index = 0; index < 3; index++) controller.tick(); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("p2wlan-dedicated-").toRealPath();
        try {
            var store = new DedicatedServerSettings(root.resolve("server.json"));
            var output = new ArrayList<String>(); var clock = new AtomicLong();
            store.save(plan(true));
            var nativeSession = new Native(); var host = new Host();
            var first = controller(host, nativeSession, store, output, clock);
            pump(first); check(nativeSession.operations.equals(List.of("connect_room")));
            pump(first); check(nativeSession.operations.size() == 1);
            nativeSession.reply(room()); pump(first);
            check(host.hosted == 0 && first.status().contains("WAITING_RUNTIME"));
            // The response may overtake native's next status event. An old idle
            // snapshot cannot cancel the newly accepted runtime selection.
            pump(first); check(host.hosted == 0);
            nativeSession.ready(); pump(first); check(host.hosted == 1 && first.status().contains("HOSTED"));
            pump(first); check(host.hosted == 1);
            first.close(); check(store.load().value().equals(plan(true)));
            var secondNative = new Native(); var secondHost = new Host();
            var second = controller(secondHost, secondNative, store, output, clock);
            pump(second); secondNative.reply(room()); pump(second); secondNative.ready(); pump(second);
            check(secondHost.hosted == 1); // A distinct MC/native process restores the same intent.
            second.unhost(); check(!store.load().value().share() && !store.load().value().room().isEmpty());
            second.close();
            var thirdNative = new Native(); var thirdHost = new Host();
            var third = controller(thirdHost, thirdNative, store, output, clock);
            pump(third); thirdNative.reply(room()); pump(third); thirdNative.ready(); pump(third);
            check(thirdHost.hosted == 0 && third.status().contains("CONNECTED"));
            third.disconnect(); pump(third); check(store.load().value().room().isEmpty());
            check(thirdNative.operations.equals(List.of("connect_room", "stop")));
            thirdNative.reply(json("{\"stopped\":true}")); pump(third); third.close();
            var fourthNative = new Native();
            controller(new Host(), fourthNative, store, output, clock).tick(); check(fourthNative.operations.isEmpty());

            store.save(plan(true));
            var changed = new Native(); changed.state.getAsJsonObject("account").addProperty("user_id", "user-two");
            var changedController = controller(new Host(), changed, store, output, clock);
            pump(changedController); check(changed.operations.isEmpty() && changedController.status().contains("ACCOUNT_CHANGED"));
            pump(changedController); check(changed.operations.isEmpty()); changedController.close();
            check(store.load().value().equals(plan(true)));

            // Late completions cannot save a room or publish into a successor world.
            store.save(new DedicatedServerSettings.Value("https://example.com", "user-one", "", false));
            var lateNative = new Native(); var lateHost = new Host();
            var late = controller(lateHost, lateNative, store, output, clock);
            late.create("World", "room-password"); pump(late); late.close(); check(!lateNative.fence.getAsBoolean());
            lateNative.reply(room()); pump(late); check(store.load().value().room().isEmpty() && lateHost.hosted == 0);
            var lostNative = new Native(); var lost = controller(new Host(), lostNative, store, output, clock);
            lost.connect("12345678"); pump(lost); lostNative.live = false; lostNative.reply(room()); pump(lost);
            check(store.load().value().room().isEmpty() && lost.status().contains("PROCESS_CHANGED")); lost.close();
            var forgedNative = new Native(); var forged = controller(new Host(), forgedNative, store, output, clock);
            forged.connect("12345678"); pump(forged); forgedNative.future.complete(new AgentReply("c".repeat(32), 1, room())); pump(forged);
            check(store.load().value().room().isEmpty()); forged.close();

            store.save(plan(true)); var slowNative = new Native(); var slowHost = new Host();
            var slow = controller(slowHost, slowNative, store, output, clock);
            pump(slow); slowNative.reply(room()); pump(slow); clock.addAndGet(java.time.Duration.ofSeconds(41).toNanos()); pump(slow);
            check(slow.status().contains("FAILED") && slowHost.hosted == 0); pump(slow); check(slowNative.operations.size() == 1); slow.close();

            // A failed Control mutation is reported once and never replayed.
            store.save(new DedicatedServerSettings.Value("https://example.com", "user-one", "", false));
            var failureNative = new Native(); var failed = controller(new Host(), failureNative, store, output, clock);
            failed.join("12345678", "room-password"); pump(failed);
            failureNative.future.completeExceptionally(new AgentException(AgentException.Code.REMOTE_REJECTED, "CONTROL_TIMEOUT"));
            pump(failed); pump(failed); check(failureNative.operations.equals(List.of("join_room")) && store.load().value().room().isEmpty()); failed.close();
            check(output.stream().noneMatch(line -> line.contains("room-password")));

            // A ready replacement must wait for the prior world's exact cleanup,
            // with the same finite deadline and no extra connect/native request.
            store.save(plan(true)); var retiringNative = new Native(); var retiringHost = new Host();
            retiringHost.available = false;
            var retiring = controller(retiringHost, retiringNative, store, output, clock);
            pump(retiring); retiringNative.reply(room()); pump(retiring); retiringNative.ready(); pump(retiring);
            check(retiringHost.hosted == 0 && retiring.status().contains("WAITING_PUBLICATION"));
            retiringHost.available = true; pump(retiring);
            check(retiringHost.hosted == 1 && retiringNative.operations.equals(List.of("connect_room"))); retiring.close();
            var stuckNative = new Native(); var stuckHost = new Host(); stuckHost.available = false;
            var stuck = controller(stuckHost, stuckNative, store, output, clock);
            pump(stuck); stuckNative.reply(room()); pump(stuck); stuckNative.ready(); pump(stuck);
            clock.addAndGet(java.time.Duration.ofSeconds(41).toNanos()); pump(stuck);
            check(stuck.status().contains("WORLD_CLEANUP_TIMEOUT") && stuckHost.hosted == 0);
            stuckHost.available = true; pump(stuck); check(stuckHost.hosted == 0); stuck.close();

            store.save(plan(false)); var earlyNative = new Native(); var earlyHost = new Host();
            var early = controller(earlyHost, earlyNative, store, output, clock);
            pump(early); earlyNative.reply(room()); pump(early);
            early.host(); check(store.load().value().share());
            earlyNative.ready(); earlyHost.capacityRejections = 1; pump(early);
            check(earlyHost.hosted == 1 && early.status().contains("HOSTED"));
            early.host(); pump(early); check(earlyHost.hosted == 1 && early.status().contains("HOSTED"));
            check(earlyNative.operations.equals(List.of("connect_room"))); early.close();

            // Native registration failure retains the durable intent for an
            // explicit restore; it does not create a replacement room.
            var retryNative = new Native(); store.save(plan(false));
            var retry = controller(new Host(), retryNative, store, output, clock);
            pump(retry); retryNative.future.completeExceptionally(new AgentException(AgentException.Code.REMOTE_REJECTED, "CONTROL_UNAVAILABLE")); pump(retry);
            check(store.load().value().equals(plan(false))); retry.restore(); pump(retry); check(retryNative.operations.equals(List.of("connect_room", "connect_room"))); retry.close();
        } finally {
            try (var files = Files.walk(root)) { for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("PASS dedicated restart/console workflow: " + checks + " checks");
    }
}
