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
        ClientDisplaySettings.load();
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((draw,ticks)->LatencyHud.render(draw));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> FabricP2wlan.AGENT.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { FabricNetworkView.retire(); AccountScreen.shutdownRequests(); FabricGameJoins.shutdown(); FabricWorlds.shutdown(); FabricP2wlan.AGENT.close(); });
        ScreenEvents.AFTER_INIT.register(io.p2wlan.mc.fabric.client.screen.MenuLogo::install);
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
