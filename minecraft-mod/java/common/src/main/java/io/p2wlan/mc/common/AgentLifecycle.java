package io.p2wlan.mc.common;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Single-use application process owner. Game adapters must recheck isCurrent
 * on their destination thread; no peer/path/room truth is stored here. One
 * startup worker, one 40-second deadline, no retries and no work queue. */
public final class AgentLifecycle implements AutoCloseable {
    public enum Phase { NEW, STARTING, READY, CLOSING, CLOSED, FAILED }
    public record Status(Phase phase, String reason) {}
    private final Object gate = new Object();
    private final Path profile;
    private final boolean development;
    private final ModDiagnostics diagnostics;
    private final BundledAgent.Resources resources;
    private final CompletableFuture<AgentClient> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private Phase phase = Phase.NEW;
    private String reason;
    private boolean closing;
    private Thread starter;
    private BundledAgent bundle;

    public AgentLifecycle(Path profile, boolean allowDevelopment) {
        this(profile, allowDevelopment, null);
    }
    public AgentLifecycle(Path profile, boolean allowDevelopment, Path logDirectory) {
        this(profile, allowDevelopment, logDirectory, null);
    }
    public AgentLifecycle(Path profile, boolean allowDevelopment, Path logDirectory, BundledAgent.Resources localResources) {
        if (profile == null || !profile.isAbsolute()) throw new IllegalArgumentException("INVALID_PROFILE_PATH");
        this.profile = profile;
        development = allowDevelopment;
        diagnostics = new ModDiagnostics(logDirectory);
        resources = localResources;
        stopped.whenComplete((ignored, error) -> { diagnostics.stage(ModDiagnostics.Stage.CLEANUP_FINISHED); diagnostics.close(); });
    }
    public void start() {
        Thread worker;
        synchronized (gate) {
            if (phase != Phase.NEW || closing) return;
            phase = Phase.STARTING;
            worker = new Thread(this::launch, "p2wlan-mod-startup");
            worker.setDaemon(true);
            starter = worker;
        }
        diagnostics.start();
        ready.orTimeout(40, TimeUnit.SECONDS).whenComplete((client, error) -> {
            if (error instanceof TimeoutException) stop(error);
        });
        try { worker.start(); }
        catch (RuntimeException error) {
            var failure = new NativeBundleException(NativeBundleException.Code.START_FAILED);
            stop(failure);
            stopped.completeExceptionally(failure);
        }
    }
    private void launch() {
        BundledAgent created = null;
        try {
            synchronized (gate) { if (closing) return; }
            created = resources == null ? BundledAgent.launch(profile, development, diagnostics)
                    : BundledAgent.launch(profile, development, diagnostics, resources);
            boolean admit;
            synchronized (gate) {
                admit = !closing;
                if (admit) bundle = created;
            }
            BundledAgent owner = created;
            owner.released().whenComplete((ignored, error) -> finished(owner, error));
            if (!admit) { owner.close(); return; }
            owner.client().ready().whenComplete((ignored, error) -> {
                if (error != null) { stop(error); return; }
                boolean current;
                synchronized (gate) {
                    current = !closing && bundle == owner && !owner.client().isClosed();
                    if (current) phase = Phase.READY;
                }
                if (current) { diagnostics.stage(ModDiagnostics.Stage.READY); ready.complete(owner.client()); }
                else stop(new AgentException(AgentException.Code.CLOSED));
            });
        } catch (Exception error) {
            Exception failure = error instanceof NativeBundleException || error instanceof AgentException ? error
                    : new NativeBundleException(NativeBundleException.Code.START_FAILED);
            stop(failure);
            if (created == null) {
                synchronized (gate) { if (phase == Phase.CLOSING) phase = Phase.CLOSED; }
                stopped.completeExceptionally(failure);
            } else created.close(); // Its already-installed exit callback owns completion.
        } finally {
            synchronized (gate) { starter = null; }
            if (created == null && closing && !stopped.isDone()) {
                synchronized (gate) { if (phase == Phase.CLOSING) phase = Phase.CLOSED; }
                stopped.complete(null);
            }
        }
    }
    private void finished(BundledAgent owner, Throwable error) {
        boolean unexpected;
        synchronized (gate) { unexpected = !closing && bundle == owner; }
        if (unexpected) stop(owner.client().failureDetails().filter(failure -> failure.code() != AgentException.Code.CLOSED).orElseGet(() -> new AgentException(AgentException.Code.UNEXPECTED_EXIT)));
        synchronized (gate) {
            if (phase == Phase.CLOSING) phase = error == null ? Phase.CLOSED : Phase.FAILED;
            if (error != null) reason = reason(error);
        }
        if (error != null) diagnostics.failure(error);
        if (error == null) stopped.complete(null); else stopped.completeExceptionally(error);
    }
    public CompletableFuture<AgentClient> ready() { return ready.copy(); }
    /** Completion after owned-child exit/cleanup; startup/cleanup failures stay exceptional. */
    public CompletableFuture<Void> stopped() { return stopped.copy(); }
    public boolean isCurrent(AgentClient client) {
        synchronized (gate) { return !closing && phase == Phase.READY && bundle != null
                && bundle.client() == client && !client.isClosed(); }
    }
    /** Nonblocking lookup; unlike ready().copy(), polling this creates no
     * pending future/listener while native startup is still in progress. */
    public java.util.Optional<AgentClient> current() {
        synchronized (gate) {
            return !closing && phase == Phase.READY && bundle != null && !bundle.client().isClosed()
                    ? java.util.Optional.of(bundle.client()) : java.util.Optional.empty();
        }
    }
    public ModDiagnostics diagnostics() { return diagnostics; }
    /** Profile location only; account credentials remain native-owned. */
    public Path profilePath() { return profile; }
    public Status status() { synchronized (gate) { return new Status(phase, reason); } }
    private void stop(Throwable failure) {
        BundledAgent owner;
        Thread worker;
        synchronized (gate) {
            if (closing) return;
            closing = true;
            reason = failure == null ? null : reason(failure);
            phase = failure == null ? Phase.CLOSING : Phase.FAILED;
            owner = bundle;
            worker = starter;
        }
        if (failure != null) diagnostics.failure(failure);
        diagnostics.stage(ModDiagnostics.Stage.CLOSE_REQUESTED);
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        if (owner != null) owner.close();
        ready.completeExceptionally(failure == null ? new AgentException(AgentException.Code.CLOSED) : failure);
        if (worker == null && owner == null) {
            synchronized (gate) { if (phase == Phase.CLOSING) phase = Phase.CLOSED; }
            if (failure == null) stopped.complete(null); else stopped.completeExceptionally(failure);
        }
    }
    private static String reason(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof NativeBundleException nativeError) return nativeError.code().name();
        if (error instanceof AgentException agentError) return agentError.diagnostic();
        if (error instanceof TimeoutException) return "STARTUP_TIMEOUT";
        return "START_FAILED";
    }
    @Override public void close() { stop(null); }
}
