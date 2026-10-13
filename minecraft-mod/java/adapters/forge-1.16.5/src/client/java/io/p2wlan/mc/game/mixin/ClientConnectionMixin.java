package io.p2wlan.mc.game.mixin;

import io.netty.channel.ChannelHandlerContext;
import io.p2wlan.mc.transport.ScopedClientConnect;
import java.util.function.Consumer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketDirection;
import net.minecraftforge.fml.network.NetworkHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Match Forge's factory activation for the original scoped client connection.
 * Its channel/address and protocol are initialized before this hook, and the
 * shared prelude owner fences activation before any Minecraft handler runs. */
@Mixin(NetworkManager.class)
public abstract class ClientConnectionMixin implements ScopedClientConnect.Hooks {
    @Shadow(remap = false) private Consumer<NetworkManager> activationHandler;

    // Netty's method name/descriptor is stable and is not a Minecraft mapping.
    @Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("TAIL"), remap = false, require = 1)
    private void p2wlan$clientActivation(ChannelHandlerContext context, CallbackInfo result) {
        var connection = (NetworkManager) (Object) this;
        if (activationHandler == null && connection.getDirection() == PacketDirection.CLIENTBOUND) {
            NetworkHooks.registerClientLoginChannel(connection);
        }
    }
}
