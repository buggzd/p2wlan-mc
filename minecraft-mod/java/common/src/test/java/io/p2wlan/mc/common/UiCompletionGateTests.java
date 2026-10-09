package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class UiCompletionGateTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void submit(UiCompletionGate gate, AgentClient client, AtomicBoolean live,
                               ArrayBlockingQueue<Runnable> queue, AtomicInteger applied) throws Exception {
        gate.submit(client, "echo", new JsonObject(), () -> !client.isClosed(), live::get, task -> {
            if (!queue.offer(task)) throw new java.util.concurrent.RejectedExecutionException();
        }, (reply, error) -> { check(error == null && client.isCurrent(reply)); applied.incrementAndGet(); });
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("AGENT_PROBE_REQUIRED");
        try (AgentClient client = AgentClient.launch(Path.of(args[0]), java.util.List.of("echo"), Duration.ofSeconds(2));
             UiCompletionGate gate = new UiCompletionGate()) {
            client.ready().get(3, TimeUnit.SECONDS);
            ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1);
            AtomicBoolean live = new AtomicBoolean(true);
            AtomicInteger applied = new AtomicInteger();
            submit(gate, client, live, queue, applied);
            Runnable first = queue.poll(3, TimeUnit.SECONDS); check(first != null && gate.busy());
            try { submit(gate, client, live, queue, applied); throw new AssertionError("second queued UI task admitted"); }
            catch (AgentException error) { check(error.code() == AgentException.Code.CAPACITY); }
            first.run(); check(applied.get() == 1 && !gate.busy());
            submit(gate, client, live, queue, applied);
            Runnable stale = queue.poll(3, TimeUnit.SECONDS); check(stale != null);
            live.set(false); stale.run(); check(applied.get() == 1 && !gate.busy());
            check(gate.failure().orElseThrow() == UiCompletionGate.Failure.VIEW_CHANGED);
            try { submit(gate, client, live, queue, applied); throw new AssertionError("closed view admitted"); }
            catch (AgentException error) { check(error.code() == AgentException.Code.CLOSED); }
            live.set(true); submit(gate, client, live, queue, applied);
            Runnable shutdown = queue.poll(3, TimeUnit.SECONDS); check(shutdown != null);
            gate.close(); shutdown.run(); check(applied.get() == 1 && !gate.busy());
            check(gate.failure().orElseThrow() == UiCompletionGate.Failure.CLOSED);
        }
        try (AgentClient client = AgentClient.launch(Path.of(args[0]), java.util.List.of("echo"), Duration.ofSeconds(2));
             UiCompletionGate gate = new UiCompletionGate()) {
            client.ready().get(3, TimeUnit.SECONDS);
            var failedDispatch = new java.util.concurrent.CountDownLatch(1);
            gate.submit(client, "echo", new JsonObject(), () -> !client.isClosed(), () -> true,
                    task -> { failedDispatch.countDown(); throw new java.util.concurrent.RejectedExecutionException(); },
                    (reply, error) -> { throw new AssertionError("callback moved to native reader"); });
            check(failedDispatch.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (gate.busy() && System.nanoTime() - deadline < 0) Thread.sleep(1);
            check(!gate.busy() && gate.failure().orElseThrow() == UiCompletionGate.Failure.DISPATCH_REJECTED);
        }
        try (AgentClient client = AgentClient.launch(Path.of(args[0]), java.util.List.of("echo"), Duration.ofSeconds(2));
             UiCompletionGate gate = new UiCompletionGate()) {
            client.ready().get(3, TimeUnit.SECONDS);
            ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1);
            gate.submit(client, "echo", new JsonObject(), () -> true, () -> true, queue::add,
                    (reply, error) -> { throw new AssertionError("retired reply applied"); });
            Runnable task = queue.poll(3, TimeUnit.SECONDS); check(task != null);
            client.close(); task.run();
            check(!gate.busy() && gate.failure().orElseThrow() == UiCompletionGate.Failure.PROCESS_CHANGED);
        }
        try (UiCompletionGate gate = new UiCompletionGate()) {
            var future = new java.util.concurrent.CompletableFuture<String>();
            var queue = new ArrayBlockingQueue<Runnable>(1);
            var issued = new AtomicInteger(); var applied = new AtomicInteger();
            var view = new AtomicBoolean(true);
            gate.submitTask(() -> { issued.incrementAndGet(); return future; }, () -> true, view::get, queue::add,
                    (value, error) -> { check("task".equals(value) && error == null); applied.incrementAndGet(); });
            check(gate.busy() && issued.get() == 1);
            try {
                gate.submitTask(() -> { issued.incrementAndGet(); return future; }, () -> true, view::get, queue::add, (value, error) -> {});
                throw new AssertionError("task side effect admitted over capacity");
            } catch (AgentException error) { check(error.code() == AgentException.Code.CAPACITY && issued.get() == 1); }
            future.complete("task"); check(queue.size() == 1 && gate.busy());
            view.set(false); queue.remove().run(); check(applied.get() == 0 && !gate.busy());
            view.set(true);
            try { gate.submitTask(() -> null, () -> true, view::get, queue::add, (value, error) -> {}); throw new AssertionError("null task admitted"); }
            catch (AgentException error) { check(error.code() == AgentException.Code.INVALID_REQUEST && !gate.busy()); }
            var next = new java.util.concurrent.CompletableFuture<String>();
            gate.submitTask(() -> next, () -> true, view::get, queue::add, (value, error) -> applied.incrementAndGet());
            next.complete("task"); queue.remove().run(); check(applied.get() == 1 && !gate.busy());
        }
        try (AgentClient client = AgentClient.launch(Path.of(args[0]), java.util.List.of("world_starting"))) {
            client.ready().get(3, TimeUnit.SECONDS); client.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS);
            check(RuntimeView.ready(client).isEmpty());
            RuntimeView starting = RuntimeView.active(client).orElseThrow();
            check(starting.isActive(client) && !starting.isCurrent(client));
            check(!new RuntimeView(starting.owner(), "f".repeat(32)).isActive(client));
            client.close(); check(!starting.isActive(client));
        }
        check(UiCompletionGate.reason(new java.util.concurrent.CompletionException(
                new AgentException(AgentException.Code.REQUEST_TIMEOUT))).equals("REQUEST_TIMEOUT"));
        System.out.println("PASS UI completion fencing: " + checks + " explicit checks against actual native subprocesses");
    }
}
