package io.p2wlan.mc.forge.v1165;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.forge.ForgeRuntime;
import io.p2wlan.mc.game.server.WorldCommands;
import io.p2wlan.mc.game.server.GameCommandApi;
import io.p2wlan.mc.transport.LoopbackBind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.NetworkSystem;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

/** Forge 1.16.5 events bind the shared world owner to the current server. */
public final class ForgeServerEvents implements ForgeRuntime {
    private final AgentLifecycle agent;
    private final WorldSessions<MinecraftServer> worlds;
    private final boolean dedicatedServer;
    private final boolean transportSupported;

    private ForgeServerEvents(AgentLifecycle agent, boolean dedicatedServer) {
        this.agent = agent;
        this.worlds = new WorldSessions<>(agent);
        this.dedicatedServer = dedicatedServer;
        this.transportSupported = LoopbackBind.supported(NetworkSystem.class);
    }

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
    private void started(FMLServerStartedEvent event) {
        var server = event.getServer();
        var network = server.getConnection();
        worlds.started(server, server::isSameThread,
                fence -> new LoopbackBind(network, network::startTcpServerListener, fence), dedicatedServer,
                message -> GameCommandApi.success(server.createCommandSourceStack(), () -> io.p2wlan.mc.game.GameText.literal(message), false));
        if (dedicatedServer) agent.start();
    }
    private void stopping(FMLServerStoppingEvent event) {
        worlds.stopped(world(event.getServer()));
        if (dedicatedServer) agent.close();
    }
    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var current = world(ServerLifecycleHooks.getCurrentServer());
        if (current != null) current.tick();
    }
    private void commands(RegisterCommandsEvent event) {
        WorldCommands.register(event.getDispatcher(), agent, this::world, this::transportSupported);
    }
}
