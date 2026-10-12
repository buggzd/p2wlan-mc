package io.p2wlan.mc.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.common.WorldGuard;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Netty pipelines and virtual-time deadlines, not Minecraft-world acceptance. */
public final class LoopbackAdmissionTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static byte[] bytes(ByteBuf buffer) {
        check(buffer != null); byte[] result = new byte[buffer.readableBytes()]; buffer.readBytes(result); buffer.release(); return result;
    }
    private static EmbeddedChannel channel(LoopbackAdmission admission) {
        return new EmbeddedChannel(DefaultChannelId.newInstance(), admission.wrap(new ChannelInboundHandlerAdapter()));
    }
    private static void actualProtectedSockets() throws Exception {
        var loops = new NioEventLoopGroup(1);
        var initialized = new java.util.concurrent.atomic.AtomicInteger();
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var accepted = new java.util.concurrent.atomic.AtomicReference<Channel>();
        AtomicBoolean current = new AtomicBoolean(true);
        Channel listener = null;
        try (LoopbackAdmission admission = new LoopbackAdmission(current::get)) {
            var binding = new ServerBootstrap().group(loops).channel(NioServerSocketChannel.class)
                    .childHandler(admission.wrap(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel child) {
                            accepted.set(child);
                            initialized.incrementAndGet();
                            child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelRead(ChannelHandlerContext context, Object message) {
                                    ByteBuf bytes = (ByteBuf) message;
                                    if (received.addAndGet(bytes.readableBytes()) > 256_000) {
                                        bytes.release(); context.close(); return;
                                    }
                                    context.writeAndFlush(bytes);
                                }
                            });
                        }
                    })).bind(new InetSocketAddress("127.0.0.1", 0));
            check(binding.awaitUninterruptibly(2, TimeUnit.SECONDS) && binding.isSuccess()); listener = binding.channel();
            check(!LoopbackAdmission.isProtected(listener) && !LoopbackAdmission.isAdmitted(listener));
            LocalEndpoint endpoint = admission.endpoint((InetSocketAddress) listener.localAddress());
            try (Socket wrong = new Socket()) {
                wrong.connect(endpoint.address(), 2000); wrong.setSoTimeout(5000);
                wrong.getOutputStream().write(new byte[40]);
                try { check(wrong.getInputStream().read() == -1); }
                catch (java.net.SocketException reset) { checks++; }
                check(initialized.get() == 0);
            }
            try (WorldGuard guard = WorldGuard.openProtected(endpoint); Socket client = new Socket()) {
                client.connect(guard.endpoint().address(), 2000); client.setSoTimeout(5000);
                client.getOutputStream().write(guard.endpoint().prelude());
                check(client.getInputStream().read() == LocalEndpoint.READY && initialized.get() == 1);
                Channel admitted = accepted.get();
                check(LoopbackAdmission.isProtected(admitted) && LoopbackAdmission.isAdmitted(admitted));
                byte[] payload = new byte[128_000]; new java.util.Random(17).nextBytes(payload);
                client.getOutputStream().write(payload);
                check(Arrays.equals(payload, client.getInputStream().readNBytes(payload.length)));
                check(received.get() == payload.length); // No capability/header/ack became a game byte.
                current.set(false);
                check(admitted.isActive() && LoopbackAdmission.isProtected(admitted) && !LoopbackAdmission.isAdmitted(admitted));
                admission.revoke();
                check(LoopbackAdmission.isProtected(admitted) && !LoopbackAdmission.isAdmitted(admitted));
                try { check(client.getInputStream().read() == -1); }
                catch (java.net.SocketException reset) { checks++; }
            }
        } finally {
            if (listener != null) check(listener.close().awaitUninterruptibly(2, TimeUnit.SECONDS));
            check(loops.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS));
        }
    }
    private static void protectedIdentityLifetime() throws Exception {
        check(!LoopbackAdmission.isProtected(null) && !LoopbackAdmission.isAdmitted(null));
        EmbeddedChannel ordinary = new EmbeddedChannel();
        try { check(!LoopbackAdmission.isProtected(ordinary) && !LoopbackAdmission.isAdmitted(ordinary)); }
        finally { ordinary.finishAndReleaseAll(); }
        AtomicBoolean parent = new AtomicBoolean(true);
        try (LoopbackAdmission old = new LoopbackAdmission(parent::get);
                LoopbackAdmission replacement = new LoopbackAdmission(() -> true)) {
            EmbeddedChannel original = channel(old), next = channel(replacement), rejected = channel(old);
            try {
                check(!LoopbackAdmission.isProtected(original) && !LoopbackAdmission.isAdmitted(original));
                byte[] prefix = old.endpoint(new InetSocketAddress("127.0.0.1", 1)).prelude();
                check(!original.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOf(prefix, prefix.length - 1))));
                check(!LoopbackAdmission.isProtected(original) && !LoopbackAdmission.isAdmitted(original));
                check(!original.writeInbound(Unpooled.wrappedBuffer(new byte[] {prefix[prefix.length - 1]})));
                check(Arrays.equals(bytes(original.readOutbound()), new byte[] {LocalEndpoint.READY}));
                check(LoopbackAdmission.isProtected(original) && LoopbackAdmission.isAdmitted(original));
                check(!rejected.writeInbound(Unpooled.wrappedBuffer(new byte[40])));
                check(!LoopbackAdmission.isProtected(rejected) && !LoopbackAdmission.isAdmitted(rejected));
                parent.set(false);
                check(original.isActive() && LoopbackAdmission.isProtected(original) && !LoopbackAdmission.isAdmitted(original));
                byte[] nextPrefix = replacement.endpoint(new InetSocketAddress("127.0.0.1", 1)).prelude();
                check(!next.writeInbound(Unpooled.wrappedBuffer(nextPrefix)));
                check(Arrays.equals(bytes(next.readOutbound()), new byte[] {LocalEndpoint.READY}));
                check(LoopbackAdmission.isProtected(next) && LoopbackAdmission.isAdmitted(next));
                check(!LoopbackAdmission.isAdmitted(original));
                old.revoke();
                check(LoopbackAdmission.isProtected(original) && !LoopbackAdmission.isAdmitted(original));
                check(LoopbackAdmission.isAdmitted(next));
                replacement.revoke();
                check(LoopbackAdmission.isProtected(next) && !LoopbackAdmission.isAdmitted(next));
            } finally { original.finishAndReleaseAll(); next.finishAndReleaseAll(); rejected.finishAndReleaseAll(); }
        }
        AtomicBoolean closing = new AtomicBoolean(true);
        try (LoopbackAdmission admission = new LoopbackAdmission(closing::get)) {
            EmbeddedChannel pending = channel(admission);
            try {
                byte[] prefix = admission.endpoint(new InetSocketAddress("127.0.0.1", 1)).prelude();
                check(!pending.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOf(prefix, prefix.length - 1))));
                closing.set(false);
                check(!pending.writeInbound(Unpooled.wrappedBuffer(new byte[] {prefix[prefix.length - 1]})));
                check(!LoopbackAdmission.isProtected(pending) && !LoopbackAdmission.isAdmitted(pending));
            } finally { pending.finishAndReleaseAll(); }
        }
    }
    private static void authenticatedChannelCannotReplaceOwner() throws Exception {
        AtomicBoolean originalCurrent = new AtomicBoolean(true);
        var initialized = new java.util.concurrent.atomic.AtomicInteger();
        var activeAtClose = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var protectedAtClose = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var admittedAtClose = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        try (LoopbackAdmission original = new LoopbackAdmission(originalCurrent::get);
                LoopbackAdmission replacement = new LoopbackAdmission(() -> true)) {
            EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance(), original.wrap(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel child) {}
            }));
            try {
                byte[] prefix = original.endpoint(new InetSocketAddress("127.0.0.1", 1)).prelude();
                check(!channel.writeInbound(Unpooled.wrappedBuffer(prefix)));
                check(Arrays.equals(bytes(channel.readOutbound()), new byte[] {LocalEndpoint.READY}));
                check(channel.isActive() && LoopbackAdmission.isAdmitted(channel));
                channel.pipeline().remove("p2wlan-world-admission");
                originalCurrent.set(false);
                check(channel.isActive() && LoopbackAdmission.isProtected(channel) && !LoopbackAdmission.isAdmitted(channel));
                // Observe before the close syscall, while the channel is still
                // active: a replaced marker would inherit the fresh owner here.
                channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                    @Override public void close(ChannelHandlerContext context, ChannelPromise promise) {
                        activeAtClose.set(context.channel().isActive());
                        protectedAtClose.set(LoopbackAdmission.isProtected(context.channel()));
                        admittedAtClose.set(LoopbackAdmission.isAdmitted(context.channel()));
                        context.close(promise);
                    }
                });
                channel.pipeline().addLast(replacement.wrap(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel child) { initialized.incrementAndGet(); }
                }));
                ByteBuf nextPrefix = Unpooled.wrappedBuffer(replacement.endpoint(new InetSocketAddress("127.0.0.1", 1)).prelude());
                check(!channel.writeInbound(nextPrefix));
                check(nextPrefix.refCnt() == 0 && !channel.isOpen());
                check(replacement.failure() == LoopbackAdmission.Failure.WORLD_CHANGED && initialized.get() == 0);
                check(Boolean.TRUE.equals(activeAtClose.get()) && Boolean.TRUE.equals(protectedAtClose.get())
                        && Boolean.FALSE.equals(admittedAtClose.get()));
                check(channel.readInbound() == null && channel.readOutbound() == null);
                check(LoopbackAdmission.isProtected(channel) && !LoopbackAdmission.isAdmitted(channel));
                check(original.connections() == 0 && replacement.connections() == 0);
            } finally { channel.finishAndReleaseAll(); }
        }
    }
    public static void main(String[] arguments) throws Exception {
        AtomicBoolean live = new AtomicBoolean(true);
        try (LoopbackAdmission admission = new LoopbackAdmission(live::get)) {
            LocalEndpoint endpoint = admission.endpoint(new InetSocketAddress("127.0.0.1", 25565));
            var initialized = new java.util.concurrent.atomic.AtomicInteger();
            var activated = new java.util.concurrent.atomic.AtomicInteger();
            EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance(), admission.wrap(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel child) {
                    initialized.incrementAndGet();
                    child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override public void channelActive(ChannelHandlerContext context) {
                            activated.incrementAndGet(); context.fireChannelActive();
                        }
                    });
                }
            }));
            try {
                check(initialized.get() == 0 && activated.get() == 0);
                byte[] prefix = endpoint.prelude();
                for (int index = 0; index < prefix.length - 1; index++) {
                    check(!channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {prefix[index]})));
                    check(channel.readOutbound() == null && channel.readInbound() == null);
                }
                check(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {prefix[prefix.length - 1], 3, 5, 7})));
                check(initialized.get() == 1 && activated.get() == 1);
                check(Arrays.equals(bytes(channel.readOutbound()), new byte[] {1}));
                check(Arrays.equals(bytes(channel.readInbound()), new byte[] {3, 5, 7}));
                check(channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] {11, 13})));
                check(Arrays.equals(bytes(channel.readOutbound()), new byte[] {11, 13}));
                live.set(false);
                ByteBuf stale = Unpooled.wrappedBuffer(new byte[] {17});
                check(!channel.writeInbound(stale)); check(stale.refCnt() == 0 && !channel.isOpen());
                check(admission.failure() == LoopbackAdmission.Failure.WORLD_CHANGED);
                check(!admission.toString().contains(endpoint.keyHex()));
            } finally { channel.finishAndReleaseAll(); }
        }
        try (LoopbackAdmission admission = new LoopbackAdmission(() -> true)) {
            EmbeddedChannel wrong = channel(admission);
            ByteBuf malformed = Unpooled.wrappedBuffer(new byte[40]);
            check(!wrong.writeInbound(malformed)); check(malformed.refCnt() == 0 && !wrong.isOpen());
            check(admission.failure() == LoopbackAdmission.Failure.CAPABILITY_REJECTED);
            wrong.finishAndReleaseAll();
            EmbeddedChannel expired = channel(admission); expired.freezeTime();
            expired.writeInbound(Unpooled.wrappedBuffer(new byte[] {'P'}));
            expired.advanceTimeBy(2100, TimeUnit.MILLISECONDS); expired.runScheduledPendingTasks();
            check(!expired.isOpen() && admission.failure() == LoopbackAdmission.Failure.ADMISSION_TIMEOUT);
            expired.finishAndReleaseAll();
            var channels = new ArrayList<EmbeddedChannel>();
            try {
                for (int index = 0; index < 32; index++) channels.add(channel(admission));
                check(admission.connections() == 32);
                EmbeddedChannel extra = channel(admission);
                check(!extra.isOpen() && admission.failure() == LoopbackAdmission.Failure.CAPACITY);
                extra.finishAndReleaseAll();
                admission.revoke(); for (EmbeddedChannel existing : channels) check(!existing.isOpen());
                check(admission.connections() == 0);
                EmbeddedChannel late = channel(admission); check(!late.isOpen()); late.finishAndReleaseAll();
            } finally { for (EmbeddedChannel existing : channels) existing.finishAndReleaseAll(); }
        }
        protectedIdentityLifetime(); authenticatedChannelCannotReplaceOwner(); actualProtectedSockets();
        System.out.println("PASS protected Minecraft transport: " + checks + " explicit Netty checks (not game acceptance)");
    }
}
