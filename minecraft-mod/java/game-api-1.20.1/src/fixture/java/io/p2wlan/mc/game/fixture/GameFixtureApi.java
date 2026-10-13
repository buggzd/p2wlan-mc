package io.p2wlan.mc.game.fixture;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.registries.Registries;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;

/** Test-only Minecraft 1.20.1 effects; gameplay assertions remain shared. */
public final class GameFixtureApi {
    private GameFixtureApi() {}

    public static boolean onboarding(Minecraft client) {
        return client.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
    }

    public static boolean completeOnboarding(Minecraft client) {
        if (!onboarding(client)) return false;
        client.screen.onClose();
        return true;
    }

    public static void command(Minecraft client, String command) {
        client.player.connection.sendCommand(command);
    }

    public static void createFreshLevel(Minecraft client, String name) {
        var settings = new LevelSettings("Integrated fixture", GameType.CREATIVE, false,
                Difficulty.PEACEFUL, false, new GameRules(), WorldDataConfiguration.DEFAULT);
        createFreshLevel(client, name, settings, new WorldOptions(1L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }


    public static ServerData savedServer(String name, String address) {
        return new ServerData(name, address, false);
    }

    public static void disconnect(Minecraft client) { client.clearLevel(); }

    public static void createFreshLevel(Minecraft client, String name, LevelSettings settings,
            WorldOptions options, Function<RegistryAccess, WorldDimensions> dimensions) {
        client.createWorldOpenFlows().createFreshLevel(name, settings, options, dimensions);
    }

    public static boolean serverPaused(IntegratedServer server) {
        if (server == null || server.getClass() != IntegratedServer.class)
            throw new IllegalStateException("FIXTURE_PAUSE_OWNER");
        // Pinned named, Forge SRG and Fabric intermediary names for the same
        // authoritative 1.20.1 field. No client pause state is used.
        Field selected = null;
        for (String name : new String[] {"paused", "f_120016_", "field_5524"}) {
            Field candidate;
            try { candidate = server.getClass().getDeclaredField(name); }
            catch (NoSuchFieldException absent) { continue; }
            if (candidate.getDeclaringClass() != server.getClass() || candidate.getType() != boolean.class
                    || Modifier.isStatic(candidate.getModifiers()) || selected != null)
                throw new IllegalStateException("FIXTURE_PAUSE_FIELD");
            selected = candidate;
        }
        if (selected == null) throw new IllegalStateException("FIXTURE_PAUSE_FIELD");
        try {
            if (!selected.trySetAccessible()) throw new IllegalStateException("FIXTURE_PAUSE_ACCESS");
            return selected.getBoolean(server);
        } catch (IllegalAccessException | SecurityException failure) {
            throw new IllegalStateException("FIXTURE_PAUSE_ACCESS", failure);
        }
    }
}
