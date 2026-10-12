package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AgentClientTests {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("agent check " + checks);
    }
    private static AgentClient peer(Path probe, String mode, long timeoutMillis) throws Exception {
        AgentClient client = AgentClient.launch(probe, List.of(mode), Duration.ofMillis(timeoutMillis));
        try { client.ready().get(3, TimeUnit.SECONDS); return client; }
        catch (Exception error) { client.close(); throw error; }
    }
    private static AgentException failure(CompletableFuture<?> future, AgentException.Code code) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); throw new AssertionError("unexpected success " + code); }
        catch (ExecutionException error) {
            check(error.getCause() instanceof AgentException);
            AgentException reason = (AgentException) error.getCause();
            check(reason.code() == code);
            return reason;
        }
    }
    private static void ended(AgentClient client, AgentException.Code code) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!client.isClosed() && System.nanoTime() - deadline < 0) Thread.sleep(5);
        check(client.isClosed() && client.failure().orElseThrow() == code);
        ProcessHandle.of(client.processId()).ifPresent(handle -> {
            try { handle.onExit().get(3, TimeUnit.SECONDS); }
            catch (Exception error) { throw new AssertionError("agent child leaked", error); }
        });
        check(client.snapshot().isEmpty());
    }
    private static JsonObject data(int value) {
        JsonObject object = new JsonObject(); object.addProperty("value", value); return object;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("AGENT_PROBE_REQUIRED");
        Path probe = Path.of(args[0]);
        for (String code : List.of("PROFILE_IO", "PROFILE_INVALID", "PROFILE_BUSY", "PROFILE_CAPACITY",
                "PROFILE_UNSUPPORTED_PLATFORM", "CONTROL_UNAVAILABLE", "ENTROPY_UNAVAILABLE")) {
            JsonObject frame = new JsonObject(); frame.addProperty("version", 1); frame.addProperty("event", "startup_error"); frame.addProperty("error", code);
            check(AgentMessages.parse(frame) instanceof AgentMessages.StartupError startup && startup.error().equals(code));
            frame.addProperty("owner", "a".repeat(32));
            try { AgentMessages.parse(frame); throw new AssertionError("startup frame granted owner"); }
            catch (AgentException invalid) { check(invalid.code() == AgentException.Code.INVALID_REPLY); }
        }
        check(new AgentException(AgentException.Code.INITIALIZATION_FAILED, "fixture-private-value").diagnostic().equals("INITIALIZATION_FAILED"));
        // Repeat immediate termination: a waitFor callback must never hide a
        // valid final startup frame before the protocol reader has drained it.
        for (int iteration = 0; iteration < 12; iteration++) {
            try (AgentClient client = AgentClient.launch(probe, List.of("startup_error"), Duration.ofSeconds(1))) {
                AgentException error = failure(client.ready(), AgentException.Code.INITIALIZATION_FAILED);
                check("PROFILE_BUSY".equals(error.remoteCode()));
                check(error.diagnostic().equals("INITIALIZATION_FAILED / PROFILE_BUSY"));
                ended(client, AgentException.Code.INITIALIZATION_FAILED);
                client.terminated().get(3, TimeUnit.SECONDS);
                check(client.failureDetails().orElseThrow().remoteCode().equals("PROFILE_BUSY"));
            }
        }
        ModDiagnostics startupLog = new ModDiagnostics(null); startupLog.start();
        try (AgentClient client = AgentClient.launch(probe, List.of("startup_diagnostic"), Duration.ofSeconds(1), startupLog)) {
            AgentException error = failure(client.ready(), AgentException.Code.INITIALIZATION_FAILED);
            check(error.startupDiagnostic().osCode() == 32);
            check(error.startupDiagnostic().target().equals("ACCOUNT_FILE"));
            ended(client, AgentException.Code.INITIALIZATION_FAILED);
            client.terminated().get(3, TimeUnit.SECONDS);
            check(startupLog.snapshot().copyText().contains("os_code=32"));
            check(startupLog.snapshot().copyText().contains("event=CHILD_EXIT"));
            check(!startupLog.snapshot().copyText().contains("fixture-private-value"));
        }
        startupLog.close();
        for (String mode : List.of("startup_unknown", "startup_extra", "startup_diagnostic_secret")) {
            try (AgentClient client = AgentClient.launch(probe, List.of(mode), Duration.ofSeconds(1))) {
                AgentException error = failure(client.ready(), AgentException.Code.INVALID_REPLY);
                check(!error.diagnostic().contains("fixture-private-value"));
                ended(client, AgentException.Code.INVALID_REPLY);
            }
        }
        try (AgentClient client = AgentClient.launch(probe, List.of("startup_hang"), Duration.ofSeconds(1))) {
            failure(client.ready(), AgentException.Code.INITIALIZATION_FAILED);
            client.terminated().get(3, TimeUnit.SECONDS);
            ended(client, AgentException.Code.INITIALIZATION_FAILED);
        }
        try (AgentClient client = AgentClient.launch(probe, List.of("exit_before_hello"), Duration.ofSeconds(1))) {
            AgentException error = failure(client.ready(), AgentException.Code.UNEXPECTED_EXIT);
            check(error.exitCode() != null && error.exitCode() == 23);
            check(error.diagnostic().equals("UNEXPECTED_EXIT / 0x00000017"));
            ended(client, AgentException.Code.UNEXPECTED_EXIT);
        }
        check(AgentException.exited((int) 0xC0000135L).diagnostic().equals("UNEXPECTED_EXIT / 0xC0000135"));
        try (AgentClient client = AgentClient.launch(probe, List.of("startup_after_hello"), Duration.ofSeconds(1))) {
            client.ready().get(3, TimeUnit.SECONDS);
            ended(client, AgentException.Code.INVALID_REPLY);
        }
        try (AgentClient client = peer(probe, "echo", 2000)) {
            JsonObject object = data(17);
            var reply = client.request("echo", object);
            object.addProperty("value", 999);
            check(reply.get(3, TimeUnit.SECONDS).data().get("value").getAsInt() == 17);
            check(client.request("echo", data(1)).thenCompose(value -> client.request("echo", value.data()))
                .get(3, TimeUnit.SECONDS).data().equals(data(1)));
            failure(client.request("INVALID OP", new JsonObject()), AgentException.Code.INVALID_REQUEST);
            JsonObject cyclic = new JsonObject(); cyclic.add("cycle", cyclic);
            failure(client.request("echo", cyclic), AgentException.Code.INVALID_REQUEST);
            check(client.request("echo", data(2)).get(3, TimeUnit.SECONDS).data().equals(data(2)));
            var executor = Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<Boolean>> replies = new ArrayList<>();
                for (int index = 0; index < 24; index++) {
                    int value = index;
                    replies.add(executor.submit(() -> client.request("echo", data(value)).get(2, TimeUnit.SECONDS).data().equals(data(value))));
                }
                for (var item : replies) check(item.get(3, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); check(executor.awaitTermination(2, TimeUnit.SECONDS)); }
        }
        try (AgentClient client = peer(probe, "echo", 5000)) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var live = new java.util.concurrent.atomic.AtomicBoolean(true);
            var queued = client.request("echo", data(6), () -> {
                if (calls.incrementAndGet() > 1) {
                    entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) return false; }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
                }
                return live.get();
            });
            check(entered.await(2, TimeUnit.SECONDS));
            live.set(false); release.countDown();
            failure(queued, AgentException.Code.SCOPE_CHANGED);
            check(!client.isClosed());
            failure(client.request("echo", data(7), () -> false), AgentException.Code.SCOPE_CHANGED);
            failure(client.request("echo", data(7), () -> { throw new IllegalStateException(); }), AgentException.Code.INVALID_REQUEST);
            check(client.request("echo", data(8)).get(3, TimeUnit.SECONDS).data().equals(data(8)));
        }
        try (AgentClient client = peer(probe, "stderr", 2000)) {
            check(client.request("echo", data(4)).get(3, TimeUnit.SECONDS).data().equals(data(4)));
        }
        try (AgentClient client = peer(probe, "status", 1000)) {
            client.request("echo", new JsonObject()).get(3, TimeUnit.SECONDS);
            check(client.snapshot().orElseThrow().revision() == 2);
            client.snapshot().orElseThrow().data().addProperty("value", 99);
            check(client.snapshot().orElseThrow().data().get("value").getAsInt() == 2);
        }
        try (AgentClient client = peer(probe, "remote", 1000)) {
            AgentException error = failure(client.request("echo", new JsonObject()), AgentException.Code.REMOTE_REJECTED);
            check("AUTH_REQUIRED".equals(error.remoteCode()));
            check(!client.isClosed());
            check(client.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS).data().size() == 0);
        }
        for (String mode : List.of("bad_owner", "bad_id", "fractional_id", "event_owner")) {
            try (AgentClient client = peer(probe, mode, 1000)) {
                failure(client.request("echo", new JsonObject()), AgentException.Code.INVALID_REPLY);
                ended(client, AgentException.Code.INVALID_REPLY);
            }
        }
        try (AgentClient client = peer(probe, "duplicate", 1000)) {
            client.request("echo", new JsonObject()).handle((result, error) -> null).get(3, TimeUnit.SECONDS);
            ended(client, AgentException.Code.INVALID_REPLY);
        }
        try (AgentClient client = peer(probe, "exit", 1000)) {
            failure(client.request("echo", new JsonObject()), AgentException.Code.UNEXPECTED_EXIT);
            ended(client, AgentException.Code.UNEXPECTED_EXIT);
        }
        for (String mode : List.of("partial", "no_read")) {
            try (AgentClient client = peer(probe, mode, 250)) {
                JsonObject large = new JsonObject(); large.addProperty("payload", "x".repeat(32000));
                var first = client.request("echo", large);
                client.request("echo", large); client.request("echo", large); client.request("echo", large);
                failure(first, AgentException.Code.REQUEST_TIMEOUT);
                ended(client, AgentException.Code.REQUEST_TIMEOUT);
            }
        }
        for (String mode : List.of("no_hello", "bad_version")) {
            AgentException.Code code = mode.equals("no_hello") ? AgentException.Code.HANDSHAKE_TIMEOUT : AgentException.Code.PROTOCOL_MISMATCH;
            try (AgentClient client = AgentClient.launch(probe, List.of(mode), Duration.ofMillis(250))) {
                failure(client.ready(), code); ended(client, code);
            }
        }
        try (AgentClient client = peer(probe, "hold", 2000)) {
            List<CompletableFuture<AgentReply>> held = new ArrayList<>();
            for (int i = 0; i < AgentClient.MAX_PENDING; i++) held.add(client.request("echo", data(i)));
            failure(client.request("echo", data(99)), AgentException.Code.CAPACITY);
            client.close();
            for (var pending : held) failure(pending, AgentException.Code.CLOSED);
            ended(client, AgentException.Code.CLOSED);
            failure(client.request("echo", new JsonObject()), AgentException.Code.CLOSED);
        }
        AgentClient old = peer(probe, "echo", 1000);
        AgentReply oldReply = old.request("echo", data(5)).get(3, TimeUnit.SECONDS);
        check(old.isCurrent(oldReply));
        oldReply.data().addProperty("value", 42);
        check(oldReply.data().equals(data(5)));
        try (AgentClient replacement = peer(probe, "echo", 1000)) {
            check(!replacement.isCurrent(oldReply));
            old.close(); ended(old, AgentException.Code.CLOSED);
            check(!old.isCurrent(oldReply));
            check(replacement.request("echo", data(7)).get(3, TimeUnit.SECONDS).data().equals(data(7)));
        } finally { old.close(); }
        try (AgentClient client = peer(probe, "echo", 1000)) {
            var field = AgentClient.class.getDeclaredField("nextId"); field.setAccessible(true);
            field.setLong(client, Long.MAX_VALUE);
            failure(client.request("echo", new JsonObject()), AgentException.Code.IDENTITY_EXHAUSTED);
            ended(client, AgentException.Code.IDENTITY_EXHAUSTED);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        boolean threadsEnded;
        do {
            threadsEnded = Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("p2wlan-agent-"));
            if (!threadsEnded) Thread.sleep(10);
        } while (!threadsEnded && System.nanoTime() - deadline < 0);
        check(threadsEnded);
        System.out.println("PASS agent lifecycle: " + checks + " explicit checks against actual native subprocesses");
    }
}
