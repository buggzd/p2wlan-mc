package io.p2wlan.mc.fabric.mixin;

import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.fabric.client.lan.*;
import io.p2wlan.mc.fabric.client.screen.JoinGameScreen;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen implements VanillaLanScreen {
    @Shadow protected MultiplayerServerListWidget serverListWidget;
    @Unique private List<GameService> p2wlan$shown;
    protected MultiplayerScreenMixin(Text title) { super(title); }
    public MultiplayerServerListWidget p2wlan$list() { return serverListWidget; }
    @Inject(method = "tick", at = @At("TAIL"))
    private void p2wlan$update(CallbackInfo callback) {
        List<GameService> next = FabricNetworkView.services();
        if (p2wlan$shown != next && serverListWidget != null) {
            var selected = serverListWidget.getSelectedOrNull();
            ((VanillaLanList) serverListWidget).p2wlan$refresh(); p2wlan$shown = next;
            if (selected instanceof VirtualLanEntry before) {
                serverListWidget.setSelected(null);
                for (var entry : serverListWidget.children()) if (entry instanceof VirtualLanEntry after && after.service().equals(before.service())) { serverListWidget.setSelected(after); break; }
            } else if (selected != null && serverListWidget.children().contains(selected)) serverListWidget.setSelected(selected);
        }
    }
    @Inject(method = "connect()V", at = @At("HEAD"), cancellable = true)
    private void p2wlan$connect(CallbackInfo callback) {
        if (serverListWidget.getSelectedOrNull() instanceof VirtualLanEntry entry) {
            // JoinProxy/daemon reacquire current grants and exact remote-world
            // capability at execution; the row itself authorizes nothing.
            if (FabricNetworkView.contains(entry.service())) client.setScreen(new JoinGameScreen(this, entry.service()));
            else client.setScreen(new net.minecraft.client.gui.screen.DisconnectedScreen(this, Text.literal("服务器列表已更新"), Text.literal("返回多人游戏列表重新选择。")));
            callback.cancel();
        }
    }
}
