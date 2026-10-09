package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Stateless presentation of a freshly fenced native account projection.
 * A saved session is NOT proof that Control currently accepts its token.
 * Callers must recheck the process owner; never cache this classification. */
public final class AccountPresentation {
    public enum State { UNKNOWN, NO_SESSION, SAVED_SESSION }
    private AccountPresentation() {}
    public static State state(JsonElement projected) {
        if (projected == null || !projected.isJsonObject()) return State.UNKNOWN;
        JsonObject account = projected.getAsJsonObject();
        JsonElement authenticated = account.get("authenticated");
        if (authenticated == null || !authenticated.isJsonPrimitive()
                || !authenticated.getAsJsonPrimitive().isBoolean()) return State.UNKNOWN;
        if (!authenticated.getAsBoolean()) {
            return account.keySet().equals(Set.of("authenticated")) ? State.NO_SESSION : State.UNKNOWN;
        }
        return account.keySet().equals(Set.of("authenticated", "server", "user_id"))
                && boundedText(account.get("server"), 2048) && boundedText(account.get("user_id"), 128)
                ? State.SAVED_SESSION : State.UNKNOWN;
    }
    private static boolean boundedText(JsonElement value, int maximum) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isEmpty() && value.getAsString().getBytes(StandardCharsets.UTF_8).length <= maximum;
    }
    public static String summary(State state) {
        return switch (state) {
            case UNKNOWN -> "账号：状态未知 / Account: unknown";
            case NO_SESSION -> "账号：未保存登录会话 / No saved session";
            case SAVED_SESSION -> "账号：已保存会话（未核验） / Session saved";
        };
    }
    public static boolean canAuthenticate(State state, boolean idle, boolean busy, boolean filled) {
        return state != State.UNKNOWN && idle && !busy && filled;
    }
    public static boolean canRegister(State state, boolean idle, boolean busy, boolean filled) {
        return state == State.NO_SESSION && canAuthenticate(state, idle, busy, filled);
    }
    public static boolean canUseAccount(State state, boolean idle, boolean busy) {
        return state == State.SAVED_SESSION && idle && !busy;
    }
}
