package io.p2wlan.mc.game.fixture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.p2wlan.mc.common.AccountPresentation;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.game.client.connect.GameJoins;
import io.p2wlan.mc.game.client.screen.AccountScreen;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Actual visible widgets, Vanilla login/configuration/play and two-way game
 * packets. One game-thread step/pending request; deadlines, no worker/retry queue. */
public final class GameClientChecks {
    private final java.util.function.BiFunction<java.nio.file.Path,java.nio.file.Path,io.p2wlan.mc.common.AgentLifecycle> diagnosticFactory;
    public GameClientChecks() { this((profile,logs)->new io.p2wlan.mc.common.AgentLifecycle(profile,Boolean.getBoolean("p2wlan.allowDevelopmentNative"),logs)); }
    public GameClientChecks(java.util.function.BiFunction<java.nio.file.Path,java.nio.file.Path,io.p2wlan.mc.common.AgentLifecycle> factory) { diagnosticFactory=factory; }
    private GameFixtureData data;
    private AgentClient helper;
    private io.p2wlan.mc.common.AgentLifecycle diagnosticOwner;
    private Screen diagnosticParent;
    private RuntimeView runtime;
    private String peer;
    private CompletableFuture<AgentReply> pending;
    private EditBox submittedPassword;
    private Screen paintedScreen;
    private io.p2wlan.mc.common.PeerTelemetry paintedTelemetry;
    private io.p2wlan.mc.common.GameService chosenService, paintedSelection;
    private int sent, received, joins, peerQueries;
    private long nextQuery, nextRound, worldTime;
    private boolean finished, telemetryConfirmed, savedServerInserted, hudCaptured;
    private io.p2wlan.mc.common.DisplayPreferences paintedHud;
    public void start() {
        try { data = new GameFixtureData("client"); }
        catch (Exception error) { throw new IllegalArgumentException("LOCAL_GAME_FIXTURE_CONFIG"); }
        data.advance("AGENT",45);
    }
    public void painted(Screen view) {
        paintedScreen=view;
        paintedTelemetry=peer==null?null:io.p2wlan.mc.game.client.lan.ClientNetworkView.peer(peer);
        paintedSelection=view instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen multiplayer
                && ((io.p2wlan.mc.game.client.lan.VanillaLanScreen) multiplayer).p2wlan$list().getSelected()
                        instanceof io.p2wlan.mc.game.client.lan.VirtualLanEntry entry?entry.service():null;
    }
    public void paintedHud() { paintedHud=GameJoins.playingService(Minecraft.getInstance())==null?null:io.p2wlan.mc.game.client.ClientDisplaySettings.current(); }
    public void message(net.minecraft.network.chat.Component message) {
        String expected="P2WLAN-fixture:"+data.run+":"+sent;
        if("PLAY".equals(data.stage) && sent>received && expected.equals(message.getString())) received=sent;
    }
    private static List<Button> buttons(Screen screen) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
    }
    private static List<EditBox> fields(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
    }
    private static Button button(Screen screen, String prefix) {
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
    private static boolean screen(Minecraft client, String name) {
        return client.screen != null && client.screen.getClass().getSimpleName().equals(name);
    }
    private static String feedback(Screen screen) throws Exception {
        var type = screen.getClass().getSuperclass();
        var field = type.getDeclaredField("message"); field.setAccessible(true);
        return (String) field.get(screen);
    }
    public void tick(Minecraft client) {
        if (finished) return;
        try {
            data.withinDeadline();
            if (client.screen instanceof DisconnectedScreen && !"CLEANUP".equals(data.stage)) {
                // Fixture-only private evidence, never parsed as control flow or packaged.
                var reason = DisconnectedScreen.class.getDeclaredField("reason"); reason.setAccessible(true);
                var text = (net.minecraft.network.chat.Component) reason.get(client.screen);
                Files.writeString(data.root.resolve("vanilla-disconnect.fixture.txt"), text.getString().substring(0, Math.min(1024, text.getString().length())),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                throw new IllegalStateException("VANILLA_DISCONNECTED");
            }
            switch (data.stage) {
                case "AGENT" -> {
                    // A fresh Vanilla install first presents accessibility setup.
                    // Complete that ordinary screen, not the Mod/account state.
                    if (client.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
                        client.screen.onClose(); return;
                    }
                    if (!io.p2wlan.mc.game.client.ClientPlatform.agent().ready().isDone() || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) return;
                    helper = io.p2wlan.mc.game.client.ClientPlatform.agent().ready().join();
                    data.check(io.p2wlan.mc.game.client.ClientPlatform.agent().isCurrent(helper), "HELPER_OWNER");
                    var ready = JsonParser.parseString(Files.readString(data.root.resolve("server-ready.json"))).getAsJsonObject();
                    data.check(ready.keySet().equals(Set.of("schema", "run", "peer")) && ready.get("run").getAsString().equals(data.run), "READY_RUN");
                    peer = ready.get("peer").getAsString(); checkLogo(client.screen); press(client.screen, "P2WLAN"); data.advance("FORM", 10);
                }
                case "FORM" -> {
                    if (!screen(client, "AccountScreen")) return;
                    if (helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.UNKNOWN).orElse(true)) return;
                    var form = fields(client.screen);
                    data.check(form.size() == 3 && !enabled(client.screen, "登录") && !enabled(client.screen, "注册"), "EMPTY_ACCOUNT_BUTTONS");
                    data.check(enabled(client.screen, "诊断日志"), "DIAGNOSTICS_ENTRY");
                    press(client.screen, "诊断日志");
                    diagnosticParent = ((io.p2wlan.mc.game.client.screen.DiagnosticsScreen) client.screen);
                    diagnosticParent.onClose(); diagnosticParent = client.screen;
                    Files.writeString(data.root.resolve("diagnostic-blocker.fixture"), "fixture-diagnostic-secret", java.nio.file.StandardOpenOption.CREATE_NEW);
                    diagnosticOwner = diagnosticFactory.apply(data.root.resolve("diagnostic-blocker.fixture/profile"), data.root.resolve("diagnostic-logs"));
                    diagnosticOwner.start();
                    client.setScreen(new io.p2wlan.mc.game.client.screen.DiagnosticsScreen(diagnosticParent, diagnosticOwner));
                    data.advance("DIAGNOSTICS", 20);
                }
                case "DIAGNOSTICS" -> {
                    if (paintedScreen != client.screen || !diagnosticOwner.stopped().isDone() || !diagnosticOwner.diagnostics().flushed().isDone()) return;
                    var snapshot = diagnosticOwner.diagnostics().snapshot();
                    data.check(diagnosticOwner.status().phase() == io.p2wlan.mc.common.AgentLifecycle.Phase.FAILED, "DIAGNOSTIC_FAILED_HELPER");
                    data.check(snapshot.failure() != null && snapshot.failure().reason().equals("INITIALIZATION_FAILED / PROFILE_IO"), "DIAGNOSTIC_IO_REASON");
                    data.check(enabled(client.screen, "复制诊断") && enabled(client.screen, "打开日志目录"), "FAILED_HELPER_DIAGNOSTIC_BUTTONS");
                    press(client.screen, "复制诊断");
                    String copied = client.keyboardHandler.getClipboard();
                    data.check(copied.contains("stage=PROFILE_OPEN") && copied.contains("operation=CREATE_DIRECTORY") && snapshot.failure().startup().osCode() != null, "DIAGNOSTIC_OS_EVIDENCE");
                    data.check(!copied.contains("fixture-diagnostic-secret") && !copied.contains(data.root.toString()), "DIAGNOSTIC_REDACTED_COPY");
                    data.check(Files.readString(data.root.resolve("diagnostic-logs/startup.log")).contains("PROFILE_IO"), "DIAGNOSTIC_INDEPENDENT_LOG");
                    press(client.screen, "返回");
                    data.check(client.screen == diagnosticParent && io.p2wlan.mc.game.client.ClientPlatform.agent().isCurrent(helper), "DIAGNOSTIC_RETURN_OWNER");
                    diagnosticOwner.close(); diagnosticOwner = null;
                    Files.delete(data.root.resolve("diagnostic-blocker.fixture"));
                    data.cases.add("startup_diagnostics"); data.advance("FORM_FILLED", 10);
                }
                case "FORM_FILLED" -> {
                    var form = fields(client.screen);
                    form.get(0).setValue(data.control); form.get(1).setValue(data.identifier); form.get(2).setValue(data.password); client.screen.tick();
                    data.check(enabled(client.screen, "登录") && enabled(client.screen, "注册"), "FILLED_ACCOUNT_BUTTONS");
                    var warning = client.screen.getClass().getDeclaredMethod("insecureControl"); warning.setAccessible(true);
                    data.check(Boolean.TRUE.equals(warning.invoke(client.screen)), "HTTP_WARNING");
                    press(client.screen, "注册"); data.check(form.get(2).getValue().isEmpty(), "REGISTER_PASSWORD_CLEAR");
                    data.cases.add("account_buttons"); data.advance("REGISTER", 15);
                }
                case "REGISTER" -> {
                    if (!screen(client,"RoomsScreen") || !enabled(client.screen,"账号设置")) return;
                    data.check(helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.SAVED_SESSION).orElse(false), "REGISTER_ACCOUNT");
                    press(client.screen,"账号设置"); data.advance("RELOGIN",10);
                }
                case "RELOGIN" -> {
                    if (!screen(client,"AccountScreen") || !enabled(client.screen,"重新登录")) return;
                    press(client.screen,"重新登录"); data.advance("RELOGIN_FORM",10);
                }
                case "RELOGIN_FORM" -> {
                    var form=fields(client.screen); if (form.size()!=3) return;
                    data.check(form.get(0).getValue().equals(data.control),"RELOGIN_SAVED_CONTROL");
                    form.get(1).setValue(data.identifier); form.get(2).setValue(data.password); client.screen.tick();
                    data.check(!enabled(client.screen,"注册") && enabled(client.screen,"登录"),"SAVED_ACCOUNT_BUTTONS");
                    submittedPassword=form.get(2); press(client.screen,"登录"); data.advance("LOGIN",15);
                }
                case "LOGIN" -> {
                    if (!screen(client,"RoomsScreen") || !enabled(client.screen,"加入房间")) return;
                    data.check(submittedPassword.getValue().isEmpty(),"LOGIN_PASSWORD_CLEAR"); submittedPassword=null;
                    data.cases.add("register_login"); data.advance("ROOMS",15);
                }
                case "ROOMS" -> {
                    if (!screen(client, "RoomsScreen") || !enabled(client.screen, "加入房间")) return;
                    press(client.screen, "加入房间"); data.advance("ROOM_FORM", 10);
                }
                case "ROOM_FORM" -> {
                    if (!screen(client, "RoomFormScreen")) return;
                    var form = fields(client.screen); data.check(form.size() == 2, "ROOM_FIELDS");
                    form.get(0).setValue(data.room); form.get(1).setValue(data.roomPassword); client.screen.tick();
                    press(client.screen, "加入房间"); data.check(form.get(1).getValue().isEmpty(), "ROOM_PASSWORD_CLEAR"); data.advance("ROOM_SELECTED", 15);
                }
                case "ROOM_SELECTED" -> {
                    if (!screen(client, "RoomsScreen") || !enabled(client.screen, "连接 · ")) return;
                    press(client.screen, "连接 · ");
                    data.cases.add("room_join"); data.advance("RUNTIME", 40);
                }
                case "RUNTIME" -> {
                    if (!screen(client, "RuntimeScreen")) return;
                    var selected = RuntimeView.ready(helper); if (selected.isEmpty()) return;
                    runtime = selected.get();
                    if (!enabled(client.screen,"打开多人游戏") || io.p2wlan.mc.game.client.lan.ClientNetworkView.peer(peer)==null) return;
                    data.check(button(client.screen,peer)==null && button(client.screen,"加入 / Join")==null,"ROOM_HAS_NO_GAME_PICKER");
                    data.check(io.p2wlan.mc.game.client.ClientPlatform.agent().isCurrent(helper) && runtime.isCurrent(helper), "RUNTIME_OWNER");
                    data.advance("PEER", 20);
                }
                case "PEER" -> {
                    if (pending != null && pending.isDone()) {
                        try {
                            var reply = pending.join();
                            if (!helper.isCurrent(reply) || !runtime.runtime().equals(RuntimeView.text(reply.data(), "runtime")) || !peer.equals(RuntimeView.text(reply.data(), "peer"))) throw new IllegalStateException("PEER_OWNER");
                            var view = reply.data().getAsJsonObject("view");
                            if (paintedScreen == client.screen && paintedTelemetry != null && paintedTelemetry.latencyMs() != null
                                    && paintedTelemetry.fresh(System.nanoTime()) && view.get("online").getAsBoolean() && ("relay".equals(RuntimeView.text(view, "path")) || "direct".equals(RuntimeView.text(view, "path")) && view.get("direct_budget_ready").getAsBoolean())) {
                                data.check(runtime.isCurrent(helper) && helper.isCurrent(reply), "PEER_READY_OWNER");
                                data.cases.add("runtime_peer");
                                net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-network.png",client.getMainRenderTarget(),message->{});
                                press(client.screen,"显示设置"); pending = null; data.advance("DISPLAY_SETTINGS", 20); return;
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
                    if (!screen(client,"DisplaySettingsScreen") || paintedScreen!=client.screen) return;
                    data.check(!io.p2wlan.mc.game.client.ClientDisplaySettings.current().hudVisible(),"HUD_DEFAULT_OFF");
                    net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-display-basic.png",client.getMainRenderTarget(),message->{});
                    press(client.screen,"游戏浮层：");press(client.screen,"高级");press(client.screen,"浮层位置：");
                    press(client.screen,"保存并返回");data.advance("DISPLAY_SAVED",10);
                }
                case "DISPLAY_SAVED" -> {
                    if (!screen(client,"RuntimeScreen")) return;
                    var settings=io.p2wlan.mc.game.client.ClientDisplaySettings.current();
                    var loaded=new io.p2wlan.mc.common.DisplayPreferencesStore(io.p2wlan.mc.game.client.ClientPlatform.config().resolve("p2wlan-display.json")).load();
                    data.check(settings.hudVisible() && settings.detail()==io.p2wlan.mc.common.DisplayPreferences.Detail.ADVANCED
                            && settings.corner()==io.p2wlan.mc.common.DisplayPreferences.Corner.TOP_RIGHT && loaded.preferences().equals(settings)
                            && loaded.reason()==io.p2wlan.mc.common.DisplayPreferencesStore.Reason.OK,"DISPLAY_PERSISTED");
                    press(client.screen,"显示设置");data.advance("DISPLAY_REOPEN",10);
                }
                case "DISPLAY_REOPEN" -> {
                    if (!screen(client,"DisplaySettingsScreen") || paintedScreen!=client.screen) return;
                    data.check(button(client.screen,"游戏浮层：开启")!=null && button(client.screen,"[ 高级 ]")!=null,"DISPLAY_REOPENED");
                    net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-display-advanced.png",client.getMainRenderTarget(),message->{});
                    press(client.screen,"延迟统计");data.advance("LATENCY_STATS",15);
                }
                case "LATENCY_STATS" -> {
                    if (!screen(client,"LatencyStatsScreen") || paintedScreen!=client.screen || paintedTelemetry==null
                            || !paintedTelemetry.statisticsCurrent(System.nanoTime()) || paintedTelemetry.statistics().samples()==0) return;
                    var stats=paintedTelemetry.statistics();
                    data.check(stats.samples()<=256 && stats.minUs()<=stats.avgUs() && stats.avgUs()<=stats.maxUs()
                            && stats.p50Us()<=stats.p90Us() && stats.p90Us()<=stats.p95Us() && stats.p95Us()<=stats.p99Us() && stats.p99Us()<=stats.maxUs(),"NATIVE_WINDOW_STATS");
                    net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-latency-statistics.png",client.getMainRenderTarget(),message->{});
                    press(client.screen,"返回");press(client.screen,"取消");
                    data.cases.add("display_preferences");data.cases.add("latency_statistics");
                    press(client.screen,"打开多人游戏");data.advance("DISCOVERY",20);
                }
                case "DISCOVERY" -> {
                    if (!(client.screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen multiplayer)) return;
                    var widget=((io.p2wlan.mc.game.client.lan.VanillaLanScreen) multiplayer).p2wlan$list();
                    if (!savedServerInserted) {
                        var ordinary = new net.minecraft.client.multiplayer.ServerData("Fixture saved server", "127.0.0.1:9", net.minecraft.client.multiplayer.ServerData.Type.OTHER);
                        multiplayer.getServers().add(ordinary, false); widget.updateOnlineServers(multiplayer.getServers());
                        savedServerInserted = true;
                        data.check(widget.children().stream().anyMatch(net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.OnlineServerEntry.class::isInstance), "VANILLA_SAVED_SERVER_RETAINED");
                    }
                    var selected=widget.children().stream().filter(io.p2wlan.mc.game.client.lan.VirtualLanEntry.class::isInstance)
                            .map(io.p2wlan.mc.game.client.lan.VirtualLanEntry.class::cast).filter(value->value.service().peer().equals(peer)).findFirst();
                    if (selected.isEmpty()) return;
                    var entry=selected.get();
                    data.check(widget instanceof io.p2wlan.mc.game.client.lan.VanillaLanList && runtime.equals(entry.service().runtime())
                            && io.p2wlan.mc.game.client.lan.ClientNetworkView.contains(entry.service()),"VANILLA_BROWSER_FENCED_ROW");
                    data.check(!entry.getNarration().getString().contains(peer) && !entry.getNarration().getString().isBlank(),"HUMAN_SERVER_LABEL");
                    widget.setSelected(entry); chosenService = entry.service(); data.advance("BROWSER_JOIN", 10);
                }
                case "BROWSER_JOIN" -> {
                    if (!(client.screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen multiplayer)
                            || paintedScreen != multiplayer || !chosenService.equals(paintedSelection)) return;
                    checkLogo(multiplayer);
                    net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-multiplayer.png",client.getMainRenderTarget(),message->{});
                    press(multiplayer,net.minecraft.network.chat.Component.translatable("selectServer.select").getString());
                    data.cases.add("discovery");data.cases.add("vanilla_browser");data.advance("JOIN",40);
                }
                case "JOIN" -> {
                    if(client.screen instanceof io.p2wlan.mc.game.client.screen.JoinGameScreen joining) {
                        var type=joining.getClass(); var failed=type.getDeclaredField("failure"); failed.setAccessible(true);
                        String code=(String)failed.get(joining);
                        if(code!=null) throw new IllegalStateException("JOIN_"+code);
                        var owned=type.getDeclaredField("attempt"); owned.setAccessible(true);
                        var intent=(GameJoins.Attempt)owned.get(joining);
                        if(intent!=null && intent.status().phase()==GameJoins.Phase.FAILED) throw new IllegalStateException("JOIN_"+intent.status().reason());
                    }
                    if (client.level == null || client.player == null || client.getConnection() == null) return;
                    data.check(client.getSingleplayerServer() == null && runtime.isCurrent(helper) && client.getConnection().getConnection().isConnected(), "VANILLA_PLAY");
                    data.check(io.p2wlan.mc.game.client.lan.ClientNetworkView.services().isEmpty() && runtime.isCurrent(helper), "BROWSER_CLOSE_KEEPS_ROOM_NOT_CATALOG");
                    worldTime = client.level.getGameTime(); joins++; data.cases.add("vanilla_play"); data.advance("PLAY", 55);
                }
                case "PLAY" -> {
                    if (client.level == null || client.player == null || client.getConnection() == null || !runtime.isCurrent(helper)) throw new IllegalStateException("PLAY_OWNER");
                    var measured = io.p2wlan.mc.game.client.lan.ClientNetworkView.peer(peer);
                    if (!telemetryConfirmed && measured != null && measured.online() && measured.fresh(System.nanoTime()) && measured.latencyMs() != null) {
                        data.check(runtime.equals(measured.runtime()) && !measured.virtualIp().isEmpty() && measured.lastSuccessAgeMs() != null
                                && measured.lastSuccessAgeMs() <= 15_000 && Set.of("direct", "relay").contains(measured.path()), "NATIVE_TIMED_PEER_TELEMETRY");
                        telemetryConfirmed = true; data.cases.add("telemetry");
                        client.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));data.advance("PLAY_MENU",10);return;
                    }
                    if (!hudCaptured && paintedHud!=null && sent>=1 && received>=1) {
                        data.check(paintedHud.hudVisible() && paintedHud.detail()==io.p2wlan.mc.common.DisplayPreferences.Detail.ADVANCED
                                && io.p2wlan.mc.common.TelemetryPresentation.hud(paintedHud,measured,System.nanoTime()).size()==6,"ADVANCED_HUD_PAINTED");
                        net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-hud-advanced.png",client.getMainRenderTarget(),message->{});
                        hudCaptured=true;data.cases.add("latency_hud");
                    }
                    if (received == GameFixtureData.ROUNDS && client.player.experienceLevel == received && telemetryConfirmed && hudCaptured) {
                        data.check(client.level.getGameTime() - worldTime >= 350, "WORLD_TICK_PROGRESS");
                        data.cases.add("sustained_duplex"); client.level.disconnect(); client.disconnect(); client.setScreen(new TitleScreen());
                        GameJoins.shutdown(); data.advance("DISCONNECT", 15); return;
                    }
                    if (received == sent && client.player.experienceLevel == sent && System.nanoTime() >= nextRound) {
                        sent++; client.getConnection().sendCommand("p2wlan-game-fixture " + data.run + " " + sent);
                        nextRound = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    }
                }
                case "PLAY_MENU" -> {
                    if (!(client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) || paintedScreen!=client.screen) return;
                    checkLogo(client.screen);data.cases.add("menu_logo");
                    net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-pause-logo.png",client.getMainRenderTarget(),message->{});
                    client.setScreen(null);data.advance("PLAY",55);
                }
                case "DISCONNECT" -> {
                    if (client.level != null || client.getConnection() != null) return;
                    data.cases.add("disconnect"); client.setScreen(AccountScreen.entry(client.screen)); data.advance("STOP_ROOM", 15);
                }
                case "STOP_ROOM" -> {
                    if (!screen(client, "RuntimeScreen") || !enabled(client.screen, "断开房间")) return;
                    press(client.screen, "断开房间"); data.advance("STOPPED", 15);
                }
                case "STOPPED" -> {
                    if (RuntimeView.active(helper).isPresent() || !screen(client, "RoomsScreen")) return;
                    data.check("idle".equals(helper.snapshot().map(value -> RuntimeView.text(value.data(), "phase")).orElse(null)), "ROOM_STOPPED");
                    data.check(helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.SAVED_SESSION
                            && AccountPresentation.server(value.data().get("account")).equals(data.control)).orElse(false), "ROOM_STOP_RETAINS_ACCOUNT_AND_CONTROL");
                    data.check(io.p2wlan.mc.game.client.lan.ClientNetworkView.peers().isEmpty()
                            && io.p2wlan.mc.game.client.lan.ClientNetworkView.services().isEmpty(), "ROOM_STOP_RETIRES_DISPLAY_SNAPSHOTS");
                    data.cases.add("room_stop"); data.cases.add("session_retained"); io.p2wlan.mc.game.client.ClientPlatform.agent().close(); data.advance("CLEANUP", 25);
                }
                case "CLEANUP" -> {
                    if (!io.p2wlan.mc.game.client.ClientPlatform.agent().stopped().isDone()) return;
                    io.p2wlan.mc.game.client.ClientPlatform.agent().stopped().join();
                    data.check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false) && io.p2wlan.mc.game.client.ClientPlatform.agent().current().isEmpty(), "HELPER_CLEANUP");
                    data.cases.add("helper_cleanup"); data.reason = "OK"; finish(client);
                }
                default -> throw new IllegalStateException("CLIENT_STEP");
            }
        } catch (Throwable error) {
            data.fail(error); if (diagnosticOwner != null) diagnosticOwner.close(); GameJoins.shutdown(); io.p2wlan.mc.game.client.ClientPlatform.agent().close(); finish(client);
        }
    }
    private void checkLogo(Screen screen) {
        var logo=button(screen,"P2WLAN");
        data.check(logo instanceof io.p2wlan.mc.game.client.screen.LogoButton && logo.getWidth()==20 && logo.getHeight()==20
                && buttons(screen).stream().filter(other->other!=logo).anyMatch(other->other.getY()==logo.getY()
                    && (other.getX()+other.getWidth()+4==logo.getX() || logo.getX()+24==other.getX()))
                && buttons(screen).stream().filter(other->other!=logo).noneMatch(other->logo.getX()<other.getX()+other.getWidth()
                    && logo.getX()+20>other.getX() && logo.getY()<other.getY()+other.getHeight() && logo.getY()+20>other.getY()),"ADJACENT_SQUARE_LOGO");
    }
    private Button member(Screen screen) {
        return buttons(screen).stream().filter(value -> value.active && !value.getMessage().getString().isBlank() && peer.startsWith(value.getMessage().getString())).findFirst().orElse(null);
    }
    private void finish(Minecraft client) {
        finished = true;
        boolean clean = helper != null && !ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false);
        try { data.result(clean, client.level == null && client.getConnection() == null, joins, received); }
        catch (Exception ignored) { /* Missing result fails the runner closed. */ }
        client.stop();
    }
}
