package io.p2wlan.mc.fabric.mixin;
import io.p2wlan.mc.fabric.client.lan.VanillaLanList;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Separate accessor keeps its descriptor mapping distinct from the injector. */
@Mixin(MultiplayerServerListWidget.class)
public interface MultiplayerListAccessor extends VanillaLanList {
    @Invoker("updateEntries") void p2wlan$refresh();
}
