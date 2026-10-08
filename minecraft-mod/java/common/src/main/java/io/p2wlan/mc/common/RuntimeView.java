package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;

/** Immutable selection of a native runtime, never a peer/path state owner. */
public record RuntimeView(String owner, String runtime) {
    public RuntimeView {
        if (owner == null || !owner.matches("[a-f0-9]{32}") || runtime == null || !runtime.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("INVALID_RUNTIME_SELECTION");
        }
    }
    public static Optional<RuntimeView> ready(AgentClient client) {
        return client.snapshot().flatMap(value -> {
            JsonObject data = value.data();
            String phase = text(data, "phase"), runtime = text(data, "runtime");
            return "ready".equals(phase) && runtime != null && runtime.matches("[a-f0-9]{32}")
                    ? Optional.of(new RuntimeView(value.owner(), runtime)) : Optional.empty();
        });
    }
    public boolean isCurrent(AgentClient client) { return ready(client).filter(this::equals).isPresent(); }
    public JsonObject arguments() { JsonObject args = new JsonObject(); args.addProperty("runtime", runtime); return args; }
    public static String text(JsonObject data, String name) {
        JsonElement value = data.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
