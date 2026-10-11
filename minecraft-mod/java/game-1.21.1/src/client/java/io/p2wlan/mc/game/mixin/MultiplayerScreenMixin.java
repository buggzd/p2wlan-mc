package io.p2wlan.mc.game.mixin;

import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.game.client.lan.*;
import io.p2wlan.mc.game.client.screen.JoinGameScreen;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen implements VanillaLanScreen {
    @Shadow protected ServerSelectionList serverSelectionList;
    @Unique private List<GameService> p2wlan$shown;
    protected MultiplayerScreenMixin(Component title) { super(title); }
    public ServerSelectionList p2wlan$list() { return serverSelectionList; }
    @Inject(method = "tick", at = @At("TAIL"))
    private void p2wlan$update(CallbackInfo callback) {
        List<GameService> next = ClientNetworkView.services();
        if (p2wlan$shown != next && serverSelectionList != null) {
            var selected = serverSelectionList.getSelected();
            ((VanillaLanList) serverSelectionList).p2wlan$refresh(); p2wlan$shown = next;
            if (selected instanceof VirtualLanEntry before) {
                serverSelectionList.setSelected(null);
                for (var entry : serverSelectionList.children()) if (entry instanceof VirtualLanEntry after && after.service().equals(before.service())) { serverSelectionList.setSelected(after); break; }
            } else if (selected != null && serverSelectionList.children().contains(selected)) serverSelectionList.setSelected(selected);
        }
    }
    @Inject(method = "joinSelectedServer()V", at = @At("HEAD"), cancellable = true)
    private void p2wlan$connect(CallbackInfo callback) {
        if (serverSelectionList.getSelected() instanceof VirtualLanEntry entry) {
            // JoinProxy/daemon reacquire current grants and exact remote-world
            // capability at execution; the row itself authorizes nothing.
            if (ClientNetworkView.contains(entry.service())) minecraft.setScreen(new JoinGameScreen(this, entry.service()));
            else minecraft.setScreen(new net.minecraft.client.gui.screens.DisconnectedScreen(this, Component.literal("服务器列表已更新"), Component.literal("返回多人游戏列表重新选择。")));
            callback.cancel();
        }
    }
}
