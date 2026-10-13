package io.p2wlan.mc.legacybootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** One immutable identity for a local source or its exact extracted child. */
final class OwnerFile {
    final Path path;
    final Object fileKey;
    final long size;
    final java.nio.file.attribute.FileTime modified;
    final String sha256;
    final byte[] bytes;
    private OwnerFile(Path path, BasicFileAttributes attributes, byte[] bytes) {
        this.path = path; fileKey = attributes.fileKey(); size = attributes.size();
        modified = attributes.lastModifiedTime(); this.bytes = bytes; sha256 = digest(bytes, "SHA-256");
    }
    static OwnerFile capture(Path path, long maximum, String expected) {
        Path local = local(path);
        try {
            BasicFileAttributes before = attributes(local);
            if (!before.isRegularFile() || before.size() <= 0 || before.size() > maximum) throw new LegacyFailure(Code.CAPACITY);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
            try (FileChannel channel = FileChannel.open(local, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer block = ByteBuffer.allocate(32768);
                while (channel.read(block) != -1) {
                    block.flip();
                    if ((long) output.size() + block.remaining() > maximum) throw new LegacyFailure(Code.CAPACITY);
                    output.write(block.array(), 0, block.remaining()); block.clear();
                }
            }
            BasicFileAttributes after = attributes(local);
            if (!same(before, after) || output.size() != before.size()) throw new LegacyFailure(Code.OWNER_CHANGED);
            OwnerFile result = new OwnerFile(local, after, output.toByteArray());
            if (expected != null && !result.sha256.equals(expected)) throw new LegacyFailure(Code.CONTENT_IDENTITY);
            return result;
        } catch (IOException failure) { throw new LegacyFailure(Code.SOURCE_IDENTITY); }
    }
    void requireCurrent(boolean content) {
        try {
            BasicFileAttributes actual = attributes(path);
            if (!actual.isRegularFile() || !Objects.equals(fileKey, actual.fileKey())
                    || size != actual.size() || !modified.equals(actual.lastModifiedTime())) throw new LegacyFailure(Code.OWNER_CHANGED);
            if (content) {
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException impossible) { throw new LegacyFailure(Code.CONTENT_IDENTITY); }
                long count = 0;
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer block = ByteBuffer.allocate(32768);
                    while (channel.read(block) != -1) {
                        block.flip(); count += block.remaining();
                        if (count > size) throw new LegacyFailure(Code.OWNER_CHANGED);
                        digest.update(block); block.clear();
                    }
                }
                if (count != size || !HexFormat.of().formatHex(digest.digest()).equals(sha256)
                        || !same(actual, attributes(path))) throw new LegacyFailure(Code.OWNER_CHANGED);
            }
        } catch (IOException failure) { throw new LegacyFailure(Code.OWNER_CHANGED); }
    }
    static Path local(Path path) {
        if (path == null || !path.isAbsolute() || !"file".equals(path.toUri().getScheme()) || path.toUri().getAuthority() != null) {
            throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
        }
        Path normalized = path.normalize();
        for (Path current = normalized; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
        }
        return normalized;
    }
    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return a.isRegularFile() == b.isRegularFile() && Objects.equals(a.fileKey(), b.fileKey())
                && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime());
    }
    static String digest(byte[] bytes, String algorithm) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new LegacyFailure(Code.CONTENT_IDENTITY); }
    }
}
