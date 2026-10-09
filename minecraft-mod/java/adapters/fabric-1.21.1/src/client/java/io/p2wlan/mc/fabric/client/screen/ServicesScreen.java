package io.p2wlan.mc.fabric.client.screen;

import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.ServiceCatalog;
import net.minecraft.client.gui.screen.Screen;

/** One bounded discovery reply (max eight opaque worlds), not an availability
 * cache. No background scanning. Native reauthorizes the actual join. */
final class ServicesScreen extends AgentScreen {
    private final RuntimeView runtime;
    private final String peer;
    private ServiceCatalog services;
    private boolean refresh = true;
    private int page, rows = 4;
    ServicesScreen(Screen parent, RuntimeView runtime, String peer) { super(parent, "P2WLAN 游戏服务 / Game services"); this.runtime = runtime; this.peer = peer; }
    @Override protected boolean actionsEnabled() { return runtime.isCurrent(agent()); }
    @Override protected void init() {
        super.init();
        rows = Math.max(1, Math.min(4, (height - top - 138) / 24));
        if (services != null && (!runtime.isCurrent(agent()) || !agent().isCurrent(services.evidence()))) services = null;
        if (services != null) {
            page = Math.min(page, Math.max(0, (services.services().size() - 1) / rows));
            for (int row = 0; row < rows && page * rows + row < services.services().size(); row++) {
                var service = services.services().get(page * rows + row);
                action("加入 / Join " + service.port(), left, top + row * 24, formWidth, button -> {
                    if (!runtime.isCurrent(agent())) { message = "RUNTIME_CHANGED"; return; }
                    client.setScreen(new JoinGameScreen(this, service));
                });
            }
        }
        int controls = top + rows * 24 + 4;
        action("<", left, controls, 35, button -> { if (page > 0) { page--; clearAndInit(); } });
        action(">", left + 40, controls, 35, button -> { if (services != null && (page + 1) * rows < services.services().size()) { page++; clearAndInit(); } });
        action("刷新服务 / Refresh", left + 80, controls, formWidth - 80, button -> refresh = true);
        back(controls + 26);
    }
    @Override public void tick() {
        super.tick();
        if (!runtime.isCurrent(agent())) { services = null; message = "RUNTIME_CHANGED"; return; }
        if (refresh && !REQUESTS.busy()) {
            refresh = false;
            var selectedAgent = agent();
            var arguments = runtime.arguments(); arguments.addProperty("peer", peer);
            request("discover", arguments, () -> runtime.isCurrent(selectedAgent), reply -> {
                services = ServiceCatalog.parse(reply, runtime, peer); page = 0; clearAndInit();
                message = services.services().isEmpty() ? "未发现服务 / No services found" : "选择服务加入 / Select a service";
            });
        }
    }
}
