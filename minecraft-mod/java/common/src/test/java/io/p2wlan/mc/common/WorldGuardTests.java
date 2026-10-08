package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static io.p2wlan.mc.common.WorldGuardException.Code;

/** Actual loopback/world socket tests. No Minecraft completion claim. */
public final class WorldGuardTests {
    private static int checks;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("world guard check " + checks);
        checks++;
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("world guard event deadline");
            Thread.sleep(1);
        }
    }
    private static Socket connect(LocalEndpoint endpoint) throws IOException {
        Socket socket = new Socket();
        socket.connect(endpoint.address(), 2000);
        socket.setSoTimeout(4000);
        return socket;
    }
    private static void authorize(Socket socket, LocalEndpoint endpoint) throws Exception {
        socket.getOutputStream().write(endpoint.prelude());
        check(socket.getInputStream().read() == LocalEndpoint.READY);
    }
    private static void eof(Socket socket) throws IOException {
        try { check(socket.getInputStream().read() == -1); }
        catch (java.net.SocketException reset) { checks++; }
    }
    private static final class Game implements AutoCloseable {
        final ServerSocket listener = new ServerSocket();
        final CountDownLatch accepted = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicReference<Socket> game = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final Thread worker;
        Game() throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            worker = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    game.set(socket);
                    accepted.countDown();
                    socket.setSoTimeout(5000);
                    byte[] bytes = new byte[8192];
                    while (true) {
                        int count = socket.getInputStream().read(bytes);
                        if (count < 0) break;
                        if (received.size() + count > 256_000) throw new AssertionError("fixture byte budget");
                        received.write(bytes, 0, count);
                        socket.getOutputStream().write(bytes, 0, count);
                    }
                    socket.shutdownOutput();
                } catch (Throwable failure) { if (!listener.isClosed()) error.set(failure); }
                finally { ended.countDown(); }
            }, "p2wlan-test-world");
            worker.setDaemon(true);
            worker.start();
        }
        InetSocketAddress address() { return (InetSocketAddress) listener.getLocalSocketAddress(); }
        void verifyEnd() throws Exception {
            check(ended.await(3, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError("game fixture failed", error.get());
        }
        @Override public void close() throws Exception {
            listener.close();
            Socket socket = game.get();
            if (socket != null) socket.close();
            worker.join(2000);
            check(!worker.isAlive());
        }
    }
    private static void invalidInputs() throws Exception {
        for (InetSocketAddress invalid : new InetSocketAddress[] {
                null, new InetSocketAddress("127.0.0.1", 0),
                new InetSocketAddress("192.0.2.1", 25565),
                InetSocketAddress.createUnresolved("example.com", 25565)}) {
            try { WorldGuard.open(invalid); throw new AssertionError("accepted invalid target"); }
            catch (WorldGuardException failure) { check(failure.code() == Code.INVALID_TARGET); }
        }
        try { LocalEndpoint.from(new InetSocketAddress("127.0.0.1", 25565), "not-a-key"); throw new AssertionError("accepted invalid key"); }
        catch (WorldGuardException failure) { check(failure.code() == Code.KEY_MISMATCH); }
    }
    private static void transferAndHalfClose() throws Exception {
        try (Game game = new Game(); WorldGuard guard = WorldGuard.open(game.address()); Socket client = connect(guard.endpoint())) {
            check(guard.isCurrent(guard.owner()));
            check(!guard.isCurrent("foreign-owner"));
            check(!guard.endpoint().toString().contains(guard.endpoint().keyHex()));
            LocalEndpoint endpoint = guard.endpoint();
            byte[] prelude = endpoint.prelude();
            check(LocalEndpoint.from(endpoint.address(), endpoint.keyHex()).accepts(prelude));
            prelude[0] = 0;
            check(!endpoint.accepts(prelude));
            prelude = endpoint.prelude();
            client.getOutputStream().write(prelude, 0, 7);
            check(!game.accepted.await(50, TimeUnit.MILLISECONDS));
            byte[] payload = new byte[128_000];
            for (int index = 0; index < payload.length; index++) payload[index] = (byte) (index % 251);
            byte[] coalesced = new byte[prelude.length - 7 + 1024];
            System.arraycopy(prelude, 7, coalesced, 0, prelude.length - 7);
            System.arraycopy(payload, 0, coalesced, prelude.length - 7, 1024);
            client.getOutputStream().write(coalesced);
            check(client.getInputStream().read() == LocalEndpoint.READY);
            client.getOutputStream().write(payload, 1024, payload.length - 1024);
            client.shutdownOutput();
            byte[] echoed = client.getInputStream().readNBytes(payload.length + 1);
            check(Arrays.equals(echoed, payload));
            game.verifyEnd();
            check(Arrays.equals(game.received.toByteArray(), payload));
            until(() -> guard.status().connections() == 0);
            check(guard.status().forwardedBytes() == 2L * payload.length);
            check(guard.status().failures() == 0);
        }
    }
    private static void stoppedWorldAndPortReuse() throws Exception {
        LocalEndpoint oldEndpoint;
        String oldOwner;
        try (Game first = new Game(); WorldGuard guard = WorldGuard.open(first.address()); Socket client = connect(guard.endpoint())) {
            oldEndpoint = guard.endpoint();
            oldOwner = guard.owner();
            authorize(client, oldEndpoint);
            client.getOutputStream().write(new byte[] {11, 12, 13});
            check(Arrays.equals(client.getInputStream().readNBytes(3), new byte[] {11, 12, 13}));
            guard.revoke();
            check(!guard.isCurrent(oldOwner));
            try { client.getOutputStream().write(new byte[] {21, 22, 23}); } catch (IOException closed) { /* permitted closure */ }
            guard.close();
            check(guard.status().terminated());
            first.verifyEnd();
            check(Arrays.equals(first.received.toByteArray(), new byte[] {11, 12, 13}));
        }
        try (Game replacement = new Game(); WorldGuard guard = WorldGuard.openAt(replacement.address(), oldEndpoint.address().getPort())) {
            check(guard.endpoint().address().equals(oldEndpoint.address()));
            check(!guard.endpoint().keyHex().equals(oldEndpoint.keyHex()));
            check(!guard.isCurrent(oldOwner));
            try (Socket stale = connect(oldEndpoint)) {
                stale.getOutputStream().write(oldEndpoint.prelude());
                eof(stale);
            }
            until(() -> guard.status().lastFailure() == Code.KEY_MISMATCH);
            check(replacement.accepted.getCount() == 1);
            try (Socket current = connect(guard.endpoint())) {
                authorize(current, guard.endpoint());
                current.getOutputStream().write(new byte[] {31});
                current.shutdownOutput();
                check(Arrays.equals(current.getInputStream().readNBytes(2), new byte[] {31}));
            }
            replacement.verifyEnd();
            check(Arrays.equals(replacement.received.toByteArray(), new byte[] {31}));
        }
    }
    private static void capacityAndDeadline() throws Exception {
        try (Game game = new Game(); WorldGuard guard = WorldGuard.open(game.address())) {
            List<Socket> clients = new ArrayList<>();
            try {
                for (int index = 0; index < WorldGuard.MAX_CONNECTIONS; index++) clients.add(connect(guard.endpoint()));
                until(() -> guard.status().connections() == WorldGuard.MAX_CONNECTIONS);
                try (Socket extra = connect(guard.endpoint())) { eof(extra); }
                until(() -> guard.status().lastFailure() == Code.CAPACITY);
                check(guard.status().connections() == WorldGuard.MAX_CONNECTIONS);
                check(game.accepted.getCount() == 1);
                for (Socket client : clients) client.getOutputStream().write(new byte[] {'P'});
                for (Socket client : clients) eof(client);
                until(() -> guard.status().connections() == 0);
                check(guard.status().lastFailure() == Code.HANDSHAKE_TIMEOUT);
                check(game.accepted.getCount() == 1);
            } finally { for (Socket client : clients) client.close(); }
        }
    }
    private static void targetFailureCannotProduceReady() throws Exception {
        InetSocketAddress unused;
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress("127.0.0.1", 0));
            unused = (InetSocketAddress) reservation.getLocalSocketAddress();
        }
        try (WorldGuard guard = WorldGuard.open(unused); Socket client = connect(guard.endpoint())) {
            client.getOutputStream().write(guard.endpoint().prelude());
            eof(client);
            until(() -> guard.status().lastFailure() == Code.IO);
            check(guard.status().connections() == 0);
        }
    }
    private static void protectedTarget() throws Exception {
        try (Game game = new Game(); WorldGuard target = WorldGuard.open(game.address());
             WorldGuard guard = WorldGuard.openProtected(target.endpoint()); Socket client = connect(guard.endpoint())) {
            authorize(client, guard.endpoint());
            byte[] payload = new byte[] {3, 5, 7, 11};
            client.getOutputStream().write(payload);
            check(Arrays.equals(payload, client.getInputStream().readNBytes(payload.length)));
            client.shutdownOutput(); eof(client); game.verifyEnd();
            check(Arrays.equals(payload, game.received.toByteArray()));
        }
        try (Game game = new Game(); WorldGuard target = WorldGuard.open(game.address());
             WorldGuard guard = WorldGuard.openProtected(LocalEndpoint.from(target.endpoint().address(), "0".repeat(64)));
             Socket client = connect(guard.endpoint())) {
            client.getOutputStream().write(guard.endpoint().prelude()); eof(client);
            until(() -> guard.status().lastFailure() == Code.TARGET_REJECTED);
            check(game.accepted.getCount() == 1);
        }
        try { WorldGuard.openProtected(null); throw new AssertionError("null target admitted"); }
        catch (WorldGuardException error) { check(error.code() == Code.INVALID_TARGET); }
    }
    private static void nativeInterop(String binary) throws Exception {
        try (Game game = new Game(); WorldGuard guard = WorldGuard.open(game.address())) {
            Process peer = new ProcessBuilder(binary).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Thread watchdog = new Thread(() -> {
                try { if (!peer.waitFor(10, TimeUnit.SECONDS)) peer.destroyForcibly(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "p2wlan-test-guard-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                JsonObject request = new JsonObject();
                request.addProperty("port", guard.endpoint().address().getPort());
                request.addProperty("key", guard.endpoint().keyHex());
                FrameCodec.write(new DataOutputStream(peer.getOutputStream()), request);
                peer.getOutputStream().close();
                JsonObject reply = FrameCodec.read(new DataInputStream(peer.getInputStream()));
                check(reply != null && reply.get("ok").getAsBoolean() && reply.get("bytes").getAsInt() == 128_000);
                check(peer.waitFor(2, TimeUnit.SECONDS) && peer.exitValue() == 0);
                game.verifyEnd();
                check(game.received.size() == 128_000);
            } finally {
                peer.destroyForcibly();
                peer.waitFor(2, TimeUnit.SECONDS);
                watchdog.interrupt();
                watchdog.join(2000);
                check(!watchdog.isAlive());
            }
        }
    }
    public static void main(String[] args) throws Exception {
        invalidInputs();
        transferAndHalfClose();
        stoppedWorldAndPortReuse();
        capacityAndDeadline();
        targetFailureCannotProduceReady();
        protectedTarget();
        if (args.length == 1) nativeInterop(args[0]);
        else if (args.length != 0) throw new IllegalArgumentException("unexpected fixture arguments");
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().equals("p2wlan-world-guard")));
        System.out.println("PASS world guard: " + checks + " explicit checks against actual local sockets" + (args.length == 1 ? " and native peer" : ""));
    }
}
