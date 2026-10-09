package io.p2wlan.mc.common;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One dispatched local intent until its runnable actually drains. The result
 * can expire first; that MUST NOT release the queued slot and admit more work
 * onto a frozen game thread. No worker/retry/queue of our own. Maximum deadline
 * is three seconds. Owners close the gate with their world/application lifetime.
 * Scope predicates and effects must be nonblocking. An already-started effect
 * is never replayed or claimed undone by a result timeout. */
public final class ScheduledTaskGate implements AutoCloseable {
    private record Ticket<T>(long deadline, CompletableFuture<T> result) {}
    private final AtomicReference<Ticket<?>> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long timeoutNanos;
    private final LongSupplier clock;
    public ScheduledTaskGate(Duration timeout) { this(timeout, System::nanoTime); }
    ScheduledTaskGate(Duration timeout, LongSupplier clock) {
        if (timeout == null || clock == null || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(3)) > 0) throw new IllegalArgumentException("INVALID_TASK_DEADLINE");
        this.timeoutNanos = timeout.toNanos(); this.clock = clock;
    }
    public boolean busy() { return pending.get() != null; }
    private static <T> CompletableFuture<T> rejected(ScheduledTaskException.Code code) {
        return CompletableFuture.failedFuture(new ScheduledTaskException(code));
    }
    public <T> CompletableFuture<T> submit(Consumer<Runnable> dispatch, BooleanSupplier current, Callable<T> effect) {
        if (dispatch == null || current == null || effect == null) return rejected(ScheduledTaskException.Code.INVALID_TASK);
        if (closed.get()) return rejected(ScheduledTaskException.Code.CLOSED);
        Ticket<T> ticket = new Ticket<>(clock.getAsLong() + timeoutNanos, new CompletableFuture<>());
        if (!pending.compareAndSet(null, ticket)) return rejected(ScheduledTaskException.Code.CAPACITY);
        if (closed.get()) { pending.compareAndSet(ticket, null); return rejected(ScheduledTaskException.Code.CLOSED); }
        ticket.result().orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
        CompletableFuture<T> result = ticket.result().handle((value, error) -> {
            if (error == null) return value;
            error = UiCompletionGate.unwrap(error);
            if (error instanceof TimeoutException) error = new ScheduledTaskException(ScheduledTaskException.Code.TASK_EXPIRED);
            throw new CompletionException(error);
        });
        try { dispatch.accept(() -> execute(ticket, current, effect)); }
        catch (RuntimeException invalid) {
            ticket.result().completeExceptionally(new ScheduledTaskException(ScheduledTaskException.Code.DISPATCH_REJECTED));
            pending.compareAndSet(ticket, null);
        }
        return result;
    }
    private <T> void execute(Ticket<T> ticket, BooleanSupplier current, Callable<T> effect) {
        try {
            if (closed.get() || pending.get() != ticket) throw new ScheduledTaskException(ScheduledTaskException.Code.CLOSED);
            if (!current.getAsBoolean()) throw new ScheduledTaskException(ScheduledTaskException.Code.SCOPE_CHANGED);
            // Last check after the scope predicate and immediately before the
            // local effect. Expired completions never authorize queued work.
            if (ticket.result().isDone() || clock.getAsLong() - ticket.deadline() >= 0) throw new ScheduledTaskException(ScheduledTaskException.Code.TASK_EXPIRED);
            ticket.result().complete(effect.call());
        } catch (Exception error) {
            ticket.result().completeExceptionally(error instanceof RuntimeException
                    ? new ScheduledTaskException(ScheduledTaskException.Code.TASK_FAILED) : error);
        } finally { pending.compareAndSet(ticket, null); }
    }
    @Override public void close() {
        closed.set(true);
        Ticket<?> ticket = pending.get();
        if (ticket != null) ticket.result().completeExceptionally(new ScheduledTaskException(ScheduledTaskException.Code.CLOSED));
        // A queued runnable keeps its slot until dispatch drains; no replacement
        // may accumulate behind it, even after close/timeout completes a future.
    }
}
