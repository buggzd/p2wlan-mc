package io.p2wlan.mc.game.client.screen;

import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.common.JoinProxy;
import io.p2wlan.mc.common.JoinProxyException;
import io.p2wlan.mc.game.client.connect.GameJoins;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Join/cancel presentation only. The connection owner survives legitimate
 * Vanilla login/configuration/resource-pack screen transitions. */
public final class JoinGameScreen extends Screen {
    private final Screen parent;
    private final GameService service;
    private GameJoins.Attempt attempt;
    private String failure;
    private boolean begun;
    public JoinGameScreen(Screen parent, GameService service) { super(Component.literal("P2WLAN 联机 / Join game")); this.parent = parent; this.service = service; }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("取消 / Cancel"), button -> onClose()).bounds(width / 2 - 100, height / 2 + 50, 200, 20).build());
        if (!begun) {
            begun = true;
            try { attempt = GameJoins.start(minecraft, service, this, parent); }
            catch (JoinProxyException error) { failure = JoinProxy.reason(error); }
        }
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { if (attempt != null) attempt.revoke(); minecraft.setScreen(parent); }
    @Override public void removed() {
        if (attempt != null && (attempt.status().phase() == GameJoins.Phase.REGISTERING || attempt.status().phase() == GameJoins.Phase.CONNECTING)) attempt.revoke();
    }
    @Override public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta); super.render(context, mouseX, mouseY, delta);
        Component status = failure != null ? Component.literal(failure) : attempt == null ? Component.literal("Starting…") : attempt.progress();
        context.drawCenteredString(font, title, width / 2, height / 2 - 25, 0xffffff);
        context.drawCenteredString(font, Component.literal(font.plainSubstrByWidth(status.getString(), Math.max(20, width - 30))), width / 2, height / 2, 0xffcc66);
    }
}
