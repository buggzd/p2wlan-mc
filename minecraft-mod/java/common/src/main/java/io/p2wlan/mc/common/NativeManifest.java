package io.p2wlan.mc.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Strict bounded package integrity metadata, not a signature/trust boundary. */
record NativeManifest(String platform, String version, String revision, String sourceDigest,
                      String buildKind, String checksum, long size) {
    static final long MAX_BINARY = 128L * 1024 * 1024;
    private static final Set<String> KEYS = Set.of("schema", "protocol", "version", "source-revision",
            "source-sha256", "build-kind", "os", "arch", "executable-sha256", "executable-size", "verification-platform");
    static String currentPlatform() throws NativeBundleException {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }
    static String platform(String os, String arch) throws NativeBundleException {
        String system = switch (os.toLowerCase(Locale.ROOT)) {
            case "mac os x", "darwin" -> "macos";
            case "linux" -> "linux";
            default -> throw new NativeBundleException(Code.PLATFORM_UNSUPPORTED);
        };
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new NativeBundleException(Code.PLATFORM_UNSUPPORTED);
        };
        return system + "-" + cpu;
    }
    static NativeManifest read(InputStream input, String platform, boolean development) throws IOException {
        byte[] bytes = input.readNBytes(4097);
        if (bytes.length == 0 || bytes.length > 4096 || bytes[bytes.length - 1] != '\n') throw invalid();
        for (byte value : bytes) if (value != '\n' && (value < 32 || value > 126)) throw invalid();
        Map<String, String> fields = new HashMap<>();
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        if (lines.length != KEYS.size() + 1) throw invalid();
        for (int index = 0; index < lines.length - 1; index++) {
            String[] pair = lines[index].split("=", -1);
            if (pair.length != 2 || !KEYS.contains(pair[0]) || fields.putIfAbsent(pair[0], pair[1]) != null) throw invalid();
        }
        if (!fields.keySet().equals(KEYS) || !"1".equals(fields.get("schema"))
                || !Integer.toString(FrameCodec.VERSION).equals(fields.get("protocol"))
                || !"0.1.0".equals(fields.get("version"))
                || !fields.get("source-revision").matches("[0-9a-f]{40}")
                || !fields.get("source-sha256").matches("[0-9a-f]{64}")
                || !fields.get("executable-sha256").matches("[0-9a-f]{64}")
                || !fields.get("executable-size").matches("[1-9][0-9]{0,8}")
                || !platform.equals(fields.get("os") + "-" + fields.get("arch"))
                || !platform.equals(fields.get("verification-platform"))) throw invalid();
        String kind = fields.get("build-kind");
        if (!Set.of("release", "development").contains(kind)) throw invalid();
        if (kind.equals("development") && !development) throw new NativeBundleException(Code.DEVELOPMENT_DISABLED);
        long size = Long.parseLong(fields.get("executable-size"));
        if (size > MAX_BINARY) throw invalid();
        return new NativeManifest(platform, fields.get("version"), fields.get("source-revision"),
                fields.get("source-sha256"), kind, fields.get("executable-sha256"), size);
    }
    private static NativeBundleException invalid() { return new NativeBundleException(Code.MANIFEST_INVALID); }
}
