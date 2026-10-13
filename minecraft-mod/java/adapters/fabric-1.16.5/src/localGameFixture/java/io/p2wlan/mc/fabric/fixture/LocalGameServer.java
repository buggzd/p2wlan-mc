package io.p2wlan.mc.fabric.fixture;

import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import io.p2wlan.mc.game.fixture.GameServerChecks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/** The old command event passes the same dispatcher to the shared checks. */
public final class LocalGameServer implements ModInitializer {
    @Override public void onInitialize() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        InstalledArchiveChecks.verify();
        var checks = new GameServerChecks(FabricP2wlan.AGENT, FabricWorlds::current, FabricWorlds::shutdown);
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> checks.commands(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> checks.joined());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> checks.disconnected());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> checks.started());
        ServerTickEvents.END_SERVER_TICK.register(checks::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(checks::stopped);
    }
}
