package io.p2wlan.mc.fabric.fixture;
import io.p2wlan.mc.game.fixture.GameClientChecks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
/** Loader-specific observations feed the exact shared widget/game assertions. */
public final class LocalGameClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        InstalledArchiveChecks.verify();
        var checks=new GameClientChecks();
        ClientLifecycleEvents.CLIENT_STARTED.register(client->checks.start());
        ClientTickEvents.END_CLIENT_TICK.register(checks::tick);
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->ScreenEvents.afterRender(screen).register((view,draw,x,y,delta)->checks.painted(view)));
        HudRenderCallback.EVENT.register((draw,ticks)->checks.paintedHud());
        ClientReceiveMessageEvents.GAME.register((message,overlay)->checks.message(message));
    }
}
