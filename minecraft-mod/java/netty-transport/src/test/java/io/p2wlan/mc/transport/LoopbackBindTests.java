package io.p2wlan.mc.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.common.WorldHostException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reusable bind decisions and actual protected sockets, with no game/loader. */
public final class LoopbackBindTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("BIND_CHECK_" + checks); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(WorldHostException.Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("EXPECTED_" + code); }
        catch (WorldHostException error) { check(error.code() == code); }
    }
    private static final class Network implements LoopbackBind.Hooks, AutoCloseable {
        final AtomicInteger initialized = new AtomicInteger();
        final NioEventLoopGroup original = new NioEventLoopGroup(1);
        final Object unrelated = new Object();
        final AtomicBoolean live;
        boolean revokeAtBind;
        Network(AtomicBoolean live) { this.live = live; }
        void bind(InetAddress address, int port) throws Exception {
            ServerBootstrap bootstrap = new ServerBootstrap().localAddress(address, port)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            initialized.incrementAndGet();
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelRead(ChannelHandlerContext context, Object bytes) {
                                    context.writeAndFlush(bytes);
                                }
                            });
                        }
                    });
            ServerBootstrap ordinary = new ServerBootstrap();
            check(LoopbackBind.group(unrelated, ordinary, original) == ordinary);
            check(ordinary.config().group() == original);
            LoopbackBind.channel(this, bootstrap, NioServerSocketChannel.class);
            LoopbackBind.group(this, bootstrap, original);
            check(bootstrap.config().group() != original);
            if (revokeAtBind) live.set(false);
            var future = LoopbackBind.bind(this, bootstrap);
            LoopbackBind.await(this, future);
            check(LoopbackBind.owns(this, future));
            check(!LoopbackBind.owns(unrelated, future));
        }
        @Override public void close() {
            check(original.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(2, TimeUnit.SECONDS));
        }
    }
    private static LoopbackBind bind(Network network) throws Exception {
        return new LoopbackBind(network, (address, port) -> {
            try { network.bind(address, port); }
            catch (java.io.IOException error) { throw error; }
            catch (Exception error) { throw new java.io.IOException("TEST_BIND", error); }
        }, network.live::get);
    }
    public static void main(String[] args) throws Exception {
        check(LoopbackBind.supported(Network.class));
        check(!LoopbackBind.supported(Object.class));
        AtomicBoolean called = new AtomicBoolean();
        try (LoopbackBind absent = new LoopbackBind(new Object(), (address, port) -> called.set(true), () -> true)) {
            fails(WorldHostException.Code.BIND_FAILED, absent::open);
            check(!called.get());
        }
        AtomicBoolean live = new AtomicBoolean(true);
        try (Network network = new Network(live); LoopbackBind binding = bind(network)) {
            binding.open();
            LocalEndpoint endpoint = binding.endpoint();
            check(endpoint.address().getAddress().isLoopbackAddress());
            check(endpoint.address().getPort() > 0);
            fails(WorldHostException.Code.WORLD_CHANGED, binding::open);
            try (Socket rejected = new Socket()) {
                rejected.connect(endpoint.address(), 2000); rejected.setSoTimeout(4000);
                rejected.getOutputStream().write(new byte[LocalEndpoint.PRELUDE_BYTES]);
                try { check(rejected.getInputStream().read() == -1); }
                catch (java.net.SocketException reset) { checks++; }
                check(network.initialized.get() == 0);
            }
            try (Socket accepted = new Socket()) {
                accepted.connect(endpoint.address(), 2000); accepted.setSoTimeout(4000);
                accepted.getOutputStream().write(endpoint.prelude());
                check(accepted.getInputStream().read() == LocalEndpoint.READY);
                byte[] bytes = new byte[] {1, 7, 9, 2};
                accepted.getOutputStream().write(bytes);
                check(java.util.Arrays.equals(accepted.getInputStream().readNBytes(bytes.length), bytes));
                check(network.initialized.get() == 1);
                live.set(false); binding.revoke();
                try { check(accepted.getInputStream().read() == -1); }
                catch (java.net.SocketException reset) { checks++; }
            }
        }
        try (Network network = new Network(new AtomicBoolean(true)); LoopbackBind binding = bind(network)) {
            network.revokeAtBind = true;
            fails(WorldHostException.Code.WORLD_CHANGED, binding::open);
            check(network.initialized.get() == 0);
            fails(WorldHostException.Code.BIND_FAILED, binding::endpoint);
        }
        // A failed bind must release the synchronous scope for a later owner.
        try (Network network = new Network(new AtomicBoolean(true)); LoopbackBind binding = bind(network)) {
            binding.open(); check(binding.endpoint().address().getPort() > 0);
        }
        System.out.println("PASS shared scoped Netty bind: " + checks + " checks");
    }
}
