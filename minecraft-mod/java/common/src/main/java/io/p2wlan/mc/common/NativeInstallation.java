package io.p2wlan.mc.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** One private executable lifetime. POSIX isolation protects other OS users.
 * Path identities detect replacements; Java path-based launch/cleanup does NOT
 * sandbox malicious code already running as this OS user. No shared cache. */
final class NativeInstallation implements AutoCloseable {
    static final int MAX_INSTALLATIONS = 2;
    private static final Semaphore SLOTS = new Semaphore(MAX_INSTALLATIONS);
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> WRITABLE = PosixFilePermissions.fromString("rw-------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("r-x------");
    private static final long TIMEOUT = Duration.ofSeconds(15).toNanos();
    final Path directory;
    final Path executable;
    private final PosixFileAttributes root;
    private final NativeManifest manifest;
    private Object fileKey;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean closed;

    private NativeInstallation(Path directory, PosixFileAttributes root, NativeManifest manifest) {
        this.directory = directory;
        this.executable = directory.resolve(manifest.executableName());
        this.root = root;
        this.manifest = manifest;
    }
    static NativeInstallation extract(NativeManifest manifest, InputStream bytes, Path parent) throws IOException {
        // Capability checks happen before creating anything. No fallback to
        // permissive attributes, deleteOnExit, PATH or a remote executable.
        Path base = parent.toRealPath();
        if (!Files.getFileStore(base).supportsFileAttributeView(PosixFileAttributeView.class)
                || !base.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED);
        }
        if (!SLOTS.tryAcquire()) throw new NativeBundleException(Code.CAPACITY);
        NativeInstallation result = null;
        boolean created = false;
        try {
            Path directory = Files.createTempDirectory(base, "p2wlan-native-", PosixFilePermissions.asFileAttribute(DIRECTORY));
            created = true;
            PosixFileAttributes attributes = attrs(directory);
            if (!attributes.isDirectory() || attributes.fileKey() == null || !attributes.permissions().equals(DIRECTORY)) {
                throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED);
            }
            result = new NativeInstallation(directory, attributes, manifest);
            result.write(bytes);
            return result;
        } catch (IOException | RuntimeException error) {
            if (result != null) {
                try { result.close(); }
                catch (NativeBundleException cleanup) {
                    cleanup.addSuppressed(new NativeBundleException(error instanceof NativeBundleException known ? known.code() : Code.IO));
                    throw cleanup;
                }
            } else if (!created) SLOTS.release();
            // If initial identity cannot be read, do not guess which directory
            // to delete. Retain the slot to bound unidentified leftovers.
            if (error instanceof NativeBundleException known) throw known;
            throw new NativeBundleException(Code.IO);
        }
    }
    private void write(InputStream input) throws IOException {
        long deadline = System.nanoTime() + TIMEOUT;
        MessageDigest digest = digest();
        checkDirectory();
        try (var channel = Files.newByteChannel(executable,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(WRITABLE))) {
            fileKey = attrs(executable).fileKey();
            if (fileKey == null) throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED);
            byte[] buffer = new byte[32768];
            long count = 0;
            while (true) {
                deadline(deadline);
                int read = input.read(buffer);
                if (read < 0) break;
                count += read;
                if (count > manifest.size()) throw new NativeBundleException(Code.BINARY_MISMATCH);
                digest.update(buffer, 0, read);
                ByteBuffer block = ByteBuffer.wrap(buffer, 0, read);
                while (block.hasRemaining()) { deadline(deadline); channel.write(block); }
            }
            if (count != manifest.size() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.checksum())) {
                throw new NativeBundleException(Code.BINARY_MISMATCH);
            }
        }
        checkFile(false);
        Files.getFileAttributeView(executable, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setPermissions(EXECUTABLE);
        checkFile(true);
    }
    Path validatedExecutable() throws IOException {
        checkFile(true);
        long deadline = System.nanoTime() + TIMEOUT;
        MessageDigest digest = digest();
        try (var channel = Files.newByteChannel(executable, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer bytes = ByteBuffer.allocate(32768);
            long count = 0;
            while (true) {
                deadline(deadline);
                int read = channel.read(bytes);
                if (read < 0) break;
                count += read;
                if (count > manifest.size()) throw new NativeBundleException(Code.BINARY_MISMATCH);
                digest.update(bytes.array(), 0, read);
                bytes.clear();
            }
            if (count != manifest.size() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.checksum())) {
                throw new NativeBundleException(Code.BINARY_MISMATCH);
            }
        }
        checkFile(true);
        return executable;
    }
    void checkLaunchedIdentity() throws IOException { checkFile(true); }
    private void checkDirectory() throws IOException {
        PosixFileAttributes actual = attrs(directory);
        if (closed || !actual.isDirectory() || !root.fileKey().equals(actual.fileKey())
                || !root.owner().equals(actual.owner()) || !DIRECTORY.equals(actual.permissions())) {
            throw new NativeBundleException(Code.FILE_IDENTITY_CHANGED);
        }
    }
    private void checkFile(boolean ready) throws IOException {
        checkDirectory();
        PosixFileAttributes actual = attrs(executable);
        Object links = Files.getAttribute(executable, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (fileKey == null || !actual.isRegularFile() || !fileKey.equals(actual.fileKey())
                || !root.owner().equals(actual.owner()) || !(links instanceof Number count) || count.longValue() != 1
                || !(ready ? EXECUTABLE : WRITABLE).equals(actual.permissions())
                || ready && actual.size() != manifest.size()) throw new NativeBundleException(Code.FILE_IDENTITY_CHANGED);
    }
    private static PosixFileAttributes attrs(Path path) throws IOException {
        return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    private static MessageDigest digest() throws NativeBundleException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED); }
    }
    private static void deadline(long deadline) throws NativeBundleException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw new NativeBundleException(Code.EXTRACTION_TIMEOUT);
    }
    @Override public void close() throws NativeBundleException {
        if (closed) return;
        if (!closing.compareAndSet(false, true)) throw new NativeBundleException(Code.CLEANUP_BUSY);
        try {
            if (closed) return;
            checkDirectory();
            if (Files.exists(executable, LinkOption.NOFOLLOW_LINKS)) {
                PosixFileAttributes actual = attrs(executable);
                if (fileKey == null || !actual.isRegularFile() || !fileKey.equals(actual.fileKey())
                        || !root.owner().equals(actual.owner())) throw new NativeBundleException(Code.FILE_IDENTITY_CHANGED);
                // A linked/replaced executable is not owned cleanup work.
                Object links = Files.getAttribute(executable, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (!(links instanceof Number count) || count.longValue() != 1) throw new NativeBundleException(Code.FILE_IDENTITY_CHANGED);
                Files.delete(executable);
            }
            checkDirectory();
            // Non-recursive deletion refuses unknown contents; never remove a
            // replacement directory, even if its path is still the old path.
            Files.delete(directory);
            closed = true;
            SLOTS.release();
        } catch (NativeBundleException error) { throw error; }
        catch (IOException | RuntimeException error) { throw new NativeBundleException(Code.CLEANUP_FAILED); }
        finally { closing.set(false); }
    }
}
