package io.p2wlan.mc.fabric;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.bootstrap.BootstrapFailure;
import io.p2wlan.mc.bootstrap.UniversalEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/** Common/dedicated bootstrap. Deliberately imports no client class. */
public final class FabricP2wlan implements ModInitializer {
    public interface ServerEvents {
        WorldSessions.World<MinecraftServer> world(MinecraftServer server);
        boolean transportSupported();
        void shutdownWorlds();
    }
    private record Binding(String version, ServerEvents events) {}
    private static final AtomicInteger INITIALIZED = new AtomicInteger();
    private static volatile Binding binding;
    public static final AgentLifecycle AGENT = new AgentLifecycle(
            FabricLoader.getInstance().getConfigDir().toAbsolutePath().resolve("p2wlan-private"),
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"),
            FabricLoader.getInstance().getGameDir().toAbsolutePath().resolve("logs/p2wlan-mod"));
    @Override public void onInitialize() {
        if (!claim(1)) return;
        var selected = UniversalEnvironment.current();
        if (selected.loader() != UniversalEnvironment.Loader.FABRIC) {
            throw new IllegalStateException("FABRIC_LOADER_BINDING_INVALID");
        }
        try {
            Object events = Class.forName(delegate(selected.minecraftVersion(), "FabricServerEvents"),
                    true, FabricP2wlan.class.getClassLoader()).getMethod("create", AgentLifecycle.class, boolean.class)
                    .invoke(null, AGENT, FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER);
            if (!(events instanceof ServerEvents server)) throw new IllegalStateException("FABRIC_SERVER_BINDING_INVALID");
            binding = new Binding(selected.minecraftVersion(), server);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw bindingFailure("FABRIC_SERVER_BINDING_FAILED", failure);
        }
    }
    public static WorldSessions.World<MinecraftServer> world(MinecraftServer server) {
        Binding value = binding;
        return value == null ? null : value.events().world(server);
    }
    public static boolean transportSupported() {
        Binding value = binding;
        return value != null && value.events().transportSupported();
    }
    public static void shutdownWorlds() {
        Binding value = binding;
        if (value != null) value.events().shutdownWorlds();
    }
    /** The client entry selects hooks without linking either game's client API. */
    public static void initializeClient() {
        Binding value = binding;
        if (value == null) throw new IllegalStateException("FABRIC_SERVER_BINDING_MISSING");
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            throw new BootstrapFailure(BootstrapFailure.Code.CLIENT_SIDE_UNAVAILABLE);
        }
        if (!claim(2)) return;
        try {
            Class.forName(delegate(value.version(), "FabricClientEvents"), true, FabricP2wlan.class.getClassLoader())
                    .getMethod("create", AgentLifecycle.class, Path.class, ServerEvents.class)
                    .invoke(null, AGENT, FabricLoader.getInstance().getConfigDir().toAbsolutePath(), value.events());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw bindingFailure("FABRIC_CLIENT_BINDING_FAILED", failure);
        }
    }
    private static boolean claim(int stage) {
        return (INITIALIZED.getAndUpdate(value -> value | stage) & stage) == 0;
    }
    private static String delegate(String version, String name) {
        String family = switch (version) {
            case "1.16.5" -> "v1_16_5";
            case "1.18.2" -> "v1_18_2";
            case "1.20.1" -> "legacy";
            case "1.21.1" -> "current";
            default -> throw new BootstrapFailure(BootstrapFailure.Code.GAME_VERSION_UNSUPPORTED);
        };
        return "io.p2wlan.mc.fabric." + family + "." + name;
    }
    private static IllegalStateException bindingFailure(String reason, Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
        return new IllegalStateException(reason, cause);
    }
}
