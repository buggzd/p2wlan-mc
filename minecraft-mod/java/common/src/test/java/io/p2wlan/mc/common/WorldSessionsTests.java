package io.p2wlan.mc.common;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Scope ownership/fencing decisions, with no game or native process. */
public final class WorldSessionsTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("WORLD_SCOPE_" + checks); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(WorldHostException.Code reason, Action action) throws Exception {
        try { action.run(); throw new AssertionError("EXPECTED_" + reason); }
        catch (WorldHostException error) { check(error.code() == reason); }
    }
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("p2wlan-world-scope-");
        try (var agent = new AgentLifecycle(directory.resolve("private"), false);
             var scopes = new WorldSessions<Object>(agent);
             var other = new WorldSessions<Object>(agent)) {
            Object server = new Object();
            AtomicBoolean onThread = new AtomicBoolean(true);
            AtomicInteger created = new AtomicInteger();
            WorldHost.TargetFactory factory = fence -> {
                created.incrementAndGet(); throw new WorldHostException(WorldHostException.Code.BIND_FAILED);
            };
            var first = scopes.started(server, onThread::get, factory);
            check(first.isCurrent() && scopes.current(server) == first);
            check(first.server() == server && scopes.current(new Object()) == null);
            check(first.status() == null && !first.hosted());
            fails(WorldHostException.Code.RUNTIME_CHANGED, first::host);
            onThread.set(false); fails(WorldHostException.Code.WORLD_CHANGED, () -> first.host(null));
            onThread.set(true);
            java.util.List<Runnable> queue = new java.util.ArrayList<>();
            var queued = first.intents().submit(queue::add, first::isCurrent, () -> { created.incrementAndGet(); return true; });
            check(first.intents().busy() && !queued.isDone());
            var second = scopes.started(server, onThread::get, factory);
            check(!first.isCurrent() && second.isCurrent());
            check(queued.isCompletedExceptionally()); queue.get(0).run();
            check(created.get() == 0 && !first.intents().busy());
            fails(WorldHostException.Code.WORLD_CHANGED, first::host);
            scopes.stopped(first); check(scopes.current(server) == second);
            other.stopped(second); check(scopes.current(server) == second);
            var foreign = other.started(server, onThread::get, factory);
            scopes.stopped(foreign); check(second.isCurrent() && foreign.isCurrent());
            scopes.stopped(second); check(!second.isCurrent() && scopes.current(server) == null);
            check(second.intents().submit(Runnable::run, () -> true, () -> true).isCompletedExceptionally());
            var third = scopes.started(new Object(), onThread::get, factory);
            check(third.isCurrent() && scopes.current(server) == null);
            scopes.close(); check(!third.isCurrent());
            scopes.close(); check(!foreign.hosted() && created.get() == 0);
            check(agent.status().phase() == AgentLifecycle.Phase.NEW);
        } finally { Files.delete(directory); }
        System.out.println("PASS shared world scope: " + checks + " ownership/thread/late-cleanup checks");
    }
}
