package io.p2wlan.mc.fabric.fixture;
import io.p2wlan.mc.game.fixture.ClientHookChecks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
/** Test-only loader event glue for shared exact-game assertions. */
public final class ClientHookFixture implements ClientModInitializer {
    @Override public void onInitializeClient() { ClientLifecycleEvents.CLIENT_STARTED.register(client -> new ClientHookChecks().start(client)); }
}
