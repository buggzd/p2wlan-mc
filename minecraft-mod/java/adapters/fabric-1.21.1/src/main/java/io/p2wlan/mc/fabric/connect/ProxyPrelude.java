package io.p2wlan.mc.fabric.connect;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.p2wlan.mc.common.LocalEndpoint;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One attempt/socket, 40 prelude bytes, no game-byte queue. Native join proxies
 * consume the prelude but send NO local acknowledgement. Vanilla initialization
 * waits for the local write to finish; peer/world admission remains native. */
public final class ProxyPrelude implements AutoCloseable {
    public enum Failure { CAPACITY, ATTEMPT_CHANGED, TARGET_MISMATCH, PREFACE_TIMEOUT, PROTOCOL_ORDER, IO }
    public static final class FailureException extends IOException {
        private final Failure reason;
        private FailureException(Failure reason) { super("JOIN_PREFACE_" + reason.name()); this.reason = reason; }
        public Failure reason() { return reason; }
    }
    private final LocalEndpoint endpoint;
    private final BooleanSupplier parent;
    private final AtomicBoolean live = new AtomicBoolean(true), claimed = new AtomicBoolean(false);
    private final AtomicReference<Failure> failure = new AtomicReference<>();
    private final CompletableFuture<Void> handedOff = new CompletableFuture<>();
    private volatile Channel transport;
    public ProxyPrelude(LocalEndpoint endpoint, BooleanSupplier parent) {
        if (endpoint == null || parent == null) throw new IllegalArgumentException("INVALID_PROXY_TRANSPORT");
        this.endpoint = endpoint; this.parent = parent;
    }
    public boolean isCurrent() { return live.get() && parent.getAsBoolean(); }
    public Failure failure() { return failure.get(); }
    /** Local ordered prelude/initializer handoff, never proof of remote login. */
    public CompletableFuture<Void> handedOff() { return handedOff.copy(); }
    public ChannelHandler wrap(ChannelHandler vanilla) {
        if (vanilla == null) throw new IllegalArgumentException("INVALID_PROXY_PIPELINE");
        return new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                if (!claimed.compareAndSet(false, true)) { failure.set(Failure.CAPACITY); channel.close(); return; }
                transport = channel;
                channel.closeFuture().addListener(ignored -> handedOff.completeExceptionally(
                        new FailureException(isCurrent() ? Failure.IO : Failure.ATTEMPT_CHANGED)));
                if (!isCurrent()) { fail(channel, Failure.ATTEMPT_CHANGED); return; }
                channel.pipeline().addLast("p2wlan-join-preface", new Prelude(vanilla));
            }
        };
    }
    private void fail(Channel channel, Failure reason) {
        failure.set(reason); handedOff.completeExceptionally(new FailureException(reason)); channel.close();
    }
    private final class Prelude extends ChannelDuplexHandler {
        private final ChannelHandler vanilla;
        private boolean installed;
        private ScheduledFuture<?> deadline, fenceWatch;
        private boolean started;
        private Prelude(ChannelHandler vanilla) { this.vanilla = vanilla; }
        @Override public void handlerAdded(ChannelHandlerContext context) {
            // Registration/connect and the prelude share this original total
            // deadline. A socket that never becomes active cannot wait forever.
            deadline = context.executor().schedule(() -> fail(context.channel(), Failure.PREFACE_TIMEOUT), 2, TimeUnit.SECONDS);
            fenceWatch = context.executor().scheduleWithFixedDelay(() -> {
                if (!isCurrent()) fail(context.channel(), Failure.ATTEMPT_CHANGED);
            }, 100, 100, TimeUnit.MILLISECONDS);
        }
        @Override public void channelActive(ChannelHandlerContext context) {
            if (started) { fail(context.channel(), Failure.PROTOCOL_ORDER); return; }
            started = true;
            if (!isCurrent()) { fail(context.channel(), Failure.ATTEMPT_CHANGED); return; }
            if (!endpoint.address().equals(context.channel().remoteAddress())) { fail(context.channel(), Failure.TARGET_MISMATCH); return; }
            if (!isCurrent()) { fail(context.channel(), Failure.ATTEMPT_CHANGED); return; }
            context.writeAndFlush(Unpooled.wrappedBuffer(endpoint.prelude())).addListener(written -> {
                if (!written.isSuccess()) { fail(context.channel(), Failure.IO); return; }
                if (!isCurrent() || !context.channel().isActive()) { fail(context.channel(), Failure.ATTEMPT_CHANGED); return; }
                if (deadline != null) deadline.cancel(false);
                try {
                    // No game handler/codecs/active callbacks before the full
                    // capability was written. The prelude bypassed those codecs.
                    context.pipeline().addLast(vanilla); installed = true;
                    if (!isCurrent()) { fail(context.channel(), Failure.ATTEMPT_CHANGED); return; }
                    context.fireChannelActive();
                    if (!isCurrent()) { fail(context.channel(), Failure.ATTEMPT_CHANGED); return; }
                    handedOff.complete(null);
                } catch (RuntimeException invalidPipeline) { fail(context.channel(), Failure.IO); }
            });
        }
        @Override public void channelRead(ChannelHandlerContext context, Object message) {
            if (!installed || !isCurrent()) {
                ReferenceCountUtil.release(message);
                fail(context.channel(), installed ? Failure.ATTEMPT_CHANGED : Failure.PROTOCOL_ORDER); return;
            }
            context.fireChannelRead(message); // Every byte is game data; no ACK is consumed.
        }
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (!installed || !isCurrent()) {
                Failure reason = installed ? Failure.ATTEMPT_CHANGED : Failure.PROTOCOL_ORDER;
                ReferenceCountUtil.release(message); promise.tryFailure(new FailureException(reason)); fail(context.channel(), reason); return;
            }
            context.write(message, promise);
        }
        private void cancelTimers() {
            if (deadline != null) deadline.cancel(false);
            if (fenceWatch != null) fenceWatch.cancel(false);
        }
        @Override public void handlerRemoved(ChannelHandlerContext context) {
            cancelTimers();
            if (context.channel().isOpen()) fail(context.channel(), Failure.IO);
        }
        @Override public void channelInactive(ChannelHandlerContext context) {
            cancelTimers();
            handedOff.completeExceptionally(new FailureException(isCurrent() ? Failure.IO : Failure.ATTEMPT_CHANGED));
            context.fireChannelInactive();
        }
        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable error) { fail(context.channel(), Failure.IO); }
    }
    public void revoke() {
        if (!live.getAndSet(false)) return;
        handedOff.completeExceptionally(new FailureException(Failure.ATTEMPT_CHANGED));
        Channel channel = transport; if (channel != null) channel.close();
    }
    @Override public void close() { revoke(); }
}
