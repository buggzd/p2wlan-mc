package io.p2wlan.mc.fabric.client;

import io.p2wlan.mc.common.DisplayPreferences;
import io.p2wlan.mc.common.TelemetryPresentation;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import io.p2wlan.mc.fabric.client.lan.FabricNetworkView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

final class LatencyHud {
    private LatencyHud() {}
    static void render(DrawContext draw) {
        var client=MinecraftClient.getInstance();var settings=ClientDisplaySettings.current();
        var service=FabricGameJoins.playingService(client);
        if (!settings.hudVisible() || service==null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
        var lines=TelemetryPresentation.hud(settings,FabricNetworkView.peer(service.peer()),System.nanoTime());
        int viewportWidth=client.getWindow().getScaledWidth(),viewportHeight=client.getWindow().getScaledHeight();
        int width=Math.min(viewportWidth-12,lines.stream().mapToInt(client.textRenderer::getWidth).max().orElse(0)+8),height=lines.size()*11+5;
        boolean right=settings.corner()==DisplayPreferences.Corner.TOP_RIGHT || settings.corner()==DisplayPreferences.Corner.BOTTOM_RIGHT;
        boolean bottom=settings.corner()==DisplayPreferences.Corner.BOTTOM_RIGHT || settings.corner()==DisplayPreferences.Corner.BOTTOM_LEFT;
        int x=right?viewportWidth-width-6:6,y=bottom?viewportHeight-height-6:6;
        draw.fill(x,y,x+width,y+height,0xb0131a25);
        for (int i=0;i<lines.size();i++) draw.drawTextWithShadow(client.textRenderer,client.textRenderer.trimToWidth(lines.get(i),width-8),x+4,y+4+i*11,0xffb5c0d0);
    }
}
