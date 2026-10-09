package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.RoomPeers;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.integrated.IntegratedServer;
import io.p2wlan.mc.common.RuntimeView;
import net.minecraft.client.gui.screen.Screen;

/** One selected native runtime and at most 128 immutable member rows. A member
 * is not a ready game server; discovery is a separate one-peer user intent. */
final class RuntimeScreen extends AgentScreen {
    private final RuntimeView runtime;
    private RoomPeers members;
    private boolean refresh = true, stopAcknowledged;
    private int page, rows = 4;
    RuntimeScreen(Screen parent, RuntimeView runtime) { super(parent, "P2WLAN 房间连接 / Room runtime"); this.runtime = runtime; }
    @Override protected boolean actionsEnabled() { return !stopAcknowledged && runtime.isActive(agent()); }
    private boolean membersCurrent() { var current = agent(); return current != null && runtime.isCurrent(current) && members != null && current.isCurrent(members.evidence()); }
    private boolean worldPresent() { var server = client.getServer(); return server != null && FabricWorlds.current(server) != null; }
    private boolean publicationPresent() { var server = client.getServer(); var world = server == null ? null : FabricWorlds.current(server); return world != null && world.status() != null && world.isCurrent(); }
    @Override protected void init() {
        super.init();
        rows = layout.listRows(3, 4);
        if (!membersCurrent()) members = null;
        if (members != null) {
            page = Math.min(page, Math.max(0, (members.peers().size() - 1) / rows));
            for (int row = 0; row < rows && page * rows + row < members.peers().size(); row++) {
                String peer = members.peers().get(page * rows + row);
                action(textRenderer.trimToWidth(peer, formWidth - 10), left, layout.row(row), formWidth, this::membersCurrent, button -> {
                    if (!runtime.isCurrent(agent())) { message = "RUNTIME_CHANGED"; return; }
                    client.setScreen(new ServicesScreen(this, runtime, peer));
                });
            }
        }
        int controls = layout.controls(rows);
        action("上一页", left, controls, 42, () -> membersCurrent() && page > 0, button -> { page--; clearAndInit(); });
        action("下一页", left + 50, controls, 42, () -> membersCurrent() && (page + 1) * rows < members.peers().size(), button -> { page++; clearAndInit(); });
        action("刷新成员 / Refresh", left + 100, controls, formWidth - 100, () -> runtime.isCurrent(agent()), button -> refresh = true);
        action("断开房间 / Stop room", left, controls + 52, formWidth, button -> stop());
        action("托管世界 / Host", left, controls + 26, layout.halfWidth(), () -> runtime.isCurrent(agent()) && worldPresent(), button -> publication(true));
        action("取消托管 / Unhost", layout.right(), controls + 26, layout.halfWidth(), this::publicationPresent, button -> publication(false));
        back();
    }
    private void publication(boolean host) {
        var server = client.getServer();
        var world = server == null ? null : FabricWorlds.current(server);
        var selectedAgent = agent();
        if (world == null) { message = "需要单人世界 / Single-player world required"; return; }
        if (!FabricWorlds.IntegratedHooks.class.isAssignableFrom(IntegratedServer.class)) { message = "BIND_FAILED"; return; }
        if (host && !runtime.isCurrent(selectedAgent)) { message = "RUNTIME_CHANGED"; return; }
        var view = viewFence();
        java.util.function.BooleanSupplier current = () -> view.getAsBoolean() && world.isCurrent()
                && FabricP2wlan.AGENT.isCurrent(selectedAgent) && runtime.isActive(selectedAgent);
        task(() -> world.intents().submit(server::execute, current, () -> {
            if (host) world.host(runtime); else world.unhost();
            return true;
        }), current, ignored -> message = host ? "托管启动中 / Host starting" : "本地托管已撤销 / Local host revoked");
    }
    private void stop() {
        var selectedAgent = agent();
        var server = client.getServer();
        var world = server == null ? null : FabricWorlds.current(server);
        if (server != null && world == null) { message = "WORLD_CHANGED"; return; }
        var view = viewFence();
        java.util.function.BooleanSupplier admitted = () -> view.getAsBoolean() && FabricP2wlan.AGENT.isCurrent(selectedAgent)
                && runtime.isActive(selectedAgent) && (world == null || world.isCurrent());
        // Successful stop changes Native to idle. Accept that presentation, but
        // never apply an old stop result while another runtime is selected.
        java.util.function.BooleanSupplier presentation = () -> runtime.isActive(selectedAgent) || selectedAgent != null && selectedAgent.snapshot()
                .map(value -> "idle".equals(RuntimeView.text(value.data(), "phase"))).orElse(false);
        task(() -> {
            if (!admitted.getAsBoolean()) return CompletableFuture.failedFuture(new AgentException(AgentException.Code.SCOPE_CHANGED));
            FabricGameJoins.shutdown();
            CompletableFuture<Boolean> revoked = world == null ? CompletableFuture.completedFuture(true)
                    : world.intents().submit(server::execute, admitted, () -> { world.unhost(); return true; });
            return revoked.thenCompose(ignored -> selectedAgent.request("stop", runtime.arguments(), admitted));
        }, presentation, reply -> {
            JsonObject expected = new JsonObject(); expected.addProperty("stopped", true);
            if (!selectedAgent.isCurrent(reply) || !reply.data().equals(expected)) throw new AgentException(AgentException.Code.INVALID_REPLY);
            stopAcknowledged = true; refresh = false; members = null;
            message = "停止已确认，等待状态 / Stop acknowledged; waiting for status";
        });
    }
    @Override public void tick() {
        super.tick();
        if (stopAcknowledged) {
            if (!runtime.isActive(agent()) && !REQUESTS.busy()) client.setScreen(AccountScreen.entry(parent));
            return;
        }
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
    @Override protected String hint() { return "成员在线不代表游戏就绪；选择成员后发现游戏服务。"; }
    @Override protected String liveSummary() {
        var current = agent();
        String phase = runtime.isActive(current) ? current.snapshot().map(value -> RuntimeView.text(value.data(), "phase")).orElse("NOT_READY") : "RUNTIME_CHANGED";
        var server = client.getServer();
        var world = server == null ? null : FabricWorlds.current(server);
        var hosted = world == null || !world.isCurrent() ? null : world.status();
        String publication = hosted == null ? "NOT_PUBLISHED" : hosted.phase() + (hosted.reason() == null ? "" : " / " + hosted.reason());
        return "房间：" + phase + " / 世界：" + publication + (members == null ? " / 成员待读取" : " / " + members.peers().size() + " 位成员，第 " + (page + 1) + " 页");
    }
}
