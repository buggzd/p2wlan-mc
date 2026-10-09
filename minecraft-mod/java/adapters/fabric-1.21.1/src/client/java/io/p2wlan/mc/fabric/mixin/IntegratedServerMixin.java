package io.p2wlan.mc.fabric.mixin;

import io.p2wlan.mc.fabric.world.FabricWorlds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Derive remote/pause semantics from the exact current publication. Never open
 * to LAN, announce a port, change cheats/game mode, or keep a second host flag. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin implements FabricWorlds.IntegratedHooks {
    @Inject(method = "isRemote", at = @At("RETURN"), cancellable = true)
    private void p2wlan$hosted(CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ() && FabricWorlds.hosted((MinecraftServer) (Object) this)) result.setReturnValue(true);
    }
}
