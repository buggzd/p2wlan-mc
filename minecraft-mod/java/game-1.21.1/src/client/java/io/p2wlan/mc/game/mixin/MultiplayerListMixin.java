package io.p2wlan.mc.game.mixin;

import io.p2wlan.mc.game.client.lan.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Append-only transient UI rows; Vanilla saved servers and LAN detector remain owned by Vanilla. */
@Mixin(ServerSelectionList.class)
public abstract class MultiplayerListMixin extends ObjectSelectionList<ServerSelectionList.Entry> {
    @Shadow @Final private JoinMultiplayerScreen screen;
    protected MultiplayerListMixin(Minecraft client, int width, int height, int y, int itemHeight) { super(client, width, height, y, itemHeight); }
    @Inject(method = "refreshEntries", at = @At("TAIL"))
    private void p2wlan$append(CallbackInfo callback) {
        for (var service : ClientNetworkView.services()) addEntry(new VirtualLanEntry(screen, service));
    }
}
