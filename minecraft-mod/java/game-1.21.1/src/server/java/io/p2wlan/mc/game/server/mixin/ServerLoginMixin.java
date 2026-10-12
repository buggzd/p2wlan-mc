package io.p2wlan.mc.game.server.mixin;

import io.netty.channel.Channel;
import io.p2wlan.mc.transport.LoopbackAdmission;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A shared singleplayer world accepts offline game names only through its
 * current capability-admitted P2WLAN listener. Vanilla owns profiles and login;
 * the server's authentication setting and other listeners keep their policy. */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginMixin {
    @Shadow @Final private Connection connection;
    @Shadow public abstract void disconnect(Component reason);

    @Unique private Channel p2wlan$channel() {
        return ((ServerLoginConnectionMixin) (Object) connection).p2wlan$channel();
    }

    @Redirect(method = "handleHello", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"), require = 1)
    private boolean p2wlan$authentication(MinecraftServer server) {
        return server.usesAuthentication()
                && !(server.isSingleplayer() && !server.isDedicatedServer()
                        && LoopbackAdmission.isAdmitted(p2wlan$channel()));
    }

    // Recheck the exact original scope both before login and at final profile
    // admission, including a scope revoked while Vanilla waited for its tick.
    @Inject(method = {"handleHello", "verifyLoginAndFinishConnectionSetup", "handleLoginAcknowledgement"},
            at = @At("HEAD"), cancellable = true, require = 1)
    private void p2wlan$currentWorld(CallbackInfo result) {
        Channel channel = p2wlan$channel();
        if (LoopbackAdmission.isProtected(channel) && !LoopbackAdmission.isAdmitted(channel)) {
            disconnect(Component.literal("P2WLAN_WORLD_CHANGED"));
            result.cancel();
        }
    }
}
