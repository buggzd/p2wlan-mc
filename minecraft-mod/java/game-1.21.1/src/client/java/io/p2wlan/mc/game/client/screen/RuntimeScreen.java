package io.p2wlan.mc.game.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.*;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.GameUi;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/** Room connection panel only; Vanilla owns game-server selection. */
final class RuntimeScreen extends AgentScreen {
    private final RuntimeView runtime;
    private boolean stopAcknowledged;
    RuntimeScreen(Screen parent, RuntimeView runtime) { super(parent,"虚拟局域网"); this.runtime=runtime; }
    @Override protected boolean actionsEnabled() { return !stopAcknowledged && runtime.isActive(agent()); }
    private boolean publicationPresent() { var server=minecraft.getSingleplayerServer(); var world=server==null?null:ClientPlatform.world(server); return world!=null && world.status()!=null && world.isCurrent(); }
    @Override protected void init() {
        super.init();
        if (minecraft.getSingleplayerServer()!=null) {
            action("共享本地世界",left,layout.row(3),layout.halfWidth(),()->runtime.isCurrent(agent()) && !publicationPresent(),button->publication(true));
            action("停止共享",layout.right(),layout.row(3),layout.halfWidth(),this::publicationPresent,button->publication(false));
        } else action("打开多人游戏",left,layout.row(3),formWidth,()->runtime.isCurrent(agent()) && ClientNetworkView.authorized() && minecraft.level==null,button->minecraft.setScreen(parent instanceof JoinMultiplayerScreen ? parent : new JoinMultiplayerScreen(parent)));
        footerAction("断开房间",()->runtime.isActive(agent()),button->stop()); back(true);
    }
    private void publication(boolean host) {
        var server = minecraft.getSingleplayerServer();
        var world = server == null ? null : ClientPlatform.world(server);
        var selectedAgent = agent();
        if (world == null) { message = "请先进入单人世界。"; return; }
        if (!ClientPlatform.IntegratedHooks.class.isAssignableFrom(IntegratedServer.class)) { message = "BIND_FAILED"; return; }
        if (host && !runtime.isCurrent(selectedAgent)) { message = "RUNTIME_CHANGED"; return; }
        var view = viewFence();
        java.util.function.BooleanSupplier current = () -> view.getAsBoolean() && world.isCurrent()
                && ClientPlatform.agent().isCurrent(selectedAgent) && runtime.isActive(selectedAgent);
        task(() -> world.intents().submit(server::execute, current, () -> {
            if (host) world.host(runtime); else world.unhost();
            return true;
        }), current, ignored -> message = host ? "正在共享世界…" : "已停止共享本地世界。");
    }
    private void stop() {
        var selectedAgent = agent();
        var server = minecraft.getSingleplayerServer();
        var world = server == null ? null : ClientPlatform.world(server);
        if (server != null && world == null) { message = "WORLD_CHANGED"; return; }
        var view = viewFence();
        java.util.function.BooleanSupplier admitted = () -> view.getAsBoolean() && ClientPlatform.agent().isCurrent(selectedAgent)
                && runtime.isActive(selectedAgent) && (world == null || world.isCurrent());
        // Successful stop changes Native to idle. Accept that presentation, but
        // never apply an old stop result while another runtime is selected.
        java.util.function.BooleanSupplier presentation = () -> runtime.isActive(selectedAgent) || selectedAgent != null && selectedAgent.snapshot()
                .map(value -> "idle".equals(RuntimeView.text(value.data(), "phase"))).orElse(false);
        task(() -> {
            if (!admitted.getAsBoolean()) return CompletableFuture.failedFuture(new AgentException(AgentException.Code.SCOPE_CHANGED));
            GameJoins.shutdown();
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
        if (stopAcknowledged && !runtime.isActive(agent()) && !REQUESTS.busy()) minecraft.setScreen(AccountScreen.entry(parent));
        else if (!runtime.isActive(agent())) message="RUNTIME_CHANGED";
    }
    @Override protected String hint() { return minecraft.level==null?"连接房间后，在多人游戏中选择服务器。":"共享世界支持离线账号，请使用不同游戏名；结束联机时断开房间。"; }
    @Override protected String liveSummary() {
        if (!runtime.isActive(agent())) return "连接已结束";
        String name=ClientNetworkView.roomName();
        return name + (ClientNetworkView.authorized()?" · 局域网已连接":" · 正在连接…");
    }
    private void metric(GuiGraphics draw, String label, String value, int x, int y, int width, int tone) {
        draw.fill(x,y,x+width,y+32,0xff202a3a);
        draw.drawString(font,label,x+7,y+4,0xff7f8ba0);
        draw.drawString(font,font.plainSubstrByWidth(value,width-14),x+7,y+18,tone);
    }
    @Override public void render(GuiGraphics draw,int mouseX,int mouseY,float delta) {
        super.render(draw,mouseX,mouseY,delta);
        long now=System.nanoTime(); var observations=ClientNetworkView.peers();
        PeerTelemetry peer = ClientNetworkView.displayPeer(minecraft);
        String latency=peer==null?"未测得":peer.latencyLabel(now);
        long online=observations.stream().filter(value->value.online() && value.fresh(now)).count();
        String ip=runtime.isCurrent(agent())?agent().snapshot().map(value->RuntimeView.text(value.data(),"virtual_ip")).orElse(null):null;
        String path=peer==null || !peer.fresh(now)?"等待链路":peer.pathLabel();
        int tone = peer == null || !peer.fresh(now) || peer.path() == null ? 0xfff1c96a
                : !peer.online() ? 0xffb5c0d0 : peer.path().equals("direct") ? 0xff8de2b1 : 0xff60a5fa;
        metric(draw,"端到端 RTT",latency,left,top,layout.halfWidth(),tone);
        metric(draw,"在线设备",ClientNetworkView.authorized()?(online+1)+" 台":"同步中",layout.right(),top,layout.halfWidth(),0xfff7f9fc);
        metric(draw,"我的虚拟 IP",ip==null?"正在分配":ip,left,top+36,layout.halfWidth(),0xfff7f9fc);
        metric(draw,"连接方式",path,layout.right(),top+36,layout.halfWidth(),tone);
        if (mouseX>=left && mouseX<left+formWidth && mouseY>=top && mouseY<top+68) {
            String tooltip=peer==null?"RTT 由 P2WLAN 网络核心测量；等待有效样本时显示未测得。\n虚拟 IP 用于 Mod 的房间游戏通道。":"测量对象："+peer.name()+"\n"+peer.tooltip(now)
                    +"\n未入服时，面板取已观测设备中最低的有效 RTT；游戏内显示当前服务端。";
            tooltip += "\n在线数为近期已观测设备（含本机），最多观测 16 个对端。";
            if (!ClientNetworkView.reason().isEmpty()) tooltip += "\n状态："+ClientNetworkView.reason();
            GameUi.tooltip(this, Component.literal(tooltip));
        }
    }
}
