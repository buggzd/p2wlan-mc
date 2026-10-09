package io.p2wlan.mc.common;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.sun.jna.Native;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Explicit real-Windows harness. Non-Windows execution fails, never skips.
 * Filesystem evidence only. NativeBundleTests separately executes the packaged
 * image via the real Windows loader; neither harness logs into Minecraft. */
public final class WindowsNativeFilesystemTests {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (NativeBundleException error) { check(error.code() == code); }
    }
    private static NativeManifest manifest(byte[] bytes) throws Exception {
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String text = "schema=1\nprotocol=1\nversion=0.1.0\nsource-revision=" + "a".repeat(40)
                + "\nsource-sha256=" + "b".repeat(64) + "\nbuild-kind=development\nos=windows\narch=x86_64\nexecutable-sha256="
                + checksum + "\nexecutable-size=" + bytes.length + "\nverification-platform=windows-x86_64\n";
        return NativeManifest.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)), "windows-x86_64", true);
    }
    private static NativeInstallation extract(Path parent, byte[] bytes) throws Exception {
        return NativeInstallation.extract(manifest(bytes), new ByteArrayInputStream(bytes), parent);
    }
    public static void main(String[] args) throws Exception {
        if (!NativeManifest.currentPlatform().equals("windows-x86_64")) {
            throw new IllegalStateException("WINDOWS_X64_REQUIRED: no NTFS tests were executed");
        }
        if (args.length != 0) throw new IllegalArgumentException("no arguments accepted");
        Path parent = Files.createTempDirectory("p2wlan-windows-filesystem-test-");
        byte[] content = "fixture executable".getBytes(StandardCharsets.US_ASCII);
        try {
            check(WindowsNativeInstallation.supported(parent).equals(parent));
            try (var attributes = new WindowsNativeCalls.Attributes(null)) {
                check(Native.POINTER_SIZE == 8 && attributes.length == 24);
                check(attributes.getPointer().getInt(0) == 24 && attributes.getPointer().getPointer(8) == null
                        && attributes.getPointer().getInt(16) == 0);
            }
            NativeInstallation first = extract(parent, content);
            NativeInstallation second = extract(parent, content);
            reject(Code.CAPACITY, () -> extract(parent, content));
            check(first.executable.getFileName().toString().equals("p2wlan-mc-agent.exe"));
            check(java.util.Arrays.equals(Files.readAllBytes(first.validatedExecutable()), content));
            first.checkLaunchedIdentity(); checks++;
            Path directory = first.directory;
            first.close(); first.close(); second.close();
            check(!Files.exists(directory));
            reject(Code.BINARY_MISMATCH, () -> NativeInstallation.extract(manifest(content), new ByteArrayInputStream(new byte[1]), parent));
            reject(Code.BINARY_MISMATCH, () -> NativeInstallation.extract(manifest(content), new ByteArrayInputStream(new byte[content.length + 1]), parent));
            try (NativeInstallation item = extract(parent, content)) {
                Path original = item.directory.resolve("saved.exe");
                Files.move(item.executable, original);
                Files.write(item.executable, content);
                reject(Code.FILE_IDENTITY_CHANGED, item::validatedExecutable);
                reject(Code.FILE_IDENTITY_CHANGED, item::close);
                check(Files.exists(original) && Files.exists(item.executable));
                Files.delete(item.executable); Files.move(original, item.executable);
                Path link = item.directory.resolve("hardlink.exe");
                Files.createLink(link, item.executable);
                reject(Code.FILE_IDENTITY_CHANGED, item::validatedExecutable);
                reject(Code.FILE_IDENTITY_CHANGED, item::close);
                Files.delete(link);
                item.checkLaunchedIdentity(); checks++;
            }
            try (NativeInstallation item = extract(parent, content)) {
                Path unknown = item.directory.resolve("foreign.txt"); Files.write(unknown, content);
                reject(Code.CLEANUP_FAILED, item::close);
                check(Files.exists(unknown));
                Files.delete(unknown);
                // Owned executable may already be deleted; closing must resume
                // the exact pending cleanup instead of recreating or guessing.
                item.close(); checks++;
            }
            try (var entries = Files.list(parent)) { check(entries.findAny().isEmpty()); }
        } finally { Files.delete(parent); }
        System.out.println("PASS Windows native filesystem: " + checks + " actual NTFS checks (not image-loader/Minecraft acceptance)");
    }
}
