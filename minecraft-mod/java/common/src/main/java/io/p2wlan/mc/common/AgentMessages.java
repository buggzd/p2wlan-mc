package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Pure strict schema decoding. No process, Minecraft, or path-state ownership. */
final class AgentMessages {
    sealed interface Message permits Owned, StartupError {}
    sealed interface Owned extends Message permits Hello, Reply, Status { String owner(); }
    record StartupError(String error, StartupDiagnostic diagnostic) implements Message {}
    record Hello(String owner) implements Owned {}
    record Reply(String owner, long id, JsonObject result, String error) implements Owned {}
    record Status(String owner, long revision, JsonObject data) implements Owned {}
    private static final Set<String> STARTUP_CODES = Set.of("PROFILE_IO", "PROFILE_INVALID", "PROFILE_BUSY",
            "PROFILE_CAPACITY", "PROFILE_UNSUPPORTED_PLATFORM", "CONTROL_UNAVAILABLE", "ENTROPY_UNAVAILABLE");
    static boolean startupCode(String code) { return code != null && STARTUP_CODES.contains(code); }

    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
    private static String text(JsonElement value) throws AgentException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }
    private static long integer(JsonElement value) throws AgentException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { throw invalid(); }
    }
    private static JsonObject object(JsonElement value) throws AgentException {
        if (value == null || !value.isJsonObject()) throw invalid();
        return JsonTrees.copy(value.getAsJsonObject());
    }
    private static void fields(JsonObject message, String... expected) throws AgentException {
        if (!JsonTrees.fields(message, Set.of(expected))) throw invalid();
    }
    static Message parse(JsonObject message) throws AgentException {
        if (integer(message.get("version")) != FrameCodec.VERSION) {
            throw new AgentException(AgentException.Code.PROTOCOL_MISMATCH);
        }
        if (message.has("event") && text(message.get("event")).equals("startup_error")) {
            if (message.has("diagnostic")) fields(message, "version", "event", "error", "diagnostic");
            else fields(message, "version", "event", "error");
            String error = text(message.get("error"));
            if (!startupCode(error)) throw invalid();
            return new StartupError(error, message.has("diagnostic") ? StartupDiagnostic.parse(message.get("diagnostic")) : null);
        }
        String owner = text(message.get("owner"));
        if (!owner.matches("[a-f0-9]{32}")) throw invalid();
        if (message.has("event")) {
            return switch (text(message.get("event"))) {
                case "hello" -> {
                    fields(message, "version", "owner", "event");
                    yield new Hello(owner);
                }
                case "status" -> {
                    fields(message, "version", "owner", "event", "revision", "data");
                    long revision = integer(message.get("revision"));
                    if (revision < 0) throw invalid();
                    yield new Status(owner, revision, object(message.get("data")));
                }
                default -> throw invalid();
            };
        }
        long id = integer(message.get("id"));
        JsonElement ok = message.get("ok");
        if (id <= 0 || ok == null || !ok.isJsonPrimitive() || !ok.getAsJsonPrimitive().isBoolean()) throw invalid();
        if (ok.getAsBoolean()) {
            fields(message, "version", "owner", "id", "ok", "result");
            return new Reply(owner, id, object(message.get("result")), null);
        }
        fields(message, "version", "owner", "id", "ok", "error");
        String error = text(message.get("error"));
        if (!error.matches("[A-Z][A-Z0-9_]{0,47}")) throw invalid();
        return new Reply(owner, id, null, error);
    }
}
