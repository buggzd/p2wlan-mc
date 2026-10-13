package io.p2wlan.mc.fabric.fixture;

import io.p2wlan.mc.game.fixture.IntegratedHostChecks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Legacy event signatures over the same integrated-world assertions. */
public final class IntegratedFixture implements ClientModInitializer {
    @Override public void onInitializeClient() {
        InstalledArchiveChecks.verify();
        var checks = new IntegratedHostChecks();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> checks.start());
        ClientTickEvents.END_CLIENT_TICK.register(checks::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> checks.commands(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> checks.joined(handler.player.getGameProfile().getName()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> checks.disconnected(handler.player.getGameProfile().getName()));
        ServerLifecycleEvents.SERVER_STOPPED.register(checks::stopped);
    }
}
