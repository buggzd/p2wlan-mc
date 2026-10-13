package io.p2wlan.mc.common;

import com.google.gson.JsonObject;

/** Immutable view of native-owned state, fenced by native process and revision. */
public record StatusSnapshot(String owner, long revision, JsonObject data) {
    public StatusSnapshot {
        if (owner == null || !owner.matches("[a-f0-9]{32}") || revision < 0 || data == null) {
            throw new IllegalArgumentException("INVALID_SNAPSHOT");
        }
        data = JsonTrees.copy(data);
    }
    @Override public JsonObject data() { return JsonTrees.copy(data); }
}
