package io.p2wlan.mc.fabric.world;

import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.fabric.FabricP2wlan;
import net.minecraft.server.MinecraftServer;

/** Stable loader API; the selected server delegate owns the one world scope. */
public final class FabricWorlds {
    /** Required client-only IntegratedServer hook, never a dedicated dependency. */
    public interface IntegratedHooks {}
    private FabricWorlds() {}
    public static boolean transportSupported() { return FabricP2wlan.transportSupported(); }
    public static WorldSessions.World<MinecraftServer> current(MinecraftServer server) { return FabricP2wlan.world(server); }
    public static boolean hosted(MinecraftServer server) { var world = current(server); return world != null && world.hosted(); }
    public static void shutdown() { FabricP2wlan.shutdownWorlds(); }
}
