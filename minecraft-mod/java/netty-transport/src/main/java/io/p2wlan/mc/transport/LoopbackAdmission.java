package io.p2wlan.mc.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.common.WorldGuardException;
import io.p2wlan.mc.common.WorldHostException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Protects the Minecraft listener itself, not just the outer world guard.
 * At most 32 children and 40 retained prelude bytes per child. Existing Minecraft
 * event loops own their two-second timers; there is no worker/queue/retry here. */
public final class LoopbackAdmission implements AutoCloseable {
    public enum Failure { CAPACITY, WORLD_CHANGED, CAPABILITY_REJECTED, ADMISSION_TIMEOUT, IO }
    private final BooleanSupplier parent;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicReference<Failure> failure = new AtomicReference<>();
    private final Semaphore slots = new Semaphore(32);
    private final DefaultChannelGroup children = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
    private final String key;
    private final byte[] expected;
    public LoopbackAdmission(BooleanSupplier parent) throws WorldHostException {
        this.parent = parent;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); key = HexFormat.of().formatHex(bytes);
        expected = endpoint(new InetSocketAddress("127.0.0.1", 1)).prelude();
    }
    public LocalEndpoint endpoint(InetSocketAddress address) throws WorldHostException {
        try { return LocalEndpoint.from(address, key); }
        catch (WorldGuardException invalid) { throw new WorldHostException(WorldHostException.Code.BIND_FAILED); }
    }
    public Failure failure() { return failure.get(); }
    public int connections() { return children.size(); }
    public boolean isCurrent() { return live.get() && parent.getAsBoolean(); }
    public ChannelHandler wrap(ChannelHandler vanilla) {
        return new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                if (!isCurrent()) { failure.set(Failure.WORLD_CHANGED); channel.close(); return; }
                if (!slots.tryAcquire()) { failure.set(Failure.CAPACITY); channel.close(); return; }
                if (!children.add(channel)) { slots.release(); failure.set(Failure.CAPACITY); channel.close(); return; }
                channel.closeFuture().addListener(ignored -> slots.release());
                if (!isCurrent()) { channel.close(); return; }
                // Do not populate Vanilla's connection list or codecs for an
                // unauthenticated local user. The initializer is released only
                // after this exact world's full capability has been consumed.
                channel.pipeline().addLast("p2wlan-world-admission", new Prelude(vanilla));
            }
        };
    }
    private final class Prelude extends ChannelDuplexHandler {
        private final ChannelHandler vanilla;
        Prelude(ChannelHandler vanilla) { this.vanilla = vanilla; }
        private final byte[] received = new byte[LocalEndpoint.PRELUDE_BYTES];
        private int count;
        private boolean authenticated;
        private io.netty.util.concurrent.ScheduledFuture<?> deadline;
        @Override public void channelActive(ChannelHandlerContext context) {
            deadline = context.executor().schedule(() -> {
                failure.set(Failure.ADMISSION_TIMEOUT); context.close();
            }, 2, TimeUnit.SECONDS);
            if (!isCurrent()) { failure.set(Failure.WORLD_CHANGED); context.close(); return; }
            // No Vanilla channel-active side effect before authentication.
        }
        private void reject(ChannelHandlerContext context, Object message, Failure reason) {
            failure.set(reason); ReferenceCountUtil.release(message); context.close();
        }
        @Override public void channelRead(ChannelHandlerContext context, Object message) {
            if (!isCurrent()) { reject(context, message, Failure.WORLD_CHANGED); return; }
            if (!(message instanceof ByteBuf bytes)) { reject(context, message, Failure.CAPABILITY_REJECTED); return; }
            if (!authenticated) {
                int take = Math.min(bytes.readableBytes(), received.length - count);
                bytes.readBytes(received, count, take); count += take;
                if (count < received.length) { bytes.release(); return; }
                if (!MessageDigest.isEqual(expected, received)) { reject(context, bytes, Failure.CAPABILITY_REJECTED); return; }
                authenticated = true;
                if (deadline != null) deadline.cancel(false);
                if (!isCurrent()) { reject(context, bytes, Failure.WORLD_CHANGED); return; }
                try {
                    context.pipeline().addLast(vanilla);
                    if (!isCurrent()) { reject(context, bytes, Failure.WORLD_CHANGED); return; }
                    context.fireChannelActive();
                } catch (RuntimeException invalidPipeline) { reject(context, bytes, Failure.IO); return; }
                if (!isCurrent()) { reject(context, bytes, Failure.WORLD_CHANGED); return; }
                // This acknowledgement bypasses all Minecraft codecs. Only
                // after it is consumed may the outer guard expose game readiness.
                context.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {LocalEndpoint.READY}))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
            if (bytes.isReadable()) {
                if (!isCurrent()) { reject(context, bytes, Failure.WORLD_CHANGED); return; }
                context.fireChannelRead(bytes);
            } else bytes.release();
        }
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (!authenticated || !isCurrent()) {
                ReferenceCountUtil.release(message);
                promise.tryFailure(new WorldHostException(WorldHostException.Code.WORLD_CHANGED)); context.close(); return;
            }
            context.write(message, promise);
        }
        @Override public void channelInactive(ChannelHandlerContext context) {
            if (deadline != null) deadline.cancel(false);
            context.fireChannelInactive();
        }
        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { failure.set(Failure.IO); context.close(); }
    }
    public void revoke() { if (live.getAndSet(false)) children.close(); }
    @Override public void close() throws WorldHostException {
        revoke();
        if (!children.close().awaitUninterruptibly(2, TimeUnit.SECONDS)) throw new WorldHostException(WorldHostException.Code.CLEANUP_FAILED);
    }
    @Override public String toString() { return "LoopbackAdmission[capability=redacted]"; }
}
