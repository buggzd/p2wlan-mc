package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One game-join intent/registration lifetime, not a networking state owner.
 * One JVM slot/worker, no queue/replay. IPC <=5s, revocation poll <=100ms, child
 * reap <=2s. A late reply is retained for exact cleanup even after cancellation.
 * ready proves a local capability exists, NOT remote game login or availability. */
public final class JoinProxy implements AutoCloseable {
    public enum Phase { STARTING, REGISTERED, CLOSING, CLOSED, FAILED }
    public record Status(Phase phase, String reason) {}
    private static final Semaphore SLOTS = new Semaphore(1);
    private final AgentClient client;
    private final GameService service;
    private final BooleanSupplier parent;
    private final String attempt = UUID.randomUUID().toString().replace("-", "");
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final CountDownLatch stop = new CountDownLatch(1);
    private final CompletableFuture<LocalEndpoint> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> released = new CompletableFuture<>();
    private final AtomicReference<Status> status = new AtomicReference<>(new Status(Phase.STARTING, null));
    private AgentReply registration;
    private String registrationId;

    private JoinProxy(AgentClient client, GameService service, BooleanSupplier parent) {
        this.client = client; this.service = service; this.parent = parent;
    }
    public static JoinProxy start(AgentClient client, GameService service, BooleanSupplier parent) throws JoinProxyException {
        if (client == null || service == null || !service.runtime().isCurrent(client)) throw new JoinProxyException(JoinProxyException.Code.RUNTIME_CHANGED);
        if (parent == null || !parent.getAsBoolean()) throw new JoinProxyException(JoinProxyException.Code.ATTEMPT_CHANGED);
        if (!SLOTS.tryAcquire()) throw new JoinProxyException(JoinProxyException.Code.CAPACITY);
        try {
            JoinProxy proxy = new JoinProxy(client, service, parent);
            Thread worker = new Thread(proxy::run, "p2wlan-game-join"); worker.setDaemon(true); worker.start();
            return proxy;
        } catch (RuntimeException failure) { SLOTS.release(); throw new JoinProxyException(JoinProxyException.Code.NATIVE_FAILED); }
    }
    public CompletableFuture<LocalEndpoint> ready() { return ready.copy(); }
    public CompletableFuture<Void> released() { return released.copy(); }
    public boolean isReleased() { return released.isDone(); }
    public Status status() { return status.get(); }
    public boolean isCurrent() { return live.get() && parent.getAsBoolean() && service.runtime().isCurrent(client); }
    private void current() throws JoinProxyException {
        if (!live.get() || !parent.getAsBoolean()) throw new JoinProxyException(JoinProxyException.Code.ATTEMPT_CHANGED);
        if (!service.runtime().isCurrent(client)) throw new JoinProxyException(JoinProxyException.Code.RUNTIME_CHANGED);
    }
    private AgentReply request(String operation, JsonObject args, BooleanSupplier fence) throws Exception {
        try { return client.request(operation, args, fence).get(5, TimeUnit.SECONDS); }
        catch (TimeoutException uncertain) { client.close(); throw new JoinProxyException(JoinProxyException.Code.NATIVE_FAILED); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw new JoinProxyException(JoinProxyException.Code.NATIVE_FAILED);
        }
    }
    private LocalEndpoint parse() throws JoinProxyException {
        JsonObject data = registration.data();
        registrationId = RuntimeView.text(data, "registration");
        String key = RuntimeView.text(data, "key");
        var port = data.get("port");
        if (!JsonTrees.fields(data, Set.of("registration", "world", "port", "key")) || registrationId == null
                || !registrationId.matches("[a-f0-9]{16}") || !attempt.equals(RuntimeView.text(data, "world")) || key == null
                || !key.matches("[a-f0-9]{64}") || port == null || !port.isJsonPrimitive()
                || !port.getAsJsonPrimitive().isNumber() || !port.getAsString().matches("[1-9][0-9]{0,4}") || port.getAsInt() > 65535) {
            client.close(); throw new JoinProxyException(JoinProxyException.Code.INVALID_REPLY);
        }
        try { return LocalEndpoint.from(new InetSocketAddress("127.0.0.1", port.getAsInt()), key); }
        catch (WorldGuardException invalid) { client.close(); throw new JoinProxyException(JoinProxyException.Code.INVALID_REPLY); }
    }
    private void run() {
        Throwable failure = null;
        try {
            current();
            JsonObject args = service.runtime().arguments(); args.addProperty("world", attempt);
            args.addProperty("peer", service.peer()); args.addProperty("port", service.port()); args.addProperty("service", service.identity());
            registration = request("proxy", args, this::isCurrent);
            LocalEndpoint endpoint = parse(); current();
            if (!client.isCurrent(registration)) throw new JoinProxyException(JoinProxyException.Code.RUNTIME_CHANGED);
            status.updateAndGet(value -> value.phase() == Phase.STARTING ? new Status(Phase.REGISTERED, null) : value);
            current(); ready.complete(endpoint);
            while (!stop.await(100, TimeUnit.MILLISECONDS)) current();
        } catch (Exception error) { failure = error; }
        finally {
            revoke();
            try {
                if (registrationId != null && registration != null && client.isCurrent(registration) && service.runtime().isCurrent(client)) {
                    JsonObject args = service.runtime().arguments(); args.addProperty("registration", registrationId); args.addProperty("world", attempt);
                    AgentReply closed = request("close_registration", args, () -> service.runtime().isCurrent(client));
                    JsonObject expected = new JsonObject(); expected.addProperty("closed", true);
                    if (!client.isCurrent(closed) || !closed.data().equals(expected)) throw new JoinProxyException(JoinProxyException.Code.CLEANUP_FAILED);
                }
            } catch (Exception error) {
                // Explicitly unsent/stale cleanup is owned by native runtime
                // replacement. An old join must not kill that replacement.
                if (!RuntimeView.staleCleanup(error)) client.close();
                failure = error;
            }
            boolean cleaned = true;
            if (client.isClosed()) {
                try { client.terminated().get(2, TimeUnit.SECONDS); }
                catch (Exception error) { cleaned = false; failure = new JoinProxyException(JoinProxyException.Code.CLEANUP_FAILED); }
            }
            status.set(new Status(failure == null ? Phase.CLOSED : Phase.FAILED, failure == null ? null : reason(failure)));
            ready.completeExceptionally(failure == null ? new JoinProxyException(JoinProxyException.Code.ATTEMPT_CHANGED) : failure);
            if (cleaned) SLOTS.release();
            if (failure == null) released.complete(null); else released.completeExceptionally(failure);
        }
    }
    public static String reason(Throwable error) {
        error = UiCompletionGate.unwrap(error);
        if (error instanceof JoinProxyException join) return join.code().name();
        if (error instanceof AgentException agent) return UiCompletionGate.reason(agent);
        if (error instanceof InterruptedException) return JoinProxyException.Code.INTERRUPTED.name();
        return JoinProxyException.Code.NATIVE_FAILED.name();
    }
    public void revoke() {
        live.set(false); stop.countDown();
        status.updateAndGet(value -> value.phase() == Phase.STARTING || value.phase() == Phase.REGISTERED ? new Status(Phase.CLOSING, null) : value);
    }
    @Override public void close() { revoke(); }
}
