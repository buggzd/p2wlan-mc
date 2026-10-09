package io.p2wlan.mc.fabric.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.common.ScheduledTaskGate;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.fabric.world.FabricWorlds;
import io.p2wlan.mc.fabric.world.LoopbackBind;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only actual dedicated world/bind/status verification, NOT remote login,
 * native room publication, Direct/Relay, gameplay or integrated-world hosting. */
public final class ServerBootFixture implements ModInitializer {
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger checks = new AtomicInteger();
    private final ScheduledTaskGate game = new ScheduledTaskGate(Duration.ofSeconds(3));
    private volatile FabricWorlds.World world;
    private volatile boolean helperCleaned;
    private volatile String reason = "FIXTURE_NOT_COMPLETED";
    private void check(boolean value) { if (!value) throw new AssertionError("SERVER_CHECK_" + checks.get()); checks.incrementAndGet(); }
    private <T> T onGame(MinecraftServer server, Callable<T> effect) throws Exception {
        var drained = new java.util.concurrent.CompletableFuture<Void>();
        var result = game.submit(task -> server.execute(() -> { try { task.run(); } finally { drained.complete(null); } }), live::get, effect);
        try { return result.get(4, TimeUnit.SECONDS); }
        finally { drained.get(4, TimeUnit.SECONDS); }
    }
    private static void varInt(DataOutputStream output, int value) throws Exception {
        do { int next = value & 127; value >>>= 7; output.writeByte(next | (value == 0 ? 0 : 128)); } while (value != 0);
    }
    private static int varInt(DataInputStream input) throws Exception {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            int value = input.readUnsignedByte();
            if (index == 4 && (value & 240) != 0) throw new IllegalArgumentException("STATUS_VARINT");
            result |= (value & 127) << (index * 7);
            if ((value & 128) == 0) return result;
        }
        throw new IllegalArgumentException("STATUS_VARINT");
    }
    private static byte[] query(int port) throws Exception {
        var handshake = new ByteArrayOutputStream();
        var body = new DataOutputStream(handshake);
        varInt(body, 0); varInt(body, SharedConstants.getGameVersion().getProtocolVersion());
        byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        varInt(body, host.length); body.write(host); body.writeShort(port); varInt(body, 1);
        var framed = new ByteArrayOutputStream(); var wire = new DataOutputStream(framed);
        varInt(wire, handshake.size()); wire.write(handshake.toByteArray()); wire.write(new byte[]{1, 0});
        return framed.toByteArray();
    }
    private void status(InetSocketAddress address, LocalEndpoint capability) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(address, 2000); socket.setSoTimeout(3000);
            var output = socket.getOutputStream();
            if (capability != null) output.write(capability.prelude());
            output.write(query(address.getPort())); output.flush();
            var input = new DataInputStream(socket.getInputStream());
            if (capability != null) check(input.readUnsignedByte() == LocalEndpoint.READY);
            int size = varInt(input);
            if (size < 2 || size > 65536) throw new IllegalArgumentException("STATUS_SIZE");
            // Frame input has a total deadline as well as socket-read deadlines.
            byte[] payload = new byte[size]; int count = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (count < size) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IllegalArgumentException("STATUS_DEADLINE");
                socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                int read = input.read(payload, count, size - count);
                if (read < 0) throw new IllegalArgumentException("STATUS_EOF");
                count += read;
            }
            var frame = new DataInputStream(new java.io.ByteArrayInputStream(payload));
            check(varInt(frame) == 0);
            int length = varInt(frame);
            if (length < 1 || length > frame.available()) throw new IllegalArgumentException("STATUS_JSON_SIZE");
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame.readNBytes(length))).toString();
            check(frame.available() == 0);
            JsonObject result = JsonParser.parseString(json).getAsJsonObject();
            check(result.getAsJsonObject("version").get("protocol").getAsInt() == SharedConstants.getGameVersion().getProtocolVersion());
            check(result.getAsJsonObject("players").get("online").getAsInt() == 0);
        }
    }
    private void run(MinecraftServer server) {
        LoopbackBind binding = null;
        AgentClient helper = null;
        try {
            world = onGame(server, () -> {
                var value = FabricWorlds.current(server);
                check(value != null && value.isCurrent()); check(server.getOverworld() != null);
                check(value.status() == null && !FabricWorlds.hosted(server));
                check(server.getNetworkIo() instanceof LoopbackBind.Hooks); check(LoopbackBind.supported());
                check(server.isOnlineMode()); check(server.isRunning());
                return value;
            });
            helper = FabricP2wlan.AGENT.ready().get(41, TimeUnit.SECONDS);
            check(FabricP2wlan.AGENT.isCurrent(helper));
            var ping = helper.request("ping", new JsonObject()).get(3, TimeUnit.SECONDS);
            check(helper.isCurrent(ping));
            var snapshot = helper.request("status", new JsonObject()).get(3, TimeUnit.SECONDS);
            check(helper.isCurrent(snapshot) && snapshot.data().get("phase").getAsString().equals("idle"));
            var capturedWorld = world;
            // Own the target before queueing open, including timeout/late-drain
            // failure. Cleanup stays off the server thread; its fence is exact.
            binding = new LoopbackBind(server.getNetworkIo(), () -> live.get() && capturedWorld.isCurrent());
            var target = binding;
            onGame(server, () -> { target.open(); return true; });
            var endpoint = binding.endpoint();
            check(endpoint.address().getAddress().isLoopbackAddress()); check(endpoint.address().getPort() != server.getServerPort());
            try (var socket = new Socket()) {
                socket.connect(endpoint.address(), 2000); socket.setSoTimeout(3000);
                byte[] wrong = endpoint.prelude(); wrong[39] ^= 1;
                socket.getOutputStream().write(wrong); socket.getOutputStream().flush();
                check(socket.getInputStream().read() == -1);
            }
            status(endpoint.address(), endpoint);
            binding.revoke(); binding.close(); binding = null;
            check(onGame(server, () -> world.isCurrent() && !FabricWorlds.hosted(server)));
            try (var socket = new Socket()) {
                boolean closed;
                try { socket.connect(endpoint.address(), 2000); closed = false; }
                catch (java.net.ConnectException expected) { closed = true; }
                check(closed);
            }
            status(new InetSocketAddress("127.0.0.1", server.getServerPort()), null);
            FabricP2wlan.AGENT.close(); FabricP2wlan.AGENT.stopped().get(20, TimeUnit.SECONDS);
            check(!ProcessHandle.of(helper.processId()).map(ProcessHandle::isAlive).orElse(false));
            check(FabricP2wlan.AGENT.current().isEmpty()); helperCleaned = true;
            reason = "OK";
        } catch (Throwable failure) { reason = failure instanceof AssertionError ? "FIXTURE_ASSERTION" : "FIXTURE_EXECUTION"; }
        finally {
            if (binding != null) { binding.revoke(); try { binding.close(); } catch (Exception error) { reason = "FIXTURE_BIND_CLEANUP"; } }
            FabricP2wlan.AGENT.close();
            try { FabricP2wlan.AGENT.stopped().get(20, TimeUnit.SECONDS); }
            catch (Exception error) { reason = "FIXTURE_HELPER_CLEANUP"; }
            live.set(false); game.close(); server.stop(false);
        }
    }
    private void stopped(MinecraftServer server) {
        try {
            check(world != null && !world.isCurrent()); check(FabricWorlds.current(server) == null);
            check(FabricP2wlan.AGENT.current().isEmpty());
        } catch (Throwable failure) { reason = "FIXTURE_WORLD_CLEANUP"; }
        try {
            JsonObject result = new JsonObject(); result.addProperty("schema", 1);
            result.addProperty("run", System.getProperty("p2wlan.fixture.run"));
            result.addProperty("status", reason.equals("OK") ? "pass" : "fail"); result.addProperty("reason", reason);
            result.addProperty("checks", checks.get()); result.addProperty("minecraft", "1.21.1");
            result.addProperty("helper_cleanup", helperCleaned); result.addProperty("fixture_wrote_eula", false);
            JsonArray cases = new JsonArray();
            for (String value : new String[]{"world_bootstrap", "transformed_bind", "protected_status", "ordinary_status", "revocation", "helper_cleanup", "world_cleanup"}) cases.add(value);
            result.add("cases", cases);
            Path target = Path.of(System.getProperty("p2wlan.fixture.result"));
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, result.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception error) { /* Missing structured output fails verification closed. */ }
    }
    @Override public void onInitialize() {
        if (!System.getProperty("p2wlan.fixture.run", "").matches("[a-f0-9]{32}") || System.getProperty("p2wlan.fixture.result") == null) {
            throw new IllegalArgumentException("FIXTURE_ARGUMENTS");
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("FIXTURE_DUPLICATE_WORLD");
            Thread worker = new Thread(() -> run(server), "p2wlan-dedicated-boot-fixture"); worker.setDaemon(true); worker.start();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(this::stopped);
    }
}
