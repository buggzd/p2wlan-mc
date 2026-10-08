package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One child-process owner. It forwards commands and fenced native snapshots;
 * it does not infer NAT, Direct/Relay, peer authorization, or Minecraft state. */
public final class AgentClient implements AutoCloseable {
    public static final int MAX_PENDING = 32;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(2).toNanos();
    private record Pending(CompletableFuture<AgentReply> future, long deadline) {}
    private record Outbound(long id, String owner, byte[] frame) {}

    private final Object gate = new Object();
    private final Process child;
    private final long timeoutNanos;
    private final long helloDeadline;
    private final ArrayBlockingQueue<Outbound> outgoing = new ArrayBlockingQueue<>(MAX_PENDING);
    private final Map<Long, Pending> pending = new HashMap<>();
    private final SessionFence snapshots = new SessionFence();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final ScheduledExecutorService watchdog;
    private volatile boolean closed;
    private volatile AgentException.Code failure;
    private String owner;
    private long nextId = 1;
    private long nextHeartbeat;

    public static AgentClient launch(Path executable, List<String> arguments) throws AgentException {
        return launch(executable, arguments, DEFAULT_TIMEOUT);
    }

    public static AgentClient launch(Path executable, List<String> arguments, Duration timeout) throws AgentException {
        if (executable == null || !executable.isAbsolute()
                || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(executable) || arguments == null || arguments.size() > 16
                || timeout == null || timeout.compareTo(Duration.ofMillis(50)) < 0
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new AgentException(AgentException.Code.INVALID_REQUEST);
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        for (String argument : arguments) {
            if (argument == null || argument.length() > 4096 || argument.indexOf('\0') >= 0) {
                throw new AgentException(AgentException.Code.INVALID_REQUEST);
            }
            command.add(argument);
        }
        try {
            // Child logs are not protocol/state and must not block its stderr
            // pipe or leak credentials into the game log. Failures travel in IPC.
            return new AgentClient(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(), timeout);
        } catch (IOException error) {
            throw new AgentException(AgentException.Code.START_FAILED);
        }
    }

    private AgentClient(Process child, Duration timeout) {
        this.child = child;
        timeoutNanos = timeout.toNanos();
        helloDeadline = System.nanoTime() + timeoutNanos;
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> daemon("p2wlan-agent-watchdog", runnable));
        // Install the timer before readers can observe an immediate child exit.
        watchdog.scheduleAtFixedRate(this::tick, 20, 20, TimeUnit.MILLISECONDS);
        daemon("p2wlan-agent-reader", this::readLoop).start();
        daemon("p2wlan-agent-writer", this::writeLoop).start();
        daemon("p2wlan-agent-exit", () -> {
            try { child.waitFor(); terminated.complete(null); fail(AgentException.Code.UNEXPECTED_EXIT); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                fail(AgentException.Code.IO);
                child.onExit().thenRun(() -> terminated.complete(null));
            }
        }).start();
    }

    private static Thread daemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public CompletableFuture<Void> ready() { return ready.copy(); }
    /** Completes only after this exact child is reaped, not merely killed. */
    public CompletableFuture<Void> terminated() { return terminated.copy(); }
    public Optional<StatusSnapshot> snapshot() {
        synchronized (gate) { return closed ? Optional.empty() : snapshots.snapshot(); }
    }
    public boolean isCurrent(AgentReply reply) {
        synchronized (gate) { return !closed && reply != null && reply.owner().equals(owner); }
    }
    public Optional<AgentException.Code> failure() { return Optional.ofNullable(failure); }
    public boolean isClosed() { return closed; }
    public long processId() { return child.pid(); }

    public CompletableFuture<AgentReply> request(String operation, JsonObject arguments) {
        if (operation == null || !operation.matches("[a-z][a-z0-9_]{0,47}") || arguments == null) {
            return CompletableFuture.failedFuture(new AgentException(AgentException.Code.INVALID_REQUEST));
        }
        CompletableFuture<AgentReply> future = new CompletableFuture<>();
        long id;
        String capturedOwner;
        synchronized (gate) {
            if (closed) return CompletableFuture.failedFuture(new AgentException(AgentException.Code.CLOSED));
            if (owner == null) return CompletableFuture.failedFuture(new AgentException(AgentException.Code.NOT_READY));
            if (pending.size() >= MAX_PENDING) return CompletableFuture.failedFuture(new AgentException(AgentException.Code.CAPACITY));
            capturedOwner = owner;
            if (nextId == Long.MAX_VALUE) id = 0;
            else {
                id = nextId++;
                pending.put(id, new Pending(future, System.nanoTime() + timeoutNanos));
            }
        }
        if (id == 0) {
            fail(AgentException.Code.IDENTITY_EXHAUSTED);
            future.completeExceptionally(new AgentException(AgentException.Code.IDENTITY_EXHAUSTED));
            return future.copy();
        }
        byte[] frame;
        try {
            JsonObject envelope = new JsonObject();
            envelope.addProperty("version", FrameCodec.VERSION);
            envelope.addProperty("owner", capturedOwner);
            envelope.addProperty("id", id);
            envelope.addProperty("op", operation);
            envelope.add("args", arguments);
            var bytes = new ByteArrayOutputStream();
            FrameCodec.write(new DataOutputStream(bytes), envelope);
            frame = bytes.toByteArray();
        } catch (IOException | RuntimeException error) {
            reject(id, future, AgentException.Code.INVALID_REQUEST);
            return future.copy();
        }
        boolean queued;
        synchronized (gate) {
            queued = !closed && capturedOwner.equals(owner) && pending.containsKey(id)
                && outgoing.offer(new Outbound(id, capturedOwner, frame));
        }
        if (!queued) reject(id, future, closed ? AgentException.Code.CLOSED : AgentException.Code.CAPACITY);
        return future.copy();
    }

