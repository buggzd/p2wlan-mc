package io.p2wlan.mc.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Per-launch diagnostics only. One bounded worker, no retries/network/raw log
 * capture. Disk I/O never runs under the memory/lifecycle lock or on game threads.
 * The sink lives outside the credential directory and is usable before hello. */
public final class ModDiagnostics implements AutoCloseable {
    public enum Stage { START_REQUESTED, BUNDLE_PLATFORM, BUNDLE_MANIFEST, BUNDLE_EXTRACT, CHILD_START,
        CHILD_STARTED, CHILD_EXIT, IPC_HELLO, READY, CLOSE_REQUESTED, CLEANUP_FINISHED }
    public record Failure(String reason, StartupDiagnostic startup) {}
    public record Snapshot(Path directory, String diskState, long evicted, long dropped, Failure failure, List<String> lines) {
        public String copyText() {
            return "P2WLAN Mod diagnostics (no credentials/raw stderr)\nlog=logs/p2wlan-mod/startup.log\n"
                    + "disk=" + diskState + " memory_evicted=" + evicted + " queue_dropped=" + dropped + "\n"
                    + (failure == null ? "" : "failure=" + failure.reason()
                    + (failure.startup() == null ? "" : " " + failure.startup().safeText()) + "\n") + String.join("\n", lines) + "\n";
        }
    }
    public static final int MEMORY_LIMIT = 64, QUEUE_LIMIT = 128, LINE_LIMIT = 1024;
    public static final long FILE_LIMIT = 256 * 1024;
    private final Object gate = new Object();
    private final ArrayDeque<String> recent = new ArrayDeque<>(MEMORY_LIMIT);
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final CompletableFuture<Void> flushed = new CompletableFuture<>();
    private final Path directory;
    private final java.util.function.Consumer<Runnable> startWorker;
    private final String run = UUID.randomUUID().toString();
    private String diskState;
    private Failure failure;
    private long sequence, evicted, dropped;
    private boolean started, closed;

