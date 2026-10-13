package io.p2wlan.mc.game.mixin;

import io.p2wlan.mc.game.client.connect.GameJoins;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric 1.16.5 discovers custom-screen login through the existing join owner. */
@Mixin(targets = "net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl", remap = false)
public abstract class FabricLoginNetworkingMixin {
    @Inject(method = "getLoginConnection", at = @At("RETURN"), cancellable = true, remap = false)
    private static void p2wlanLoginConnection(CallbackInfoReturnable<Connection> result) {
        if (result.getReturnValue() == null) {
            Connection connection = GameJoins.currentLoginConnection();
            if (connection != null) result.setReturnValue(connection);
        }
    }
}