    private void reject(long id, CompletableFuture<AgentReply> future, AgentException.Code reason) {
        synchronized (gate) { pending.remove(id); }
        future.completeExceptionally(new AgentException(reason));
    }

    private void readLoop() {
        try {
            var input = new DataInputStream(child.getInputStream());
            while (!closed) {
                JsonObject frame = FrameCodec.read(input);
                if (frame == null) { fail(AgentException.Code.UNEXPECTED_EXIT); return; }
                receive(AgentMessages.parse(frame));
            }
        } catch (AgentException error) { fail(error.code()); }
        catch (ProtocolException error) { fail(AgentException.Code.INVALID_REPLY); }
        catch (IOException | RuntimeException error) { fail(AgentException.Code.IO); }
    }

    private void receive(AgentMessages.Message message) throws AgentException {
        Pending completion = null;
        synchronized (gate) {
            if (closed) return;
            if (message instanceof AgentMessages.Hello hello) {
                if (owner != null) throw new AgentException(AgentException.Code.INVALID_REPLY);
                owner = hello.owner();
                snapshots.begin(owner);
                nextHeartbeat = System.nanoTime() + HEARTBEAT_NANOS;
            } else {
                if (owner == null || !owner.equals(message.owner())) throw new AgentException(AgentException.Code.INVALID_REPLY);
                if (message instanceof AgentMessages.Status status) {
                    snapshots.accept(new StatusSnapshot(owner, status.revision(), status.data()));
                } else if (message instanceof AgentMessages.Reply reply) {
                    completion = pending.remove(reply.id());
                    if (completion == null) throw new AgentException(AgentException.Code.INVALID_REPLY);
                }
            }
        }
        // Completing futures may invoke arbitrary UI callbacks; never do it
        // under the lifecycle/pending lock or on a Minecraft thread by accident.
        if (message instanceof AgentMessages.Hello) ready.complete(null);
        if (message instanceof AgentMessages.Reply reply && completion != null) {
            boolean current;
            synchronized (gate) { current = !closed && reply.owner().equals(owner); }
            if (!current) completion.future().completeExceptionally(new AgentException(failure == null ? AgentException.Code.CLOSED : failure));
            else if (reply.error() == null) completion.future().complete(new AgentReply(reply.owner(), reply.id(), reply.result()));
            else completion.future().completeExceptionally(new AgentException(AgentException.Code.REMOTE_REJECTED, reply.error()));
        }
    }

    private void writeLoop() {
        try {
            var output = child.getOutputStream();
            while (!closed) {
                Outbound item = outgoing.poll(100, TimeUnit.MILLISECONDS);
                if (item == null) continue;
                synchronized (gate) {
                    Pending request = pending.get(item.id());
                    if (closed || request == null || !item.owner().equals(owner)) continue;
                    if (System.nanoTime() - request.deadline() >= 0) { throw new AgentException(AgentException.Code.REQUEST_TIMEOUT); }
                }
                // No lock crosses pipe I/O. The watchdog kills this exact child
                // on deadline, including when its stdin stops being consumed.
                output.write(item.frame());
                output.flush();
            }
        } catch (AgentException error) { fail(error.code()); }
        catch (IOException error) { fail(AgentException.Code.IO); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); fail(AgentException.Code.IO); }
    }

    private void tick() {
        long now = System.nanoTime();
        AgentException.Code expired = null;
        boolean ping = false;
        synchronized (gate) {
            if (closed) return;
            if (owner == null && now - helloDeadline >= 0) expired = AgentException.Code.HANDSHAKE_TIMEOUT;
            else if (pending.values().stream().anyMatch(item -> now - item.deadline() >= 0)) expired = AgentException.Code.REQUEST_TIMEOUT;
            else if (owner != null && now - nextHeartbeat >= 0 && pending.size() < MAX_PENDING) {
                nextHeartbeat = now + HEARTBEAT_NANOS;
                ping = true;
            }
        }
        if (expired != null) fail(expired);
        else if (ping) request("ping", new JsonObject()).whenComplete((reply, error) -> {
            Throwable reason = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            if (reason instanceof AgentException agent && agent.code() == AgentException.Code.REMOTE_REJECTED) {
                fail(AgentException.Code.PROTOCOL_MISMATCH);
            }
        });
    }

    private void fail(AgentException.Code reason) {
        List<Pending> abandoned;
        String oldOwner;
        synchronized (gate) {
            if (closed) return;
            failure = reason;
            oldOwner = owner;
            owner = null;
            abandoned = new ArrayList<>(pending.values());
            pending.clear();
            outgoing.clear();
            // Publish closure after its reason and lifecycle state are fixed.
            closed = true;
        }
        watchdog.shutdownNow();
        // Killing the process unblocks both pipes without waiting under a lock.
        child.destroyForcibly();
        snapshots.close(oldOwner);
        ready.completeExceptionally(new AgentException(reason));
        for (Pending item : abandoned) item.future().completeExceptionally(new AgentException(reason));
    }

    @Override public void close() { fail(AgentException.Code.CLOSED); }
}
