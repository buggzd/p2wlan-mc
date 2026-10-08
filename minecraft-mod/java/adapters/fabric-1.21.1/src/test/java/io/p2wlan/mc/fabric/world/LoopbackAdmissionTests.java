package io.p2wlan.mc.fabric.world;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
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
        Channel listener = null;
        try (LoopbackAdmission admission = new LoopbackAdmission(() -> true)) {
            var binding = new ServerBootstrap().group(loops).channel(NioServerSocketChannel.class)
                    .childHandler(admission.wrap(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel child) {
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
                byte[] payload = new byte[128_000]; new java.util.Random(17).nextBytes(payload);
                client.getOutputStream().write(payload);
                check(Arrays.equals(payload, client.getInputStream().readNBytes(payload.length)));
                check(received.get() == payload.length); // No capability/header/ack became a game byte.
                admission.revoke();
                try { check(client.getInputStream().read() == -1); }
                catch (java.net.SocketException reset) { checks++; }
            }
        } finally {
            if (listener != null) check(listener.close().awaitUninterruptibly(2, TimeUnit.SECONDS));
            check(loops.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS));
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
        actualProtectedSockets();
        System.out.println("PASS protected Minecraft transport: " + checks + " explicit Netty checks (not game acceptance)");
    }
}
