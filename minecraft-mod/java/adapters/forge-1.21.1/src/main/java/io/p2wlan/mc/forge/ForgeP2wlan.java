package io.p2wlan.mc.forge;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.WorldSessions;
import io.p2wlan.mc.game.server.WorldCommands;
import io.p2wlan.mc.transport.LoopbackBind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;

/** Common/dedicated event glue. No client class is loaded from this bootstrap. */
@Mod("p2wlan")
public final class ForgeP2wlan {
    public static final AgentLifecycle AGENT = new AgentLifecycle(
            FMLPaths.CONFIGDIR.get().toAbsolutePath().resolve("p2wlan-private"),
            Boolean.getBoolean("p2wlan.allowDevelopmentNative"),
            FMLPaths.GAMEDIR.get().toAbsolutePath().resolve("logs/p2wlan-mod"),
            new LocalNativeResources(ForgeP2wlan.class.getClassLoader(), ForgeP2wlan.class.getModule().getLayer()));
    private static final WorldSessions<MinecraftServer> WORLDS = new WorldSessions<>(AGENT);
    private static volatile boolean transportSupported;
    public ForgeP2wlan() {
        transportSupported = LoopbackBind.supported(ServerConnectionListener.class);
        MinecraftForge.EVENT_BUS.addListener(this::starting);
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::stopping);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
    }
    public static void shutdownWorlds() { WORLDS.close(); }
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
    private void commands(RegisterCommandsEvent event) { WorldCommands.register(event.getDispatcher(), AGENT, ForgeP2wlan::world, ForgeP2wlan::transportSupported); }
}
