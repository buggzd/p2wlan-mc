package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One native request OR queued UI completion, with no work queue/retry/thread.
 * Closing a view discards presentation only; it cannot undo a submitted native
 * mutation with uncertain delivery. The native process remains the authority. */
public final class UiCompletionGate implements AutoCloseable {
    public enum Failure { CLOSED, PROCESS_CHANGED, VIEW_CHANGED, DISPATCH_REJECTED }
    private final AtomicReference<Failure> failure = new AtomicReference<>();
    public java.util.Optional<Failure> failure() { return java.util.Optional.ofNullable(failure.get()); }
    private boolean revoked(BooleanSupplier process, BooleanSupplier view) {
        Failure reason = closed.get() ? Failure.CLOSED : !process.getAsBoolean() ? Failure.PROCESS_CHANGED
                : !view.getAsBoolean() ? Failure.VIEW_CHANGED : null;
        if (reason != null) failure.set(reason);
        return reason != null;
    }
    private final AtomicReference<Object> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    public boolean busy() { return pending.get() != null; }
    public void submit(AgentClient client, String operation, JsonObject arguments,
            BooleanSupplier processCurrent, BooleanSupplier viewCurrent,
            Consumer<Runnable> dispatch, BiConsumer<AgentReply, Throwable> completion) throws AgentException {
        if (revoked(processCurrent, viewCurrent)) throw new AgentException(AgentException.Code.CLOSED);
        Object ticket = new Object();
        if (!pending.compareAndSet(null, ticket)) throw new AgentException(AgentException.Code.CAPACITY);
        if (revoked(processCurrent, viewCurrent)) {
            pending.compareAndSet(ticket, null);
            throw new AgentException(AgentException.Code.CLOSED);
        }
        client.request(operation, arguments).whenComplete((reply, error) -> {
            if (revoked(processCurrent, viewCurrent)) {
                pending.compareAndSet(ticket, null);
                return;
            }
            try {
                dispatch.accept(() -> {
                    try {
                        // The actual presentation/game-thread side effect is
                        // fenced again after crossing the executor boundary.
                        if (pending.get() == ticket && !revoked(processCurrent, viewCurrent)) {
                            if (reply != null && !client.isCurrent(reply)) failure.set(Failure.PROCESS_CHANGED);
                            else completion.accept(reply, unwrap(error));
                        }
                    } finally { pending.compareAndSet(ticket, null); }
                });
            } catch (RuntimeException rejected) {
                failure.set(Failure.DISPATCH_REJECTED);
                pending.compareAndSet(ticket, null);
                // Do not run a UI callback on the native reader as a fallback.
            }
        });
    }
    public static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
    /** Stable code only, never a server body, URL, credential or stderr. */
    public static String reason(Throwable error) {
        error = unwrap(error);
        if (error instanceof AgentException agent) return agent.remoteCode() == null ? agent.code().name() : agent.remoteCode();
        if (error instanceof NativeBundleException nativeError) return nativeError.code().name();
        return "INVALID_REPLY";
    }
    @Override public void close() { failure.set(Failure.CLOSED); closed.set(true); pending.set(null); }
}
