package io.p2wlan.mc.fabric.v1_16_5;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.game.GameText;
import io.p2wlan.mc.game.client.ClientDisplaySettings;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.LatencyHud;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.connect.MinecraftConnect;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import io.p2wlan.mc.game.client.screen.AccountScreen;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/** Exact legacy client events; no alternate native lifecycle/path state. */
public final class FabricClientEvents {
    private FabricClientEvents() {}
    public static void create(AgentLifecycle agent, Path config, FabricP2wlan.ServerEvents worlds) {
        if (agent == null || config == null || !config.isAbsolute() || worlds == null) throw new IllegalArgumentException("FABRIC_CLIENT_BINDING_INVALID");
        ClientPlatform.bind(agent, config, worlds::world, worlds::shutdownWorlds);
        MinecraftConnect.supported();
        ClientPlatform.IntegratedHooks.class.isAssignableFrom(net.minecraft.client.server.IntegratedServer.class);
        ClientTickEvents.END_CLIENT_TICK.register(GameJoins::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ClientNetworkView::tick);
        ClientDisplaySettings.load();
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((draw, ticks) -> LatencyHud.render(draw));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> agent.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientPlatform.close());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen);
            io.p2wlan.mc.game.client.screen.MenuLogo.install(client, screen, width, height, buttons, buttons::add);
        });
        ClientCommandManager.DISPATCHER.register(ClientCommandManager.literal("p2wlan-client").executes(context -> {
            var client = context.getSource().getClient(); client.setScreen(AccountScreen.entry(client.screen)); return 1;
        }).then(ClientCommandManager.literal("status").executes(context -> {
            var status = agent.status();
            context.getSource().sendFeedback(GameText.literal("P2WLAN helper: " + status.phase() + (status.reason() == null ? "" : " / " + status.reason())));
            return status.phase() == AgentLifecycle.Phase.READY ? 1 : 0;
        })));
    }
}
