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
import java.util.HexFormat;
import java.util.Set;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** POSIX executable identities and side effects; lifetime/capacity belong to NativeInstallation. */
final class PosixNativeInstallation extends NativeInstallation {
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> WRITABLE = PosixFilePermissions.fromString("rw-------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("r-x------");
    private final PosixFileAttributes root;
    private Object fileKey;

    private PosixNativeInstallation(Path directory, PosixFileAttributes root, NativeManifest manifest, Slot slot) {
        super(directory, manifest, slot);
        this.root = root;
    }
    static void supported(Path base) throws IOException {
        if (!Files.getFileStore(base).supportsFileAttributeView(PosixFileAttributeView.class)
                || !base.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED);
        }
    }
    static PosixNativeInstallation create(Path base, NativeManifest manifest, Slot slot) throws IOException {
        Path directory = Files.createTempDirectory(base, "p2wlan-native-", PosixFilePermissions.asFileAttribute(DIRECTORY));
        slot.created();
        PosixFileAttributes attributes = attrs(directory);
        if (!attributes.isDirectory() || attributes.fileKey() == null || !attributes.permissions().equals(DIRECTORY)) {
            throw new NativeBundleException(Code.FILESYSTEM_UNSUPPORTED);
        }
        return new PosixNativeInstallation(directory, attributes, manifest, slot);
    }
    @Override void write(InputStream input) throws IOException {
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
    @Override Path validate() throws IOException {
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
    @Override void checkIdentity() throws IOException { checkFile(true); }
    private void checkDirectory() throws IOException {
        PosixFileAttributes actual = attrs(directory);
        if (isClosed() || !actual.isDirectory() || !root.fileKey().equals(actual.fileKey())
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
    @Override void cleanup() throws IOException {
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
    }
}
