package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Native replies and status events advance independently to reproduce console races. */
public final class DedicatedServerAccountTests {
    private static final String OWNER = "a".repeat(32), RUNTIME = "b".repeat(32), NEXT_RUNTIME = "c".repeat(32);
    private static final String SERVER = "https://example.com", USER = "fixture-user";
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError("DEDICATED_ACCOUNT_" + checks + ": " + reason);
    }
    @FunctionalInterface private interface Action { void run() throws AgentException; }
    private static void rejected(String reason, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + reason); }
        catch (AgentException error) { check(reason.equals(UiCompletionGate.reason(error)), "structured failure"); }
    }
    private record Request(String operation, JsonObject arguments, BooleanSupplier fence) {}
    private static final class Native implements DedicatedServerController.Session {
        boolean live = true;
        JsonObject state = idle(USER);
        final List<Request> requests = new ArrayList<>();
        CompletableFuture<AgentReply> future;
        public boolean current() { return live; }
        public boolean owns(AgentReply reply) { return live && OWNER.equals(reply.owner()); }
        public JsonObject snapshot() { return state.deepCopy(); }
        public CompletableFuture<AgentReply> request(String operation, JsonObject arguments, BooleanSupplier fence) {
            check(fence.getAsBoolean(), "request scope current");
            check(future == null || future.isDone(), "single pending request");
            requests.add(new Request(operation, arguments.deepCopy(), fence));
            future = new CompletableFuture<>(); return future;
        }
        Request last() { return requests.get(requests.size() - 1); }
        List<String> operations() { return requests.stream().map(Request::operation).toList(); }
        void reply(JsonObject value) { future.complete(new AgentReply(OWNER, requests.size(), value)); }
    }
    private static final class Host implements DedicatedServerController.Host {
        int hosted, revoked;
        WorldHost.Status status;
        public boolean current() { return true; }
        public void host(RuntimeView runtime) {
            check(runtime.owner().equals(OWNER), "publication process owner");
            hosted++; status = new WorldHost.Status(WorldHost.Phase.HOSTED, null);
        }
        public void unhost() { revoked++; status = null; }
        public WorldHost.Status status() { return status; }
    }
    private static final class Harness implements AutoCloseable {
        final Path file;
        final DedicatedServerSettings store;
        final Native nativeSession = new Native();
        final Host host = new Host();
        final List<String> output = new ArrayList<>();
        final AtomicInteger starts = new AtomicInteger();
        final DedicatedServerController controller;
        Harness(Path root, String name, DedicatedServerSettings.Value initial) throws Exception {
            file = root.resolve(name).resolve("server.json"); store = new DedicatedServerSettings(file);
            if (initial != null) store.save(initial);
            controller = new DedicatedServerController(host, () -> nativeSession, starts::incrementAndGet,
                    () -> true, store, output::add, () -> 0L);
        }
        void complete(JsonObject value) { nativeSession.reply(value); controller.tick(); }
        void expect(String operation) { check(nativeSession.last().operation().equals(operation), "expected " + operation); }
        @Override public void close() { controller.close(); }
    }
    private static JsonObject account(String user) {
        var value = new JsonObject(); value.addProperty("authenticated", true);
        value.addProperty("server", SERVER); value.addProperty("user_id", user); return value;
    }
    private static JsonObject idle(String user) {
        var value = new JsonObject(); value.addProperty("phase", "idle"); value.add("account", account(user)); return value;
    }
    private static JsonObject active(String user, String runtime) {
        var value = idle(user); value.addProperty("phase", "ready"); value.addProperty("runtime", runtime); return value;
    }
    private static JsonObject room(String id, String runtime) {
        var room = new JsonObject(); room.addProperty("id", id); room.addProperty("code", "12345678");
        room.addProperty("name", "Fixture"); room.addProperty("cidr", "10.0.0.0/24"); room.addProperty("role", "owner");
        var value = new JsonObject(); value.add("room", room);
        if (runtime != null) { value.addProperty("runtime", runtime); value.addProperty("phase", "starting"); }
        return value;
    }
    private static JsonObject stopped() { var value = new JsonObject(); value.addProperty("stopped", true); return value; }
    private static DedicatedServerSettings.Value selected(String user, String room, boolean share) {
        return new DedicatedServerSettings.Value(SERVER, user, room, share);
    }
    private static void initializationAndLogin(Path root) throws Exception {
        try (var h = new Harness(root, "initialization", null)) {
            h.controller.init("HTTPS://EXAMPLE.COM:443/");
            check(h.starts.get() == 1, "init starts helper");
            check(h.store.load().value().equals(selected("", "", false)), "canonical endpoint saved");
            h.controller.login("fixture-login", "fixture-account-secret", false); h.expect("status");
            h.complete(idle(USER)); h.expect("login");
            check(h.nativeSession.last().arguments().get("server").getAsString().equals(SERVER), "login endpoint");
            check(h.nativeSession.last().arguments().get("password").getAsString().equals("fixture-account-secret"), "password sent only to native");
            h.complete(account(USER));
            check(h.store.load().value().equals(selected(USER, "", false)), "login identity saved");
            check(h.controller.status().contains("IDLE"), "login completed");
            check(h.output.stream().noneMatch(line -> line.contains("fixture-account-secret") || line.contains("fixture-login")), "login feedback redacted");
            check(!Files.readString(h.file).contains("fixture-account-secret"), "restart intent excludes password");
        }
    }
    private static void delayedAccountAndMembership(Path root) throws Exception {
        try (var h = new Harness(root, "delayed-account", selected("prior-user", "room-prior", true))) {
            h.nativeSession.state = idle("prior-user");
            h.controller.login("fixture-login", "fixture-account-secret", false);
            h.complete(idle("prior-user")); h.expect("login"); h.complete(account(USER));
            check(h.store.load().value().equals(selected(USER, "", false)), "account replacement clears prior room");
            int requests = h.nativeSession.requests.size();
            rejected("ACCOUNT_CHANGED", () -> h.controller.create("Fixture", "fixture-room-secret"));
            rejected("ACCOUNT_CHANGED", () -> h.controller.connect("12345678"));
            check(h.nativeSession.requests.size() == requests, "old snapshot cannot dispatch account work");
            h.nativeSession.state = idle(USER);
            h.controller.create("Fixture", "fixture-room-secret"); h.expect("status");
            h.complete(idle(USER)); h.expect("create_room");
            check(h.nativeSession.last().arguments().get("name").getAsString().equals("Fixture"), "create name preserved");
            h.complete(room("room-created", null)); h.expect("connect_room");
            check(h.nativeSession.last().arguments().get("room").getAsString().equals("room-created"), "connect canonical created ID");
            check(h.store.load().value().equals(selected(USER, "room-created", false)), "created room durable before connect");
            h.complete(room("room-created", RUNTIME));
            h.nativeSession.state = active(USER, RUNTIME); h.controller.tick();
            check(h.controller.status().contains("CONNECTED"), "created room ready");
            h.controller.host(); h.expect("status"); h.complete(active(USER, RUNTIME));
            check(h.host.hosted == 1 && h.controller.status().contains("HOSTED"), "explicit host publishes exact runtime");
            check(h.store.load().value().equals(selected(USER, "room-created", true)), "initial share intent durable");
            check(h.output.stream().noneMatch(line -> line.contains("fixture-account-secret") || line.contains("fixture-room-secret")), "membership feedback redacted");
            check(!Files.readString(h.file).contains("fixture-room-secret"), "restart intent excludes room password");
            h.host.status = new WorldHost.Status(WorldHost.Phase.FAILED, "RUNTIME_CHANGED"); h.controller.tick();
            check(h.controller.status().contains("FAILED") && h.controller.status().contains("RUNTIME_CHANGED"), "publication loss updates current status");
            int messages = h.output.size(); h.controller.tick();
            check(h.output.size() == messages && h.store.load().value().share(), "failure reported once and restart intent retained");
        }
        try (var h = new Harness(root, "joining", selected(USER, "", false))) {
            h.controller.join("12345678", "fixture-room-secret"); h.complete(idle(USER)); h.expect("join_room");
            check(h.nativeSession.last().arguments().get("code").getAsString().equals("12345678"), "join code preserved");
            h.complete(room("room-joined", null)); h.expect("connect_room");
            check(h.nativeSession.last().arguments().get("room").getAsString().equals("room-joined"), "join connects canonical ID");
            h.complete(room("room-joined", RUNTIME));
            check(h.store.load().value().equals(selected(USER, "room-joined", false)), "joined room durable");
        }
    }
    private static void exactRuntimeSwitchAndDisconnect(Path root) throws Exception {
        try (var h = new Harness(root, "switching", selected(USER, "room-prior", false))) {
            h.nativeSession.state = active(USER, RUNTIME);
            h.controller.connect("87654321"); h.expect("status");
            h.complete(active(USER, NEXT_RUNTIME)); h.expect("stop");
            check(h.nativeSession.last().arguments().get("runtime").getAsString().equals(NEXT_RUNTIME), "switch stops fresh exact runtime");
            h.complete(stopped()); h.expect("connect_room");
            check(h.nativeSession.last().arguments().get("room").getAsString().equals("87654321"), "manual room selection dispatched after stop");
            String replacement = "d".repeat(32);
            h.complete(room("room-next", replacement));
            check(h.controller.status().contains("WAITING_RUNTIME"), "old runtime snapshot cannot finish new selection");
            h.nativeSession.state = active(USER, replacement); h.controller.tick();
            check(h.controller.status().contains("CONNECTED"), "exact new runtime becomes ready");
            check(h.nativeSession.operations().equals(List.of("status", "stop", "connect_room")), "single bounded switch chain");
            check(h.store.load().value().room().equals("room-next"), "switched room canonical ID saved");
        }
        try (var h = new Harness(root, "disconnecting", selected(USER, "room-prior", true))) {
            // The cached status is still idle after a prior connect reply.
            h.nativeSession.state = idle(USER); h.controller.disconnect(); h.expect("status");
            check(h.store.load().value().equals(selected(USER, "", false)), "manual disconnect disables restart intent");
            h.complete(active(USER, NEXT_RUNTIME)); h.expect("stop");
            check(h.nativeSession.last().arguments().get("runtime").getAsString().equals(NEXT_RUNTIME), "disconnect catches reply-before-snapshot runtime");
            h.complete(stopped());
            check(h.controller.status().contains("IDLE") && h.nativeSession.operations().equals(List.of("status", "stop")), "disconnect finishes once");
        }
        try (var h = new Harness(root, "offline-disconnecting", selected(USER, "room-prior", true))) {
            h.nativeSession.live = false; h.controller.disconnect();
            check(h.store.load().value().equals(selected(USER, "", false)), "offline disconnect clears durable intent");
            check(h.host.revoked == 1 && h.nativeSession.requests.isEmpty(), "offline disconnect revokes without unavailable helper");
            check(h.controller.status().contains("IDLE"), "offline disconnect completes");
        }
    }
    private static void saveFailureRevokesAndReports(Path root) throws Exception {
        try (var h = new Harness(root, "save-failure", selected(USER, "room-fixture", false))) {
            h.nativeSession.state = active(USER, RUNTIME);
            h.controller.host(); h.complete(active(USER, RUNTIME));
            check(h.host.hosted == 1, "fixture hosted");
            Path retained = h.file.resolveSibling("retained.json"); Files.move(h.file, retained); Files.createDirectory(h.file);
            int revocations = h.host.revoked;
            rejected("DEDICATED_PATH_UNSAFE", h.controller::unhost);
            check(h.host.revoked == revocations + 1 && h.host.status == null, "failed save still revokes publication");
            check(h.controller.status().contains("FAILED") && h.controller.status().contains("DEDICATED_PATH_UNSAFE"), "save failure visible in status");
            check(new DedicatedServerSettings(retained).load().value().equals(selected(USER, "room-fixture", true)), "prior durable intent retained");
            check(h.nativeSession.operations().equals(List.of("status")), "save failure does not dispatch new network work");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("p2wlan-dedicated-accounts-").toRealPath();
        try {
            initializationAndLogin(root); delayedAccountAndMembership(root);
            exactRuntimeSwitchAndDisconnect(root); saveFailureRevokesAndReports(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS dedicated account/room console workflow: " + checks + " checks");
    }
}
