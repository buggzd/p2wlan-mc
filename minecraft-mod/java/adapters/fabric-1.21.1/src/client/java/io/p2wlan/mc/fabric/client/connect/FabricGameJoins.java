package io.p2wlan.mc.fabric.client.connect;

import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.GameService;
import io.p2wlan.mc.common.JoinProxy;
import io.p2wlan.mc.common.JoinProxyException;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.connect.ProxyPrelude;
import io.p2wlan.mc.fabric.connect.ScopedClientConnect;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.text.Text;

/** Sole local game-join lifetime. Native authorization/path truth is not stored.
 * One attempt and two one-shot completion cells; no executor/work queue/retry.
 * Only the game thread starts/login/ticks Minecraft connections. Netty close
 * immediately revokes the exact native intent without touching game state. */
public final class FabricGameJoins {
    public enum Phase { REGISTERING, CONNECTING, LOGIN, PLAY, CLOSING, CLOSED, FAILED }
    public record Status(Phase phase, String reason) {}
    private record Registration(LocalEndpoint endpoint, Throwable error) {}
    private record Handoff(Throwable error) {}
    public static final class Attempt {
        private final AgentClient agent;
        private final GameService service;
        private final Screen connectingScreen, parent;
        private final AtomicBoolean live = new AtomicBoolean(true);
        private final AtomicReference<Registration> registration = new AtomicReference<>();
        private final AtomicReference<Handoff> handoff = new AtomicReference<>();
        private final AtomicReference<Text> progress = new AtomicReference<>(Text.literal("P2WLAN: registering"));
        private final JoinProxy join;
        private volatile Phase phase = Phase.REGISTERING;
        private volatile String reason;
        private volatile ChannelFuture socket;
        private volatile ProxyPrelude prelude;
        private ClientConnection minecraft;
        private LocalEndpoint endpoint;
        private boolean loginStarted, disconnectionHandled;
        private Attempt(AgentClient agent, GameService service, Screen connectingScreen, Screen parent) throws JoinProxyException {
            this.agent = agent; this.service = service; this.connectingScreen = connectingScreen; this.parent = parent;
            // CURRENT is published before worker admission, not a second native runtime owner.
            CURRENT.set(this);
            try { join = JoinProxy.start(agent, service, this::isCurrent); }
            catch (JoinProxyException error) { live.set(false); CURRENT.compareAndSet(this, null); throw error; }
            join.ready().whenComplete((value, error) -> {
                if (isCurrent()) registration.compareAndSet(null, new Registration(value, error));
            });
        }
        public boolean isCurrent() { return live.get() && CURRENT.get() == this && FabricP2wlan.AGENT.isCurrent(agent) && service.runtime().isCurrent(agent); }
        public Status status() { return new Status(phase, reason); }
        public Text progress() { return progress.get(); }
        public boolean isReleased() { ChannelFuture value = socket; return join.isReleased() && (value == null || value.channel().closeFuture().isDone()); }
        private void fail(Throwable error) {
            error = io.p2wlan.mc.common.UiCompletionGate.unwrap(error);
            reason = error instanceof ProxyPrelude.FailureException failure ? failure.getMessage()
                : error instanceof ScopedClientConnect.FailureException failure ? failure.getMessage() : JoinProxy.reason(error);
            phase = Phase.FAILED; revoke();
        }
        private void tick(MinecraftClient client) {
            if (CURRENT.get() != this) return;
            if (!isCurrent()) {
                if (live.get()) { reason = "RUNTIME_CHANGED"; phase = Phase.FAILED; }
                revoke();
            }
            if (!live.get()) {
                // A runtime failure during Vanilla login/configuration must
                // finish the original handler too, not strand its pack screen.
                if (phase == Phase.FAILED && loginStarted) handlePrePlayClose(client);
                if (isReleased() && phase == Phase.CLOSING) phase = Phase.CLOSED;
                if (phase == Phase.FAILED && client.currentScreen == connectingScreen) progress.set(Text.literal(reason == null ? "JOIN_FAILED" : reason));
                return;
            }
            if ((phase == Phase.REGISTERING || phase == Phase.CONNECTING) && client.currentScreen != connectingScreen) { revoke(); return; }
            try {
                Registration ready = registration.getAndSet(null);
                if (ready != null) {
                    if (ready.error() != null) { fail(ready.error()); return; }
                    if (!isCurrent()) { revoke(); return; }
                    endpoint = ready.endpoint();
                    minecraft = new ClientConnection(NetworkSide.CLIENTBOUND);
                    prelude = new ProxyPrelude(endpoint, this::isCurrent);
                    phase = Phase.CONNECTING; progress.set(Text.literal("P2WLAN: connecting"));
                    socket = MinecraftConnect.open(minecraft, endpoint, prelude);
                    socket.channel().closeFuture().addListener(ignored -> {
                        // No game-state mutation or old-owner cleanup of a replacement.
                        join.revoke();
                    });
                    prelude.handedOff().whenComplete((ignored, error) -> {
                        if (isCurrent()) handoff.compareAndSet(null, new Handoff(error));
                    });
                }
                Handoff readySocket = handoff.getAndSet(null);
                if (readySocket != null) {
                    if (readySocket.error() != null) { fail(readySocket.error()); return; }
                    if (!isCurrent() || client.currentScreen != connectingScreen || !minecraft.isOpen()) { revoke(); return; }
                    phase = Phase.LOGIN; loginStarted = true;
                    MinecraftConnect.login(client, minecraft, endpoint, parent, progress::set);
                    endpoint = null; // Capability need not remain in game-thread presentation state.
                }
                if (phase == Phase.LOGIN || phase == Phase.PLAY) {
                    var handler = client.getNetworkHandler();
                    boolean vanillaOwnsTick = handler != null && handler.getConnection() == minecraft;
                    if (vanillaOwnsTick && client.world != null) phase = Phase.PLAY;
                    if (!minecraft.isOpen()) {
                        if (!vanillaOwnsTick) handlePrePlayClose(client);
                        revoke();
                    } else if (!vanillaOwnsTick) minecraft.tick(); // Login/configuration only; never double-tick play.
                }
                if (socket != null && socket.channel().closeFuture().isDone() && phase == Phase.CONNECTING) {
                    fail(new ScopedClientConnect.FailureException(ScopedClientConnect.Failure.IO));
                }
            } catch (Exception error) { fail(error); }
        }
        private void handlePrePlayClose(MinecraftClient client) {
            if (minecraft == null || minecraft.isOpen() || disconnectionHandled || CURRENT.get() != this) return;
            var handler = client.getNetworkHandler();
            if (handler != null) return; // Vanilla owns play or an unrelated connection.
            disconnectionHandled = true; minecraft.handleDisconnection();
        }
        /** Nonblocking; never waits for a Netty future on the game thread. */
        public void revoke() {
            if (!live.getAndSet(false)) return;
            if (phase != Phase.FAILED) phase = Phase.CLOSING;
            registration.set(null); handoff.set(null);
            ProxyPrelude value = prelude; if (value != null) value.revoke();
            ChannelFuture pending = socket; if (pending != null) pending.channel().close();
            join.revoke();
        }
    }
    private static final AtomicReference<Attempt> CURRENT = new AtomicReference<>();
    private FabricGameJoins() {}
    public static Attempt start(MinecraftClient client, GameService service, Screen screen, Screen parent) throws JoinProxyException {
        Attempt previous = CURRENT.get();
        if (previous != null && !previous.isReleased()) throw new JoinProxyException(JoinProxyException.Code.CAPACITY);
        if (client.world != null || client.getServer() != null || client.getNetworkHandler() != null) throw new JoinProxyException(JoinProxyException.Code.ATTEMPT_CHANGED);
        if (!MinecraftConnect.supported()) throw new JoinProxyException(JoinProxyException.Code.NATIVE_FAILED);
        AgentClient agent = FabricP2wlan.AGENT.current().orElse(null);
        return new Attempt(agent, service, screen, parent);
    }
    public static void tick(MinecraftClient client) { Attempt value = CURRENT.get(); if (value != null) value.tick(client); }
    public static void shutdown() { Attempt value = CURRENT.get(); if (value != null) value.revoke(); }
}
