package io.p2wlan.mc.neoforge;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.transport.LoopbackBind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Common/dedicated event glue. No client class is loaded from this bootstrap. */
@Mod("p2wlan")
public final class NeoForgeP2wlan {
    public static final AgentLifecycle AGENT = new AgentLifecycle(
            FMLPaths.CONFIGDIR.get().toAbsolutePath().resolve("p2wlan-private"),
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"),
            FMLPaths.GAMEDIR.get().toAbsolutePath().resolve("logs/p2wlan-mod"),
            new LocalNativeResources(NeoForgeP2wlan.class.getClassLoader(), NeoForgeP2wlan.class.getModule().getLayer()));
    private static final WorldSessions<MinecraftServer> WORLDS = new WorldSessions<>(AGENT);
    private static volatile boolean transportSupported;
    public NeoForgeP2wlan() {
        transportSupported = LoopbackBind.supported(ServerConnectionListener.class);
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }
    public static boolean transportSupported() { return transportSupported; }
    public static WorldSessions.World<MinecraftServer> world(MinecraftServer server) { return WORLDS.current(server); }
    private void starting(ServerStartingEvent event) {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) AGENT.start();
    }
    private void started(ServerStartedEvent event) {
        var server = event.getServer(); var network = server.getConnection();
        WORLDS.started(server, server::isSameThread, fence -> new LoopbackBind(network, network::startTcpServerListener, fence));
    }
    private void stopping(ServerStoppingEvent event) {
        WORLDS.stopped(world(event.getServer()));
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) AGENT.close();
    }
    private void tick(ServerTickEvent.Post event) { var world = world(event.getServer()); if (world != null) world.tick(); }
    private void commands(RegisterCommandsEvent event) { WorldCommands.register(event.getDispatcher()); }
}
