package io.p2wlan.mc.game.fixture;

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

/** One bounded installed-file provenance check before game assertions. */
public final class InstalledArtifact {
    private static final long MAX_ARCHIVE = 144L * 1024 * 1024;
    private InstalledArtifact() {}
    public static Path checked(Path gameDirectory) {
        String selected = System.getProperty("p2wlan.gameFixture.installedArchive");
        String expected = System.getProperty("p2wlan.gameFixture.installedSha256");
        if (selected == null && expected == null) return null;
        if (selected == null || expected == null || !expected.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("INSTALLED_ARCHIVE_ARGUMENTS");
        }
        try {
            Path archive = Path.of(selected);
            Path mods = gameDirectory.toAbsolutePath().normalize().resolve("mods");
            if (!archive.isAbsolute() || !archive.normalize().equals(archive) || !mods.equals(archive.getParent())
                    || !archive.getFileName().toString().endsWith(".jar") || Files.isSymbolicLink(archive)) {
                throw new IllegalStateException("INSTALLED_ARCHIVE_PATH");
            }
            var before = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() < 1 || before.size() > MAX_ARCHIVE) {
                throw new IllegalStateException("INSTALLED_ARCHIVE_FILE");
            }
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = FileChannel.open(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = input.size();
                if (size != before.size()) throw new IllegalStateException("INSTALLED_ARCHIVE_CHANGED");
                var buffer = ByteBuffer.allocate(32768);
                long count = 0;
                while (count < size) {
                    buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), size - count));
                    int read = input.read(buffer);
                    if (read <= 0) throw new IllegalStateException("INSTALLED_ARCHIVE_CHANGED");
                    count += read; buffer.flip(); digest.update(buffer);
                }
                if (input.size() != size) throw new IllegalStateException("INSTALLED_ARCHIVE_CHANGED");
            }
            var after = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size() || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !HexFormat.of().formatHex(digest.digest()).equals(expected)) {
                throw new IllegalStateException("INSTALLED_ARCHIVE_CHANGED");
            }
            return archive;
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException | SecurityException failure) {
            throw new IllegalStateException("INSTALLED_ARCHIVE_IO");
        }
    }
}
