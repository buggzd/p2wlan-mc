package io.p2wlan.mc.neoforge.fixture;
import io.p2wlan.mc.game.fixture.GameClientChecks;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
/** Client-only test renderer/message observations; original game widgets stay intact. */
@EventBusSubscriber(modid="p2wlan_local_game_test",value=Dist.CLIENT)
public final class LocalGameClientEvents {
    private static final GameClientChecks CHECKS=new GameClientChecks((profile,logs)->new io.p2wlan.mc.common.AgentLifecycle(profile,
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"),logs,
            new io.p2wlan.mc.neoforge.LocalNativeResources(LocalGameClientEvents.class.getClassLoader(),LocalGameClientEvents.class.getModule().getLayer())));
    private static boolean started;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!started) { started=true; CHECKS.start(); }
        CHECKS.tick(Minecraft.getInstance());
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) { CHECKS.painted(event.getScreen()); }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) { CHECKS.paintedHud(); }
    @SubscribeEvent public static void message(ClientChatReceivedEvent event) { if(started) CHECKS.message(event.getMessage()); }
}
