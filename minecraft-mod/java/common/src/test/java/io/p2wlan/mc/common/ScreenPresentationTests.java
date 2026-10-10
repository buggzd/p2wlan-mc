package io.p2wlan.mc.common;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CompletionException;

/** No Minecraft, helper, account files or network. These are presentation tests,
 * not acceptance of server authentication or a real game's rendered pixels. */
public final class ScreenPresentationTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("presentation check " + checks); }
    private static JsonObject account(boolean saved) {
        JsonObject value = new JsonObject(); value.addProperty("authenticated", saved);
        if (saved) { value.addProperty("server", "http://control.example.com:18080"); value.addProperty("user_id", "fixture-user"); }
        return value;
    }
    private static AgentException remote(String code) { return new AgentException(AgentException.Code.REMOTE_REJECTED, code); }
    private static void accounts() {
        check(AccountPresentation.state(null) == AccountPresentation.State.UNKNOWN);
        check(AccountPresentation.state(JsonNull.INSTANCE) == AccountPresentation.State.UNKNOWN);
        for (String value : new String[]{"[]", "true", "{}", "{\"authenticated\":\"true\"}", "{\"authenticated\":1}", "{\"authenticated\":null}"}) {
            check(AccountPresentation.state(JsonParser.parseString(value)) == AccountPresentation.State.UNKNOWN);
        }
        check(AccountPresentation.state(account(false)) == AccountPresentation.State.NO_SESSION);
        check(AccountPresentation.state(account(true)) == AccountPresentation.State.SAVED_SESSION);
        check(AccountPresentation.server(account(true)).equals("http://control.example.com:18080"));
        check(AccountPresentation.server(account(false)).isEmpty());
        check(AccountPresentation.server(null).isEmpty());
        JsonObject secret = account(true); secret.addProperty("token", "never-project");
        check(AccountPresentation.server(secret).isEmpty());
        for (String field : new String[]{"server", "user_id", "authenticated"}) {
            JsonObject missing = account(true); missing.remove(field);
            check(AccountPresentation.state(missing) == AccountPresentation.State.UNKNOWN);
            JsonObject malformed = account(true); malformed.add(field, JsonNull.INSTANCE);
            check(AccountPresentation.state(malformed) == AccountPresentation.State.UNKNOWN);
        }
        for (String field : new String[]{"server", "user_id"}) {
            for (String value : new String[]{"", "x".repeat(field.equals("server") ? 2049 : 129), "界".repeat(field.equals("server") ? 683 : 43)}) {
                JsonObject malformed = account(true); malformed.addProperty(field, value);
                check(AccountPresentation.state(malformed) == AccountPresentation.State.UNKNOWN);
            }
        }
        for (boolean saved : new boolean[]{false, true}) {
            JsonObject extra = account(saved); extra.addProperty("token", "fixture-secret");
            check(AccountPresentation.state(extra) == AccountPresentation.State.UNKNOWN);
        }
        JsonObject latest = account(false);
        check(AccountPresentation.state(latest) == AccountPresentation.State.NO_SESSION);
        latest = account(true); check(AccountPresentation.state(latest) == AccountPresentation.State.SAVED_SESSION);
        latest = account(false); check(AccountPresentation.state(latest) == AccountPresentation.State.NO_SESSION);
        check(AccountPresentation.summary(AccountPresentation.State.UNKNOWN).contains("未知"));
        check(AccountPresentation.summary(AccountPresentation.State.NO_SESSION).contains("未保存"));
        check(AccountPresentation.summary(AccountPresentation.State.SAVED_SESSION).contains("未核验"));
        for (var state : AccountPresentation.State.values()) for (boolean idle : new boolean[]{false, true})
                for (boolean busy : new boolean[]{false, true}) for (boolean filled : new boolean[]{false, true}) {
            check(AccountPresentation.canAuthenticate(state, idle, busy, filled) == (state != AccountPresentation.State.UNKNOWN && idle && !busy && filled));
            check(AccountPresentation.canRegister(state, idle, busy, filled) == (state == AccountPresentation.State.NO_SESSION && idle && !busy && filled));
            check(AccountPresentation.canUseAccount(state, idle, busy) == (state == AccountPresentation.State.SAVED_SESSION && idle && !busy));
        }
    }
    private static void feedback() {
        for (String code : new String[]{"CONTROL_UNAVAILABLE", "CONTROL_TIMEOUT", "CONTROL_RESPONSE", "REQUEST_TIMEOUT", "IO", "UNEXPECTED_EXIT", "CLOSED", "INVALID_REPLY", "SCOPE_CHANGED"}) {
            String result = UiFeedback.failure("register", remote(code));
            check(result.startsWith("注册：")); check(result.contains("是否已建号未知"));
            check(result.contains("请勿重复注册")); check(result.contains("[" + code + "]"));
        }
        for (String code : new String[]{"PROFILE_IO", "PROFILE_INVALID"}) {
            String result = UiFeedback.failure("register", remote(code));
            check(result.contains("本机会话保存失败")); check(result.contains("不要再次注册"));
        }
        check(UiFeedback.failure("rooms", remote("AUTH_REQUIRED")).contains("会话已失效"));
        check(UiFeedback.failure("rooms", remote("AUTH_REQUIRED")).startsWith("读取房间："));
        check(UiFeedback.failure("login", remote("CONTROL_UNAVAILABLE")).startsWith("登录："));
        check(!UiFeedback.failure("login", remote("CONTROL_UNAVAILABLE")).contains("是否已建号未知"));
        check(UiFeedback.failure("register", new CompletionException(remote("CONTROL_TIMEOUT"))).contains("是否已建号未知"));
        for (Throwable error : new Throwable[]{new RuntimeException("fixture-secret /private/path"), remote("fixture-secret /private/path"), null}) {
            String result = UiFeedback.failure("login", error);
            check(!result.contains("fixture-secret")); check(!result.contains("/private/path")); check(result.contains("[INVALID_REPLY]"));
        }
        check(UiFeedback.failure("unexpected-input", remote("FORBIDDEN")).startsWith("操作："));
        check(UiFeedback.working("register").startsWith("注册："));
    }
    private static void layouts() {
        for (int width : new int[]{320, 321, 427, 515, 640, 853, 1280})
                for (int height : new int[]{240, 241, 335, 360, 480, 720, 1080}) {
            ScreenLayout panel = ScreenLayout.of(width, height);
            check(panel.left() >= 0 && panel.left() + panel.width() <= width);
            check(panel.right() >= panel.left() + panel.halfWidth() + ScreenLayout.GAP);
            check(panel.right() + panel.halfWidth() <= panel.left() + panel.width());
            check(panel.top() > 50 + 9); // HTTP warning has its own header row.
            for (int index = 0; index < 4; index++) check(panel.inBody(panel.row(index), 20)); // Account form.
            for (int controls : new int[]{1, 3}) {
                int rows = panel.listRows(controls, 4); check(rows >= 1 && rows <= 4);
                for (int index = 0; index < rows; index++) check(panel.inBody(panel.row(index), 20));
                for (int index = 0; index < controls; index++) check(panel.inBody(panel.controls(rows) + index * 26, 20));
            }
            check(panel.bodyBottom() + ScreenLayout.GAP <= panel.footerY());
            check(panel.footerY() + 20 < panel.messageY());
            check(panel.messageY() + 2 * 11 + 9 <= height - 3);
        }
        // Actual regression: a 640 x 480 physical window at GUI scale 2.
        ScreenLayout minimum = ScreenLayout.of(320, 240);
        check(minimum.listRows(3, 4) == 1);
        check(minimum.footerY() + 20 < minimum.messageY());
    }
    public static void main(String[] arguments) {
        accounts(); feedback(); layouts();
        System.out.println("PASS screen presentation: " + checks + " explicit checks");
    }
}
