package io.p2wlan.mc.common;

import com.google.gson.JsonObject;

/** Immutable command completion, retaining the native owner and request ID.
 * Recheck AgentClient.isCurrent on the destination thread before a side effect. */
public record AgentReply(String owner, long id, JsonObject data) {
    public AgentReply {
        if (owner == null || !owner.matches("[a-f0-9]{32}") || id <= 0 || data == null) {
            throw new IllegalArgumentException("INVALID_AGENT_REPLY");
        }
        data = data.deepCopy();
    }
    @Override public JsonObject data() { return data.deepCopy(); }
}
