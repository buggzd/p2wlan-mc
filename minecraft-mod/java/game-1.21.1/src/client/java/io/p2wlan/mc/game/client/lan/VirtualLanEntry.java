package io.p2wlan.mc.game.client.lan;

import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.common.PeerTelemetry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.Component;

/** Transient LAN row, never written to servers.dat, broadcast or resolved as DNS.
 * Inherits Vanilla selection, double-click, Enter and narration behavior. */
public final class VirtualLanEntry extends ServerSelectionList.NetworkServerEntry {
    private final GameService service;
    private final JoinMultiplayerScreen screen;
    public VirtualLanEntry(JoinMultiplayerScreen screen, GameService service) {
        super(screen, new LanServer("P2WLAN Minecraft", "p2wlan.invalid:" + service.port()));
        this.service = service; this.screen = screen;
    }
    public GameService service() { return service; }
    @Override public void render(GuiGraphics draw, int index, int y, int x, int width, int height,
            int mouseX, int mouseY, boolean hovered, float delta) {
        PeerTelemetry peer = ClientNetworkView.peer(service.peer()); long now = System.nanoTime();
        String label = peer == null || peer.label().equals("Minecraft Mod") ? "Minecraft 服务器" : peer.name();
        String latency = peer == null ? "待测量" : peer.latencyLabel(now);
        String path = peer == null || !peer.fresh(now) ? "连接中" : peer.pathLabel();
        int color = path.equals("直连") ? 0xff72dba0 : path.equals("中继") ? 0xff60a5fa : 0xfff1c96a;
        draw.fill(x + 1, y + 1, x + 30, y + 30, 0xff192536);
        for (int row = 0; row < 3; row++) {
            draw.fill(x + 6, y + 5 + row * 8, x + 25, y + 10 + row * 8, 0xff60a5fa);
            draw.fill(x + 21, y + 6 + row * 8, x + 23, y + 8 + row * 8, 0xff72dba0);
        }
        int left = x + 36, right = x + width - 6;
        draw.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(label, Math.max(20, width - 120)), left, y + 2, 0xfff7f9fc);
        draw.drawString(minecraft.font, "P2WLAN", right - minecraft.font.width("P2WLAN"), y + 2, 0xff60a5fa);
        draw.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(ClientNetworkView.roomName() + " · 虚拟局域网", width - 44), left, y + 14, 0xffb5c0d0);
        String metric = path + " · " + latency;
        String address = peer == null || peer.virtualIp().isEmpty() ? "正在同步" : peer.virtualIp() + ":" + service.port();
        draw.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(address, Math.max(20, width - 44 - minecraft.font.width(metric) - 8)), left, y + 26, 0xff7f8ba0);
        draw.drawString(minecraft.font, metric, right - minecraft.font.width(metric), y + 26, color);
        if (hovered && mouseX >= left && mouseY >= y + 24 && peer != null) screen.setTooltipForNextRenderPass(Component.literal(peer.tooltip(now)));
    }
    @Override public Component getNarration() { var peer = ClientNetworkView.peer(service.peer()); return Component.literal("P2WLAN " + (peer == null || peer.label().equals("Minecraft Mod") ? "Minecraft 服务器" : peer.name()) + "，" + ClientNetworkView.roomName()); }
    @Override public Component getServerNarration() { return getNarration(); }
}
