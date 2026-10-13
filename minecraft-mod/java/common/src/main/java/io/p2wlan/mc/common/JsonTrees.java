package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.util.Set;

/** Detached JSON values through the same bounded IPC codec on every Gson SDK.
 * No retained tree, runtime selection, or network/session ownership. */
public final class JsonTrees {
    private JsonTrees() {}

    public static JsonObject copy(JsonObject value) {
        if (value == null) throw new IllegalArgumentException("INVALID_JSON_SNAPSHOT");
        try {
            return FrameCodec.copy(value);
        } catch (ProtocolException | RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_JSON_SNAPSHOT");
        }
    }

    /** Exact fields without JsonObject.keySet(), which is absent in Gson 2.8.0.
     * Size is checked first, so an oversized caller tree is never enumerated. */
    public static boolean fields(JsonObject value, Set<String> expected) {
        if (value == null || expected == null || value.size() != expected.size()) return false;
        for (var entry : value.entrySet()) if (!expected.contains(entry.getKey())) return false;
        return true;
    }
}
