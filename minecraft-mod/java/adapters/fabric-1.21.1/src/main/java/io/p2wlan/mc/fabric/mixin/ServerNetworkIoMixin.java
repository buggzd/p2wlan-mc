package io.p2wlan.mc.fabric.mixin;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.transport.LoopbackBind;
import java.io.IOException;
import java.util.List;
import net.minecraft.server.ServerNetworkIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the explicitly scoped Mod bind is changed; Vanilla listeners remain Vanilla-owned. */
@Mixin(ServerNetworkIo.class)
public abstract class ServerNetworkIoMixin implements LoopbackBind.Hooks {
    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false), require = 1)
    private AbstractBootstrap<?, ?> p2wlanChannel(ServerBootstrap bootstrap, Class<? extends ServerChannel> channel) {
        return LoopbackBind.channel((ServerNetworkIo) (Object) this, bootstrap, channel);
    }
    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;", remap = false), require = 1)
    private ServerBootstrap p2wlanEventLoop(ServerBootstrap bootstrap, EventLoopGroup group) {
        return LoopbackBind.group((ServerNetworkIo) (Object) this, bootstrap, group);
    }
    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;", remap = false), require = 1)
    private ChannelFuture p2wlanBind(ServerBootstrap bootstrap) throws IOException {
        return LoopbackBind.bind((ServerNetworkIo) (Object) this, bootstrap);
    }
    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lio/netty/channel/ChannelFuture;syncUninterruptibly()Lio/netty/channel/ChannelFuture;", remap = false), require = 1)
    private ChannelFuture p2wlanBoundWait(ChannelFuture future) throws IOException {
        return LoopbackBind.await((ServerNetworkIo) (Object) this, future);
    }
    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", remap = false), require = 1)
    private boolean p2wlanOwnListener(List<ChannelFuture> channels, Object future) {
        return LoopbackBind.owns((ServerNetworkIo) (Object) this, future) || channels.add((ChannelFuture) future);
    }
}
