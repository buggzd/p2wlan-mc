package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One local-world publication owner. Network authorization remains native.
 * One worker/slot, no queue/retry. Bind <=2s, IPC <=5s, guard teardown <=2s, target teardown <=7s.
 * The slot is released only after exact native/local cleanup. revoke is immediate. */
public final class WorldHost implements AutoCloseable {
    public interface Target extends AutoCloseable {
        void open() throws WorldHostException;
        LocalEndpoint endpoint() throws WorldHostException;
        void revoke();
        @Override void close() throws WorldHostException;
    }
    public interface TargetFactory { Target create(BooleanSupplier current) throws WorldHostException; }
    public enum Phase { STARTING, HOSTED, CLOSING, CLOSED, FAILED }
    public record Status(Phase phase, String reason) {}
    public record Hosted(String world, int port, String service) {}
    private static final Semaphore SLOTS = new Semaphore(1);
    private final AgentClient client;
    private final RuntimeView runtime;
    private final BooleanSupplier parent;
    private final TargetFactory factory;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final CountDownLatch stop = new CountDownLatch(1);
    private final CompletableFuture<Hosted> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> released = new CompletableFuture<>();
    private final AtomicReference<Status> status = new AtomicReference<>(new Status(Phase.STARTING, null));
    private volatile Target target;
    private volatile WorldGuard guard;
    private AgentReply registration;
    private String registrationId;

