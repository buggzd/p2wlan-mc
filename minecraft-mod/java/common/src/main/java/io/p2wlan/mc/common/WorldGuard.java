package io.p2wlan.mc.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static io.p2wlan.mc.common.WorldGuardException.Code;

/**
 * One Minecraft world lifetime, loopback capability and selector owner.
 * No Minecraft/loader classes or P2WLAN peer/path/session state live here.
 * The adapter must revoke on its game thread BEFORE closing/replacing the world.
 */
public final class WorldGuard implements AutoCloseable {
    public static final int MAX_CONNECTIONS = 32;
    public static final int BUFFER_BYTES = 8192;
    public static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);
    private static final long CLOSE_MILLIS = 2000;

    public record Status(String owner, boolean running, boolean terminated, int connections,
                         long forwardedBytes, long failures, Code lastFailure) {}
    private enum Phase { PRELUDE, CONNECTING, READY, STREAMING }
    private final String owner;
    private final InetSocketAddress target;
    private final LocalEndpoint endpoint;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final Thread worker;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicReference<Status> published;
    // Mutated only by the selector owner. close/revoke never touches this set.
    private final Set<Link> links = new HashSet<>();
    private long forwarded;
    private long failures;
    private Code lastFailure;

    private WorldGuard(InetSocketAddress target, Selector selector, ServerSocketChannel listener)
            throws IOException {
        this.target = target;
        this.selector = selector;
        this.listener = listener;
        SecureRandom random = new SecureRandom();
        byte[] identity = new byte[16];
        random.nextBytes(identity);
        owner = HexFormat.of().formatHex(identity);
        endpoint = LocalEndpoint.generate((InetSocketAddress) listener.getLocalAddress(), random);
        published = new AtomicReference<>(new Status(owner, true, false, 0, 0, 0, null));
        worker = new Thread(this::run, "p2wlan-world-guard");
        worker.setDaemon(true);
    }
    public static WorldGuard open(InetSocketAddress target) throws WorldGuardException {
        return openAt(target, 0);
    }
    static WorldGuard openAt(InetSocketAddress target, int port) throws WorldGuardException {
        LocalEndpoint.validateAddress(target);
        if (port < 0 || port > 65535) throw new WorldGuardException(Code.INVALID_TARGET);
        Selector selector = null;
        ServerSocketChannel listener = null;
        try {
            selector = Selector.open();
            listener = ServerSocketChannel.open();
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress("127.0.0.1", port), MAX_CONNECTIONS);
            listener.register(selector, SelectionKey.OP_ACCEPT);
            WorldGuard guard = new WorldGuard(target, selector, listener);
            guard.worker.start();
            return guard;
        } catch (IOException | RuntimeException error) {
            closeResource(listener);
            closeResource(selector);
            throw new WorldGuardException(Code.IO, error);
        }
    }
    public String owner() { return owner; }
    public LocalEndpoint endpoint() { return endpoint; }
    public boolean isCurrent(String expectedOwner) { return owner.equals(expectedOwner) && live.get(); }
    public Status status() {
        Status snapshot = published.get();
        return new Status(owner, live.get(), snapshot.terminated(), snapshot.connections(),
                snapshot.forwardedBytes(), snapshot.failures(), snapshot.lastFailure());
    }
    /** Nonblocking world-thread revocation. Already buffered game bytes stay fenced. */
    public void revoke() {
        live.set(false);
        selector.wakeup();
    }
    @Override public void close() throws WorldGuardException {
        revoke();
        if (Thread.currentThread() == worker) return;
        try { worker.join(CLOSE_MILLIS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WorldGuardException(Code.INTERRUPTED, error);
        }
        if (worker.isAlive()) throw new WorldGuardException(Code.CLOSE_TIMEOUT);
    }
    private void current() throws WorldGuardException {
        if (!live.get()) throw new WorldGuardException(Code.WORLD_CLOSED);
    }
    private static long add(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
    private void failure(Code code) {
        if (code != Code.WORLD_CLOSED) {
            failures = add(failures, 1);
            lastFailure = code;
        }
    }
    private void publish(boolean terminated) {
        published.set(new Status(owner, live.get(), terminated, links.size(), forwarded, failures, lastFailure));
    }
    private void run() {
        try {
            while (live.get()) {
                selector.select(100);
                Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                while (selected.hasNext() && live.get()) {
                    SelectionKey key = selected.next();
                    selected.remove();
                    if (!key.isValid()) continue;
                    if (key.channel() == listener) { accept(); continue; }
                    Side side = (Side) key.attachment();
                    try {
                        if (key.isConnectable()) side.link.connected();
                        if (key.isValid() && key.isReadable()) side.link.read(side.front);
                        if (key.isValid() && key.isWritable()) side.link.write(side.front);
                        if (!side.link.closed) side.link.interests();
                    } catch (IOException | CancelledKeyException error) {
                        failure(error instanceof WorldGuardException guardError ? guardError.code() : Code.IO);
                        side.link.close();
                    }
                }
                long now = System.nanoTime();
                for (Link link : Set.copyOf(links)) {
                    if (link.phase != Phase.STREAMING && now - link.born >= HANDSHAKE_TIMEOUT.toNanos()) {
                        failure(Code.HANDSHAKE_TIMEOUT);
                        link.close();
                    } else if (now - link.progress >= IDLE_TIMEOUT.toNanos()) {
                        failure(Code.IDLE_TIMEOUT);
                        link.close();
                    }
                }
                publish(false);
            }
        } catch (IOException | RuntimeException error) { failure(Code.IO); }
        finally {
            live.set(false);
            for (Link link : Set.copyOf(links)) link.close();
            closeResource(listener);
            closeResource(selector);
            publish(true);
        }
    }
    private void accept() throws IOException {
        for (int index = 0; index < MAX_CONNECTIONS && live.get(); index++) {
            SocketChannel socket = listener.accept();
            if (socket == null) break;
            if (links.size() >= MAX_CONNECTIONS) {
                failure(Code.CAPACITY);
                closeResource(socket);
                continue;
            }
            try {
                current();
                socket.configureBlocking(false);
                socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
                Link link = new Link(socket);
                links.add(link);
            } catch (IOException | RuntimeException error) {
                closeResource(socket);
                throw error;
            }
        }
    }
    private static void closeResource(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) { /* Revocation has already fenced I/O. */ }
    }
    private final class Side {
        final Link link;
        final boolean front;
        Side(Link link, boolean front) { this.link = link; this.front = front; }
    }
    /** Owns only this pair's nonblocking forwarding buffers and local lifecycle. */
    private final class Link {
        final SocketChannel front;
        final SelectionKey frontKey;
        SocketChannel back;
        SelectionKey backKey;
        final ByteBuffer prelude = ByteBuffer.allocate(LocalEndpoint.PRELUDE_BYTES);
        final ByteBuffer ready = ByteBuffer.wrap(new byte[] {LocalEndpoint.READY});
        final ByteBuffer toBack = ByteBuffer.allocate(BUFFER_BYTES);
        final ByteBuffer toFront = ByteBuffer.allocate(BUFFER_BYTES);
        final long born = System.nanoTime();
        long progress = born;
        Phase phase = Phase.PRELUDE;
        boolean frontEof, backEof, frontShutdown, backShutdown, closed;
        Link(SocketChannel front) throws IOException {
            this.front = front;
            frontKey = front.register(selector, SelectionKey.OP_READ, new Side(this, true));
        }
        void read(boolean fromFront) throws IOException {
            current();
            if (phase == Phase.PRELUDE) {
                if (!fromFront) throw new WorldGuardException(Code.IO);
                int count = front.read(prelude);
                if (count < 0) { close(); return; }
                if (prelude.hasRemaining()) return;
                if (!endpoint.accepts(prelude.array())) throw new WorldGuardException(Code.KEY_MISMATCH);
                current();
                back = SocketChannel.open();
                back.configureBlocking(false);
                back.setOption(StandardSocketOptions.TCP_NODELAY, true);
                backKey = back.register(selector, SelectionKey.OP_CONNECT, new Side(this, false));
                phase = Phase.CONNECTING;
                current();
                if (back.connect(target)) connected();
                return;
            }
            if (phase != Phase.STREAMING) return;
            SocketChannel socket = fromFront ? front : back;
            ByteBuffer bytes = fromFront ? toBack : toFront;
            current();
            int count = socket.read(bytes);
            if (count < 0) {
                if (fromFront) frontEof = true; else backEof = true;
            } else if (count > 0) progress = System.nanoTime();
        }
        void connected() throws IOException {
            current();
            if (phase == Phase.CONNECTING && back.finishConnect()) phase = Phase.READY;
        }
        void write(boolean toFrontend) throws IOException {
            if (phase == Phase.READY && toFrontend) {
                current();
                front.write(ready);
                if (!ready.hasRemaining()) { phase = Phase.STREAMING; progress = System.nanoTime(); }
                return;
            }
            if (phase != Phase.STREAMING) return;
            SocketChannel socket = toFrontend ? front : back;
            ByteBuffer bytes = toFrontend ? toFront : toBack;
            bytes.flip();
            current(); // No await/callback/lock between this world fence and the actual write.
            int count = socket.write(bytes);
            bytes.compact();
            if (count > 0) { progress = System.nanoTime(); forwarded = add(forwarded, count); }
        }
        void interests() throws IOException {
            if (closed) return;
            current();
            if (phase == Phase.STREAMING) {
                if (frontEof && toBack.position() == 0 && !backShutdown) {
                    current(); back.shutdownOutput(); backShutdown = true;
                }
                if (backEof && toFront.position() == 0 && !frontShutdown) {
                    current(); front.shutdownOutput(); frontShutdown = true;
                }
                if (frontEof && backEof && toFront.position() == 0 && toBack.position() == 0) { close(); return; }
            }
            int frontOps = switch (phase) {
                case PRELUDE -> SelectionKey.OP_READ;
                case CONNECTING -> 0;
                case READY -> SelectionKey.OP_WRITE;
                case STREAMING -> (!frontEof && toBack.hasRemaining() ? SelectionKey.OP_READ : 0)
                        | (toFront.position() > 0 ? SelectionKey.OP_WRITE : 0);
            };
            frontKey.interestOps(frontOps);
            if (backKey != null) {
                int backOps = phase == Phase.CONNECTING ? SelectionKey.OP_CONNECT : phase == Phase.STREAMING
                        ? (!backEof && toFront.hasRemaining() ? SelectionKey.OP_READ : 0)
                          | (toBack.position() > 0 ? SelectionKey.OP_WRITE : 0) : 0;
                backKey.interestOps(backOps);
            }
        }
        void close() {
            if (closed) return;
            closed = true;
            frontKey.cancel();
            if (backKey != null) backKey.cancel();
            closeResource(front);
            closeResource(back);
            links.remove(this);
        }
    }
}
