package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.*;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import io.p2wlan.mc.fabric.client.lan.FabricNetworkView;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;

/** Room connection panel only; Vanilla owns game-server selection. */
final class RuntimeScreen extends AgentScreen {
    private final RuntimeView runtime;
    private boolean stopAcknowledged;
    RuntimeScreen(Screen parent, RuntimeView runtime) { super(parent,"虚拟局域网"); this.runtime=runtime; }
    @Override protected boolean actionsEnabled() { return !stopAcknowledged && runtime.isActive(agent()); }
    private boolean publicationPresent() { var server=client.getServer(); var world=server==null?null:FabricWorlds.current(server); return world!=null && world.status()!=null && world.isCurrent(); }
    @Override protected void init() {
        super.init();
        if (client.getServer()!=null) {
            action("共享本地世界",left,layout.row(3),layout.halfWidth(),()->runtime.isCurrent(agent()) && !publicationPresent(),button->publication(true));
            action("停止共享",layout.right(),layout.row(3),layout.halfWidth(),this::publicationPresent,button->publication(false));
        } else action("打开多人游戏",left,layout.row(3),formWidth,()->runtime.isCurrent(agent()) && FabricNetworkView.authorized() && client.world==null,button->client.setScreen(parent instanceof MultiplayerScreen ? parent : new MultiplayerScreen(parent)));
        footerAction("断开房间",()->runtime.isActive(agent()),button->stop()); back(true);
    }
    private void publication(boolean host) {
        var server = client.getServer();
        var world = server == null ? null : FabricWorlds.current(server);
        var selectedAgent = agent();
        if (world == null) { message = "请先进入单人世界。"; return; }
        if (!FabricWorlds.IntegratedHooks.class.isAssignableFrom(IntegratedServer.class)) { message = "BIND_FAILED"; return; }
        if (host && !runtime.isCurrent(selectedAgent)) { message = "RUNTIME_CHANGED"; return; }
        var view = viewFence();
        java.util.function.BooleanSupplier current = () -> view.getAsBoolean() && world.isCurrent()
                && FabricP2wlan.AGENT.isCurrent(selectedAgent) && runtime.isActive(selectedAgent);
        task(() -> world.intents().submit(server::execute, current, () -> {
            if (host) world.host(runtime); else world.unhost();
            return true;
        }), current, ignored -> message = host ? "正在共享世界…" : "已停止共享本地世界。");
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
            stopAcknowledged = true;
            message = "已断开，正在返回房间列表…";
        });
    }
    @Override public void tick() {
        super.tick();
        if (stopAcknowledged && !runtime.isActive(agent()) && !REQUESTS.busy()) client.setScreen(AccountScreen.entry(parent));
        else if (!runtime.isActive(agent())) message="RUNTIME_CHANGED";
    }
    @Override protected String hint() { return client.world==null?"房间只保持局域网连接；到多人游戏选择服务器。":"退出游戏后仍保持房间连接；返回不等于断开。"; }
    @Override protected String liveSummary() {
        if (!runtime.isActive(agent())) return "连接已结束";
        String name=FabricNetworkView.roomName();
        return name + (FabricNetworkView.authorized()?" · 局域网已连接":" · 正在连接…");
    }
    private void metric(DrawContext draw, String label, String value, int x, int y, int width, int tone) {
        draw.fill(x,y,x+width,y+32,0xff202a3a);
        draw.drawTextWithShadow(textRenderer,label,x+7,y+4,0xff7f8ba0);
        draw.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(value,width-14),x+7,y+18,tone);
    }
    @Override public void render(DrawContext draw,int mouseX,int mouseY,float delta) {
        super.render(draw,mouseX,mouseY,delta);
        long now=System.nanoTime(); var observations=FabricNetworkView.peers();
        PeerTelemetry peer = FabricNetworkView.displayPeer(client);
        String latency=peer==null?"未测得":peer.latencyLabel(now);
        long online=observations.stream().filter(value->value.online() && value.fresh(now)).count();
        String ip=runtime.isCurrent(agent())?agent().snapshot().map(value->RuntimeView.text(value.data(),"virtual_ip")).orElse(null):null;
        String path=peer==null || !peer.fresh(now)?"等待链路":peer.pathLabel();
        int tone = peer == null || !peer.fresh(now) || peer.path() == null ? 0xfff1c96a
                : !peer.online() ? 0xffb5c0d0 : peer.path().equals("direct") ? 0xff8de2b1 : 0xff60a5fa;
        metric(draw,"端到端 RTT",latency,left,top,layout.halfWidth(),tone);
        metric(draw,"在线设备",FabricNetworkView.authorized()?(online+1)+" 台":"同步中",layout.right(),top,layout.halfWidth(),0xfff7f9fc);
        metric(draw,"我的虚拟 IP",ip==null?"正在分配":ip,left,top+36,layout.halfWidth(),0xfff7f9fc);
        metric(draw,"连接方式",path,layout.right(),top+36,layout.halfWidth(),tone);
        if (mouseX>=left && mouseX<left+formWidth && mouseY>=top && mouseY<top+68) {
            String tooltip=peer==null?"RTT 由原 P2WLAN 网络核心测量；无有效样本时显示未测得。\n虚拟 IP 只用于房间，不会修改系统路由。":"测量对象："+peer.name()+"\n"+peer.tooltip(now)
                    +"\n未入服时，面板取已观测设备中最低的有效 RTT；游戏内显示当前服务端。";
            tooltip += "\n在线数为近期已观测设备（含本机），最多观测 16 个对端。";
            if (!FabricNetworkView.reason().isEmpty()) tooltip += "\n状态："+FabricNetworkView.reason();
            setTooltip(Text.literal(tooltip));
        }
    }
}