    private WorldHost(AgentClient client, RuntimeView runtime, BooleanSupplier parent, TargetFactory factory) {
        this.client = client; this.runtime = runtime; this.parent = parent; this.factory = factory;
    }
    public static WorldHost start(AgentClient client, RuntimeView runtime, BooleanSupplier parent, TargetFactory factory) throws WorldHostException {
        if (client == null || runtime == null || !runtime.isCurrent(client)) throw new WorldHostException(WorldHostException.Code.RUNTIME_CHANGED);
        if (parent == null || factory == null || !parent.getAsBoolean()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
        if (!SLOTS.tryAcquire()) throw new WorldHostException(WorldHostException.Code.CAPACITY);
        WorldHost host = new WorldHost(client, runtime, parent, factory);
        try {
            Thread thread = new Thread(host::run, "p2wlan-world-host"); thread.setDaemon(true); thread.start();
        } catch (RuntimeException error) { SLOTS.release(); throw new WorldHostException(WorldHostException.Code.BIND_FAILED); }
        return host;
    }
    public CompletableFuture<Hosted> ready() { return ready.copy(); }
    public CompletableFuture<Void> released() { return released.copy(); }
    public Status status() { return status.get(); }
    public boolean isReleased() { return released.isDone(); }
    public boolean isCurrent() { return live.get() && parent.getAsBoolean() && runtime.isCurrent(client); }
    private void current() throws WorldHostException {
        if (!live.get() || !parent.getAsBoolean()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
        if (!runtime.isCurrent(client)) throw new WorldHostException(WorldHostException.Code.RUNTIME_CHANGED);
    }
    private AgentReply request(String operation, JsonObject args, BooleanSupplier fence) throws Exception {
        try { return client.request(operation, args, fence).get(5, TimeUnit.SECONDS); }
        catch (TimeoutException uncertain) { client.close(); throw new WorldHostException(WorldHostException.Code.NATIVE_FAILED); }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new WorldHostException(WorldHostException.Code.NATIVE_FAILED);
        }
    }
    private Hosted parse() throws WorldHostException {
        JsonObject data = registration.data();
        String world = RuntimeView.text(data, "world"), service = RuntimeView.text(data, "service");
        registrationId = RuntimeView.text(data, "registration");
        var port = data.get("port");
        if (!JsonTrees.fields(data, Set.of("registration", "world", "virtual_ip", "port", "service"))
                || registrationId == null || !registrationId.matches("[a-f0-9]{16}") || !guard.owner().equals(world)
                || service == null || !service.matches("[a-f0-9]{64}") || port == null || !port.isJsonPrimitive()
                || !port.getAsJsonPrimitive().isNumber() || !port.getAsString().matches("[1-9][0-9]{0,4}")) {
            client.close(); throw new WorldHostException(WorldHostException.Code.INVALID_REPLY);
        }
        int number = port.getAsInt();
        if (number > 65535) { client.close(); throw new WorldHostException(WorldHostException.Code.INVALID_REPLY); }
        return new Hosted(world, number, service);
    }
    private void run() {
        Throwable failure = null;
        try {
            current(); target = factory.create(this::isCurrent); current(); target.open(); current();
            guard = WorldGuard.openProtected(target.endpoint()); current();
            JsonObject args = runtime.arguments(); args.addProperty("world", guard.owner());
            args.addProperty("port", guard.endpoint().address().getPort()); args.addProperty("key", guard.endpoint().keyHex());
            registration = request("host", args, this::isCurrent);
            // Even a revoked/late success must retain its exact registration
            // for cleanup; it cannot be published as the replacement world.
            Hosted hosted = parse(); current();
            if (!client.isCurrent(registration)) throw new WorldHostException(WorldHostException.Code.RUNTIME_CHANGED);
            status.updateAndGet(value -> value.phase() == Phase.STARTING ? new Status(Phase.HOSTED, null) : value); current(); ready.complete(hosted);
            stop.await(); // This wait belongs solely to the explicitly revocable world lifetime.
        } catch (Exception error) { failure = error; }
        finally {
            revoke();
            try {
                if (registration != null && registrationId != null && client.isCurrent(registration) && runtime.isCurrent(client)) {
                    JsonObject args = runtime.arguments(); args.addProperty("registration", registrationId); args.addProperty("world", guard.owner());
                    AgentReply closed = request("close_registration", args, () -> runtime.isCurrent(client));
                    if (!client.isCurrent(closed) || !closed.data().equals(closedReply())) throw new WorldHostException(WorldHostException.Code.CLEANUP_FAILED);
                }
            } catch (Exception error) {
                if (!RuntimeView.staleCleanup(error)) client.close();
                failure = error;
            }
            boolean cleaned = true;
            try { if (guard != null) guard.close(); }
            catch (Exception error) { cleaned = false; failure = error; }
            try { if (target != null) target.close(); }
            catch (Exception error) { cleaned = false; failure = error; }
            if (client.isClosed()) {
                try { client.terminated().get(2, TimeUnit.SECONDS); }
                catch (Exception error) { cleaned = false; failure = new WorldHostException(WorldHostException.Code.CLEANUP_FAILED); }
            }
            String reason = failure == null ? null : reason(failure);
            status.set(new Status(failure == null ? Phase.CLOSED : Phase.FAILED, reason));
            ready.completeExceptionally(failure == null ? new WorldHostException(WorldHostException.Code.WORLD_CHANGED) : failure);
            if (cleaned) SLOTS.release();
            if (failure == null) released.complete(null); else released.completeExceptionally(failure);
        }
    }
    private static JsonObject closedReply() { JsonObject value = new JsonObject(); value.addProperty("closed", true); return value; }
    public static String reason(Throwable error) {
        error = UiCompletionGate.unwrap(error);
        if (error instanceof WorldHostException world) return world.code().name();
        if (error instanceof WorldGuardException guard) return guard.code().name();
        if (error instanceof AgentException agent) return UiCompletionGate.reason(agent);
        if (error instanceof InterruptedException) return WorldHostException.Code.INTERRUPTED.name();
        return WorldHostException.Code.BIND_FAILED.name();
    }
    public void revoke() {
        live.set(false);
        WorldGuard oldGuard = guard; if (oldGuard != null) oldGuard.revoke();
        Target oldTarget = target; if (oldTarget != null) oldTarget.revoke();
        stop.countDown();
        status.updateAndGet(value -> value.phase() == Phase.STARTING || value.phase() == Phase.HOSTED ? new Status(Phase.CLOSING, null) : value);
    }
    @Override public void close() { revoke(); }
}
