package io.p2wlan.mc.game.mixin;

import io.p2wlan.mc.game.client.lan.VirtualLanEntry;
import net.minecraft.client.gui.chat.NarratorChatListener;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** This SDK narrates only saved server entries. Supply the selected transient
 * row's existing human label through the same Vanilla narrator. */
@Mixin(ServerSelectionList.class)
public abstract class LanNarrationMixin {
    @Inject(method = "setSelected(Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList$Entry;)V", at = @At("TAIL"))
    private void p2wlan$narration(ServerSelectionList.Entry entry, CallbackInfo result) {
        if (entry instanceof VirtualLanEntry row) NarratorChatListener.INSTANCE.sayNow(row.getNarration().getString());
    }
}
