package io.p2wlan.mc.fabric.world;

import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.transport.LoopbackBind;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;

/** Loader events/version API only; common WorldSessions owns local scopes. */
public final class FabricWorlds {
    /** Required client-only IntegratedServer hook, never a dedicated dependency. */
    public interface IntegratedHooks {}
    private static final WorldSessions<MinecraftServer> WORLDS = new WorldSessions<>(FabricP2wlan.AGENT);
    private static volatile boolean transportSupported;
    private FabricWorlds() {}
    public static boolean transportSupported() { return transportSupported; }
    public static WorldSessions.World<MinecraftServer> current(MinecraftServer server) { return WORLDS.current(server); }
    public static boolean hosted(MinecraftServer server) { var world = current(server); return world != null && world.hosted(); }
    public static void initialize() {
        transportSupported = LoopbackBind.supported(ServerConnectionListener.class);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var network = server.getConnection();
            WORLDS.started(server, server::isSameThread, fence -> new LoopbackBind(network, network::startTcpServerListener, fence));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> WORLDS.stopped(current(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> { var world = current(server); if (world != null) world.tick(); });
    }
    public static void shutdown() { WORLDS.close(); }
}
