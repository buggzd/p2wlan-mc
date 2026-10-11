package io.p2wlan.mc.fabric;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import io.p2wlan.mc.game.server.WorldCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Common/dedicated bootstrap. Deliberately imports no client class. */
public final class FabricP2wlan implements ModInitializer {
    public static final AgentLifecycle AGENT = new AgentLifecycle(
            FabricLoader.getInstance().getConfigDir().toAbsolutePath().resolve("p2wlan-private"),
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"),
            FabricLoader.getInstance().getGameDir().toAbsolutePath().resolve("logs/p2wlan-mod"));
    @Override public void onInitialize() {
        FabricWorlds.initialize();
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> AGENT.start());
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> { FabricWorlds.shutdown(); AGENT.close(); });
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                WorldCommands.register(dispatcher, AGENT, FabricWorlds::current, FabricWorlds::transportSupported));
    }
}
