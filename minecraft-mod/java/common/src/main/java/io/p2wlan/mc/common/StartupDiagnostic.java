package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Optional ownerless failure evidence. Never grants native session authority. */
public record StartupDiagnostic(String stage, String operation, String target, Integer osCode, Integer pathDepth) {
    private static final Set<String> STAGES = Set.of("PROFILE_OPEN", "CONTROL_INIT", "IDENTITY_INIT");
    private static final Set<String> OPERATIONS = Set.of("NONE", "OPEN_FILE", "CREATE_DIRECTORY", "FILE_IDENTITY",
            "VOLUME_INFORMATION", "RENAME_FILE", "DISCARD_TEMPORARY", "OPEN_PROCESS_TOKEN", "TOKEN_INFORMATION",
            "SID_STRING", "SECURITY_DESCRIPTOR", "SECURITY_INFORMATION", "FILE_METADATA", "READ_FILE",
            "WRITE_FILE", "SYNC_FILE", "READ_DIRECTORY", "READ_DIRECTORY_ENTRY", "RESOLVE_PATH", "REMOVE_FILE");
    private static final Set<String> TARGETS = Set.of("UNKNOWN", "PROCESS_TOKEN", "ANCESTOR", "PROFILE_DIRECTORY",
            "PROFILE_LOCK", "ACCOUNT_FILE", "ROOM_FILE", "TEMPORARY_FILE");
    public StartupDiagnostic {
        if (!STAGES.contains(stage) || !OPERATIONS.contains(operation) || !TARGETS.contains(target)
                || pathDepth != null && (pathDepth < 0 || pathDepth > 64)) throw new IllegalArgumentException("INVALID_DIAGNOSTIC");
    }
    static StartupDiagnostic parse(JsonElement value) throws AgentException {
        try {
            if (value == null || !value.isJsonObject()) throw new IllegalArgumentException();
            JsonObject data = value.getAsJsonObject();
            if (!data.keySet().equals(Set.of("stage", "operation", "target", "os_code", "path_depth"))) throw new IllegalArgumentException();
            return new StartupDiagnostic(text(data.get("stage")), text(data.get("operation")), text(data.get("target")),
                    integer(data.get("os_code")), integer(data.get("path_depth")));
        } catch (RuntimeException error) { throw new AgentException(AgentException.Code.INVALID_REPLY); }
    }
    private static String text(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }
    private static Integer integer(JsonElement value) {
        if (value == null) throw new IllegalArgumentException();
        if (value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }
    public String safeText() {
        return "stage=" + stage + " operation=" + operation + " target=" + target
                + " os_code=" + (osCode == null ? "unknown" : osCode)
                + " path_depth=" + (pathDepth == null ? "unknown" : pathDepth);
    }
}
