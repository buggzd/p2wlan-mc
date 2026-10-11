package io.p2wlan.mc.neoforge.client;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.ClientDisplaySettings;
import io.p2wlan.mc.game.client.LatencyHud;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.connect.MinecraftConnect;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import io.p2wlan.mc.game.client.screen.AccountScreen;
import io.p2wlan.mc.game.client.screen.MenuLogo;
import io.p2wlan.mc.neoforge.NeoForgeP2wlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Client-only loader event glue. All screens and game-join/observer lifetimes
 * are the same version-specific implementation compiled by Fabric. */
@EventBusSubscriber(modid="p2wlan", value=Dist.CLIENT, bus=EventBusSubscriber.Bus.MOD)
public final class NeoForgeClient {
    private NeoForgeClient() {}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientPlatform.bind(NeoForgeP2wlan.AGENT, FMLPaths.CONFIGDIR.get().toAbsolutePath(), NeoForgeP2wlan::world, NeoForgeP2wlan::shutdownWorlds);
            MinecraftConnect.supported();
            ClientPlatform.IntegratedHooks.class.isAssignableFrom(net.minecraft.client.server.IntegratedServer.class);
            ClientDisplaySettings.load(); NeoForgeP2wlan.AGENT.start();
        });
    }
    @SubscribeEvent public static void layers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("p2wlan", "latency"), (draw,ticks) -> LatencyHud.render(draw));
    }
    @EventBusSubscriber(modid="p2wlan", value=Dist.CLIENT)
    public static final class Events {
        private Events() {}
        @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
            var client = Minecraft.getInstance(); GameJoins.tick(client); ClientNetworkView.tick(client);
        }
        @SubscribeEvent public static void screen(ScreenEvent.Init.Post event) {
            var client = Minecraft.getInstance(); var screen = event.getScreen();
            var buttons = event.getListenersList().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
            MenuLogo.install(client,screen,screen.width,screen.height,buttons,event::addListener);
        }
        @SubscribeEvent public static void stopping(GameShuttingDownEvent event) { ClientPlatform.close(); }
        @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("p2wlan-client").executes(context -> {
                var client = Minecraft.getInstance(); client.setScreen(AccountScreen.entry(client.screen)); return 1;
            }).then(Commands.literal("status").executes(context -> {
                var status = NeoForgeP2wlan.AGENT.status();
                context.getSource().sendSuccess(() -> Component.literal("P2WLAN helper: " + status.phase()
                        + (status.reason()==null ? "" : " / " + status.reason())), false);
                return status.phase()==AgentLifecycle.Phase.READY ? 1 : 0;
            })));
        }
    }
}
