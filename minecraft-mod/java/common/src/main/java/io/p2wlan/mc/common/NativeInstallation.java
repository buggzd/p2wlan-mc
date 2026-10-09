package io.p2wlan.mc.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** One executable lifetime/capacity owner, independent of filesystem effects.
 * Platform backends isolate other OS users and fence file identities. Neither
 * backend sandboxes malicious code already running as this OS user. No cache. */
abstract class NativeInstallation implements AutoCloseable {
    static final int MAX_INSTALLATIONS = 2;
    private static final Semaphore SLOTS = new Semaphore(MAX_INSTALLATIONS);
    static final long TIMEOUT = Duration.ofSeconds(15).toNanos();
    final Path directory;
    final Path executable;
    final NativeManifest manifest;
    private final Slot slot;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean closed;

    NativeInstallation(Path directory, NativeManifest manifest, Slot slot) {
        this.directory = directory;
        this.executable = directory.resolve(manifest.executableName());
        this.manifest = manifest;
        this.slot = slot;
    }
    static final class Slot {
        private boolean created;
        private final AtomicBoolean released = new AtomicBoolean();
        void created() { created = true; }
        void release() { if (released.compareAndSet(false, true)) SLOTS.release(); }
    }
    static NativeInstallation extract(NativeManifest manifest, InputStream bytes, Path parent) throws IOException {
        boolean windows = NativeManifest.currentPlatform().startsWith("windows-");
        Path base;
        try {
            // Windows examines each ancestor without following reparse points.
            base = windows ? WindowsNativeInstallation.supported(parent) : parent.toRealPath();
            if (!windows) PosixNativeInstallation.supported(base);
        } catch (LinkageError error) { throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED); }
        if (!SLOTS.tryAcquire()) throw new NativeBundleException(Code.CAPACITY);
        Slot slot = new Slot();
        NativeInstallation result = null;
        try {
            result = windows ? WindowsNativeInstallation.create(base, manifest, slot)
                    : PosixNativeInstallation.create(base, manifest, slot);
            result.write(bytes);
            return result;
        } catch (IOException | RuntimeException | LinkageError error) {
            if (result != null) {
                try { result.close(); }
                catch (NativeBundleException cleanup) {
                    cleanup.addSuppressed(new NativeBundleException(error instanceof NativeBundleException known ? known.code() : Code.IO));
                    throw cleanup;
                }
            } else if (!slot.created) slot.release();
            // Unknown created identities retain their slot: no guessed deletion
            // or unbounded leftovers, even when the failing backend is Windows.
            if (error instanceof NativeBundleException known) throw known;
            throw new NativeBundleException(Code.IO);
        }
    }
    abstract void write(InputStream input) throws IOException;
    abstract Path validate() throws IOException;
    abstract void checkIdentity() throws IOException;
    abstract void cleanup() throws IOException;
    final boolean isClosed() { return closed; }
    final Path validatedExecutable() throws IOException {
        if (closed) throw new NativeBundleException(Code.FILE_IDENTITY_CHANGED);
        return validate();
    }
    final void checkLaunchedIdentity() throws IOException {
        if (closed) throw new NativeBundleException(Code.FILE_IDENTITY_CHANGED);
        checkIdentity();
    }
    static MessageDigest digest() throws NativeBundleException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED); }
    }
    static void deadline(long deadline) throws NativeBundleException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw new NativeBundleException(Code.EXTRACTION_TIMEOUT);
    }
    @Override public final void close() throws NativeBundleException {
        if (closed) return;
        if (!closing.compareAndSet(false, true)) throw new NativeBundleException(Code.CLEANUP_BUSY);
        try {
            if (closed) return;
            cleanup();
            closed = true;
            slot.release();
        } catch (NativeBundleException error) { throw error; }
        catch (IOException | RuntimeException error) { throw new NativeBundleException(Code.CLEANUP_FAILED); }
        finally { closing.set(false); }
    }
}
