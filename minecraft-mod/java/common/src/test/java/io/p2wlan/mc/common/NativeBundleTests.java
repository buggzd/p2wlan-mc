package io.p2wlan.mc.common;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import static io.p2wlan.mc.common.NativeBundleException.Code;

public final class NativeBundleTests {
    private static int checks;
    private static final byte[] CONTENT = "fixture executable".getBytes(StandardCharsets.US_ASCII);
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (NativeBundleException error) { check(error.code() == code); }
    }
    private static String text() throws Exception {
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        return "schema=1\nprotocol=1\nversion=0.1.0\nsource-revision=" + "a".repeat(40)
            + "\nsource-sha256=" + "b".repeat(64) + "\nbuild-kind=development\nos=macos\narch=aarch64\nexecutable-sha256="
            + checksum + "\nexecutable-size=" + CONTENT.length + "\nverification-platform=macos-aarch64\n";
    }
    private static NativeManifest parse(String value, boolean development) throws Exception {
        return NativeManifest.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), "macos-aarch64", development);
    }
    private static NativeInstallation extract(Path parent) throws Exception {
        return NativeInstallation.extract(parse(text(), true), new ByteArrayInputStream(CONTENT), parent);
    }
    public static void main(String[] arguments) throws Exception {
        check(NativeManifest.platform("Mac OS X", "aarch64").equals("macos-aarch64"));
        check(NativeManifest.platform("Linux", "amd64").equals("linux-x86_64"));
        reject(Code.PLATFORM_UNSUPPORTED, () -> NativeManifest.platform("Windows 11", "amd64"));
        reject(Code.PLATFORM_UNSUPPORTED, () -> NativeManifest.platform("Linux", "riscv64"));
        check(parse(text(), true).size() == CONTENT.length);
        reject(Code.DEVELOPMENT_DISABLED, () -> parse(text(), false));
        check(parse(text().replace("development", "release"), false).buildKind().equals("release"));
        for (String bad : new String[] {text().replace("schema=1", "schema=2"), text().replace("protocol=1", "protocol=2"),
                text().replace("version=0.1.0", "version=9.0.0"), text().replace("arch=aarch64", "arch=x86_64"),
                text().replace("verification-platform=macos-aarch64", "verification-platform=linux-aarch64"),
                text().replace("executable-size=18", "executable-size=0"), text().replace("executable-size=18", "executable-size=134217729"),
                text().replace("build-kind=development", "build-kind=unknown"), text().replace("protocol=1", "schema=1"),
                text() + "extra=x\n", text().replace("\n", "\r\n"), "x".repeat(4097), text().replace("schema=1", "schema=é"),
                text().replace("schema=1", "schema=1=2"), text().substring(0, text().length() - 1)}) {
            reject(Code.MANIFEST_INVALID, () -> parse(bad, true));
        }
        Path parent = Files.createTempDirectory("p2wlan-bundle-test-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            NativeInstallation first = extract(parent);
            NativeInstallation second = extract(parent);
            reject(Code.CAPACITY, () -> extract(parent));
            check(Files.getPosixFilePermissions(first.directory).equals(PosixFilePermissions.fromString("rwx------")));
            check(Files.getPosixFilePermissions(first.executable).equals(PosixFilePermissions.fromString("r-x------")));
            check(java.util.Arrays.equals(Files.readAllBytes(first.validatedExecutable()), CONTENT));
            Path removed = first.directory;
            first.close(); first.close(); second.close();
            check(!Files.exists(removed, LinkOption.NOFOLLOW_LINKS));
            reject(Code.BINARY_MISMATCH, () -> NativeInstallation.extract(parse(text(), true), new ByteArrayInputStream(new byte[1]), parent));
            reject(Code.BINARY_MISMATCH, () -> NativeInstallation.extract(parse(text(), true), new ByteArrayInputStream(new byte[19]), parent));
            reject(Code.BINARY_MISMATCH, () -> NativeInstallation.extract(parse(text(), true), new ByteArrayInputStream(new byte[18]), parent));
            try (var paths = Files.list(parent)) { check(paths.findAny().isEmpty()); }
            try (NativeInstallation item = extract(parent)) {
                Files.setPosixFilePermissions(item.executable, PosixFilePermissions.fromString("rw-------"));
                Files.write(item.executable, new byte[18]);
                Files.setPosixFilePermissions(item.executable, PosixFilePermissions.fromString("r-x------"));
                reject(Code.BINARY_MISMATCH, item::validatedExecutable);
            }
            try (NativeInstallation item = extract(parent)) {
                Path original = item.directory.resolve("saved");
                Files.move(item.executable, original);
                Files.write(item.executable, CONTENT);
                reject(Code.FILE_IDENTITY_CHANGED, item::validatedExecutable);
                reject(Code.FILE_IDENTITY_CHANGED, item::close);
                check(Files.exists(item.executable) && Files.exists(original));
                Files.delete(item.executable); Files.move(original, item.executable);
                Path link = parent.resolve("hardlink");
                Files.createLink(link, item.executable);
                reject(Code.FILE_IDENTITY_CHANGED, item::validatedExecutable);
                reject(Code.FILE_IDENTITY_CHANGED, item::close);
                Files.delete(link);
                Files.move(item.executable, original);
                Files.createSymbolicLink(item.executable, original);
                reject(Code.FILE_IDENTITY_CHANGED, item::validatedExecutable);
                reject(Code.FILE_IDENTITY_CHANGED, item::close);
                Files.delete(item.executable); Files.move(original, item.executable);
            }
            try (NativeInstallation item = extract(parent)) {
                Path original = parent.resolve("original");
                Files.move(item.directory, original);
                Files.createDirectory(item.directory);
                Path foreign = item.directory.resolve("foreign"); Files.write(foreign, CONTENT);
                reject(Code.FILE_IDENTITY_CHANGED, item::validatedExecutable);
                reject(Code.FILE_IDENTITY_CHANGED, item::close);
                check(Files.exists(foreign));
                Files.delete(foreign); Files.delete(item.directory); Files.move(original, item.directory);
            }
            try (NativeInstallation item = extract(parent)) {
                Path unknown = item.directory.resolve("unknown");
                Files.write(unknown, CONTENT);
                reject(Code.CLEANUP_FAILED, item::close);
                check(Files.exists(unknown));
                Files.delete(unknown);
            }
            try (var paths = Files.list(parent)) { check(paths.findAny().isEmpty()); }
        } finally { Files.delete(parent); }
        if (arguments.length == 1 && arguments[0].equals("--bundled")) {
            Path profile = Files.createTempDirectory("p2wlan-bundled-profile-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try {
                String platform = NativeManifest.currentPlatform();
                NativeManifest manifest;
                try (var input = NativeBundleTests.class.getClassLoader().getResourceAsStream(
                        "p2wlan/native/" + platform + "/manifest.properties")) {
                    check(input != null);
                    manifest = NativeManifest.read(input, platform, true);
                }
                boolean development = manifest.buildKind().equals("development");
                if (development) reject(Code.DEVELOPMENT_DISABLED, () -> BundledAgent.launch(profile, false));
                // Release resources must exercise extraction, IPC and cleanup without opt-in.
                for (int iteration = 0; iteration < 3; iteration++) {
                    BundledAgent owner = BundledAgent.launch(profile, development);
                    try {
                        owner.client().ready().get(5, TimeUnit.SECONDS);
                        check(owner.client().isCurrent(owner.client().request("ping", new com.google.gson.JsonObject()).get(5, TimeUnit.SECONDS)));
                    } finally { owner.close(); }
                    owner.released().get(5, TimeUnit.SECONDS);
                    check(owner.client().terminated().isDone());
                    check(ProcessHandle.of(owner.client().processId()).isEmpty());
                }
                for (int iteration = 0; iteration < 3; iteration++) {
                    BundledAgent owner = BundledAgent.launch(profile, development);
                    owner.close();
                    owner.released().get(5, TimeUnit.SECONDS);
                    check(owner.client().terminated().isDone());
                }
            } finally {
                // Only fixture-owned known profile files, never recursive cleanup.
                try (var files = Files.list(profile)) { for (Path path : files.toList()) Files.delete(path); }
                Files.delete(profile);
            }
        }
        System.out.println("PASS native bundle: " + checks + " explicit checks" + (arguments.length == 1 ? " including actual bundled subprocesses" : ""));
    }
}
