package io.p2wlan.mc.game.fixture;

import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;

/** Test-only Minecraft 1.21.1 effects; gameplay assertions remain shared. */
public final class GameFixtureApi {
    private GameFixtureApi() {}

    public static ServerData savedServer(String name, String address) {
        return new ServerData(name, address, ServerData.Type.OTHER);
    }

    public static void disconnect(Minecraft client) { client.disconnect(); }

    public static void createFreshLevel(Minecraft client, String name, LevelSettings settings,
            WorldOptions options, Function<RegistryAccess, WorldDimensions> dimensions) {
        client.createWorldOpenFlows().createFreshLevel(name, settings, options, dimensions, client.screen);
    }

    public static boolean serverPaused(IntegratedServer server) { return server.isPaused(); }
}
