package io.p2wlan.mc.fabric.fixture;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

/** Real dedicated world + native host. One tick-owned step/future; bounded by
 * per-step deadlines. Only actual Vanilla player commands count as gameplay. */
public final class LocalGameServer implements ModInitializer {
    private GameFixtureData data;
    private AgentClient helper;
    private RuntimeView runtime;
    private io.p2wlan.mc.common.WorldSessions.World<MinecraftServer> world;
    private CompletableFuture<AgentReply> pending;
    private int joins, rounds, disconnects;
    private long playStarted;
    private boolean stopping;
    @Override public void onInitialize() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        try { data = new GameFixtureData("server"); }
        catch (Exception error) { throw new IllegalArgumentException("LOCAL_GAME_FIXTURE_CONFIG"); }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                CommandManager.literal("p2wlan-game-fixture")
                .then(CommandManager.argument("run", StringArgumentType.word())
                .then(CommandManager.argument("round", IntegerArgumentType.integer(1, GameFixtureData.ROUNDS)).executes(context -> {
                    var player = context.getSource().getPlayerOrThrow();
                    data.check(data.run.equals(StringArgumentType.getString(context, "run")), "COMMAND_RUN");
                    int next = IntegerArgumentType.getInteger(context, "round");
                    data.check("PLAY".equals(data.stage) && next == rounds + 1 && joins == 1 && disconnects == 0, "COMMAND_ORDER");
                    data.check(runtime != null && runtime.isCurrent(helper) && world.isCurrent() && FabricWorlds.hosted(player.getServer()), "COMMAND_OWNER");
                    rounds = next;
                    // Real S2C game state and message through Vanilla packet codecs.
                    player.setExperienceLevel(next);
                    player.networkHandler.sendPacket(new GameMessageS2CPacket(Text.literal(receipt(next)), false));
                    return 1;
                })))));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            joins++;
            if (joins == 1) playStarted = System.nanoTime();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> disconnects++);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> data.advance("AGENT", 45));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::stopped);
    }
    private String receipt(int round) { return "P2WLAN-fixture:" + data.run + ":" + round; }
    private void tick(MinecraftServer server) {
        if (stopping) return;
        try {
            data.withinDeadline();
            switch (data.stage) {
                case "AGENT" -> {
                    if (!FabricP2wlan.AGENT.ready().isDone()) return;
                    helper = FabricP2wlan.AGENT.ready().join();
                    data.check(FabricP2wlan.AGENT.isCurrent(helper) && !server.isOnlineMode(), "ISOLATED_OFFLINE_SERVER");
                    JsonObject arguments = new JsonObject(); arguments.addProperty("room", data.room);
                    pending = helper.request("connect_room", arguments);
                    data.advance("CONNECT_ROOM", 20);
                }
                case "CONNECT_ROOM" -> {
                    if (!pending.isDone()) return;
                    var reply = pending.join();
                    data.check(helper.isCurrent(reply), "CONNECT_OWNER");
                    runtime = new RuntimeView(reply.owner(), RuntimeView.text(reply.data(), "runtime")); pending = null;
                    data.advance("RUNTIME", 30);
                }
                case "RUNTIME" -> {
                    if (!runtime.isCurrent(helper)) return;
                    world = FabricWorlds.current(server);
                    data.check(world != null && world.isCurrent() && server.getOverworld() != null, "WORLD_OWNER");
                    world.host(runtime); data.advance("HOST", 20);
                }
                case "HOST" -> {
                    var status = world.status();
                    if (status.phase() == WorldHost.Phase.FAILED) throw new IllegalStateException("HOST_FAILED");
                    if (!FabricWorlds.hosted(server)) return;
                    data.check(runtime.isCurrent(helper), "HOST_RUNTIME");
                    pending = helper.request("status", new JsonObject());
                    data.advance("READY", 5);
                }
                case "READY" -> {
                    if (!pending.isDone()) return;
                    var reply = pending.join();
                    data.check(helper.isCurrent(reply) && runtime.isCurrent(helper), "READY_OWNER");
                    JsonObject ready = new JsonObject(); ready.addProperty("schema", 1); ready.addProperty("run", data.run);
                    ready.addProperty("peer", RuntimeView.text(reply.data(), "peer_id"));
                    GameFixtureData.write(data.root.resolve("server-ready.json"), ready);
                    data.cases.add("world_host"); pending = null; data.advance("PLAY", 180);
                }
                case "PLAY" -> {
                    if (rounds < GameFixtureData.ROUNDS || disconnects < 1) return;
                    data.check(joins == 1 && disconnects == 1 && server.getPlayerManager().getPlayerList().isEmpty(), "PLAYER_DISCONNECTED");
                    data.check(System.nanoTime() - playStarted >= java.util.concurrent.TimeUnit.SECONDS.toNanos(18), "SUSTAINED_GAMEPLAY");
                    data.cases.add("vanilla_login"); data.cases.add("sustained_duplex"); data.cases.add("player_disconnect");
                    FabricWorlds.shutdown(); FabricP2wlan.AGENT.close(); data.advance("CLEANUP", 25);
                }
                case "CLEANUP" -> {
                    if (!FabricP2wlan.AGENT.stopped().isDone()) return;
                    FabricP2wlan.AGENT.stopped().join();
                    data.check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false), "HELPER_CLEANUP");
                    data.reason = "OK"; stopping = true; server.stop(false);
                }
                default -> throw new IllegalStateException("SERVER_STEP");
            }
        } catch (Throwable error) {
            data.fail(error); stopping = true; server.stop(false);
        }
    }
    private void stopped(MinecraftServer server) {
        boolean clean = helper != null && !ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false);
        boolean worldClean = world != null && !world.isCurrent() && FabricWorlds.current(server) == null;
        if (clean) data.cases.add("helper_cleanup");
        if (worldClean) data.cases.add("world_cleanup");
        try { data.result(clean, worldClean, joins, rounds); }
        catch (Exception ignored) { /* Missing result fails the runner closed. */ }
    }
}
