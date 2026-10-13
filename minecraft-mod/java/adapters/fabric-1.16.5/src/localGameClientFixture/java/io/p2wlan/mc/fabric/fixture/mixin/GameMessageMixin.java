package io.p2wlan.mc.fabric.fixture.mixin;

import io.p2wlan.mc.fabric.fixture.LocalGameClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.protocol.game.ClientboundChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation after Vanilla has consumed a system message. */
@Mixin(ClientPacketListener.class)
public abstract class GameMessageMixin {
    @Inject(method = "handleChat", at = @At("TAIL"))
    private void p2wlan$message(ClientboundChatPacket packet, CallbackInfo result) {
        if (packet.getType() == ChatType.SYSTEM) LocalGameClient.message(packet.getMessage());
    }
}
