package io.p2wlan.mc.game.fixture;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

/** Test-only 1.18.2 world/client effects; shared assertions keep their scope. */
public final class GameFixtureApi {
    private GameFixtureApi() {}
    public static ServerData savedServer(String name, String address) { return new ServerData(name, address, false); }
    public static void disconnect(Minecraft client) { client.clearLevel(); }
    public static boolean onboarding(Minecraft client) { return false; }
    public static boolean completeOnboarding(Minecraft client) { return false; }
    public static void command(Minecraft client, String command) { client.player.chat("/" + command); }
    public static Component disconnectedReason(Screen screen) throws ReflectiveOperationException {
        if (screen == null || screen.getClass() != DisconnectedScreen.class) throw new IllegalStateException("FIXTURE_DISCONNECT_OWNER");
        Field selected = null;
        for (Field field : DisconnectedScreen.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != Component.class || !Modifier.isPrivate(modifiers)
                    || !Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) continue;
            if (selected != null) throw new IllegalStateException("FIXTURE_DISCONNECT_FIELD");
            selected = field;
        }
        if (selected == null) throw new IllegalStateException("FIXTURE_DISCONNECT_FIELD");
        if (!selected.trySetAccessible()) throw new IllegalStateException("FIXTURE_DISCONNECT_ACCESS");
        return (Component) selected.get(screen);
    }
    public static void createFreshLevel(Minecraft client, String name) {
        RegistryAccess.Writable registries = RegistryAccess.builtinCopy();
        var structures = registries.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
        var flat = new FlatLevelSource(structures, FlatLevelGeneratorSettings.getDefault(
                registries.registryOrThrow(Registry.BIOME_REGISTRY), structures));
        var base = WorldGenSettings.makeDefault(registries).withSeed(false, OptionalLong.of(1L));
        var dimensions = WorldGenSettings.withOverworld(registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY), base.dimensions(), flat);
        var world = new WorldGenSettings(1L, false, false, dimensions);
        var settings = new LevelSettings("Integrated fixture", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                false, new GameRules(), DataPackConfig.DEFAULT);
        client.createLevel(name, settings, registries, world);
    }
    public static boolean serverPaused(IntegratedServer server) {
        if (server == null || server.getClass() != IntegratedServer.class) throw new IllegalStateException("FIXTURE_PAUSE_OWNER");
        Field selected = null;
        for (String name : new String[] {"paused", "f_120016_", "field_5524"}) {
            Field candidate;
            try { candidate = server.getClass().getDeclaredField(name); }
            catch (NoSuchFieldException absent) { continue; }
            if (candidate.getDeclaringClass() != server.getClass() || candidate.getType() != boolean.class
                    || Modifier.isStatic(candidate.getModifiers()) || selected != null) throw new IllegalStateException("FIXTURE_PAUSE_FIELD");
            selected = candidate;
        }
        if (selected == null) throw new IllegalStateException("FIXTURE_PAUSE_FIELD");
        try {
            if (!selected.trySetAccessible()) throw new IllegalStateException("FIXTURE_PAUSE_ACCESS");
            return selected.getBoolean(server);
        } catch (IllegalAccessException | SecurityException failure) { throw new IllegalStateException("FIXTURE_PAUSE_ACCESS", failure); }
    }
}
