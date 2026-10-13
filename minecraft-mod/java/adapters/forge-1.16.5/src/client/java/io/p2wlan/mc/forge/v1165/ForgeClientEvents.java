package io.p2wlan.mc.forge.v1165;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.game.GameText;
import io.p2wlan.mc.game.client.ClientDisplaySettings;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.LatencyHud;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.connect.MinecraftConnect;
import io.p2wlan.mc.game.client.lan.ClientNetworkView;
import io.p2wlan.mc.game.client.screen.AccountScreen;
import io.p2wlan.mc.game.client.screen.MenuLogo;
import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Old Forge events supply only client effects and the original world view. */
public final class ForgeClientEvents {
    private final AgentLifecycle agent;
    private final Path config;
    private final Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds;
    private final Runnable retireWorlds;
    private ForgeClientEvents(AgentLifecycle agent, Path config,
            Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds, Runnable retireWorlds) {
        this.agent = agent; this.config = config; this.worlds = worlds; this.retireWorlds = retireWorlds;
    }
    public static ForgeClientEvents create(AgentLifecycle agent, Path config,
            Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds, Runnable retireWorlds,
            IEventBus modBus, IEventBus gameBus) {
        if (agent == null || config == null || !config.isAbsolute() || worlds == null || retireWorlds == null
                || modBus == null || gameBus == null) throw new IllegalArgumentException("FORGE_CLIENT_BINDING_INVALID");
        var events = new ForgeClientEvents(agent, config, worlds, retireWorlds);
        modBus.addListener(events::setup);
        gameBus.addListener(events::layers);
        gameBus.addListener(events::tick);
        gameBus.addListener(events::screen);
        gameBus.addListener(events::command);
        return events;
    }
    private void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientPlatform.bind(agent, config, worlds, retireWorlds);
            MinecraftConnect.supported();
            ClientPlatform.IntegratedHooks.class.isAssignableFrom(net.minecraft.server.integrated.IntegratedServer.class);
            ClientDisplaySettings.load();
            agent.start();
        });
    }
    private void layers(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) LatencyHud.render(event.getMatrixStack());
    }
    private void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var client = Minecraft.getInstance(); GameJoins.tick(client); ClientNetworkView.tick(client);
    }
    private void screen(GuiScreenEvent.InitGuiEvent.Post event) {
        var client = Minecraft.getInstance(); var screen = event.getGui();
        MenuLogo.install(client, screen, screen.width, screen.height, event.getWidgetList(), event::addWidget);
    }
    private void command(ClientChatEvent event) {
        String text = event.getMessage();
        if (!"/p2wlan-client".equals(text) && !"/p2wlan-client status".equals(text)) return;
        event.setCanceled(true);
        var client = Minecraft.getInstance();
        if (text.equals("/p2wlan-client")) client.setScreen(AccountScreen.entry(client.screen));
        else if (client.player != null) {
            var status = agent.status();
            client.player.displayClientMessage(GameText.literal("P2WLAN helper: " + status.phase()
                    + (status.reason() == null ? "" : " / " + status.reason())), false);
        }
    }
}
