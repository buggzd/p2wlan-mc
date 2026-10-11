package io.p2wlan.mc.game.client.screen;

import io.p2wlan.mc.common.LatencyStatistics;
import io.p2wlan.mc.common.PeerTelemetry;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only current-peer statistics. Polling is owned by ClientNetworkView. */
public final class LatencyStatsScreen extends LocalPanelScreen {
    public LatencyStatsScreen(Screen parent) { super(parent,"延迟统计"); }
    @Override protected void init() {
        super.init();
        button("显示设置",layout.left(),layout.footerY(),layout.halfWidth(),pressed->minecraft.setScreen(parent instanceof DisplaySettingsScreen?parent:new DisplaySettingsScreen(this)));
        button("返回",layout.right(),layout.footerY(),layout.halfWidth(),pressed->onClose());
    }
    @Override protected String summary() {
        var peer=ClientNetworkView.displayPeer(minecraft);long now=System.nanoTime();
        if (peer==null || !peer.statisticsCurrent(now)) return "等待有效 RTT 测量。窗口为最近 60 秒，最多 256 个成功样本。";
        var stats=peer.statistics();
        return stats.windowLabel()+(stats.samples()<100?" · P99 样本较少":"")+"\n当前设备："+peer.name()+" · "+peer.pathLabel();
    }
    private void metric(GuiGraphics draw,String label,String value,int x,int y,int width) {
        draw.fill(x,y,x+width,y+24,0xff202a3a);
        draw.drawString(font,label,x+5,y+2,0xff7f8ba0);
        draw.drawString(font,font.plainSubstrByWidth(value,width-10),x+5,y+13,0xfff7f9fc);
    }
    @Override public void render(GuiGraphics draw,int mouseX,int mouseY,float delta) {
        super.render(draw,mouseX,mouseY,delta);
        long now=System.nanoTime();PeerTelemetry peer=ClientNetworkView.displayPeer(minecraft);
        LatencyStatistics stats=peer!=null && peer.statisticsCurrent(now)?peer.statistics():null;
        metric(draw,"当前 RTT",peer==null?"未测得":peer.latencyLabel(now),layout.left(),layout.row(0),layout.halfWidth());
        metric(draw,"抖动 Jitter",peer!=null && peer.recentRtt(now) && peer.jitterMs()!=null?peer.jitterMs()+" ms":"未测得",layout.right(),layout.row(0),layout.halfWidth());
        int third=(layout.width()-16)/3;
        metric(draw,"Min",LatencyStatistics.ms(stats==null?null:stats.minUs())+" ms",layout.left(),layout.row(1),third);
        metric(draw,"Avg",LatencyStatistics.ms(stats==null?null:stats.avgUs())+" ms",layout.left()+third+8,layout.row(1),third);
        metric(draw,"Max",LatencyStatistics.ms(stats==null?null:stats.maxUs())+" ms",layout.left()+(third+8)*2,layout.row(1),third);
        String[] labels={"P50","P90","P95","P99"};
        Long[] values=stats==null?new Long[4]:new Long[]{stats.p50Us(),stats.p90Us(),stats.p95Us(),stats.p99Us()};
        for (int i=0;i<4;i++) metric(draw,labels[i],LatencyStatistics.ms(values[i])+" ms",i%2==0?layout.left():layout.right(),layout.row(2+i/2),layout.halfWidth());
        if (mouseX>=layout.left() && mouseX<layout.left()+layout.width() && mouseY>=layout.top() && mouseY<layout.row(3)+24)
            setTooltipForNextRenderPass(Component.literal(peer==null?"RTT 是 P2WLAN 网络核心测得的路径往返时间。":peer.tooltip(now)));
    }
}
