package io.p2wlan.mc.fabric.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.common.ScheduledTaskGate;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.client.connect.MinecraftConnect;
import io.p2wlan.mc.fabric.client.connect.FabricGameJoins;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.text.Text;
import io.p2wlan.mc.fabric.connect.ProxyPrelude;
import io.p2wlan.mc.fabric.connect.ScopedClientConnect;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.integrated.IntegratedServer;

/** Actual Minecraft/Fabric transformation and Vanilla outbound codecs only.
 * Own loopback sockets, one worker, one queued game-thread task, bounded waits.
 * No world/server, Control login, Minecraft authentication or EULA acceptance. */
public final class ClientHookFixture implements ClientModInitializer {
    private record Opened(ClientConnection connection, ChannelFuture socket, ProxyPrelude prelude, AtomicBoolean owner) {}
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final ScheduledTaskGate game = new ScheduledTaskGate(Duration.ofSeconds(3));
    private final Set<String> cases = new LinkedHashSet<>();
    private int checks;
    private void check(boolean value) { checks++; if (!value) throw new AssertionError("FIXTURE_CHECK_" + checks); }
    private <T> T onGame(MinecraftClient client, Callable<T> effect) throws Exception {
        var drained = new CompletableFuture<Void>();
        var result = game.submit(task -> client.execute(() -> { try { task.run(); } finally { drained.complete(null); } }), live::get, effect);
        try { return result.get(4, TimeUnit.SECONDS); }
        finally { drained.get(4, TimeUnit.SECONDS); }
    }
    private static ServerSocket listener() throws IOException {
        var socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 1);
        socket.setSoTimeout(3000); return socket;
    }
    private static Socket accept(ServerSocket listener) throws IOException { Socket socket = listener.accept(); socket.setSoTimeout(3000); return socket; }
    private static LocalEndpoint endpoint(ServerSocket listener) throws Exception {
        return LocalEndpoint.from((InetSocketAddress) listener.getLocalSocketAddress(), "ab".repeat(32));
    }
    private Opened open(MinecraftClient client, LocalEndpoint endpoint, boolean protectedJoin) throws Exception {
        return onGame(client, () -> {
            var connection = new ClientConnection(NetworkSide.CLIENTBOUND);
            var owner = new AtomicBoolean(true);
            ProxyPrelude prelude = protectedJoin ? new ProxyPrelude(endpoint, () -> live.get() && owner.get()) : null;
            var socket = protectedJoin ? MinecraftConnect.open(connection, endpoint, prelude) : ClientConnection.connect(endpoint.address(), false, connection);
            return new Opened(connection, socket, prelude, owner);
        });
    }
    private static int varInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int part = input.readUnsignedByte();
            if (i == 4 && (part & 0xf0) != 0) throw new IOException("FIXTURE_VARINT");
            value |= (part & 0x7f) << (i * 7);
            if ((part & 0x80) == 0) return value;
        }
        throw new IOException("FIXTURE_VARINT");
    }
    private static DataInputStream frame(Socket socket) throws IOException {
        var input = new DataInputStream(socket.getInputStream());
        int length = varInt(input);
        if (length < 1 || length > 4096) throw new IOException("FIXTURE_FRAME_LIMIT");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("FIXTURE_FRAME_EOF");
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
    private static String string(DataInputStream input, int maximum) throws IOException {
        int size = varInt(input);
        if (size < 0 || size > maximum) throw new IOException("FIXTURE_STRING_LIMIT");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new IOException("FIXTURE_STRING_EOF");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private void vanillaLogin(MinecraftClient client, Opened opened, Socket receiver, LocalEndpoint endpoint) throws Exception {
        onGame(client, () -> {
            check(opened.connection().isOpen());
            check(opened.socket().channel().pipeline().get("packet_handler") == opened.connection());
            check((opened.socket().channel().pipeline().get("p2wlan-join-preface") != null) == (opened.prelude() != null));
            MinecraftConnect.login(client, opened.connection(), endpoint, client.currentScreen, text -> {}); return true;
        });
        var handshake = frame(receiver);
        check(varInt(handshake) == 0);
        check(varInt(handshake) == SharedConstants.getGameVersion().getProtocolVersion());
        check(string(handshake, 1024).equals("127.0.0.1"));
        check(handshake.readUnsignedShort() == endpoint.address().getPort());
        check(varInt(handshake) == 2 && handshake.available() == 0);
        var hello = frame(receiver);
        check(varInt(hello) == 0);
        check(string(hello, 64).equals(client.getSession().getUsername()));
        check(new UUID(hello.readLong(), hello.readLong()).equals(client.getSession().getUuidOrNull()));
        check(hello.available() == 0);
    }
    private void close(Opened opened) throws Exception {
        opened.owner().set(false);
        if (opened.prelude() != null) opened.prelude().revoke();
        opened.socket().channel().close().get(3, TimeUnit.SECONDS);
        check(opened.socket().channel().closeFuture().isDone());
    }
    private void ownedLogin(MinecraftClient client) throws Exception {
        try (var server = listener()) {
            var endpoint = endpoint(server); Opened opened = open(client, endpoint, true);
            try (var receiver = accept(server)) {
                opened.socket().get(3, TimeUnit.SECONDS); opened.prelude().handedOff().get(3, TimeUnit.SECONDS);
                check(Arrays.equals(receiver.getInputStream().readNBytes(40), endpoint.prelude()));
                // Receiver sends no local ACK. The first game bytes must remain
                // a real Vanilla handshake followed by the real LoginHello.
                vanillaLogin(client, opened, receiver, endpoint);
            } finally { close(opened); }
        }
        cases.add("owned_login");
    }
    private void ordinaryLogin(MinecraftClient client) throws Exception {
        try (var server = listener()) {
            var endpoint = endpoint(server); Opened opened = open(client, endpoint, false);
            try (var receiver = accept(server)) {
                opened.socket().get(3, TimeUnit.SECONDS); vanillaLogin(client, opened, receiver, endpoint);
            } finally { close(opened); }
        }
        cases.add("ordinary_login");
    }
    private void mismatch(MinecraftClient client) throws Exception {
        try (var server = listener(); var other = listener()) {
            var endpoint = endpoint(server); var alternate = endpoint(other);
            onGame(client, () -> {
                var connection = new ClientConnection(NetworkSide.CLIENTBOUND);
                var foreign = new ClientConnection(NetworkSide.CLIENTBOUND);
                try {
                    ScopedClientConnect.open(connection, endpoint, new ProxyPrelude(endpoint, live::get), () -> ClientConnection.connect(endpoint.address(), false, foreign));
                    throw new AssertionError("FIXTURE_FOREIGN_CONNECTION");
                } catch (ScopedClientConnect.FailureException error) { check(error.reason() == ScopedClientConnect.Failure.CONNECTION_CHANGED); }
                try {
                    ScopedClientConnect.open(connection, endpoint, new ProxyPrelude(endpoint, live::get), () -> ClientConnection.connect(alternate.address(), false, connection));
                    throw new AssertionError("FIXTURE_FOREIGN_TARGET");
                } catch (ScopedClientConnect.FailureException error) { check(error.reason() == ScopedClientConnect.Failure.TARGET_MISMATCH); }
                return true;
            });
        }
        cases.add("scope_mismatch");
    }
    private void cancellation(MinecraftClient client) throws Exception {
        try (var server = listener()) {
            var endpoint = endpoint(server);
            onGame(client, () -> {
                var prelude = new ProxyPrelude(endpoint, () -> false);
                try { MinecraftConnect.open(new ClientConnection(NetworkSide.CLIENTBOUND), endpoint, prelude); throw new AssertionError("FIXTURE_RETIRED_CONNECT"); }
                catch (ScopedClientConnect.FailureException error) { check(error.reason() == ScopedClientConnect.Failure.ATTEMPT_CHANGED); }
                return true;
            });
            Opened old = open(client, endpoint, true);
            try (var receiver = accept(server)) {
                old.prelude().handedOff().get(3, TimeUnit.SECONDS);
                check(receiver.getInputStream().readNBytes(40).length == 40);
                close(old); check(!old.prelude().isCurrent());
            }
            // A retired prelude must not close the replacement real channel.
            Opened replacement = open(client, endpoint, true);
            try (var receiver = accept(server)) {
                replacement.prelude().handedOff().get(3, TimeUnit.SECONDS);
                check(Arrays.equals(receiver.getInputStream().readNBytes(40), endpoint.prelude()));
                old.prelude().revoke();
                vanillaLogin(client, replacement, receiver, endpoint);
                check(replacement.connection().isOpen());
            } finally { close(replacement); }
        }
        cases.add("cancellation");
    }
    private void retirementPolicy(MinecraftClient client) throws Exception {
        onGame(client, () -> {
            var owned = new ClientConnection(NetworkSide.CLIENTBOUND);
            var foreign = new ClientConnection(NetworkSide.CLIENTBOUND);
            var title = new TitleScreen();
            // Construct the actual Vanilla presentation only; do not call its
            // connect factory, resolve a host or start another network worker.
            var constructor = ConnectScreen.class.getDeclaredConstructor(Screen.class, Text.class);
            constructor.setAccessible(true);
            var vanilla = constructor.newInstance(title, Text.literal("Fixture"));
            check(!FabricGameJoins.otherClientIntent(owned, null, title));
            check(!FabricGameJoins.otherClientIntent(owned, owned, title));
            check(FabricGameJoins.otherClientIntent(owned, foreign, null));
            check(FabricGameJoins.otherClientIntent(null, foreign, title));
            check(FabricGameJoins.otherClientIntent(owned, null, vanilla));
            check(FabricGameJoins.otherClientIntent(owned, owned, vanilla));
            return true;
        });
        cases.add("retirement_policy");
    }
    private void run(MinecraftClient client) {
        String status = "fail", reason = "FIXTURE_FAILED";
        boolean helperCleanup = false;
        AgentClient helper = null;
        try {
            helper = FabricP2wlan.AGENT.ready().get(40, TimeUnit.SECONDS);
            helper.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS);
            check(onGame(client, () -> client.getServer() == null && client.world == null));
            check(MinecraftConnect.supported()); check(FabricWorlds.transportSupported());
            check(FabricWorlds.IntegratedHooks.class.isAssignableFrom(IntegratedServer.class));
            cases.add("transformed_hooks");
            ownedLogin(client); ordinaryLogin(client); mismatch(client); cancellation(client); retirementPolicy(client);
            check(onGame(client, () -> client.getServer() == null && client.world == null));
            FabricP2wlan.AGENT.close(); FabricP2wlan.AGENT.stopped().get(20, TimeUnit.SECONDS);
            check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false));
            check(FabricP2wlan.AGENT.current().isEmpty()); helperCleanup = true; cases.add("helper_cleanup");
            status = "pass"; reason = "OK";
        } catch (Throwable failure) { reason = failure instanceof AssertionError ? "FIXTURE_ASSERTION" : "FIXTURE_EXECUTION"; }
        finally {
            FabricP2wlan.AGENT.close();
            try { FabricP2wlan.AGENT.stopped().get(20, TimeUnit.SECONDS); helperCleanup = helper == null || !ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false); }
            catch (Exception failedCleanup) { status = "fail"; reason = "FIXTURE_CLEANUP"; }
            live.set(false); game.close();
            try {
                var result = new JsonObject(); result.addProperty("schema", 1); result.addProperty("run", System.getProperty("p2wlan.fixture.run"));
                result.addProperty("status", status); result.addProperty("reason", reason); result.addProperty("checks", checks);
                result.addProperty("minecraft", "1.21.1"); result.addProperty("helper_cleanup", helperCleanup); result.addProperty("eula_accepted", false);
                var finished = new JsonArray(); cases.forEach(finished::add); result.add("cases", finished);
                Path target = Path.of(System.getProperty("p2wlan.fixture.result")); Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(temporary, result.toString(), StandardCharsets.UTF_8); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception resultFailure) { /* Missing structured output fails the outer verifier closed. */ }
            client.execute(client::scheduleStop);
        }
    }
    @Override public void onInitializeClient() {
        String identity = System.getProperty("p2wlan.fixture.run", "");
        if (!identity.matches("[a-f0-9]{32}") || System.getProperty("p2wlan.fixture.result") == null) throw new IllegalArgumentException("FIXTURE_ARGUMENTS");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Thread worker = new Thread(() -> run(client), "p2wlan-client-hook-fixture"); worker.setDaemon(true); worker.start();
        });
    }
}
