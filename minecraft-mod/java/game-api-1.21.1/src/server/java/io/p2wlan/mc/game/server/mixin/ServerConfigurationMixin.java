package io.p2wlan.mc.game.server.mixin;

import io.netty.channel.Channel;
import io.p2wlan.mc.transport.LoopbackAdmission;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rechecks the original admission owner after queued configuration handling,
 * immediately before Vanilla commits the player to the current world. */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationMixin extends ServerCommonPacketListenerImpl {
    protected ServerConfigurationMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Inject(method = "handleConfigurationFinished", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"),
            cancellable = true, require = 1)
    private void p2wlan$currentWorld(CallbackInfo result) {
        Channel channel = ((ServerLoginConnectionMixin) (Object) connection).p2wlan$channel();
        if (LoopbackAdmission.isProtected(channel) && !LoopbackAdmission.isAdmitted(channel)) {
            disconnect(Component.literal("P2WLAN_WORLD_CHANGED"));
            result.cancel();
        }
    }
}
