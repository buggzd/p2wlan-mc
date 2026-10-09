package io.p2wlan.mc.common;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

/** Explicit scheme/warning checks, not an alternate URL admission policy. */
public final class ControlTransportTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("transport check " + checks); }
    public static void main(String[] arguments) {
        for (String value : new String[] {"http://control.example.com", "http://127.0.0.1:18080", "http://203.0.113.1:18080",
                "HTTP://example.com", "  HTTP://example.com", "\t\r\nHTTP://example.com", "h\tt\nt\rp://example.com",
                "\u0000http://example.com", "http:", "http://name:password@example.com/path?token=secret"}) {
            check(ControlTransport.isPlaintext(value));
        }
        for (String value : new String[] {"", "https://example.com", " HTTPS://example.com", "ftp://example.com",
                "https://example.com/http://other", "example.com", "htt p://example.com", "httpx://example.com",
                "h ttp://example.com", "ht\u0000tp://example.com", " ".repeat(2048) + "http://example.com"}) {
            check(!ControlTransport.isPlaintext(value));
        }
        check(!ControlTransport.isPlaintext(null));
        check(!ControlTransport.accountIsPlaintext(null)); check(!ControlTransport.accountIsPlaintext(JsonNull.INSTANCE));
        for (String value : new String[] {"[]", "12", "true", "{}", "{\"authenticated\":false}",
                "{\"authenticated\":false,\"server\":\"http://example.com\"}",
                "{\"authenticated\":\"true\",\"server\":\"http://example.com\"}",
                "{\"authenticated\":true,\"server\":12}", "{\"authenticated\":true,\"server\":null}",
                "{\"authenticated\":true,\"server\":\"https://example.com\"}"}) {
            check(!ControlTransport.accountIsPlaintext(JsonParser.parseString(value)));
        }
        var account = JsonParser.parseString("{\"authenticated\":true,\"server\":\"http://example.com\",\"user_id\":\"fixture-secret\"}");
        check(ControlTransport.accountIsPlaintext(account));
        account.getAsJsonObject().addProperty("server", "https://example.com");
        check(!ControlTransport.accountIsPlaintext(account)); // Never cached independently.
        check(ControlTransport.WARNING.startsWith("WARNING HTTP_UNENCRYPTED: "));
        check(!ControlTransport.WARNING.contains("example.com") && !ControlTransport.WARNING.contains("fixture-secret"));
        check(ControlTransport.WARNING.contains("passwords") && ControlTransport.WARNING.contains("tokens") && ControlTransport.WARNING.contains("signaling"));
        System.out.println("PASS control transport warnings: " + checks + " explicit checks");
    }
}
