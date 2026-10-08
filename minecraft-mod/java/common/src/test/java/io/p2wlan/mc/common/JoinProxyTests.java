package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Actual subprocess IPC fixture, not a real networking Agent/game connection. */
public final class JoinProxyTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("fixture deadline");
            Thread.sleep(1);
        }
    }
    private static AgentClient agent(Path probe, String mode) throws Exception {
        AgentClient client = AgentClient.launch(probe, List.of(mode));
        client.ready().get(3, TimeUnit.SECONDS); client.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS); return client;
    }
    private static GameService selection(AgentClient client) {
        return new GameService(RuntimeView.ready(client).orElseThrow(), "peer", 1024, "e".repeat(64));
    }
    private static Throwable failed(CompletableFuture<?> future) throws Exception {
        try { future.get(8, TimeUnit.SECONDS); throw new AssertionError("expected failure"); }
        catch (ExecutionException error) { checks++; return error.getCause(); }
    }
    private static void counts(AgentClient client, int joined, int closed) throws Exception {
        JsonObject value = client.request("inspect", new JsonObject()).get(3, TimeUnit.SECONDS).data();
        check(value.get("joined").getAsInt() == joined && value.get("closed").getAsInt() == closed);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("AGENT_PROBE_REQUIRED");
        Path probe = Path.of(args[0]);
        try (AgentClient client = agent(probe, "join")) {
            AtomicBoolean parent = new AtomicBoolean(true);
            JoinProxy proxy = JoinProxy.start(client, selection(client), parent::get);
            LocalEndpoint endpoint = proxy.ready().get(3, TimeUnit.SECONDS);
            check(endpoint.address().getPort() == 4242 && endpoint.keyHex().equals("d".repeat(64)));
            check(!endpoint.toString().contains(endpoint.keyHex()));
            check(proxy.isCurrent() && proxy.status().phase() == JoinProxy.Phase.REGISTERED);
            try { JoinProxy.start(client, selection(client), parent::get); throw new AssertionError("capacity"); }
            catch (JoinProxyException error) { check(error.code() == JoinProxyException.Code.CAPACITY); }
            proxy.revoke(); check(!proxy.isCurrent()); proxy.released().get(3, TimeUnit.SECONDS);
            check(proxy.status().phase() == JoinProxy.Phase.CLOSED && proxy.isReleased()); counts(client, 1, 1);
            proxy.revoke(); check(proxy.status().phase() == JoinProxy.Phase.CLOSED);
            JoinProxy next = JoinProxy.start(client, selection(client), parent::get);
            next.ready().get(3, TimeUnit.SECONDS); parent.set(false);
            check(failed(next.released()) instanceof JoinProxyException error && error.code() == JoinProxyException.Code.ATTEMPT_CHANGED);
            check(!next.isCurrent()); counts(client, 2, 2);
            check(!client.isClosed());
        }
        try (AgentClient client = agent(probe, "join_late")) {
            JoinProxy proxy = JoinProxy.start(client, selection(client), () -> true);
            until(() -> client.snapshot().orElseThrow().data().has("proxy_received")); proxy.revoke();
            check(failed(proxy.ready()) instanceof JoinProxyException error && error.code() == JoinProxyException.Code.ATTEMPT_CHANGED);
            failed(proxy.released()); counts(client, 1, 1); check(!client.isClosed());
        }
        try (AgentClient client = agent(probe, "join_replace")) {
            JoinProxy old = JoinProxy.start(client, selection(client), () -> true);
            old.ready().get(3, TimeUnit.SECONDS); old.revoke();
            check(failed(old.released()) instanceof AgentException error && "RUNTIME_CHANGED".equals(error.remoteCode()));
            check(!client.isClosed() && RuntimeView.ready(client).orElseThrow().runtime().equals("f".repeat(32)));
            old.revoke(); client.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS);
            check(!client.isClosed());
        }
        for (String mode : List.of("join_bad", "join_close_bad")) {
            try (AgentClient client = agent(probe, mode)) {
                JoinProxy proxy = JoinProxy.start(client, selection(client), () -> true);
                if (mode.equals("join_close_bad")) { proxy.ready().get(3, TimeUnit.SECONDS); proxy.revoke(); }
                else check(failed(proxy.ready()) instanceof JoinProxyException error && error.code() == JoinProxyException.Code.INVALID_REPLY);
                failed(proxy.released()); check(client.isClosed() && client.terminated().isDone());
                check(ProcessHandle.of(client.processId()).isEmpty());
            }
        }
        try (AgentClient client = agent(probe, "join")) {
            JoinProxy proxy = JoinProxy.start(client, selection(client), () -> true);
            proxy.ready().get(3, TimeUnit.SECONDS); client.close();
            check(failed(proxy.released()) instanceof JoinProxyException error && error.code() == JoinProxyException.Code.RUNTIME_CHANGED);
            check(client.terminated().isDone());
        }
        until(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().equals("p2wlan-game-join")));
        check(true);
        System.out.println("PASS join proxy lifecycle: " + checks + " explicit subprocess checks (not Minecraft acceptance)");
    }
}
