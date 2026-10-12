package io.p2wlan.mc.forge;

import io.p2wlan.mc.common.WorldSessions;
import net.minecraft.server.MinecraftServer;

/** Version-neutral view of the selected server event delegate. */
public interface ForgeRuntime {
    WorldSessions.World<MinecraftServer> world(MinecraftServer server);
    void shutdownWorlds();
    boolean transportSupported();
}
