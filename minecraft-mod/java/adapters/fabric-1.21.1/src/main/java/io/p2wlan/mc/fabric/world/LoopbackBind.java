package io.p2wlan.mc.fabric.world;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.server.ServerNetworkIo;

/** Owns only the additional private Minecraft listener and its admission scope.
 * Vanilla codecs/handshake/connection-list ownership are reused unchanged. */
public final class LoopbackBind implements WorldHost.Target {
    /** Added by the required Mixin; absence must fail before any unguarded bind. */
    public interface Hooks {}
    public static boolean supported() { return Hooks.class.isAssignableFrom(ServerNetworkIo.class); }
    private static final ThreadLocal<LoopbackBind> ACTIVE = new ThreadLocal<>();
    private final ServerNetworkIo network;
    private final LoopbackAdmission admission;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicBoolean listenerCloseRequested = new AtomicBoolean();
    // One event loop drives both accept and child registration, so unauthenticated
    // local connects cannot queue unbounded work onto another game's event loop.
    private final NioEventLoopGroup loops = new NioEventLoopGroup(1);
    private volatile ChannelFuture bound;
    public LoopbackBind(ServerNetworkIo network, BooleanSupplier current) throws WorldHostException {
        this.network = network; admission = new LoopbackAdmission(() -> live.get() && current.getAsBoolean());
    }
    @Override public void open() throws WorldHostException {
        if (!supported()) throw new WorldHostException(WorldHostException.Code.BIND_FAILED);
        if (bound != null || ACTIVE.get() != null || !admission.isCurrent()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
        ACTIVE.set(this);
        try {
            network.bind(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0);
            if (bound == null || !bound.isSuccess() || !admission.isCurrent()) throw new WorldHostException(WorldHostException.Code.BIND_FAILED);
        } catch (WorldHostException typed) { throw typed; }
        catch (IOException | RuntimeException failure) { throw new WorldHostException(WorldHostException.Code.BIND_FAILED); }
        finally { ACTIVE.remove(); }
    }
    private static LoopbackBind current(ServerNetworkIo network) {
        LoopbackBind value = ACTIVE.get(); return value != null && value.network == network ? value : null;
    }
    public static AbstractBootstrap<?, ?> channel(ServerNetworkIo network, ServerBootstrap bootstrap, Class<? extends ServerChannel> channel) {
        return bootstrap.channel(current(network) == null ? channel : NioServerSocketChannel.class);
    }
    public static ServerBootstrap group(ServerNetworkIo network, ServerBootstrap bootstrap, EventLoopGroup group) {
        LoopbackBind value = current(network); return bootstrap.group(value == null ? group : value.loops);
    }
    public static ChannelFuture bind(ServerNetworkIo network, ServerBootstrap bootstrap) throws IOException {
        LoopbackBind value = current(network);
        if (value == null) return bootstrap.bind();
        if (!value.admission.isCurrent()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
        bootstrap.childHandler(value.admission.wrap(bootstrap.config().childHandler()));
        // Recheck the immutable world/runtime scope at the actual bind boundary.
        if (!value.admission.isCurrent()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
        ChannelFuture future = bootstrap.bind(); value.bound = future;
        if (!value.admission.isCurrent()) { value.revoke(); throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED); }
        return future;
    }
    public static ChannelFuture await(ServerNetworkIo network, ChannelFuture future) throws IOException {
        LoopbackBind value = current(network);
        if (value == null) return future.syncUninterruptibly();
        // Vanilla serializes bind/stop under its channel-list monitor. Keep that
        // ordering, but limit this Mod-owned bind wait to 2s. No pipe/HTTP/game
        // callback is executed here. Existing unrelated Vanilla binds are untouched.
        if (!future.awaitUninterruptibly(2, TimeUnit.SECONDS)) { value.revoke(); throw new WorldHostException(WorldHostException.Code.BIND_TIMEOUT); }
        if (!future.isSuccess()) { value.revoke(); throw new WorldHostException(WorldHostException.Code.BIND_FAILED); }
        if (!value.admission.isCurrent()) { value.revoke(); throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED); }
        return future;
    }
    /** The Mod owns/removes this listener, so do not accumulate closed entries
     * in Vanilla's server-lifetime channel list. This is not a second game list. */
    public static boolean owns(ServerNetworkIo network, Object future) {
        LoopbackBind value = current(network); return value != null && value.bound == future;
    }
    @Override public LocalEndpoint endpoint() throws WorldHostException {
        ChannelFuture future = bound;
        if (future == null || !future.isSuccess() || !(future.channel().localAddress() instanceof InetSocketAddress address)) {
            throw new WorldHostException(WorldHostException.Code.BIND_FAILED);
        }
        return admission.endpoint(address);
    }
    @Override public void revoke() {
        live.set(false); admission.revoke();
        ChannelFuture future = bound;
        if (future != null && listenerCloseRequested.compareAndSet(false, true)) future.channel().close();
    }
    @Override public void close() throws WorldHostException {
        revoke();
        boolean childrenClosed;
        try { admission.close(); childrenClosed = true; }
        catch (WorldHostException failure) { childrenClosed = false; }
        ChannelFuture future = bound;
        boolean listenerClosed = future == null || future.channel().closeFuture().awaitUninterruptibly(2, TimeUnit.SECONDS);
        boolean loopsStopped = loops.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
        if (!childrenClosed || !listenerClosed || !loopsStopped) throw new WorldHostException(WorldHostException.Code.CLEANUP_FAILED);
    }
}
