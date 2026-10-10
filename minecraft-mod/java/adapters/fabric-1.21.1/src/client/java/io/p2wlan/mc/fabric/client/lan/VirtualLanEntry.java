package io.p2wlan.mc.fabric.client.lan;

import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.common.PeerTelemetry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.LanServerInfo;
import net.minecraft.text.Text;

/** Transient LAN row, never written to servers.dat, broadcast or resolved as DNS.
 * Inherits Vanilla selection, double-click, Enter and narration behavior. */
public final class VirtualLanEntry extends MultiplayerServerListWidget.LanServerEntry {
    private final GameService service;
    private final MultiplayerScreen screen;
    public VirtualLanEntry(MultiplayerScreen screen, GameService service) {
        super(screen, new LanServerInfo("P2WLAN Minecraft", "p2wlan.invalid:" + service.port()));
        this.service = service; this.screen = screen;
    }
    public GameService service() { return service; }
    @Override public void render(DrawContext draw, int index, int y, int x, int width, int height,
            int mouseX, int mouseY, boolean hovered, float delta) {
        PeerTelemetry peer = FabricNetworkView.peer(service.peer()); long now = System.nanoTime();
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
        draw.drawTextWithShadow(client.textRenderer, client.textRenderer.trimToWidth(label, Math.max(20, width - 120)), left, y + 2, 0xfff7f9fc);
        draw.drawTextWithShadow(client.textRenderer, "P2WLAN", right - client.textRenderer.getWidth("P2WLAN"), y + 2, 0xff60a5fa);
        draw.drawTextWithShadow(client.textRenderer, client.textRenderer.trimToWidth(FabricNetworkView.roomName() + " · 虚拟局域网", width - 44), left, y + 14, 0xffb5c0d0);
        String metric = path + " · " + latency;
        String address = peer == null || peer.virtualIp().isEmpty() ? "正在同步" : peer.virtualIp() + ":" + service.port();
        draw.drawTextWithShadow(client.textRenderer, client.textRenderer.trimToWidth(address, Math.max(20, width - 44 - client.textRenderer.getWidth(metric) - 8)), left, y + 26, 0xff7f8ba0);
        draw.drawTextWithShadow(client.textRenderer, metric, right - client.textRenderer.getWidth(metric), y + 26, color);
        if (hovered && mouseX >= left && mouseY >= y + 24 && peer != null) screen.setTooltip(Text.literal(peer.tooltip(now)));
    }
    @Override public Text getNarration() { var peer = FabricNetworkView.peer(service.peer()); return Text.literal("P2WLAN " + (peer == null || peer.label().equals("Minecraft Mod") ? "Minecraft 服务器" : peer.name()) + "，" + FabricNetworkView.roomName()); }
    @Override public Text getMotdNarration() { return getNarration(); }
}
