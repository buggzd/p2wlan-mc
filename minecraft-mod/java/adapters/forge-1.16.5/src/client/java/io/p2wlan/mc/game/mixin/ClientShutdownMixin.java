package io.p2wlan.mc.game.mixin;

import io.p2wlan.mc.game.client.ClientPlatform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** This Forge SDK has no client application stopping event. */
@Mixin(Minecraft.class)
public abstract class ClientShutdownMixin {
    @Inject(method = "close", at = @At("HEAD"), require = 1)
    private void p2wlan$stop(CallbackInfo result) { ClientPlatform.close(); }
}
