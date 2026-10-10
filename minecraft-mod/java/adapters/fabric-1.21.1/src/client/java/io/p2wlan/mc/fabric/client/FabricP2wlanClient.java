package io.p2wlan.mc.fabric.client;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import io.p2wlan.mc.fabric.client.screen.AccountScreen;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import io.p2wlan.mc.fabric.client.connect.MinecraftConnect;
import io.p2wlan.mc.fabric.client.lan.FabricNetworkView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.text.Text;

/** Client lifecycle is application-scoped, not recreated for each local world. */
public final class FabricP2wlanClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        // Force the required exact-version transformation before any join intent.
        MinecraftConnect.supported();
        FabricWorlds.IntegratedHooks.class.isAssignableFrom(net.minecraft.server.integrated.IntegratedServer.class);
        ClientTickEvents.END_CLIENT_TICK.register(FabricGameJoins::tick);
        ClientTickEvents.END_CLIENT_TICK.register(FabricNetworkView::tick);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((draw,ticks)-> {
            var client=net.minecraft.client.MinecraftClient.getInstance();
            var service=FabricGameJoins.playingService(client);
            if (service==null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
            var peer=FabricNetworkView.peer(service.peer());
            String label="P2WLAN · "+(peer==null || !peer.fresh(System.nanoTime())?"待测量":peer.latencyLabel(System.nanoTime())+" · "+peer.pathLabel());
            draw.fill(5,5,client.textRenderer.getWidth(label)+13,19,0xb0131a25);
            draw.drawTextWithShadow(client.textRenderer,label,9,8,0xffb5c0d0);
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> FabricP2wlan.AGENT.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { FabricNetworkView.retire(); AccountScreen.shutdownRequests(); FabricGameJoins.shutdown(); FabricWorlds.shutdown(); FabricP2wlan.AGENT.close(); });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("P2WLAN"), button -> client.setScreen(AccountScreen.entry(screen)))
                        .dimensions(Math.max(4, width - 104), 4, 100, 20).build());
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("p2wlan-client").executes(context -> {
                    var client = context.getSource().getClient();
                    client.setScreen(AccountScreen.entry(client.currentScreen)); return 1;
                }).then(ClientCommandManager.literal("status").executes(context -> {
                    AgentLifecycle.Status status = FabricP2wlan.AGENT.status();
                    context.getSource().sendFeedback(Text.literal("P2WLAN helper: " + status.phase()
                            + (status.reason() == null ? "" : " / " + status.reason())));
                    return status.phase() == AgentLifecycle.Phase.READY ? 1 : 0;
                }))));
    }
}
