package io.p2wlan.mc.forge.fixture;
import io.p2wlan.mc.game.fixture.GameClientChecks;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ScreenEvent;
/** Client-only test renderer/message observations; original game widgets stay intact. */
@EventBusSubscriber(modid="p2wlan_local_game_test",value=Dist.CLIENT)
public final class LocalGameClientEvents {
    private static final GameClientChecks CHECKS=new GameClientChecks((profile,logs)->new io.p2wlan.mc.common.AgentLifecycle(profile,
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"),logs,
            new io.p2wlan.mc.forge.legacy.LocalNativeResources(LocalGameClientEvents.class.getClassLoader(),LocalGameClientEvents.class.getModule().getLayer())));
    private static boolean started;
    @SubscribeEvent public static void tick(ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END) return;
        if(!started) { InstalledArchiveChecks.verify(); started=true; CHECKS.start(); }
        CHECKS.tick(Minecraft.getInstance());
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) { CHECKS.painted(event.getScreen()); }
    @EventBusSubscriber(modid="p2wlan_local_game_test",value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        @SubscribeEvent public static void layers(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("hud_observation",(gui,draw,ticks,width,height)->CHECKS.paintedHud());
        }
    }
    @SubscribeEvent public static void message(ClientChatReceivedEvent.System event) { if(started) CHECKS.message(event.getMessage()); }
}
