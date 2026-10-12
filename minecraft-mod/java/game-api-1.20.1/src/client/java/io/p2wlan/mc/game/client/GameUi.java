package io.p2wlan.mc.game.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;

/** Minecraft 1.20.1 UI effects used by the shared screens and HUD. */
public final class GameUi {
    private GameUi() {}

    public static boolean debugVisible(Minecraft client) {
        return client.options.renderDebug;
    }

    public static void background(Screen screen, GuiGraphics draw, int mouseX, int mouseY, float delta) {
        screen.renderBackground(draw);
    }

    public static void tooltip(Screen screen, Component text) {
        screen.setTooltipForNextRenderPass(Tooltip.create(text), DefaultTooltipPositioner.INSTANCE, true);
    }
}
