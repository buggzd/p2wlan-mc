package io.p2wlan.mc.fabric.connect;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.p2wlan.mc.common.LocalEndpoint;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Netty/socket ordering and deterministic deadlines. No Minecraft client
 * or live native networking Agent is started by this transport fixture. */
public final class ProxyPreludeTests {
    private static int checks;
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 4242);
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static byte[] bytes(ByteBuf buffer) {
        check(buffer != null); byte[] value = new byte[buffer.readableBytes()]; buffer.readBytes(value); buffer.release(); return value;
    }
    private static class TargetChannel extends EmbeddedChannel {
        TargetChannel(ChannelHandler... handlers) { super(handlers); }
        @Override protected SocketAddress remoteAddress0() { return ADDRESS; }
    }
    private static ChannelHandler vanilla(AtomicInteger initialized, AtomicInteger active) {
        return new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                initialized.incrementAndGet();
                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override public void channelActive(ChannelHandlerContext context) {
                        active.incrementAndGet(); context.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{17, 18})); context.fireChannelActive();
                    }
                });
            }
        };
    }
    private static final class HeldWrite extends ChannelDuplexHandler {
        Object bytes; ChannelPromise promise;
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise pending) { bytes = message; promise = pending; }
        void finish() {
            if (bytes != null) { io.netty.util.ReferenceCountUtil.release(bytes); bytes = null; }
            if (promise != null) promise.trySuccess();
        }
    }
    private static void actualSockets() throws Exception {
        var loops = new NioEventLoopGroup(1);
        Channel channel = null;
        byte[] payload = new byte[128_000]; new java.util.Random(41).nextBytes(payload);
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0)); listener.setSoTimeout(5000);
            LocalEndpoint endpoint = LocalEndpoint.from((InetSocketAddress) listener.getLocalSocketAddress(), "d".repeat(64));
            CompletableFuture<Boolean> server = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    boolean valid = Arrays.equals(socket.getInputStream().readNBytes(40), endpoint.prelude());
                    valid &= Arrays.equals(socket.getInputStream().readNBytes(payload.length), payload);
                    socket.getOutputStream().write(payload); server.complete(valid);
                } catch (Exception failure) { server.completeExceptionally(failure); }
            }, "p2wlan-test-join-target"); worker.setDaemon(true); worker.start();
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                var connect = new Bootstrap().group(loops).channel(NioSocketChannel.class)
                    .handler(transport.wrap(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel child) {
                            child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                final byte[] bytes = new byte[payload.length]; int count;
                                @Override public void channelActive(ChannelHandlerContext context) { context.writeAndFlush(Unpooled.wrappedBuffer(payload)); }
                                @Override public void channelRead(ChannelHandlerContext context, Object message) {
                                    ByteBuf part = (ByteBuf) message;
                                    int take = part.readableBytes();
                                    if (take > bytes.length - count) { part.release(); context.close(); received.completeExceptionally(new AssertionError("byte overflow")); return; }
                                    part.readBytes(bytes, count, take); count += take; part.release();
                                    if (count == bytes.length) received.complete(bytes);
                                }
                            });
                        }
                    })).connect(endpoint.address());
                check(connect.awaitUninterruptibly(2, TimeUnit.SECONDS) && connect.isSuccess()); channel = connect.channel();
                transport.handedOff().get(3, TimeUnit.SECONDS);
                check(server.get(5, TimeUnit.SECONDS));
                check(Arrays.equals(payload, received.get(5, TimeUnit.SECONDS)));
                transport.revoke(); check(channel.closeFuture().awaitUninterruptibly(2, TimeUnit.SECONDS));
            }
            worker.join(2000); check(!worker.isAlive());
        } finally {
            if (channel != null) check(channel.close().awaitUninterruptibly(2, TimeUnit.SECONDS));
            check(loops.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS));
        }
    }
    public static void main(String[] args) throws Exception {
        LocalEndpoint endpoint = LocalEndpoint.from(ADDRESS, "d".repeat(64));
        AtomicInteger initialized = new AtomicInteger(), active = new AtomicInteger();
        AtomicBoolean parent = new AtomicBoolean(true);
        try (ProxyPrelude transport = new ProxyPrelude(endpoint, parent::get)) {
            TargetChannel channel = new TargetChannel(transport.wrap(vanilla(initialized, active)));
            try {
                check(Arrays.equals(bytes(channel.readOutbound()), endpoint.prelude()));
                check(Arrays.equals(bytes(channel.readOutbound()), new byte[]{17, 18}));
                check(initialized.get() == 1 && active.get() == 1 && transport.handedOff().isDone());
                byte[] game = new byte[]{1, 2, 3, 4}; check(channel.writeInbound(Unpooled.wrappedBuffer(game)));
                check(Arrays.equals(bytes(channel.readInbound()), game)); // No byte is treated as an ACK.
                TargetChannel extra = new TargetChannel(transport.wrap(new ChannelInboundHandlerAdapter()));
                check(!extra.isOpen() && transport.failure() == ProxyPrelude.Failure.CAPACITY); extra.finishAndReleaseAll();
                parent.set(false); channel.freezeTime(); channel.advanceTimeBy(110, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
                check(!channel.isOpen() && transport.failure() == ProxyPrelude.Failure.ATTEMPT_CHANGED);
            } finally { channel.finishAndReleaseAll(); }
        }
        for (boolean cancel : new boolean[]{false, true}) {
            initialized.set(0); active.set(0);
            HeldWrite held = new HeldWrite();
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
                TargetChannel channel = new TargetChannel(held, transport.wrap(vanilla(initialized, active)));
                try {
                    check(initialized.get() == 0 && active.get() == 0 && !transport.handedOff().isDone());
                    check(((ByteBuf) held.bytes).readableBytes() == 40);
                    if (cancel) transport.revoke();
                    else { channel.freezeTime(); channel.advanceTimeBy(2100, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks(); }
                    check(!channel.isOpen() && transport.handedOff().isCompletedExceptionally());
                    if (!cancel) check(transport.failure() == ProxyPrelude.Failure.PREFACE_TIMEOUT);
                    held.finish(); channel.runPendingTasks(); // A successful late write must not install Vanilla.
                    check(initialized.get() == 0 && active.get() == 0);
                } finally { held.finish(); channel.finishAndReleaseAll(); }
            }
        }
        try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
            HeldWrite held = new HeldWrite(); TargetChannel channel = new TargetChannel(held, transport.wrap(new ChannelInboundHandlerAdapter()));
            ByteBuf unexpected = Unpooled.wrappedBuffer(new byte[]{4});
            check(!channel.writeInbound(unexpected)); check(unexpected.refCnt() == 0 && !channel.isOpen());
            check(transport.failure() == ProxyPrelude.Failure.PROTOCOL_ORDER); held.finish(); channel.finishAndReleaseAll();
        }
        try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> false)) {
            TargetChannel channel = new TargetChannel(transport.wrap(new ChannelInboundHandlerAdapter()));
            check(!channel.isOpen() && transport.handedOff().isCompletedExceptionally()); check(channel.readOutbound() == null); channel.finishAndReleaseAll();
        }
        initialized.set(0); active.set(0);
        try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
            TargetChannel wrong = new TargetChannel(transport.wrap(vanilla(initialized, active))) {
                @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 4243); }
            };
            check(!wrong.isOpen() && transport.failure() == ProxyPrelude.Failure.TARGET_MISMATCH);
            check(initialized.get() == 0 && active.get() == 0 && wrong.readOutbound() == null); wrong.finishAndReleaseAll();
        }
        for (boolean outbound : new boolean[]{false, true}) {
            parent.set(true);
            try (ProxyPrelude transport = new ProxyPrelude(endpoint, parent::get)) {
                TargetChannel channel = new TargetChannel(transport.wrap(new ChannelInboundHandlerAdapter()));
                bytes(channel.readOutbound()); parent.set(false);
                ByteBuf revoked = Unpooled.wrappedBuffer(new byte[]{5});
                if (outbound) {
                    var write = channel.writeAndFlush(revoked); channel.runPendingTasks();
                    check(write.isDone() && !write.isSuccess());
                } else check(!channel.writeInbound(revoked));
                check(revoked.refCnt() == 0 && !channel.isOpen() && transport.failure() == ProxyPrelude.Failure.ATTEMPT_CHANGED);
                channel.finishAndReleaseAll();
            }
        }
        var idleLoops = new NioEventLoopGroup(1);
        try (ProxyPrelude transport = new ProxyPrelude(endpoint, () -> true)) {
            Channel idle = new NioSocketChannel();
            idle.pipeline().addLast(transport.wrap(new ChannelInboundHandlerAdapter()));
            check(idleLoops.register(idle).awaitUninterruptibly(2, TimeUnit.SECONDS));
            // Register without ever connecting: the original total timer must
            // close even when channelActive/channelInactive were never emitted.
            try { transport.handedOff().get(3, TimeUnit.SECONDS); throw new AssertionError("expected pre-active timeout"); }
            catch (java.util.concurrent.ExecutionException failure) {
                check(failure.getCause() instanceof ProxyPrelude.FailureException reason && reason.reason() == ProxyPrelude.Failure.PREFACE_TIMEOUT);
            }
            check(idle.closeFuture().awaitUninterruptibly(2, TimeUnit.SECONDS));
            check(transport.failure() == ProxyPrelude.Failure.PREFACE_TIMEOUT);
        } finally { check(idleLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS)); }
        actualSockets();
        System.out.println("PASS join capability preface: " + checks + " explicit Netty/socket checks (not Minecraft acceptance)");
    }
}
