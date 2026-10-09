package io.p2wlan.mc.common;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ScheduledTaskGateTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void rejected(CompletableFuture<?> future, ScheduledTaskException.Code code) throws Exception {
        try { future.get(2, TimeUnit.SECONDS); throw new AssertionError("admitted " + code); }
        catch (ExecutionException error) { check(error.getCause() instanceof ScheduledTaskException failure && failure.code() == code); }
    }
    public static void main(String[] args) throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var effects = new AtomicInteger();
        try (var gate = new ScheduledTaskGate(Duration.ofSeconds(3))) {
            var first = gate.submit(queue::add, () -> true, effects::incrementAndGet);
            check(gate.busy() && queue.size() == 1 && effects.get() == 0);
            rejected(gate.submit(queue::add, () -> true, effects::incrementAndGet), ScheduledTaskException.Code.CAPACITY);
            check(queue.size() == 1); queue.remove().run();
            check(first.get() == 1 && effects.get() == 1 && !gate.busy());
            var scope = new AtomicBoolean(true);
            var stale = gate.submit(queue::add, scope::get, effects::incrementAndGet);
            scope.set(false); queue.remove().run(); rejected(stale, ScheduledTaskException.Code.SCOPE_CHANGED);
            check(effects.get() == 1 && !gate.busy());
            rejected(gate.submit(task -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> true, effects::incrementAndGet), ScheduledTaskException.Code.DISPATCH_REJECTED);
            check(!gate.busy() && effects.get() == 1);
        }
        var clock = new AtomicLong(100);
        try (var gate = new ScheduledTaskGate(Duration.ofSeconds(3), clock::get)) {
            var expired = gate.submit(queue::add, () -> true, effects::incrementAndGet);
            clock.addAndGet(Duration.ofSeconds(3).toNanos()); queue.remove().run();
            rejected(expired, ScheduledTaskException.Code.TASK_EXPIRED); check(effects.get() == 1 && !gate.busy());
            var invalidScope = gate.submit(queue::add, () -> { throw new IllegalStateException(); }, effects::incrementAndGet);
            queue.remove().run(); rejected(invalidScope, ScheduledTaskException.Code.TASK_FAILED); check(!gate.busy());
            var badEffect = gate.submit(queue::add, () -> true, () -> { throw new IllegalStateException(); });
            queue.remove().run(); rejected(badEffect, ScheduledTaskException.Code.TASK_FAILED); check(!gate.busy());
            var stopped = gate.submit(queue::add, () -> true, effects::incrementAndGet);
            gate.close(); rejected(stopped, ScheduledTaskException.Code.CLOSED); check(gate.busy());
            rejected(gate.submit(queue::add, () -> true, effects::incrementAndGet), ScheduledTaskException.Code.CLOSED);
            queue.remove().run(); check(!gate.busy() && effects.get() == 1);
        }
        try (var gate = new ScheduledTaskGate(Duration.ofMillis(30))) {
            var timeout = gate.submit(queue::add, () -> true, effects::incrementAndGet);
            rejected(timeout, ScheduledTaskException.Code.TASK_EXPIRED);
            check(gate.busy() && queue.size() == 1);
            rejected(gate.submit(queue::add, () -> true, effects::incrementAndGet), ScheduledTaskException.Code.CAPACITY);
            queue.remove().run(); check(effects.get() == 1 && !gate.busy());
            var inline = gate.submit(Runnable::run, () -> true, effects::incrementAndGet);
            check(inline.get() == 2 && !gate.busy());
            rejected(gate.submit(null, () -> true, effects::incrementAndGet), ScheduledTaskException.Code.INVALID_TASK);
        }
        System.out.println("PASS scheduled task fencing: " + checks + " explicit capacity/deadline/cancel checks (not Minecraft acceptance)");
    }
}
