package io.p2wlan.mc.transport;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.p2wlan.mc.common.LocalEndpoint;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bootstrap admission/identity tests; actual Minecraft transformation is not
 * simulated by these fake marker identities. */
public final class ScopedClientConnectTests {
    private static int checks;
    private static final class Connection implements ScopedClientConnect.Hooks {}
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(ScopedClientConnect.Failure reason, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + reason); }
        catch (ScopedClientConnect.FailureException failure) { check(failure.reason() == reason); }
    }
    public static void main(String[] args) throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 4242);
        LocalEndpoint endpoint = LocalEndpoint.from(address, "d".repeat(64));
        Connection connection = new Connection(); ChannelHandler vanilla = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            check(ScopedClientConnect.wrap(new Object(), address, vanilla) == vanilla);
            AtomicBoolean called = new AtomicBoolean();
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                reject(ScopedClientConnect.Failure.MISSING_HOOK, () -> ScopedClientConnect.open(new Object(), endpoint, transport, () -> { called.set(true); return channel.newSucceededFuture(); }));
                check(!called.get());
            }
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                var future = ScopedClientConnect.open(connection, endpoint, transport, () -> {
                    check(ScopedClientConnect.wrap(connection, address, vanilla) != vanilla);
                    try {
                        reject(ScopedClientConnect.Failure.CAPACITY, () -> ScopedClientConnect.open(connection, endpoint, transport, channel::newSucceededFuture));
                    } catch (ScopedClientConnect.FailureException failure) { throw failure; }
                    catch (Exception unexpected) { throw new AssertionError(unexpected); }
                    return channel.newSucceededFuture();
                });
                check(future.isSuccess() && transport.isCurrent());
            }
            for (boolean target : new boolean[]{false, true}) {
                try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                    reject(target ? ScopedClientConnect.Failure.TARGET_MISMATCH : ScopedClientConnect.Failure.CONNECTION_CHANGED,
                        () -> ScopedClientConnect.open(connection, endpoint, transport, () -> {
                            ScopedClientConnect.wrap(target ? connection : new Connection(), target ? new InetSocketAddress("127.0.0.1", 4243) : address, vanilla);
                            called.set(true); return channel.newSucceededFuture();
                        }));
                    check(!transport.isCurrent());
                }
                check(ScopedClientConnect.wrap(connection, address, vanilla) == vanilla);
            }
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                reject(ScopedClientConnect.Failure.CAPACITY, () -> ScopedClientConnect.open(connection, endpoint, transport, () -> {
                    ScopedClientConnect.wrap(connection, address, vanilla); ScopedClientConnect.wrap(connection, address, vanilla);
                    return channel.newSucceededFuture();
                }));
            }
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                reject(ScopedClientConnect.Failure.MISSING_HOOK, () -> ScopedClientConnect.open(connection, endpoint, transport, channel::newSucceededFuture));
                check(!channel.isOpen());
            }
            AtomicBoolean current = new AtomicBoolean(true);
            EmbeddedChannel old = new EmbeddedChannel();
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, current::get)) {
                reject(ScopedClientConnect.Failure.ATTEMPT_CHANGED, () -> ScopedClientConnect.open(connection, endpoint, transport, () -> {
                    ScopedClientConnect.wrap(connection, address, vanilla); current.set(false); return old.newSucceededFuture();
                }));
                check(!old.isOpen());
            } finally { old.finishAndReleaseAll(); }
            check(ScopedClientConnect.wrap(connection, address, vanilla) == vanilla);
        } finally { channel.finishAndReleaseAll(); }
        System.out.println("PASS scoped client bootstrap: " + checks + " explicit checks (not Minecraft transformation acceptance)");
    }
}
