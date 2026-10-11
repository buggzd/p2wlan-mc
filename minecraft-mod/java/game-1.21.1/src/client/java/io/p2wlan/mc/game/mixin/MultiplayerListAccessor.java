package io.p2wlan.mc.game.mixin;
import io.p2wlan.mc.game.client.lan.VanillaLanList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Separate accessor keeps its descriptor mapping distinct from the injector. */
@Mixin(ServerSelectionList.class)
public interface MultiplayerListAccessor extends VanillaLanList {
    @Invoker("refreshEntries") void p2wlan$refresh();
}
