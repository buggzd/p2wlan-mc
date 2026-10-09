package io.p2wlan.mc.fabric.connect;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.p2wlan.mc.common.LocalEndpoint;
import java.io.IOException;
import java.net.InetSocketAddress;

/** A synchronous bootstrap scope, not a transport/peer owner. Captures the
 * exact Minecraft connection and target before Netty's asynchronous boundary.
 * Ordinary Vanilla connection/query bootstraps remain unchanged. */
public final class ScopedClientConnect {
    /** Added only by the required exact-version client Mixin. */
    public interface Hooks {}
    public enum Failure { MISSING_HOOK, CAPACITY, ATTEMPT_CHANGED, CONNECTION_CHANGED, TARGET_MISMATCH, IO }
    public static final class FailureException extends IOException {
        private final Failure reason;
        public FailureException(Failure reason) { super("JOIN_BOOTSTRAP_" + reason.name()); this.reason = reason; }
        public Failure reason() { return reason; }
    }
    public interface Factory { ChannelFuture connect() throws FailureException; }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final class Scope {
        final Object connection; final LocalEndpoint endpoint; final ProxyPrelude prelude;
        boolean consumed;
        Scope(Object connection, LocalEndpoint endpoint, ProxyPrelude prelude) {
            this.connection = connection; this.endpoint = endpoint; this.prelude = prelude;
        }
        void current() throws FailureException {
            if (!prelude.isCurrent()) throw new FailureException(Failure.ATTEMPT_CHANGED);
        }
    }
    private ScopedClientConnect() {}
    /** No I/O wait, work queue, retry or global address matching. The factory
     * must call the exact hooked Minecraft connect overload synchronously. */
    public static ChannelFuture open(Object connection, LocalEndpoint endpoint, ProxyPrelude prelude, Factory factory) throws FailureException {
        if (!(connection instanceof Hooks)) throw new FailureException(Failure.MISSING_HOOK);
        if (endpoint == null || prelude == null || factory == null) throw new FailureException(Failure.IO);
        if (CURRENT.get() != null) throw new FailureException(Failure.CAPACITY);
        Scope scope = new Scope(connection, endpoint, prelude); scope.current();
        ChannelFuture future = null;
        CURRENT.set(scope);
        try {
            future = factory.connect();
            if (!scope.consumed) throw new FailureException(Failure.MISSING_HOOK);
            scope.current();
            if (future == null) throw new FailureException(Failure.IO);
            return future;
        } catch (FailureException failure) {
            prelude.revoke(); if (future != null) future.channel().close(); throw failure;
        } catch (RuntimeException failure) {
            prelude.revoke(); if (future != null) future.channel().close(); throw new FailureException(Failure.IO);
        } finally { CURRENT.remove(); }
    }
    /** Invoked by the scoped redirect before an unguarded socket can open. */
    public static ChannelHandler wrap(Object connection, InetSocketAddress address, ChannelHandler vanilla) throws FailureException {
        Scope scope = CURRENT.get();
        if (scope == null) return vanilla;
        scope.current();
        if (scope.connection != connection) throw new FailureException(Failure.CONNECTION_CHANGED);
        if (!scope.endpoint.address().equals(address)) throw new FailureException(Failure.TARGET_MISMATCH);
        if (scope.consumed) throw new FailureException(Failure.CAPACITY);
        scope.consumed = true;
        return scope.prelude.wrap(vanilla);
    }
}
