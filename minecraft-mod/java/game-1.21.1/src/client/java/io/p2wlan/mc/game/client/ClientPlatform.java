package io.p2wlan.mc.game.client;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

/** Immutable loader binding for one client application. Native and WorldSessions
 * retain their original authority; this layer owns no room/path/world snapshot. */
public final class ClientPlatform {
    public interface IntegratedHooks {}
    private record Binding(AgentLifecycle agent, Path config, Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds, Runnable retireWorlds) {}
    private static final AtomicReference<Binding> CURRENT = new AtomicReference<>();
    private ClientPlatform() {}
    public static void bind(AgentLifecycle agent, Path config, Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds, Runnable retireWorlds) {
        if (agent == null || config == null || !config.isAbsolute() || worlds == null || retireWorlds == null) throw new IllegalArgumentException("CLIENT_BINDING_INVALID");
        if (!CURRENT.compareAndSet(null, new Binding(agent, config, worlds, retireWorlds))) throw new IllegalStateException("CLIENT_BINDING_DUPLICATE");
    }
    private static Binding binding() { var value = CURRENT.get(); if (value == null) throw new IllegalStateException("CLIENT_BINDING_MISSING"); return value; }
    public static AgentLifecycle agent() { return binding().agent(); }
    public static Path config() { return binding().config(); }
    public static WorldSessions.World<MinecraftServer> world(MinecraftServer server) { return binding().worlds().apply(server); }
    public static boolean hosted(MinecraftServer server) { var value = world(server); return value != null && value.hosted(); }
    public static void close() {
        var owner=CURRENT.get(); if(owner==null) return; // Shutdown may precede loader setup.
        io.p2wlan.mc.game.client.lan.ClientNetworkView.retire();
        io.p2wlan.mc.game.client.screen.AccountScreen.shutdownRequests();
        io.p2wlan.mc.game.client.connect.GameJoins.shutdown();
        owner.retireWorlds().run(); owner.agent().close();
    }
}
