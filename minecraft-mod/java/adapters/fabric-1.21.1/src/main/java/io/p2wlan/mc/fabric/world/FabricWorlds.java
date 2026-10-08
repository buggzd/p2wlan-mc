package io.p2wlan.mc.fabric.world;

import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import io.p2wlan.mc.fabric.FabricP2wlan;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerNetworkIo;

/** Sole local Minecraft-world owner. All mutable access is on the server thread;
 * workers receive only a revocable identity and final server network handle. */
public final class FabricWorlds {
    public static final class World {
        private final MinecraftServer server;
        private final ServerNetworkIo network;
        private final AtomicBoolean live = new AtomicBoolean(true);
        private volatile WorldHost host;
        private final UiCompletionGate commands = new UiCompletionGate();
        private World(MinecraftServer server) { this.server = server; network = server.getNetworkIo(); }
        public boolean isCurrent() { return live.get() && CURRENT.get() == this; }
        public MinecraftServer server() { return server; }
        public UiCompletionGate commands() { return commands; }
        public WorldHost.Status status() { WorldHost value = host; return value == null ? null : value.status(); }
        public void host() throws WorldHostException {
            if (!isCurrent()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
            if (host != null && !host.isReleased()) throw new WorldHostException(WorldHostException.Code.CAPACITY);
            AgentClient client = FabricP2wlan.AGENT.current().orElse(null);
            RuntimeView runtime = client == null ? null : RuntimeView.ready(client).orElse(null);
            if (runtime == null) throw new WorldHostException(WorldHostException.Code.RUNTIME_CHANGED);
            host = WorldHost.start(client, runtime, this::isCurrent, fence -> new LoopbackBind(network, fence));
        }
        public void unhost() { WorldHost value = host; if (value != null) value.revoke(); }
        private void revoke() { live.set(false); commands.close(); unhost(); }
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
    public static void initialize() {
        // Force required hook transformation during bootstrap, without binding
        // a listener, loading a world or accepting the Minecraft EULA.
        transportSupported = LoopbackBind.supported();
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
