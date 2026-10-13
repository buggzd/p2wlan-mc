package io.p2wlan.mc.fabric.fixture;

import io.p2wlan.mc.game.fixture.GameClientChecks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.network.chat.Component;

/** Shared observations with the old SDK's test-only chat packet hook. */
public final class LocalGameClient implements ClientModInitializer {
    private static GameClientChecks checks;
    @Override public void onInitializeClient() {
        InstalledArchiveChecks.verify();
        if (checks != null) throw new IllegalStateException("FIXTURE_CLIENT_OWNER");
        checks = new GameClientChecks();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> checks.start());
        ClientTickEvents.END_CLIENT_TICK.register(checks::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((view, draw, x, y, delta) -> checks.painted(view)));
        HudRenderCallback.EVENT.register((draw, ticks) -> checks.paintedHud());
    }
    public static void message(Component message) {
        GameClientChecks current = checks;
        if (current != null) current.message(message);
    }
}
