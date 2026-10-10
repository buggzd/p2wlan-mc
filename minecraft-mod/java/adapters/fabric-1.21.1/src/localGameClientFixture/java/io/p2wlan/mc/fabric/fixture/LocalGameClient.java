package io.p2wlan.mc.fabric.fixture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.p2wlan.mc.common.AccountPresentation;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import io.p2wlan.mc.fabric.client.screen.AccountScreen;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Actual visible widgets, Vanilla login/configuration/play and two-way game
 * packets. One game-thread step/pending request; deadlines, no worker/retry queue. */
public final class LocalGameClient implements ClientModInitializer {
    private GameFixtureData data;
    private AgentClient helper;
    private RuntimeView runtime;
    private String peer;
    private CompletableFuture<AgentReply> pending;
    private TextFieldWidget submittedPassword;
    private Screen paintedScreen;
    private io.p2wlan.mc.common.PeerTelemetry paintedTelemetry;
    private io.p2wlan.mc.common.GameService chosenService, paintedSelection;
    private int sent, received, joins, peerQueries;
    private long nextQuery, nextRound, worldTime;
    private boolean finished, telemetryConfirmed, savedServerInserted, hudCaptured;
    private io.p2wlan.mc.common.DisplayPreferences paintedHud;
    @Override public void onInitializeClient() {
        try { data = new GameFixtureData("client"); }
        catch (Exception error) { throw new IllegalArgumentException("LOCAL_GAME_FIXTURE_CONFIG"); }
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> data.advance("AGENT", 45));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // Single last-painted cell, not a screenshot queue. Capture the next
        // tick's completed framebuffer, not the frame before a row appeared.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register((view, draw, x, y, delta) -> {
                    paintedScreen = view;
                    paintedTelemetry = peer == null ? null : io.p2wlan.mc.fabric.client.lan.FabricNetworkView.peer(peer);
                    paintedSelection = view instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen multiplayer
                            && ((io.p2wlan.mc.fabric.client.lan.VanillaLanScreen) multiplayer).p2wlan$list().getSelectedOrNull()
                                    instanceof io.p2wlan.mc.fabric.client.lan.VirtualLanEntry entry ? entry.service() : null;
                }));
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((draw,ticks) -> {
            var client=MinecraftClient.getInstance();
            paintedHud=FabricGameJoins.playingService(client)==null?null:io.p2wlan.mc.fabric.client.ClientDisplaySettings.current();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String expected = "P2WLAN-fixture:" + data.run + ":" + sent;
            if ("PLAY".equals(data.stage) && sent > received && expected.equals(message.getString())) received = sent;
        });
    }
    private static List<ButtonWidget> buttons(Screen screen) {
        return screen.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast).toList();
    }
    private static List<TextFieldWidget> fields(Screen screen) {
        return screen.children().stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).toList();
    }
    private static ButtonWidget button(Screen screen, String prefix) {
        return buttons(screen).stream().filter(value -> value.getMessage().getString().startsWith(prefix)).findFirst().orElse(null);
    }
    private static boolean uiBusy() {
        try {
            var field = AccountScreen.class.getSuperclass().getDeclaredField("REQUESTS"); field.setAccessible(true);
            return ((io.p2wlan.mc.common.UiCompletionGate) field.get(null)).busy();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("UI_GATE_HOOK"); }
    }
    private static boolean enabled(Screen screen, String prefix) {
        var value = button(screen, prefix); return value != null && value.active && !uiBusy();
    }
    private void press(Screen screen, String prefix) {
        var value = button(screen, prefix); data.check(value != null && enabled(screen, prefix) && value.visible, "BUTTON_DISABLED"); value.onPress();
    }
    private static boolean screen(MinecraftClient client, String name) {
        return client.currentScreen != null && client.currentScreen.getClass().getSimpleName().equals(name);
    }
    private static String feedback(Screen screen) throws Exception {
        var type = screen.getClass().getSuperclass();
        var field = type.getDeclaredField("message"); field.setAccessible(true);
        return (String) field.get(screen);
    }
    private void tick(MinecraftClient client) {
        if (finished) return;
        try {
            data.withinDeadline();
            if (client.currentScreen instanceof DisconnectedScreen && !"CLEANUP".equals(data.stage)) throw new IllegalStateException("VANILLA_DISCONNECTED");
            switch (data.stage) {
                case "AGENT" -> {
                    // A fresh Vanilla install first presents accessibility setup.
                    // Complete that ordinary screen, not the Mod/account state.
                    if (client.currentScreen instanceof net.minecraft.client.gui.screen.AccessibilityOnboardingScreen) {
                        client.currentScreen.close(); return;
                    }
                    if (!FabricP2wlan.AGENT.ready().isDone() || client.getOverlay() != null || !(client.currentScreen instanceof TitleScreen)) return;
                    helper = FabricP2wlan.AGENT.ready().join();
                    data.check(FabricP2wlan.AGENT.isCurrent(helper), "HELPER_OWNER");
                    var ready = JsonParser.parseString(Files.readString(data.root.resolve("server-ready.json"))).getAsJsonObject();
                    data.check(ready.keySet().equals(Set.of("schema", "run", "peer")) && ready.get("run").getAsString().equals(data.run), "READY_RUN");
                    peer = ready.get("peer").getAsString(); checkLogo(client.currentScreen); press(client.currentScreen, "P2WLAN"); data.advance("FORM", 10);
                }
                case "FORM" -> {
                    if (!screen(client, "AccountScreen")) return;
                    if (helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.UNKNOWN).orElse(true)) return;
                    var form = fields(client.currentScreen);
                    data.check(form.size() == 3 && !enabled(client.currentScreen, "登录") && !enabled(client.currentScreen, "注册"), "EMPTY_ACCOUNT_BUTTONS");
                    form.get(0).setText(data.control); form.get(1).setText(data.identifier); form.get(2).setText(data.password); client.currentScreen.tick();
                    data.check(enabled(client.currentScreen, "登录") && enabled(client.currentScreen, "注册"), "FILLED_ACCOUNT_BUTTONS");
                    var warning = client.currentScreen.getClass().getDeclaredMethod("insecureControl"); warning.setAccessible(true);
                    data.check(Boolean.TRUE.equals(warning.invoke(client.currentScreen)), "HTTP_WARNING");
                    press(client.currentScreen, "注册"); data.check(form.get(2).getText().isEmpty(), "REGISTER_PASSWORD_CLEAR");
                    data.cases.add("account_buttons"); data.advance("REGISTER", 15);
                }
                case "REGISTER" -> {
                    if (!screen(client,"RoomsScreen") || !enabled(client.currentScreen,"账号设置")) return;
                    data.check(helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.SAVED_SESSION).orElse(false), "REGISTER_ACCOUNT");
                    press(client.currentScreen,"账号设置"); data.advance("RELOGIN",10);
                }
                case "RELOGIN" -> {
                    if (!screen(client,"AccountScreen") || !enabled(client.currentScreen,"重新登录")) return;
                    press(client.currentScreen,"重新登录"); data.advance("RELOGIN_FORM",10);
                }
                case "RELOGIN_FORM" -> {
                    var form=fields(client.currentScreen); if (form.size()!=3) return;
                    data.check(form.get(0).getText().equals(data.control),"RELOGIN_SAVED_CONTROL");
                    form.get(1).setText(data.identifier); form.get(2).setText(data.password); client.currentScreen.tick();
                    data.check(!enabled(client.currentScreen,"注册") && enabled(client.currentScreen,"登录"),"SAVED_ACCOUNT_BUTTONS");
                    submittedPassword=form.get(2); press(client.currentScreen,"登录"); data.advance("LOGIN",15);
                }
                case "LOGIN" -> {
                    if (!screen(client,"RoomsScreen") || !enabled(client.currentScreen,"加入房间")) return;
                    data.check(submittedPassword.getText().isEmpty(),"LOGIN_PASSWORD_CLEAR"); submittedPassword=null;
                    data.cases.add("register_login"); data.advance("ROOMS",15);
                }
                case "ROOMS" -> {
                    if (!screen(client, "RoomsScreen") || !enabled(client.currentScreen, "加入房间")) return;
                    press(client.currentScreen, "加入房间"); data.advance("ROOM_FORM", 10);
                }
                case "ROOM_FORM" -> {
                    if (!screen(client, "RoomFormScreen")) return;
                    var form = fields(client.currentScreen); data.check(form.size() == 2, "ROOM_FIELDS");
                    form.get(0).setText(data.room); form.get(1).setText(data.roomPassword); client.currentScreen.tick();
                    press(client.currentScreen, "加入房间"); data.check(form.get(1).getText().isEmpty(), "ROOM_PASSWORD_CLEAR"); data.advance("ROOM_SELECTED", 15);
                }
                case "ROOM_SELECTED" -> {
                    if (!screen(client, "RoomsScreen") || !enabled(client.currentScreen, "连接 · ")) return;
                    press(client.currentScreen, "连接 · ");
                    data.cases.add("room_join"); data.advance("RUNTIME", 40);
                }
                case "RUNTIME" -> {
                    if (!screen(client, "RuntimeScreen")) return;
                    var selected = RuntimeView.ready(helper); if (selected.isEmpty()) return;
                    runtime = selected.get();
                    if (!enabled(client.currentScreen,"打开多人游戏") || io.p2wlan.mc.fabric.client.lan.FabricNetworkView.peer(peer)==null) return;
                    data.check(button(client.currentScreen,peer)==null && button(client.currentScreen,"加入 / Join")==null,"ROOM_HAS_NO_GAME_PICKER");
                    data.check(FabricP2wlan.AGENT.isCurrent(helper) && runtime.isCurrent(helper), "RUNTIME_OWNER");
                    data.advance("PEER", 20);
                }
                case "PEER" -> {
                    if (pending != null && pending.isDone()) {
                        try {
                            var reply = pending.join();
                            if (!helper.isCurrent(reply) || !runtime.runtime().equals(RuntimeView.text(reply.data(), "runtime")) || !peer.equals(RuntimeView.text(reply.data(), "peer"))) throw new IllegalStateException("PEER_OWNER");
                            var view = reply.data().getAsJsonObject("view");
                            if (paintedScreen == client.currentScreen && paintedTelemetry != null && paintedTelemetry.latencyMs() != null
                                    && paintedTelemetry.fresh(System.nanoTime()) && view.get("online").getAsBoolean() && ("relay".equals(RuntimeView.text(view, "path")) || "direct".equals(RuntimeView.text(view, "path")) && view.get("direct_budget_ready").getAsBoolean())) {
                                data.check(runtime.isCurrent(helper) && helper.isCurrent(reply), "PEER_READY_OWNER");
                                data.cases.add("runtime_peer");
                                net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-network.png",client.getFramebuffer(),message->{});
                                press(client.currentScreen,"显示设置"); pending = null; data.advance("DISPLAY_SETTINGS", 20); return;
                            }
                        } catch (java.util.concurrent.CompletionException error) {
                            var failure = io.p2wlan.mc.common.UiCompletionGate.unwrap(error);
                            if (!(failure instanceof AgentException agent) || !Set.of("UNKNOWN_PEER", "ROOM_REVOKED", "SESSION_MISSING", "SESSION_CONTENDED").contains(agent.remoteCode())) throw error;
                        }
                        pending = null; nextQuery = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                    }
                    if (pending == null && System.nanoTime() >= nextQuery) {
                        if (++peerQueries > 100) throw new IllegalStateException("PEER_QUERY_CAPACITY");
                        var args = runtime.arguments(); args.addProperty("peer", peer);
                        pending = helper.request("peer_status", args, () -> runtime.isCurrent(helper));
                    }
                }
                case "DISPLAY_SETTINGS" -> {
                    if (!screen(client,"DisplaySettingsScreen") || paintedScreen!=client.currentScreen) return;
                    data.check(!io.p2wlan.mc.fabric.client.ClientDisplaySettings.current().hudVisible(),"HUD_DEFAULT_OFF");
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-display-basic.png",client.getFramebuffer(),message->{});
                    press(client.currentScreen,"游戏浮层：");press(client.currentScreen,"高级");press(client.currentScreen,"浮层位置：");
                    press(client.currentScreen,"保存并返回");data.advance("DISPLAY_SAVED",10);
                }
                case "DISPLAY_SAVED" -> {
                    if (!screen(client,"RuntimeScreen")) return;
                    var settings=io.p2wlan.mc.fabric.client.ClientDisplaySettings.current();
                    var loaded=new io.p2wlan.mc.common.DisplayPreferencesStore(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("p2wlan-display.json")).load();
                    data.check(settings.hudVisible() && settings.detail()==io.p2wlan.mc.common.DisplayPreferences.Detail.ADVANCED
                            && settings.corner()==io.p2wlan.mc.common.DisplayPreferences.Corner.TOP_RIGHT && loaded.preferences().equals(settings)
                            && loaded.reason()==io.p2wlan.mc.common.DisplayPreferencesStore.Reason.OK,"DISPLAY_PERSISTED");
                    press(client.currentScreen,"显示设置");data.advance("DISPLAY_REOPEN",10);
                }
                case "DISPLAY_REOPEN" -> {
                    if (!screen(client,"DisplaySettingsScreen") || paintedScreen!=client.currentScreen) return;
                    data.check(button(client.currentScreen,"游戏浮层：开启")!=null && button(client.currentScreen,"[ 高级 ]")!=null,"DISPLAY_REOPENED");
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-display-advanced.png",client.getFramebuffer(),message->{});
                    press(client.currentScreen,"延迟统计");data.advance("LATENCY_STATS",15);
                }
                case "LATENCY_STATS" -> {
                    if (!screen(client,"LatencyStatsScreen") || paintedScreen!=client.currentScreen || paintedTelemetry==null
                            || !paintedTelemetry.statisticsCurrent(System.nanoTime()) || paintedTelemetry.statistics().samples()==0) return;
                    var stats=paintedTelemetry.statistics();
                    data.check(stats.samples()<=256 && stats.minUs()<=stats.avgUs() && stats.avgUs()<=stats.maxUs()
                            && stats.p50Us()<=stats.p90Us() && stats.p90Us()<=stats.p95Us() && stats.p95Us()<=stats.p99Us() && stats.p99Us()<=stats.maxUs(),"NATIVE_WINDOW_STATS");
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-latency-statistics.png",client.getFramebuffer(),message->{});
                    press(client.currentScreen,"返回");press(client.currentScreen,"取消");
                    data.cases.add("display_preferences");data.cases.add("latency_statistics");
                    press(client.currentScreen,"打开多人游戏");data.advance("DISCOVERY",20);
                }
                case "DISCOVERY" -> {
                    if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen multiplayer)) return;
                    var widget=((io.p2wlan.mc.fabric.client.lan.VanillaLanScreen) multiplayer).p2wlan$list();
                    if (!savedServerInserted) {
                        var ordinary = new net.minecraft.client.network.ServerInfo("Fixture saved server", "127.0.0.1:9", net.minecraft.client.network.ServerInfo.ServerType.OTHER);
                        multiplayer.getServerList().add(ordinary, false); widget.setServers(multiplayer.getServerList());
                        savedServerInserted = true;
                        data.check(widget.children().stream().anyMatch(net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget.ServerEntry.class::isInstance), "VANILLA_SAVED_SERVER_RETAINED");
                    }
                    var selected=widget.children().stream().filter(io.p2wlan.mc.fabric.client.lan.VirtualLanEntry.class::isInstance)
                            .map(io.p2wlan.mc.fabric.client.lan.VirtualLanEntry.class::cast).filter(value->value.service().peer().equals(peer)).findFirst();
                    if (selected.isEmpty()) return;
                    var entry=selected.get();
                    data.check(widget instanceof io.p2wlan.mc.fabric.client.lan.VanillaLanList && runtime.equals(entry.service().runtime())
                            && io.p2wlan.mc.fabric.client.lan.FabricNetworkView.contains(entry.service()),"VANILLA_BROWSER_FENCED_ROW");
                    data.check(!entry.getNarration().getString().contains(peer) && !entry.getNarration().getString().isBlank(),"HUMAN_SERVER_LABEL");
                    widget.setSelected(entry); chosenService = entry.service(); data.advance("BROWSER_JOIN", 10);
                }
                case "BROWSER_JOIN" -> {
                    if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen multiplayer)
                            || paintedScreen != multiplayer || !chosenService.equals(paintedSelection)) return;
                    checkLogo(multiplayer);
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-multiplayer.png",client.getFramebuffer(),message->{});
                    press(multiplayer,net.minecraft.text.Text.translatable("selectServer.select").getString());
                    data.cases.add("discovery");data.cases.add("vanilla_browser");data.advance("JOIN",40);
                }
                case "JOIN" -> {
                    if (client.world == null || client.player == null || client.getNetworkHandler() == null) return;
                    data.check(client.getServer() == null && runtime.isCurrent(helper) && client.getNetworkHandler().getConnection().isOpen(), "VANILLA_PLAY");
                    data.check(io.p2wlan.mc.fabric.client.lan.FabricNetworkView.services().isEmpty() && runtime.isCurrent(helper), "BROWSER_CLOSE_KEEPS_ROOM_NOT_CATALOG");
                    worldTime = client.world.getTime(); joins++; data.cases.add("vanilla_play"); data.advance("PLAY", 55);
                }
                case "PLAY" -> {
                    if (client.world == null || client.player == null || client.getNetworkHandler() == null || !runtime.isCurrent(helper)) throw new IllegalStateException("PLAY_OWNER");
                    var measured = io.p2wlan.mc.fabric.client.lan.FabricNetworkView.peer(peer);
                    if (!telemetryConfirmed && measured != null && measured.online() && measured.fresh(System.nanoTime()) && measured.latencyMs() != null) {
                        data.check(runtime.equals(measured.runtime()) && !measured.virtualIp().isEmpty() && measured.lastSuccessAgeMs() != null
                                && measured.lastSuccessAgeMs() <= 15_000 && Set.of("direct", "relay").contains(measured.path()), "NATIVE_TIMED_PEER_TELEMETRY");
                        telemetryConfirmed = true; data.cases.add("telemetry");
                        client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));data.advance("PLAY_MENU",10);return;
                    }
                    if (!hudCaptured && paintedHud!=null && sent>=1 && received>=1) {
                        data.check(paintedHud.hudVisible() && paintedHud.detail()==io.p2wlan.mc.common.DisplayPreferences.Detail.ADVANCED
                                && io.p2wlan.mc.common.TelemetryPresentation.hud(paintedHud,measured,System.nanoTime()).size()==6,"ADVANCED_HUD_PAINTED");
                        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-hud-advanced.png",client.getFramebuffer(),message->{});
                        hudCaptured=true;data.cases.add("latency_hud");
                    }
                    if (received == GameFixtureData.ROUNDS && client.player.experienceLevel == received && telemetryConfirmed && hudCaptured) {
                        data.check(client.world.getTime() - worldTime >= 350, "WORLD_TICK_PROGRESS");
                        data.cases.add("sustained_duplex"); client.world.disconnect(); client.disconnect(); client.setScreen(new TitleScreen());
                        FabricGameJoins.shutdown(); data.advance("DISCONNECT", 15); return;
                    }
                    if (received == sent && client.player.experienceLevel == sent && System.nanoTime() >= nextRound) {
                        sent++; client.getNetworkHandler().sendChatCommand("p2wlan-game-fixture " + data.run + " " + sent);
                        nextRound = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    }
                }
                case "PLAY_MENU" -> {
                    if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) || paintedScreen!=client.currentScreen) return;
                    checkLogo(client.currentScreen);data.cases.add("menu_logo");
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(data.root.toFile(),"p2wlan-pause-logo.png",client.getFramebuffer(),message->{});
                    client.setScreen(null);data.advance("PLAY",55);
                }
                case "DISCONNECT" -> {
                    if (client.world != null || client.getNetworkHandler() != null) return;
                    data.cases.add("disconnect"); client.setScreen(AccountScreen.entry(client.currentScreen)); data.advance("STOP_ROOM", 15);
                }
                case "STOP_ROOM" -> {
                    if (!screen(client, "RuntimeScreen") || !enabled(client.currentScreen, "断开房间")) return;
                    press(client.currentScreen, "断开房间"); data.advance("STOPPED", 15);
                }
                case "STOPPED" -> {
                    if (RuntimeView.active(helper).isPresent() || !screen(client, "RoomsScreen")) return;
                    data.check("idle".equals(helper.snapshot().map(value -> RuntimeView.text(value.data(), "phase")).orElse(null)), "ROOM_STOPPED");
                    data.check(helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.SAVED_SESSION
                            && AccountPresentation.server(value.data().get("account")).equals(data.control)).orElse(false), "ROOM_STOP_RETAINS_ACCOUNT_AND_CONTROL");
                    data.check(io.p2wlan.mc.fabric.client.lan.FabricNetworkView.peers().isEmpty()
                            && io.p2wlan.mc.fabric.client.lan.FabricNetworkView.services().isEmpty(), "ROOM_STOP_RETIRES_DISPLAY_SNAPSHOTS");
                    data.cases.add("room_stop"); data.cases.add("session_retained"); FabricP2wlan.AGENT.close(); data.advance("CLEANUP", 25);
                }
                case "CLEANUP" -> {
                    if (!FabricP2wlan.AGENT.stopped().isDone()) return;
                    FabricP2wlan.AGENT.stopped().join();
                    data.check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false) && FabricP2wlan.AGENT.current().isEmpty(), "HELPER_CLEANUP");
                    data.cases.add("helper_cleanup"); data.reason = "OK"; finish(client);
                }
                default -> throw new IllegalStateException("CLIENT_STEP");
            }
        } catch (Throwable error) {
            data.fail(error); FabricGameJoins.shutdown(); FabricP2wlan.AGENT.close(); finish(client);
        }
    }
    private void checkLogo(Screen screen) {
        var logo=button(screen,"P2WLAN");
        data.check(logo instanceof io.p2wlan.mc.fabric.client.screen.LogoButton && logo.getWidth()==20 && logo.getHeight()==20
                && buttons(screen).stream().filter(other->other!=logo).anyMatch(other->other.getY()==logo.getY()
                    && (other.getX()+other.getWidth()+4==logo.getX() || logo.getX()+24==other.getX()))
                && buttons(screen).stream().filter(other->other!=logo).noneMatch(other->logo.getX()<other.getX()+other.getWidth()
                    && logo.getX()+20>other.getX() && logo.getY()<other.getY()+other.getHeight() && logo.getY()+20>other.getY()),"ADJACENT_SQUARE_LOGO");
    }
    private ButtonWidget member(Screen screen) {
        return buttons(screen).stream().filter(value -> value.active && !value.getMessage().getString().isBlank() && peer.startsWith(value.getMessage().getString())).findFirst().orElse(null);
    }
    private void finish(MinecraftClient client) {
        finished = true;
        boolean clean = helper != null && !ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false);
        try { data.result(clean, client.world == null && client.getNetworkHandler() == null, joins, received); }
        catch (Exception ignored) { /* Missing result fails the runner closed. */ }
        client.scheduleStop();
    }
}
