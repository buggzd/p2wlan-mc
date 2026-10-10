package io.p2wlan.mc.fabric.mixin;

import io.p2wlan.mc.fabric.client.lan.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Append-only transient UI rows; Vanilla saved servers and LAN detector remain owned by Vanilla. */
@Mixin(MultiplayerServerListWidget.class)
public abstract class MultiplayerListMixin extends AlwaysSelectedEntryListWidget<MultiplayerServerListWidget.Entry> {
    @Shadow @Final private MultiplayerScreen screen;
    protected MultiplayerListMixin(MinecraftClient client, int width, int height, int y, int itemHeight) { super(client, width, height, y, itemHeight); }
    @Inject(method = "updateEntries", at = @At("TAIL"))
    private void p2wlan$append(CallbackInfo callback) {
        for (var service : FabricNetworkView.services()) addEntry(new VirtualLanEntry(screen, service));
    }
}
