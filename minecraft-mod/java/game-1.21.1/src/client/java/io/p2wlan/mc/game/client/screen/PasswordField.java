package io.p2wlan.mc.game.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Mask rendering, narration and copy/cut; clearing removes widget retention.
 * Java/Minecraft immutable strings are not claimed to be securely zeroisable. */
final class PasswordField extends EditBox {
    PasswordField(Font renderer, int x, int y, int width, Component label) {
        super(renderer, x, y, width, 20, label);
        setMaxLength(512);
        setFormatter((value, offset) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (Screen.isCopy(key) || Screen.isCut(key)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void updateWidgetNarration(NarrationElementOutput builder) {
        builder.add(NarratedElementType.TITLE, getMessage());
    }
}
