package io.p2wlan.mc.fabric.client;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.ClientDisplaySettings;
import io.p2wlan.mc.game.client.LatencyHud;
import io.p2wlan.mc.game.client.screen.AccountScreen;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.connect.MinecraftConnect;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/** Client lifecycle is application-scoped, not recreated for each local world. */
public final class FabricP2wlanClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlatform.bind(FabricP2wlan.AGENT, net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toAbsolutePath(), FabricWorlds::current, FabricWorlds::shutdown);
        // Force the required exact-version transformation before any join intent.
        MinecraftConnect.supported();
        ClientPlatform.IntegratedHooks.class.isAssignableFrom(net.minecraft.client.server.IntegratedServer.class);
        ClientTickEvents.END_CLIENT_TICK.register(GameJoins::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ClientNetworkView::tick);
        ClientDisplaySettings.load();
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((draw,ticks)->LatencyHud.render(draw));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> FabricP2wlan.AGENT.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientPlatform.close());
        ScreenEvents.AFTER_INIT.register((client,screen,width,height) -> {
            var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen);
            io.p2wlan.mc.game.client.screen.MenuLogo.install(client,screen,width,height,buttons,buttons::add);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("p2wlan-client").executes(context -> {
                    var client = context.getSource().getClient();
                    client.setScreen(AccountScreen.entry(client.screen)); return 1;
                }).then(ClientCommandManager.literal("status").executes(context -> {
                    AgentLifecycle.Status status = FabricP2wlan.AGENT.status();
                    context.getSource().sendFeedback(Component.literal("P2WLAN helper: " + status.phase()
                            + (status.reason() == null ? "" : " / " + status.reason())));
                    return status.phase() == AgentLifecycle.Phase.READY ? 1 : 0;
                }))));
    }
}
