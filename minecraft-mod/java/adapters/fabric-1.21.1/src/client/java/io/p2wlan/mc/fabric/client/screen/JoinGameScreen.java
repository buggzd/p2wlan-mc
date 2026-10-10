package io.p2wlan.mc.fabric.client.screen;

import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.common.JoinProxy;
import io.p2wlan.mc.common.JoinProxyException;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Join/cancel presentation only. The connection owner survives legitimate
 * Vanilla login/configuration/resource-pack screen transitions. */
public final class JoinGameScreen extends Screen {
    private final Screen parent;
    private final GameService service;
    private FabricGameJoins.Attempt attempt;
    private String failure;
    private boolean begun;
    public JoinGameScreen(Screen parent, GameService service) { super(Text.literal("P2WLAN 联机 / Join game")); this.parent = parent; this.service = service; }
    @Override protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("取消 / Cancel"), button -> close()).dimensions(width / 2 - 100, height / 2 + 50, 200, 20).build());
        if (!begun) {
            begun = true;
            try { attempt = FabricGameJoins.start(client, service, this, parent); }
            catch (JoinProxyException error) { failure = JoinProxy.reason(error); }
        }
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { if (attempt != null) attempt.revoke(); client.setScreen(parent); }
    @Override public void removed() {
        if (attempt != null && (attempt.status().phase() == FabricGameJoins.Phase.REGISTERING || attempt.status().phase() == FabricGameJoins.Phase.CONNECTING)) attempt.revoke();
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta); super.render(context, mouseX, mouseY, delta);
        Text status = failure != null ? Text.literal(failure) : attempt == null ? Text.literal("Starting…") : attempt.progress();
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 25, 0xffffff);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status.getString(), Math.max(20, width - 30))), width / 2, height / 2, 0xffcc66);
    }
}
