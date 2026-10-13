package io.p2wlan.mc.game.fixture;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.WorldHost;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Real dedicated world + native host. One tick-owned step/future; bounded by
 * per-step deadlines. Only actual Vanilla player commands count as gameplay. */
public final class GameServerChecks {
    private final io.p2wlan.mc.common.AgentLifecycle agent;
    private final java.util.function.Function<MinecraftServer, io.p2wlan.mc.common.WorldSessions.World<MinecraftServer>> worlds;
    private final Runnable retireWorlds;
    private GameFixtureData data;
    private AgentClient helper;
    private RuntimeView runtime;
    private io.p2wlan.mc.common.WorldSessions.World<MinecraftServer> world;
    private CompletableFuture<AgentReply> pending;
    private int joins, rounds, disconnects;
    private long playStarted;
    private boolean stopping;
    public GameServerChecks(io.p2wlan.mc.common.AgentLifecycle agent,
            java.util.function.Function<MinecraftServer, io.p2wlan.mc.common.WorldSessions.World<MinecraftServer>> worlds, Runnable retireWorlds) {
        this.agent=agent; this.worlds=worlds; this.retireWorlds=retireWorlds;
        try { data = new GameFixtureData("server"); }
        catch (Exception error) { throw new IllegalArgumentException("LOCAL_GAME_FIXTURE_CONFIG"); }
    }
    public void commands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("p2wlan-game-fixture")
                .then(Commands.argument("run", StringArgumentType.word())
                .then(Commands.argument("round", IntegerArgumentType.integer(1, GameFixtureData.ROUNDS)).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    data.check(data.run.equals(StringArgumentType.getString(context, "run")), "COMMAND_RUN");
                    int next = IntegerArgumentType.getInteger(context, "round");
                    data.check("PLAY".equals(data.stage) && next == rounds + 1 && joins == 1 && disconnects == 0, "COMMAND_ORDER");
                    data.check(runtime != null && runtime.isCurrent(helper) && world.isCurrent() && hosted(player.getServer()), "COMMAND_OWNER");
                    rounds = next;
                    // Real S2C game state and message through Vanilla packet codecs.
                    player.setExperienceLevels(next);
                    GameFixtureServerApi.message(player, Component.literal(receipt(next)));
                    return 1;
                }))));
    }
    public void joined() { joins++; if(joins==1) playStarted=System.nanoTime(); }
    public void disconnected() { disconnects++; }
    public void started() { data.advance("AGENT",45); }
    private boolean hosted(MinecraftServer server) { var value=worlds.apply(server); return value!=null && value.hosted(); }
    private String receipt(int round) { return "P2WLAN-fixture:" + data.run + ":" + round; }
    public void tick(MinecraftServer server) {
        if (stopping) return;
        try {
            data.withinDeadline();
            switch (data.stage) {
                case "AGENT" -> {
                    if (!agent.ready().isDone()) return;
                    helper = agent.ready().join();
                    data.check(agent.isCurrent(helper) && !server.usesAuthentication(), "ISOLATED_OFFLINE_SERVER");
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
                    world = worlds.apply(server);
                    data.check(world != null && world.isCurrent() && server.overworld() != null, "WORLD_OWNER");
                    world.host(runtime); data.advance("HOST", 20);
                }
                case "HOST" -> {
                    var status = world.status();
                    if (status.phase() == WorldHost.Phase.FAILED) throw new IllegalStateException("HOST_FAILED");
                    if (!hosted(server)) return;
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
                    data.check(joins == 1 && disconnects == 1 && server.getPlayerList().getPlayers().isEmpty(), "PLAYER_DISCONNECTED");
                    data.check(System.nanoTime() - playStarted >= java.util.concurrent.TimeUnit.SECONDS.toNanos(18), "SUSTAINED_GAMEPLAY");
                    data.cases.add("vanilla_login"); data.cases.add("sustained_duplex"); data.cases.add("player_disconnect");
                    retireWorlds.run(); agent.close(); data.advance("CLEANUP", 25);
                }
                case "CLEANUP" -> {
                    if (!agent.stopped().isDone()) return;
                    agent.stopped().join();
                    data.check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false), "HELPER_CLEANUP");
                    data.reason = "OK"; stopping = true; server.halt(false);
                }
                default -> throw new IllegalStateException("SERVER_STEP");
            }
        } catch (Throwable error) {
            data.fail(error); stopping = true; server.halt(false);
        }
    }
    public void stopped(MinecraftServer server) {
        boolean clean = helper != null && !ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false);
        boolean worldClean = world != null && !world.isCurrent() && worlds.apply(server) == null;
        if (clean) data.cases.add("helper_cleanup");
        if (worldClean) data.cases.add("world_cleanup");
        try { data.result(clean, worldClean, joins, rounds); }
        catch (Exception ignored) { /* Missing result fails the runner closed. */ }
    }
}
