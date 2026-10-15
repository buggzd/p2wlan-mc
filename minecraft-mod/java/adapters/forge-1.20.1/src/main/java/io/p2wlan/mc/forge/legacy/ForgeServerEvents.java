package io.p2wlan.mc.forge.legacy;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.forge.ForgeRuntime;
import io.p2wlan.mc.game.server.WorldCommands;
import io.p2wlan.mc.game.server.GameCommandApi;
import io.p2wlan.mc.transport.LoopbackBind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** Minecraft 1.20.1 server event glue. The selected delegate owns world sessions;
 * the loader entry supplies the one application AgentLifecycle. */
public final class ForgeServerEvents implements ForgeRuntime {
    private final AgentLifecycle agent;
    private final WorldSessions<MinecraftServer> worlds;
    private final boolean dedicatedServer;
    private final boolean transportSupported;

    private ForgeServerEvents(AgentLifecycle agent, boolean dedicatedServer) {
        this.agent = agent;
        this.worlds = new WorldSessions<>(agent);
        this.dedicatedServer = dedicatedServer;
        this.transportSupported = LoopbackBind.supported(ServerConnectionListener.class);
    }

    /** Called once by the loader entry after version selection. */
    public static ForgeServerEvents create(AgentLifecycle agent, IEventBus gameBus, boolean dedicatedServer) {
        if (agent == null || gameBus == null) throw new IllegalArgumentException("FORGE_SERVER_BINDING_INVALID");
        var events = new ForgeServerEvents(agent, dedicatedServer);
        gameBus.addListener(events::started);
        gameBus.addListener(events::stopping);
        gameBus.addListener(events::tick);
        gameBus.addListener(events::commands);
        return events;
    }

    @Override public WorldSessions.World<MinecraftServer> world(MinecraftServer server) { return worlds.current(server); }
    @Override public void shutdownWorlds() { worlds.close(); }
    @Override public boolean transportSupported() { return transportSupported; }

    private void started(ServerStartedEvent event) {
        var server = event.getServer();
        var network = server.getConnection();
        worlds.started(server, server::isSameThread,
                fence -> new LoopbackBind(network, network::startTcpServerListener, fence), dedicatedServer,
                message -> GameCommandApi.success(server.createCommandSourceStack(), () -> Component.literal(message), false));
        if (dedicatedServer) agent.start();
    }

    private void stopping(ServerStoppingEvent event) {
        worlds.stopped(world(event.getServer()));
        if (dedicatedServer) agent.close();
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var world = world(event.getServer());
        if (world != null) world.tick();
    }

    private void commands(RegisterCommandsEvent event) {
        WorldCommands.register(event.getDispatcher(), agent, this::world, this::transportSupported);
    }
}
