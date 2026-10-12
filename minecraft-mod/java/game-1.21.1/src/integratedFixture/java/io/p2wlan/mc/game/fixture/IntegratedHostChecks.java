package io.p2wlan.mc.game.fixture;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.p2wlan.mc.common.*;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.screen.AccountScreen;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.registries.Registries;

/** Test-only real integrated host. Client UI and server commands retain their
 * original game-thread ownership; no production world/profile is opened. */
public final class IntegratedHostChecks {
    private GameFixtureData data;
    private AgentClient helper;
    private RuntimeView runtime;
    private WorldSessions.World<MinecraftServer> world;
    private CompletableFuture<AgentReply> pending;
    private volatile int joins,rounds,disconnects;
    private volatile boolean serverStopped;
    private long startTick,startedPlay;
    private boolean finished;
    public void start() { try { data=new GameFixtureData("server"); data.advance("AGENT",45); }
        catch(Exception error) { throw new IllegalArgumentException("INTEGRATED_FIXTURE_CONFIG"); } }
    public void commands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("p2wlan-game-fixture").then(Commands.argument("run",StringArgumentType.word())
                .then(Commands.argument("round",IntegerArgumentType.integer(1,GameFixtureData.ROUNDS)).executes(context->{
                    var player=context.getSource().getPlayerOrException();
                    data.check(data.run.equals(StringArgumentType.getString(context,"run")),"COMMAND_RUN");
                    int next=IntegerArgumentType.getInteger(context,"round");
                    data.check("PLAY".equals(data.stage)&&next==rounds+1&&joins==1&&disconnects==0,"COMMAND_ORDER");
                    data.check(runtime!=null&&runtime.isCurrent(helper)&&world.isCurrent()&&world.hosted(),"COMMAND_OWNER");
                    rounds=next;player.setExperienceLevels(next);
                    player.connection.send(new ClientboundSystemChatPacket(Component.literal("P2WLAN-fixture:"+data.run+":"+next),false));return 1;
                }))));
    }
    public void joined(String name) { if(!name.equals("IntegratedHost")) { joins++; if(joins==1) startedPlay=System.nanoTime(); } }
    public void disconnected(String name) { if(!name.equals("IntegratedHost")) disconnects++; }
    public void stopped(MinecraftServer server) { serverStopped=true; }
    private void press(Minecraft client,String text) {
        var button=client.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value->value.active&&value.getMessage().getString().startsWith(text)).findFirst().orElseThrow(()->new IllegalStateException("BUTTON_MISSING"));
        button.onPress();
    }
    private boolean uiBusy() throws Exception { var field=AccountScreen.class.getSuperclass().getDeclaredField("REQUESTS");field.setAccessible(true);return ((UiCompletionGate)field.get(null)).busy(); }
    public void tick(Minecraft client) {
        if(finished||data==null) return;
        try {
            data.withinDeadline();var server=client.getSingleplayerServer();
            switch(data.stage) {
                case "AGENT" -> {
                    if(client.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) { client.screen.onClose();return; }
                    if(!ClientPlatform.agent().ready().isDone()||client.getOverlay()!=null||!(client.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
                    helper=ClientPlatform.agent().ready().join();
                    data.check(ClientPlatform.agent().isCurrent(helper)&&client.level==null,"HOST_APPLICATION_OWNER");
                    var args=new JsonObject();args.addProperty("room",data.room);pending=helper.request("connect_room",args);data.advance("ROOM",20);
                }
                case "ROOM" -> {
                    if(!pending.isDone()) return;var reply=pending.join();data.check(helper.isCurrent(reply),"ROOM_OWNER");runtime=new RuntimeView(reply.owner(),RuntimeView.text(reply.data(),"runtime"));pending=null;data.advance("RUNTIME",30);
                }
                case "RUNTIME" -> {
                    if(!runtime.isCurrent(helper)) return;
                    var settings=new LevelSettings("Integrated fixture",GameType.CREATIVE,false,Difficulty.PEACEFUL,false,new GameRules(),WorldDataConfiguration.DEFAULT);
                    GameFixtureApi.createFreshLevel(client,"p2wlan-integrated-fixture",settings,new WorldOptions(1L,false,false),
                            registries->registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                    data.advance("WORLD",90);
                }
                case "WORLD" -> {
                    if(server==null||client.level==null||client.player==null) return;world=ClientPlatform.world(server);
                    data.check(world!=null&&world.isCurrent(),"INTEGRATED_WORLD_OWNER");
                    data.check(!server.isPublished()&&server.getPort()<0,"NO_VANILLA_LAN");
                    server.execute(()->server.setUsesAuthentication(false));
                    client.setScreen(new PauseScreen(true));data.advance("LOCAL_PAUSE",15);
                }
                case "LOCAL_PAUSE" -> {
                    if(server==null||!GameFixtureApi.serverPaused(server)) return;data.check(!world.hosted(),"LOCAL_PAUSE_UNSHARED");
                    client.setScreen(AccountScreen.entry(client.screen));data.advance("SHARE",15);
                }
                case "SHARE" -> {
                    if(uiBusy()) return;press(client,"共享本地世界");data.advance("HOSTED",25);
                }
                case "HOSTED" -> {
                    if(!world.hosted()||uiBusy()) return;
                    data.check(runtime.isCurrent(helper)&&world.isCurrent(),"HOSTED_OWNER");
                    data.check(server.isPublished()&&server.getPort()<0,"SHARED_WITHOUT_LAN_PORT");
                    client.setScreen(new PauseScreen(true));startTick=server.getTickCount();data.advance("REMOTE_PAUSE",15);
                }
                case "REMOTE_PAUSE" -> {
                    if(server.getTickCount()<startTick+10||GameFixtureApi.serverPaused(server)) return;data.check(world.hosted(),"SHARED_PAUSE_TICKS");
                    data.cases.add("integrated_world");data.cases.add("no_vanilla_lan");data.cases.add("shared_pause");
                    String peer=helper.snapshot().map(value->RuntimeView.text(value.data(),"peer_id")).orElse(null);data.check(peer!=null&&!peer.isBlank(),"HOST_PEER");
                    var ready=new JsonObject();ready.addProperty("schema",1);ready.addProperty("run",data.run);ready.addProperty("peer",peer);GameFixtureData.write(data.root.resolve("server-ready.json"),ready);data.advance("PLAY",140);
                }
                case "PLAY" -> {
                    if(joins<1||rounds<GameFixtureData.ROUNDS||disconnects<1) return;
                    data.check(joins==1&&rounds==GameFixtureData.ROUNDS&&disconnects==1,"REMOTE_PLAYER_LIFETIME");
                    data.check(System.nanoTime()-startedPlay>=java.util.concurrent.TimeUnit.SECONDS.toNanos(18),"SUSTAINED_PLAY");
                    data.check(world.hosted()&&server.getTickCount()>startTick+20,"WORLD_TICKS_WHILE_MENU");
                    data.cases.add("vanilla_login");data.cases.add("sustained_duplex");data.cases.add("remote_disconnect");
                    client.setScreen(AccountScreen.entry(client.screen));data.advance("UNHOST",15);
                }
                case "UNHOST" -> { if(uiBusy()) return;press(client,"停止共享");data.advance("REVOKED",25); }
                case "REVOKED" -> {
                    var status=world.status();if(status==null||status.phase()!=WorldHost.Phase.CLOSED||uiBusy()) return;
                    data.check(!world.hosted()&&!server.isPublished()&&server.getPort()<0,"UNHOST_RESTORES_LOCAL_PAUSE");
                    client.setScreen(new PauseScreen(true));data.advance("PAUSED_AGAIN",15);
                }
                case "PAUSED_AGAIN" -> { if(!GameFixtureApi.serverPaused(server)) return;data.check(world.isCurrent(),"PAUSE_RESTORED_OWNER");data.cases.add("unhost_pause");pending=helper.request("stop",runtime.arguments());data.advance("STOP_ROOM",20); }
                case "STOP_ROOM" -> { if(!pending.isDone()) return;data.check(helper.isCurrent(pending.join()),"ROOM_STOP_OWNER");
                    // Match Vanilla PauseScreen: close the local play connection
                    // first so the integrated server can stop before disconnect waits.
                    data.advance("WORLD_CLOSE",25);client.level.disconnect();GameFixtureApi.disconnect(client);client.setScreen(new net.minecraft.client.gui.screens.TitleScreen()); }
                case "WORLD_CLOSE" -> { if(!serverStopped||client.getSingleplayerServer()!=null||client.level!=null) return;data.check(!world.isCurrent(),"WORLD_RETIRED");data.cases.add("world_cleanup");ClientPlatform.agent().close();data.advance("HELPER_CLOSE",25); }
                case "HELPER_CLOSE" -> { if(!ClientPlatform.agent().stopped().isDone()) return;ClientPlatform.agent().stopped().join();data.check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false),"HELPER_REAPED");data.cases.add("helper_cleanup");data.reason="OK";finish(client,true); }
                default -> throw new IllegalStateException("INTEGRATED_STAGE");
            }
        } catch(Throwable error) { data.fail(error);ClientPlatform.agent().close();finish(client,false); }
    }
    private void finish(Minecraft client,boolean success) { finished=true;try {data.result(success,world!=null&&!world.isCurrent(),joins,rounds);}catch(Exception ignored){}client.stop(); }
}
