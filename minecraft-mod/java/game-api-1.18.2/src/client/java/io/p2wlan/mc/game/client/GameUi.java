package io.p2wlan.mc.game.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Minecraft 1.18.2 drawing effects. The shared source owns layout and content. */
public final class GameUi {
    private GameUi() {}
    public static boolean debugVisible(Minecraft client) { return client.options.renderDebug; }
    public static void background(Screen screen, PoseStack draw, int mouseX, int mouseY, float delta) { screen.renderBackground(draw); }
    public static void fill(PoseStack draw, int left, int top, int right, int bottom, int color) { GuiComponent.fill(draw, left, top, right, bottom, color); }
    public static int drawString(PoseStack draw, Font font, String text, int x, int y, int color) { return font.drawShadow(draw, text, x, y, color); }
    public static int drawString(PoseStack draw, Font font, Component text, int x, int y, int color) { return font.drawShadow(draw, text, x, y, color); }
    public static int drawString(PoseStack draw, Font font, FormattedCharSequence text, int x, int y, int color) { return font.drawShadow(draw, text, x, y, color); }
    public static void drawCenteredString(PoseStack draw, Font font, String text, int x, int y, int color) { GuiComponent.drawCenteredString(draw, font, text, x, y, color); }
    public static void drawCenteredString(PoseStack draw, Font font, Component text, int x, int y, int color) { GuiComponent.drawCenteredString(draw, font, text, x, y, color); }
    public static void tooltip(Screen screen, PoseStack draw, int mouseX, int mouseY, Component text) { screen.renderTooltip(draw, text, mouseX, mouseY); }
    /** These shared forms already render persistent field labels. This SDK has
     * no placeholder API; suggestion text would change editable-value semantics. */
    public static void hint(EditBox field, Component text) {}

    public static void buttonTooltip(Button button, Component text) {
        if (!(button instanceof TooltipButton target)) throw new IllegalArgumentException("GAME_TOOLTIP_TARGET");
        target.tooltip = text;
    }

    /** Tooltip storage belongs to the widget and expires with its screen. The
     * old Button callback is skipped by shared custom drawing, so the outer
     * render hook supplies equivalent hovered/focused drawing and narration. */
    public static class TooltipButton extends Button {
        private Component tooltip;
        public TooltipButton(int x, int y, int width, int height, Component text, OnPress action, OnTooltip callback) {
            super(x, y, width, height, text, action, callback);
        }
        @Override public void render(PoseStack draw, int mouseX, int mouseY, float delta) {
            super.render(draw, mouseX, mouseY, delta);
            Screen screen = Minecraft.getInstance().screen;
            if (visible && isHoveredOrFocused() && tooltip != null && screen != null) screen.renderTooltip(draw, tooltip, mouseX, mouseY);
        }
        @Override public void updateNarration(NarrationElementOutput output) {
            super.updateNarration(output);
            if (tooltip != null && isHoveredOrFocused()) output.add(NarratedElementType.HINT, tooltip);
        }
    }
}
