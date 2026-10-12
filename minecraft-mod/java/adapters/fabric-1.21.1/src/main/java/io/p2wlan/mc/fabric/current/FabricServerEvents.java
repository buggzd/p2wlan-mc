package io.p2wlan.mc.fabric.current;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.game.server.WorldCommands;
import io.p2wlan.mc.transport.LoopbackBind;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;

/** Minecraft 1.21.1 events; the common owner fences each local world. */
public final class FabricServerEvents implements FabricP2wlan.ServerEvents {
    private final WorldSessions<MinecraftServer> worlds;
    private final boolean transportSupported;
    private FabricServerEvents(AgentLifecycle agent) {
        worlds = new WorldSessions<>(agent);
        transportSupported = LoopbackBind.supported(ServerConnectionListener.class);
    }
    /** Only the selected version is created by the application entry. */
    public static FabricServerEvents create(AgentLifecycle agent, boolean dedicatedServer) {
        if (agent == null) throw new IllegalArgumentException("FABRIC_SERVER_BINDING_INVALID");
        var events = new FabricServerEvents(agent);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var network = server.getConnection();
            events.worlds.started(server, server::isSameThread,
                    fence -> new LoopbackBind(network, network::startTcpServerListener, fence));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            events.worlds.stopped(events.world(server));
            if (dedicatedServer) { events.shutdownWorlds(); agent.close(); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var world = events.world(server);
            if (world != null) world.tick();
        });
        if (dedicatedServer) ServerLifecycleEvents.SERVER_STARTING.register(server -> agent.start());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                WorldCommands.register(dispatcher, agent, events::world, events::transportSupported));
        return events;
    }
    @Override public WorldSessions.World<MinecraftServer> world(MinecraftServer server) { return worlds.current(server); }
    @Override public boolean transportSupported() { return transportSupported; }
    @Override public void shutdownWorlds() { worlds.close(); }
}
