package io.p2wlan.mc.fabric.mixin;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandler;
import io.p2wlan.mc.transport.ScopedClientConnect;
import java.net.InetSocketAddress;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the explicitly scoped connection is wrapped, before async initialization.
 * This configuration is client-only, including on an integrated-server JVM. */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin implements ScopedClientConnect.Hooks {
    @Redirect(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;handler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false), require = 1)
    private static AbstractBootstrap<?, ?> p2wlanHandler(Bootstrap bootstrap, ChannelHandler vanilla,
            InetSocketAddress address, boolean nativeTransport, ClientConnection connection) throws ScopedClientConnect.FailureException {
        return bootstrap.handler(ScopedClientConnect.wrap(connection, address, vanilla));
    }
}
