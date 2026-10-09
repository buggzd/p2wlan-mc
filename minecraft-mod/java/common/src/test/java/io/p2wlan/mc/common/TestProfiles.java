package io.p2wlan.mc.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Fixture-owned profiles only; never used by the production lifecycle. */
final class TestProfiles {
    static Path create(String prefix) throws Exception {
        if (!NativeManifest.currentPlatform().equals("windows-x86_64")) {
            return Files.createTempDirectory(prefix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Path parent = WindowsNativeInstallation.supported(Path.of(System.getProperty("java.io.tmpdir")));
        Path directory = parent.resolve(prefix + UUID.randomUUID());
        try (var descriptor = new WindowsNativeCalls.Descriptor(WindowsNativeCalls.currentUser(), WindowsNativePolicy.FULL_ACCESS)) {
            WindowsNativeCalls.directory(directory, descriptor);
        }
        return directory;
    }
    static void dispose(Path profile) throws Exception {
        // These harnesses only ping; they never create account/room state.
        // Refuse foreign contents rather than recursively cleaning a profile.
        try (var files = Files.list(profile)) {
            var entries = files.limit(3).toList();
            if (entries.size() > 2 || entries.stream().anyMatch(path ->
                    !java.util.Set.of("profile.lock", "account.json").contains(path.getFileName().toString())
                    || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("unexpected fixture profile contents");
            }
            for (Path file : entries) Files.delete(file);
        }
        Files.delete(profile);
    }
}
