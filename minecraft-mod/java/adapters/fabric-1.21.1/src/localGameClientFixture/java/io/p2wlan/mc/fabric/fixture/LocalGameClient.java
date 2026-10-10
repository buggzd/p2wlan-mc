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
    private int sent, received, joins, peerQueries;
    private long nextQuery, nextRound, worldTime;
    private boolean finished;
    @Override public void onInitializeClient() {
        try { data = new GameFixtureData("client"); }
        catch (Exception error) { throw new IllegalArgumentException("LOCAL_GAME_FIXTURE_CONFIG"); }
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> data.advance("AGENT", 45));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
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
                    if (!FabricP2wlan.AGENT.ready().isDone() || !(client.currentScreen instanceof TitleScreen)) return;
                    helper = FabricP2wlan.AGENT.ready().join();
                    data.check(FabricP2wlan.AGENT.isCurrent(helper), "HELPER_OWNER");
                    var ready = JsonParser.parseString(Files.readString(data.root.resolve("server-ready.json"))).getAsJsonObject();
                    data.check(ready.keySet().equals(Set.of("schema", "run", "peer")) && ready.get("run").getAsString().equals(data.run), "READY_RUN");
                    peer = ready.get("peer").getAsString(); press(client.currentScreen, "P2WLAN"); data.advance("FORM", 10);
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
                    if (!feedback(client.currentScreen).startsWith("注册成功") || !enabled(client.currentScreen, "我的房间")) return;
                    data.check(helper.snapshot().map(value -> AccountPresentation.state(value.data().get("account")) == AccountPresentation.State.SAVED_SESSION).orElse(false), "REGISTER_ACCOUNT");
                    fields(client.currentScreen).get(2).setText(data.password); client.currentScreen.tick();
                    data.check(!enabled(client.currentScreen, "注册") && enabled(client.currentScreen, "登录"), "SAVED_ACCOUNT_BUTTONS");
                    press(client.currentScreen, "登录"); data.advance("LOGIN", 15);
                }
                case "LOGIN" -> {
                    if (!feedback(client.currentScreen).startsWith("登录成功") || !enabled(client.currentScreen, "我的房间")) return;
                    data.check(fields(client.currentScreen).get(2).getText().isEmpty(), "LOGIN_PASSWORD_CLEAR");
                    data.cases.add("register_login"); press(client.currentScreen, "我的房间"); data.advance("ROOMS", 15);
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
                    if (!screen(client, "RoomsScreen") || !enabled(client.currentScreen, data.room)) return;
                    press(client.currentScreen, data.room); client.currentScreen.tick(); press(client.currentScreen, "连接选中房间");
                    data.cases.add("room_join"); data.advance("RUNTIME", 40);
                }
                case "RUNTIME" -> {
                    if (!screen(client, "RuntimeScreen")) return;
                    var selected = RuntimeView.ready(helper); if (selected.isEmpty()) return;
                    runtime = selected.get();
                    if (member(client.currentScreen) == null) return;
                    data.check(FabricP2wlan.AGENT.isCurrent(helper) && runtime.isCurrent(helper), "RUNTIME_OWNER");
                    data.advance("PEER", 20);
                }
                case "PEER" -> {
                    if (pending != null && pending.isDone()) {
                        try {
                            var reply = pending.join();
                            if (!helper.isCurrent(reply) || !runtime.runtime().equals(RuntimeView.text(reply.data(), "runtime")) || !peer.equals(RuntimeView.text(reply.data(), "peer"))) throw new IllegalStateException("PEER_OWNER");
                            var view = reply.data().getAsJsonObject("view");
                            if (view.get("online").getAsBoolean() && ("relay".equals(RuntimeView.text(view, "path")) || "direct".equals(RuntimeView.text(view, "path")) && view.get("direct_budget_ready").getAsBoolean())) {
                                var member = member(client.currentScreen); if (member == null) throw new IllegalStateException("MEMBER_CHANGED");
                                data.check(runtime.isCurrent(helper) && helper.isCurrent(reply), "PEER_READY_OWNER");
                                data.cases.add("runtime_peer"); member.onPress(); pending = null; data.advance("DISCOVERY", 20); return;
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
                case "DISCOVERY" -> {
                    if (!screen(client, "ServicesScreen") || !enabled(client.currentScreen, "加入 / Join")) return;
                    press(client.currentScreen, "加入 / Join"); data.cases.add("discovery"); data.advance("JOIN", 40);
                }
                case "JOIN" -> {
                    if (client.world == null || client.player == null || client.getNetworkHandler() == null) return;
                    data.check(client.getServer() == null && runtime.isCurrent(helper) && client.getNetworkHandler().getConnection().isOpen(), "VANILLA_PLAY");
                    worldTime = client.world.getTime(); joins++; data.cases.add("vanilla_play"); data.advance("PLAY", 55);
                }
                case "PLAY" -> {
                    if (client.world == null || client.player == null || client.getNetworkHandler() == null || !runtime.isCurrent(helper)) throw new IllegalStateException("PLAY_OWNER");
                    if (received == GameFixtureData.ROUNDS && client.player.experienceLevel == received) {
                        data.check(client.world.getTime() - worldTime >= 350, "WORLD_TICK_PROGRESS");
                        data.cases.add("sustained_duplex"); client.world.disconnect(); client.disconnect(); client.setScreen(new TitleScreen());
                        FabricGameJoins.shutdown(); data.advance("DISCONNECT", 15); return;
                    }
                    if (received == sent && client.player.experienceLevel == sent && System.nanoTime() >= nextRound) {
                        sent++; client.getNetworkHandler().sendChatCommand("p2wlan-game-fixture " + data.run + " " + sent);
                        nextRound = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    }
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
                    data.cases.add("room_stop"); FabricP2wlan.AGENT.close(); data.advance("CLEANUP", 25);
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
