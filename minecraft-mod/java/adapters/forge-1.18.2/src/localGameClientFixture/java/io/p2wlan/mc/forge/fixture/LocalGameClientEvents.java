package io.p2wlan.mc.forge.fixture;

import io.p2wlan.mc.game.fixture.GameClientChecks;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** Client render and chat events feed the unchanged shared gameplay checks. */
@EventBusSubscriber(modid = "p2wlan_local_game_test", value = Dist.CLIENT)
public final class LocalGameClientEvents {
    private static final GameClientChecks CHECKS = new GameClientChecks((profile, logs) ->
            new io.p2wlan.mc.common.AgentLifecycle(profile,
                    Boolean.getBoolean("p2wlan.allowDevelopmentNative"), logs,
                    new io.p2wlan.mc.forge.v1182.LocalNativeResources(LocalGameClientEvents.class.getClassLoader(),
                            LocalGameClientEvents.class.getModule().getLayer())));
    private static boolean started;
    @SubscribeEvent public static void tick(ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!started) { InstalledArchiveChecks.verify(); started = true; CHECKS.start(); }
        CHECKS.tick(Minecraft.getInstance());
    }
    @SubscribeEvent public static void render(ScreenEvent.DrawScreenEvent.Post event) { CHECKS.painted(event.getScreen()); }
    @SubscribeEvent public static void layers(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) CHECKS.paintedHud();
    }
    @SubscribeEvent public static void message(ClientChatReceivedEvent event) { if (started) CHECKS.message(event.getMessage()); }
}
