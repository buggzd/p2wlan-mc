package io.p2wlan.mc.fabric.client.screen;

import io.p2wlan.mc.common.RoomPeers;
import io.p2wlan.mc.common.RuntimeView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** One selected native runtime and at most 128 immutable member rows. A member
 * is not a ready game server; discovery is a separate one-peer user intent. */
final class RuntimeScreen extends AgentScreen {
    private final RuntimeView runtime;
    private RoomPeers members;
    private boolean refresh = true;
    private int page, rows = 4;
    RuntimeScreen(Screen parent, RuntimeView runtime) { super(parent, "P2WLAN 房间连接 / Room runtime"); this.runtime = runtime; }
    @Override protected boolean actionsEnabled() { return runtime.isActive(agent()); }
    @Override protected void init() {
        super.init();
        rows = Math.max(1, Math.min(4, (height - top - 138) / 24));
        if (members != null && (!runtime.isCurrent(agent()) || !agent().isCurrent(members.evidence()))) members = null;
        if (members != null) {
            page = Math.min(page, Math.max(0, (members.peers().size() - 1) / rows));
            for (int row = 0; row < rows && page * rows + row < members.peers().size(); row++) {
                String peer = members.peers().get(page * rows + row);
                action(textRenderer.trimToWidth(peer, formWidth - 10), left, top + row * 24, formWidth, button -> {
                    if (!runtime.isCurrent(agent())) { message = "RUNTIME_CHANGED"; return; }
                    client.setScreen(new ServicesScreen(this, runtime, peer));
                });
            }
        }
        int controls = top + rows * 24 + 4;
        action("<", left, controls, 35, button -> { if (page > 0) { page--; clearAndInit(); } });
        action(">", left + 40, controls, 35, button -> { if (members != null && (page + 1) * rows < members.peers().size()) { page++; clearAndInit(); } });
        action("刷新成员 / Refresh", left + 80, controls, formWidth - 80, button -> refresh = true);
        back(controls + 26);
    }
    @Override public void tick() {
        super.tick();
        if (!runtime.isActive(agent())) { members = null; message = "RUNTIME_CHANGED"; return; }
        if (refresh && runtime.isCurrent(agent()) && !REQUESTS.busy()) {
            refresh = false;
            var selectedAgent = agent();
            request("room_peers", runtime.arguments(), () -> runtime.isCurrent(selectedAgent), reply -> {
                members = RoomPeers.parse(reply, runtime); page = 0; clearAndInit();
                message = "成员不代表游戏就绪 / Members are not game readiness";
            });
        }
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        String phase = runtime.isActive(agent()) ? agent().snapshot().map(value -> RuntimeView.text(value.data(), "phase")).orElse("NOT_READY") : "RUNTIME_CHANGED";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Native: " + phase + (members == null ? "" : " / " + members.peers().size() + " members / Page " + (page + 1))), width / 2, top - 15, 0xa0a0a0);
    }
}
