package io.p2wlan.mc.fabric.world;

import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.ScheduledTaskGate;
import java.time.Duration;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.transport.LoopbackBind;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerNetworkIo;

/** Sole local Minecraft-world owner. All mutable access is on the server thread;
 * workers receive only a revocable identity and final server network handle. */
public final class FabricWorlds {
    /** Required client-only IntegratedServer hook, never a dedicated dependency. */
    public interface IntegratedHooks {}
    public static final class World {
        private final MinecraftServer server;
        private final ServerNetworkIo network;
        private final AtomicBoolean live = new AtomicBoolean(true);
        private volatile WorldHost host;
        private final ScheduledTaskGate intents = new ScheduledTaskGate(Duration.ofSeconds(3));
        private final UiCompletionGate commands = new UiCompletionGate();
        private World(MinecraftServer server) { this.server = server; network = server.getNetworkIo(); }
        public boolean isCurrent() { return live.get() && CURRENT.get() == this; }
        public MinecraftServer server() { return server; }
        public ScheduledTaskGate intents() { return intents; }
        public UiCompletionGate commands() { return commands; }
        public WorldHost.Status status() { WorldHost value = host; return value == null ? null : value.status(); }
        public void host() throws WorldHostException {
            host(RuntimeView.ready(FabricP2wlan.AGENT.current().orElse(null)).orElse(null));
        }
        /** Captured runtime is not reacquired after a queued client intent. */
        public void host(RuntimeView runtime) throws WorldHostException {
            if (!isCurrent() || !server.isOnThread()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
            if (host != null && !host.isReleased()) throw new WorldHostException(WorldHostException.Code.CAPACITY);
            AgentClient client = FabricP2wlan.AGENT.current().orElse(null);
            if (runtime == null || !runtime.isCurrent(client)) throw new WorldHostException(WorldHostException.Code.RUNTIME_CHANGED);
            host = WorldHost.start(client, runtime, () -> isCurrent() && FabricP2wlan.AGENT.isCurrent(client), fence -> new LoopbackBind(network, network::bind, fence));
        }
        public void unhost() { WorldHost value = host; if (value != null) value.revoke(); }
        private boolean hosted() { WorldHost value = host; return isCurrent() && value != null && value.status().phase() == WorldHost.Phase.HOSTED && value.isCurrent(); }
        private void revoke() { live.set(false); intents.close(); commands.close(); unhost(); }
        private void tick() {
            WorldHost value = host;
            if (value != null && !value.isCurrent()) value.revoke();
        }
    }
    private static volatile boolean transportSupported;
    public static boolean transportSupported() { return transportSupported; }
    private static final AtomicReference<World> CURRENT = new AtomicReference<>();
    private FabricWorlds() {}
    public static World current(MinecraftServer server) {
        World value = CURRENT.get(); return value != null && value.server == server && value.isCurrent() ? value : null;
    }
    /** Derived from the one exact local world/publication owner, never cached. */
    public static boolean hosted(MinecraftServer server) { World value = current(server); return value != null && value.hosted(); }
    public static void initialize() {
        // Force required hook transformation during bootstrap, without binding
        // a listener, loading a world or accepting the Minecraft EULA.
        transportSupported = LoopbackBind.supported(ServerNetworkIo.class);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            World previous = CURRENT.get(); if (previous != null) previous.revoke();
            CURRENT.set(new World(server));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            World value = CURRENT.get();
            if (value != null && value.server == server) { value.revoke(); CURRENT.compareAndSet(value, null); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { World value = current(server); if (value != null) value.tick(); });
    }
    public static void shutdown() { World value = CURRENT.get(); if (value != null) { value.revoke(); CURRENT.compareAndSet(value, null); } }
}
