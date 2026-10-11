package io.p2wlan.mc.game.client;

import io.p2wlan.mc.common.DisplayPreferences;
import io.p2wlan.mc.common.TelemetryPresentation;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class LatencyHud {
    private LatencyHud() {}
    public static void render(GuiGraphics draw) {
        var client=Minecraft.getInstance();var settings=ClientDisplaySettings.current();
        var service=GameJoins.playingService(client);
        if (!settings.hudVisible() || service==null || client.options.hideGui || client.getDebugOverlay().showDebugScreen()) return;
        var lines=TelemetryPresentation.hud(settings,ClientNetworkView.peer(service.peer()),System.nanoTime());
        int viewportWidth=client.getWindow().getGuiScaledWidth(),viewportHeight=client.getWindow().getGuiScaledHeight();
        int width=Math.min(viewportWidth-12,lines.stream().mapToInt(client.font::width).max().orElse(0)+8),height=lines.size()*11+5;
        boolean right=settings.corner()==DisplayPreferences.Corner.TOP_RIGHT || settings.corner()==DisplayPreferences.Corner.BOTTOM_RIGHT;
        boolean bottom=settings.corner()==DisplayPreferences.Corner.BOTTOM_RIGHT || settings.corner()==DisplayPreferences.Corner.BOTTOM_LEFT;
        int x=right?viewportWidth-width-6:6,y=bottom?viewportHeight-height-6:6;
        draw.fill(x,y,x+width,y+height,0xb0131a25);
        for (int i=0;i<lines.size();i++) draw.drawString(client.font,client.font.plainSubstrByWidth(lines.get(i),width-8),x+4,y+4+i*11,0xffb5c0d0);
    }
}