    public ModDiagnostics(Path directory) {
        this(directory, task -> {
            Thread worker = new Thread(task, "p2wlan-mod-log");
            worker.setDaemon(true); worker.start();
        });
    }
    // Inject only the dispatch boundary to test overflow/cancellation without sleeps.
    ModDiagnostics(Path directory, java.util.function.Consumer<Runnable> startWorker) {
        if (directory != null && !directory.isAbsolute()) throw new IllegalArgumentException("INVALID_LOG_DIRECTORY");
        this.directory = directory;
        this.startWorker = java.util.Objects.requireNonNull(startWorker);
        diskState = directory == null ? "MEMORY_ONLY" : "NOT_STARTED";
    }
    public void start() {
        synchronized (gate) {
            if (closed || started) return;
            started = true;
        }
        stage(Stage.START_REQUESTED);
        environment();
        if (directory == null) return;
        try { startWorker.accept(this::writeLoop); }
        catch (RuntimeException error) { diskFailed("LOG_WORKER_UNAVAILABLE"); flushed.complete(null); }
    }
    public void stage(Stage stage) { record(stage.name(), ""); }
    public void displaySettingsSave(DisplayPreferencesStore.Saved result) {
        record("DISPLAY_SETTINGS_SAVE", "target=DISPLAY_SETTINGS reason=" + result.reason().name()
                + " operation=" + result.operation().name() + " error_kind=" + result.failure().name()
                + " cleanup_error_kind=" + result.cleanupFailure().name());
    }
    void childStarted(long pid) { record(Stage.CHILD_STARTED.name(), "pid=" + pid); }
    void childExited(int status) { record(Stage.CHILD_EXIT.name(), "exit=" + String.format(Locale.ROOT, "0x%08X", status)); }
    void build(NativeManifest manifest) {
        record("BUILD", "platform=" + manifest.platform() + " version=" + manifest.version()
                + " source=" + manifest.revision() + " source_sha256=" + manifest.sourceDigest()
                + " kind=" + manifest.buildKind() + " executable_sha256=" + manifest.checksum());
    }
    public void failure(Throwable error) {
        Failure detail = classify(error);
        synchronized (gate) { if (!closed && failure == null) failure = detail; }
        record("FAILURE", detail.reason() + (detail.startup() == null ? "" : " " + detail.startup().safeText()));
    }
    private static Failure classify(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof AgentException agent) return new Failure(agent.diagnostic(), agent.startupDiagnostic());
        if (error instanceof NativeBundleException bundle) return new Failure(bundle.code().name(), null);
        return new Failure(error instanceof TimeoutException ? "STARTUP_TIMEOUT" : "START_FAILED", null);
    }
    private void environment() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String platform = os.startsWith("windows") ? "windows" : switch (os) {
            case "mac os x", "darwin" -> "macos"; case "linux" -> "linux"; default -> "other";
        };
        String arch = switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64"; case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i686" -> "x86"; default -> "other";
        };
        String bits = System.getProperty("sun.arch.data.model", "");
        record("ENVIRONMENT", "os=" + platform + " arch=" + arch + " java_major=" + Runtime.version().feature()
                + " java_bits=" + (bits.equals("32") || bits.equals("64") ? bits : "unknown"));
    }
    private void record(String event, String detail) {
        synchronized (gate) {
            if (closed) return;
            String line = "time=" + Instant.now() + " run=" + run + " seq=" + (++sequence) + " event=" + event
                    + (detail.isEmpty() ? "" : " " + detail);
            if (line.length() > LINE_LIMIT) {
                dropped++; line = "run=" + run + " event=LOG_FAILURE reason=LOG_RECORD_OVER_BUDGET";
            }
            remember(line);
            if (started && directory != null && (diskState.equals("NOT_STARTED") || diskState.equals("WRITING"))
                    && !queue.offer(line)) dropped++;
        }
    }
    private void remember(String line) {
        if (recent.size() == MEMORY_LIMIT) { recent.removeFirst(); evicted++; }
        recent.addLast(line);
    }
    public Snapshot snapshot() {
        synchronized (gate) { return new Snapshot(directory, diskState, evicted, dropped, failure, List.copyOf(recent)); }
    }
    public CompletableFuture<Void> flushed() { return flushed.copy(); }
    private void diskFailed(String reason) {
        synchronized (gate) {
            diskState = reason;
            dropped += queue.size(); queue.clear();
            remember("time=" + Instant.now() + " run=" + run + " event=LOG_FAILURE reason=" + reason);
        }
    }
    private static void regular(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new LogFailure("LOG_UNSAFE_FILE");
    }
    private void writeLoop() {
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new LogFailure("LOG_UNSAFE_DIRECTORY");
            Path lockPath = directory.resolve("startup.lock"); regular(lockPath);
            // Single writer across game processes too. Never retry or alter private-profile ACLs.
            try (FileChannel lockFile = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = lockFile.tryLock()) {
                if (lock == null) throw new LogFailure("LOG_BUSY");
                synchronized (gate) { diskState = "WRITING"; }
                while (true) {
                    String line = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (line != null) append(line);
                    synchronized (gate) { if (closed && queue.isEmpty()) break; }
                }
                Snapshot finalState = snapshot();
                append("time=" + Instant.now() + " run=" + run + " event=LOG_SUMMARY memory_evicted=" + finalState.evicted()
                        + " queue_dropped=" + finalState.dropped());
                synchronized (gate) { diskState = "SAVED"; }
            }
        } catch (LogFailure error) { diskFailed(error.reason); }
        catch (AccessDeniedException | SecurityException error) { diskFailed("LOG_ACCESS_DENIED"); }
        catch (java.nio.channels.OverlappingFileLockException error) { diskFailed("LOG_BUSY"); }
        catch (IOException | UnsupportedOperationException error) { diskFailed("LOG_IO_FAILED"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); diskFailed("LOG_CANCELLED"); }
        finally { flushed.complete(null); }
    }
    private void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("startup.log"), backup = directory.resolve("startup.log.1");
        regular(file); regular(backup);
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && Files.size(backup) > FILE_LIMIT)
            throw new LogFailure("LOG_FILE_OVER_BUDGET");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) + bytes.length > FILE_LIMIT) {
            if (Files.size(file) > FILE_LIMIT) throw new LogFailure("LOG_FILE_OVER_BUDGET");
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (output.write(buffer) <= 0) throw new LogFailure("LOG_WRITE_STALLED");
            }
        }
    }
    @Override public void close() {
        boolean completed;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            completed = directory == null || !started;
        }
        if (completed) flushed.complete(null);
    }
    private static final class LogFailure extends IOException {
        private final String reason;
        private LogFailure(String reason) { super(reason); this.reason = reason; }
    }
}
