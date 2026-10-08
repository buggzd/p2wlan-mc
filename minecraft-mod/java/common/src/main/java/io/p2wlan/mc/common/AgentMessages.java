package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Pure strict schema decoding. No process, Minecraft, or path-state ownership. */
final class AgentMessages {
    sealed interface Message permits Hello, Reply, Status { String owner(); }
    record Hello(String owner) implements Message {}
    record Reply(String owner, long id, JsonObject result, String error) implements Message {}
    record Status(String owner, long revision, JsonObject data) implements Message {}

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
        return value.getAsJsonObject().deepCopy();
    }
    private static void fields(JsonObject message, String... expected) throws AgentException {
        if (!message.keySet().equals(Set.of(expected))) throw invalid();
    }
    static Message parse(JsonObject message) throws AgentException {
        if (integer(message.get("version")) != FrameCodec.VERSION) {
            throw new AgentException(AgentException.Code.PROTOCOL_MISMATCH);
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
