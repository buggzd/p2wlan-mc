package io.p2wlan.mc.fabric.client.screen;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/** Mask rendering, narration and copy/cut; clearing removes widget retention.
 * Java/Minecraft immutable strings are not claimed to be securely zeroisable. */
final class PasswordField extends TextFieldWidget {
    PasswordField(TextRenderer renderer, int x, int y, int width, Text label) {
        super(renderer, x, y, width, 20, label);
        setMaxLength(512);
        setRenderTextProvider((value, offset) -> OrderedText.styledForwardsVisitedString("*".repeat(value.length()), Style.EMPTY));
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (Screen.isCopy(key) || Screen.isCut(key)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, getMessage());
    }
}
