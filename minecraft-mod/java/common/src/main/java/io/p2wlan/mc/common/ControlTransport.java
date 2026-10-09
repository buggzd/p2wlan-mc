package io.p2wlan.mc.common;

import com.google.gson.JsonElement;

/** Presentation-only risk classification. Native owns URL admission, account
 * state and networking. This helper does not validate or authorize an origin. */
public final class ControlTransport {
    public static final String WARNING = "WARNING HTTP_UNENCRYPTED: Control uses plain HTTP; passwords, login tokens and signaling may be intercepted or modified. Prefer HTTPS.";
    public static final String BANNER = "HTTP 未加密：密码/凭据/信令可能被监听或篡改";
    private ControlTransport() {}
    public static boolean isPlaintext(String entered) {
        if (entered == null) return false;
        // Match the URL parser's leading C0/space and embedded ASCII-tab/CR/LF
        // preprocessing, without becoming a second origin-validation policy.
        // Only inspect a bounded scheme prefix; never expose the input itself.
        int end = Math.min(entered.length(), 2048), start = 0;
        while (start < end && entered.charAt(start) <= 32) start++;
        StringBuilder scheme = new StringBuilder(5);
        for (int index = start; index < end && scheme.length() < 5; index++) {
            char value = entered.charAt(index);
            if (value != '\t' && value != '\r' && value != '\n') {
                scheme.append(value >= 'A' && value <= 'Z' ? (char) (value + 32) : value);
            }
        }
        return scheme.toString().equals("http:");
    }
    /** Consume a current native-owned account projection, never retain it. */
    public static boolean accountIsPlaintext(JsonElement projected) {
        if (projected == null || !projected.isJsonObject()) return false;
        var account = projected.getAsJsonObject();
        var signedIn = account.get("authenticated"); var server = account.get("server");
        return signedIn != null && signedIn.isJsonPrimitive() && signedIn.getAsJsonPrimitive().isBoolean()
                && signedIn.getAsBoolean() && server != null && server.isJsonPrimitive()
                && server.getAsJsonPrimitive().isString() && isPlaintext(server.getAsString());
    }
}
