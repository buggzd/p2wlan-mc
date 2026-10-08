package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Real child IPC + local listeners; these fixtures do not start Minecraft. */
public final class WorldHostTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void until(BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() - end >= 0) throw new AssertionError("fixture event deadline");
            Thread.sleep(1);
        }
    }
    private static AgentClient agent(Path probe, String mode) throws Exception {
        AgentClient client = AgentClient.launch(probe, java.util.List.of(mode));
        client.ready().get(3, TimeUnit.SECONDS); client.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS); return client;
    }
    private static Throwable failed(java.util.concurrent.CompletableFuture<?> future) throws Exception {
        try { future.get(8, TimeUnit.SECONDS); throw new AssertionError("expected failure"); }
        catch (ExecutionException error) { checks++; return error.getCause(); }
    }
    private static final class Target implements WorldHost.Target {
        final InetSocketAddress game;
        final BooleanSupplier current;
        final CountDownLatch entered = new CountDownLatch(1), proceed;
        volatile WorldGuard guard;
        Target(InetSocketAddress game, BooleanSupplier current, CountDownLatch proceed) { this.game = game; this.current = current; this.proceed = proceed; }
        @Override public void open() throws WorldHostException {
            entered.countDown();
            try {
                if (proceed != null && !proceed.await(2, TimeUnit.SECONDS)) throw new WorldHostException(WorldHostException.Code.BIND_TIMEOUT);
                if (!current.getAsBoolean()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
                guard = WorldGuard.open(game);
            } catch (InterruptedException interrupted) { throw new WorldHostException(WorldHostException.Code.INTERRUPTED); }
            catch (WorldGuardException error) { throw new WorldHostException(WorldHostException.Code.BIND_FAILED); }
        }
        @Override public LocalEndpoint endpoint() { return guard.endpoint(); }
        @Override public void revoke() { if (guard != null) guard.revoke(); }
        @Override public void close() throws WorldHostException {
            try { if (guard != null) guard.close(); }
            catch (WorldGuardException error) { throw new WorldHostException(WorldHostException.Code.CLEANUP_FAILED); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("AGENT_PROBE_REQUIRED");
        Path probe = Path.of(args[0]);
        try (ServerSocket game = new ServerSocket()) {
            game.bind(new InetSocketAddress("127.0.0.1", 0)); InetSocketAddress address = (InetSocketAddress) game.getLocalSocketAddress();
            try (AgentClient client = agent(probe, "world")) {
                RuntimeView runtime = RuntimeView.ready(client).orElseThrow(); check(runtime.isCurrent(client));
                check(!new RuntimeView(runtime.owner(), "a".repeat(32)).isCurrent(client));
                AtomicBoolean parent = new AtomicBoolean(true);
                WorldHost host = WorldHost.start(client, runtime, parent::get, fence -> new Target(address, fence, null));
                var hosted = host.ready().get(3, TimeUnit.SECONDS);
                check(hosted.world().matches("[a-f0-9]{32}") && hosted.service().equals("d".repeat(64)) && hosted.port() == 1024);
                check(host.status().phase() == WorldHost.Phase.HOSTED && host.isCurrent());
                try { WorldHost.start(client, runtime, parent::get, fence -> new Target(address, fence, null)); throw new AssertionError("second host admitted"); }
                catch (WorldHostException error) { check(error.code() == WorldHostException.Code.CAPACITY); }
                host.revoke(); check(!host.isCurrent()); host.released().get(8, TimeUnit.SECONDS);
                check(host.isReleased() && host.status().phase() == WorldHost.Phase.CLOSED);
                JsonObject counts = client.request("inspect", new JsonObject()).get(3, TimeUnit.SECONDS).data();
                check(counts.get("hosted").getAsInt() == 1 && counts.get("closed").getAsInt() == 1);
                host.revoke(); check(host.status().phase() == WorldHost.Phase.CLOSED);
                CountDownLatch proceed = new CountDownLatch(1);
                Target target = new Target(address, parent::get, proceed);
                WorldHost cancelled = WorldHost.start(client, runtime, parent::get, fence -> target);
                check(target.entered.await(2, TimeUnit.SECONDS)); parent.set(false); cancelled.revoke(); proceed.countDown();
                check(failed(cancelled.ready()) instanceof WorldHostException error && error.code() == WorldHostException.Code.WORLD_CHANGED);
                failed(cancelled.released()); check(cancelled.isReleased());
                counts = client.request("inspect", new JsonObject()).get(3, TimeUnit.SECONDS).data(); check(counts.get("hosted").getAsInt() == 1);
                parent.set(true);
                WorldHost next = WorldHost.start(client, runtime, parent::get, fence -> new Target(address, fence, null));
                next.ready().get(3, TimeUnit.SECONDS); next.revoke(); next.released().get(8, TimeUnit.SECONDS);
                check(!client.isClosed());
            }
            try (AgentClient client = agent(probe, "world_late")) {
                WorldHost host = WorldHost.start(client, RuntimeView.ready(client).orElseThrow(), () -> true, fence -> new Target(address, fence, null));
                until(() -> client.snapshot().orElseThrow().data().has("host_received")); host.revoke();
                check(failed(host.ready()) instanceof WorldHostException error && error.code() == WorldHostException.Code.WORLD_CHANGED);
                failed(host.released());
                JsonObject counts = client.request("inspect", new JsonObject()).get(3, TimeUnit.SECONDS).data();
                check(counts.get("hosted").getAsInt() == 1 && counts.get("closed").getAsInt() == 1);
            }
            try (AgentClient client = agent(probe, "world_bad")) {
                WorldHost host = WorldHost.start(client, RuntimeView.ready(client).orElseThrow(), () -> true, fence -> new Target(address, fence, null));
                check(failed(host.ready()) instanceof WorldHostException error && error.code() == WorldHostException.Code.INVALID_REPLY);
                failed(host.released()); check(client.isClosed()); check(host.status().reason().equals("INVALID_REPLY"));
            }
        }
        until(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && (thread.getName().equals("p2wlan-world-host") || thread.getName().equals("p2wlan-world-guard"))));
        check(true);
        System.out.println("PASS local world publication: " + checks + " explicit child/socket checks (not Minecraft acceptance)");
    }
}
