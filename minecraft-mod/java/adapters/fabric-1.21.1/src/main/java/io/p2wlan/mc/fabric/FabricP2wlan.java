package io.p2wlan.mc.fabric;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import io.p2wlan.mc.fabric.world.WorldCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

/** Common/dedicated bootstrap. Deliberately imports no client class. */
public final class FabricP2wlan implements ModInitializer {
    public static final AgentLifecycle AGENT = new AgentLifecycle(
            FabricLoader.getInstance().getConfigDir().toAbsolutePath().resolve("p2wlan-private"),
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"));
    @Override public void onInitialize() {
        FabricWorlds.initialize();
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> AGENT.start());
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> { FabricWorlds.shutdown(); AGENT.close(); });
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = CommandManager.literal("p2wlan").requires(source -> source.hasPermissionLevel(4))
                    .then(CommandManager.literal("status").executes(context -> {
                        AgentLifecycle.Status status = AGENT.status();
                        context.getSource().sendFeedback(() -> Text.literal("P2WLAN helper: " + status.phase()
                                + (status.reason() == null ? "" : " / " + status.reason())
                                + "; world transport hooks: " + (FabricWorlds.transportSupported() ? "linked" : "missing")), false);
                        return status.phase() == AgentLifecycle.Phase.READY ? 1 : 0;
                    }));
            WorldCommands.add(root); dispatcher.register(root);
        });
    }
}
