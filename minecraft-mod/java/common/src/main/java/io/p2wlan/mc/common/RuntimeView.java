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
    public static Optional<RuntimeView> ready(AgentClient client) { return selected(client, false); }
    /** Starting runtimes can be stopped before any gateway is ready. */
    public static Optional<RuntimeView> active(AgentClient client) { return selected(client, true); }
    private static Optional<RuntimeView> selected(AgentClient client, boolean starting) {
        if (client == null) return Optional.empty();
        return client.snapshot().flatMap(value -> {
            JsonObject data = value.data();
            String phase = text(data, "phase"), runtime = text(data, "runtime");
            return ("ready".equals(phase) || starting && "starting".equals(phase)) && runtime != null && runtime.matches("[a-f0-9]{32}")
                    ? Optional.of(new RuntimeView(value.owner(), runtime)) : Optional.empty();
        });
    }
    public boolean isCurrent(AgentClient client) { return ready(client).filter(this::equals).isPresent(); }
    public boolean isActive(AgentClient client) { return active(client).filter(this::equals).isPresent(); }
    /** For cleanup requests whose local scope is exactly this runtime. Known
     * unsent/replaced operations cannot justify killing a replacement owner. */
    static boolean staleCleanup(Exception error) {
        if (!(error instanceof AgentException agent)) return false;
        return agent.code() == AgentException.Code.SCOPE_CHANGED || agent.code() == AgentException.Code.REMOTE_REJECTED
                && ("RUNTIME_CHANGED".equals(agent.remoteCode()) || "RUNTIME_NOT_READY".equals(agent.remoteCode()));
    }
    public JsonObject arguments() { JsonObject args = new JsonObject(); args.addProperty("runtime", runtime); return args; }
    public static String text(JsonObject data, String name) {
        JsonElement value = data.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
