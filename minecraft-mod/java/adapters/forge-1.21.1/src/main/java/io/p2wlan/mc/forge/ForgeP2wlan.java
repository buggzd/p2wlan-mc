package io.p2wlan.mc.forge;

import io.p2wlan.mc.bootstrap.UniversalEnvironment;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.WorldSessions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/** One Forge-compatible application entry. Only selected SDK factories load;
 * the server delegate owns worlds and this entry owns the native application. */
@Mod("p2wlan")
public final class ForgeP2wlan {
    private record Factories(String server, String client, String resources) {}
    private static final Factories FACTORIES = factories(UniversalEnvironment.current().minecraftVersion());
    private static final Path CONFIG = FMLPaths.CONFIGDIR.get().toAbsolutePath();
    public static final AgentLifecycle AGENT = new AgentLifecycle(
            CONFIG.resolve("p2wlan-private"), Boolean.getBoolean("p2wlan.allowDevelopmentNative"),
            FMLPaths.GAMEDIR.get().toAbsolutePath().resolve("logs/p2wlan-mod"), nativeResources());
    private static final AtomicBoolean CREATED = new AtomicBoolean();
    private static volatile ForgeRuntime runtime;

    // Both pinned FML SDKs provide this shared context accessor.
    @SuppressWarnings("removal")
    public ForgeP2wlan() {
        if (!CREATED.compareAndSet(false, true)) throw new StartupError(Code.DUPLICATE_ENTRY, null);
        ForgeRuntime selected = null;
        try {
            var dist = FMLEnvironment.dist;
            if (dist != Dist.CLIENT && dist != Dist.DEDICATED_SERVER) throw new StartupError(Code.SIDE_INVALID, null);
            var gameBus = MinecraftForge.EVENT_BUS;
            var modBus = FMLJavaModLoadingContext.get().getModEventBus();
            Object server = create(FACTORIES.server(),
                    new Class<?>[] {AgentLifecycle.class, IEventBus.class, boolean.class},
                    AGENT, gameBus, dist == Dist.DEDICATED_SERVER);
            if (!(server instanceof ForgeRuntime owner)) throw new StartupError(Code.FACTORY_INVALID, null);
            selected = owner;
            if (dist == Dist.CLIENT) {
                create(FACTORIES.client(),
                        new Class<?>[] {AgentLifecycle.class, Path.class, Function.class, Runnable.class,
                                IEventBus.class, IEventBus.class},
                        AGENT, CONFIG, (Function<MinecraftServer, WorldSessions.World<MinecraftServer>>) selected::world,
                        (Runnable) selected::shutdownWorlds, modBus, gameBus);
            }
            runtime = selected;
        } catch (StartupError failure) {
            if (selected != null) selected.shutdownWorlds();
            AGENT.close();
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            if (selected != null) selected.shutdownWorlds();
            AGENT.close();
            throw new StartupError(Code.FACTORY_FAILED, failure);
        }
    }

    public static void shutdownWorlds() { var selected = runtime; if (selected != null) selected.shutdownWorlds(); }
    public static boolean transportSupported() { var selected = runtime; return selected != null && selected.transportSupported(); }
    public static WorldSessions.World<MinecraftServer> world(MinecraftServer server) {
        var selected = runtime; return selected == null ? null : selected.world(server);
    }

    private static Factories factories(String version) {
        return switch (version) {
            case "1.16.5" -> new Factories("io.p2wlan.mc.forge.v1165.ForgeServerEvents",
                    "io.p2wlan.mc.forge.v1165.ForgeClientEvents", "io.p2wlan.mc.forge.v1165.LocalNativeResources");
            case "1.18.2" -> new Factories("io.p2wlan.mc.forge.v1182.ForgeServerEvents",
                    "io.p2wlan.mc.forge.v1182.ForgeClientEvents", "io.p2wlan.mc.forge.v1182.LocalNativeResources");
            case "1.20.1" -> new Factories("io.p2wlan.mc.forge.legacy.ForgeServerEvents",
                    "io.p2wlan.mc.forge.legacy.ForgeClientEvents", "io.p2wlan.mc.forge.legacy.LocalNativeResources");
            case "1.21.1" -> new Factories("io.p2wlan.mc.forge.ForgeServerEvents",
                    "io.p2wlan.mc.forge.client.ForgeClient", "io.p2wlan.mc.forge.LocalNativeResources");
            default -> throw new StartupError(Code.VERSION_UNSUPPORTED, null);
        };
    }

    private static BundledAgent.Resources nativeResources() {
        try {
            Class<?> type = Class.forName(FACTORIES.resources(), true, ForgeP2wlan.class.getClassLoader());
            Object value = type.getConstructor(ClassLoader.class, ModuleLayer.class)
                    .newInstance(ForgeP2wlan.class.getClassLoader(), ForgeP2wlan.class.getModule().getLayer());
            if (!(value instanceof BundledAgent.Resources resources) || value.getClass() != type)
                throw new StartupError(Code.RESOURCES_INVALID, null);
            return resources;
        } catch (StartupError failure) {
            throw failure;
        } catch (InvocationTargetException failure) {
            throw new StartupError(Code.RESOURCES_INVALID, failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new StartupError(Code.RESOURCES_INVALID, failure);
        }
    }

    private static Object create(String name, Class<?>[] parameters, Object... arguments) {
        try {
            Class<?> type = Class.forName(name, true, ForgeP2wlan.class.getClassLoader());
            if (name.equals(FACTORIES.server()) && !ForgeRuntime.class.isAssignableFrom(type))
                throw new StartupError(Code.FACTORY_INVALID, null);
            var method = type.getDeclaredMethod("create", parameters);
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers()))
                throw new StartupError(Code.FACTORY_INVALID, null);
            Object value = method.invoke(null, arguments);
            if (value == null || value.getClass() != type) throw new StartupError(Code.FACTORY_INVALID, null);
            return value;
        } catch (InvocationTargetException failure) {
            throw new StartupError(Code.FACTORY_FAILED, failure.getCause());
        } catch (StartupError failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new StartupError(Code.FACTORY_INVALID, failure);
        }
    }

    public enum Code { DUPLICATE_ENTRY, SIDE_INVALID, VERSION_UNSUPPORTED, RESOURCES_INVALID, FACTORY_INVALID, FACTORY_FAILED }
    public static final class StartupError extends RuntimeException {
        private final Code code;
        private StartupError(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
        public Code code() { return code; }
    }
}
