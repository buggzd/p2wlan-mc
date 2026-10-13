package io.p2wlan.mc.legacybootstrap;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Exact, duplicate-rejecting source binding; Java Properties escapes are absent. */
final class LegacyIndex {
    static final String PATH = "META-INF/p2wlan/legacy-core.properties";
    static final int MAXIMUM = 8192;
    final Map<String,String> values;
    private LegacyIndex(Map<String,String> values) { this.values = Map.copyOf(values); }
    static Map<String,String> lines(byte[] bytes, int maximum) {
        if (bytes.length == 0 || bytes.length > maximum) throw new LegacyFailure(Code.CAPACITY);
        for (byte value : bytes) if (value < 0 || value == 0 || value == '\r') throw new LegacyFailure(Code.INDEX_CONTRACT);
        Map<String,String> result = new HashMap<>();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n")) {
            int boundary = line.indexOf('=');
            if (boundary <= 0 || result.putIfAbsent(line.substring(0,boundary), line.substring(boundary+1)) != null) {
                throw new LegacyFailure(Code.INDEX_CONTRACT);
            }
        }
        return Map.copyOf(result);
    }
    static LegacyIndex parse(byte[] bytes) {
        Map<String,String> values = lines(bytes, MAXIMUM);
        Set<String> expected = new HashSet<>(Set.of("schema", "minecraft", "forge", "modlauncher", "java", "mod-version", "source-revision", "native-source-sha256"));
        for (String owner : LegacyInventory.OWNERS.keySet()) for (String key : Set.of("path", "size", "sha256")) expected.add(owner + "." + key);
        if (!values.keySet().equals(expected) || !"1".equals(values.get("schema")) || !"1.16.5".equals(values.get("minecraft"))
                || !"36.2.42".equals(values.get("forge")) || !"8.1.3".equals(values.get("modlauncher"))
                || !"17".equals(values.get("java")) || !"0.1.0".equals(values.get("mod-version"))
                || !values.get("source-revision").matches("[0-9a-f]{40}") || !values.get("native-source-sha256").matches("[0-9a-f]{64}")) {
            throw new LegacyFailure(Code.INDEX_CONTRACT);
        }
        for (var owner : LegacyInventory.OWNERS.entrySet()) {
            String name = owner.getKey();
            if (!owner.getValue().equals(values.get(name + ".path")) || !values.get(name + ".sha256").matches("[0-9a-f]{64}")) {
                throw new LegacyFailure(Code.INDEX_CONTRACT);
            }
            size(values.get(name + ".size"));
        }
        return new LegacyIndex(values);
    }
    static int size(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) throw new LegacyFailure(Code.INDEX_CONTRACT);
        try {
            int size = Integer.parseInt(value);
            if (size > 128 * 1024 * 1024) throw new LegacyFailure(Code.CAPACITY);
            return size;
        } catch (NumberFormatException failure) { throw new LegacyFailure(Code.INDEX_CONTRACT); }
    }
    int ownerSize(String owner) { return size(values.get(owner + ".size")); }
    String digest(String owner) { return values.get(owner + ".sha256"); }
}
