package io.p2wlan.mc.common;

import java.util.List;

/** Actual argument parsing: quoting, literal secret bytes, capacity and fixed failures. */
public final class ConsoleCommandInputTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("CONSOLE_INPUT_" + checks); }
    private static void valid(String input, String... expected) throws Exception {
        check(ConsoleCommandInput.parse(input, expected.length).equals(List.of(expected)));
    }
    private static void invalid(String input, int expected) throws Exception {
        try { ConsoleCommandInput.parse(input, expected); throw new AssertionError("CONSOLE_INPUT_ACCEPTED"); }
        catch (AgentException error) {
            check(error.code() == AgentException.Code.INVALID_REQUEST);
            check(error.getMessage().equals("INVALID_REQUEST"));
        }
    }
    public static void main(String[] args) throws Exception {
        valid("https://control.example.com", "https://control.example.com");
        valid("player@example.com fixture-secret", "player@example.com", "fixture-secret");
        valid("  player@example.com  \" fixture secret \"  ", "player@example.com", " fixture secret ");
        valid("\"A room\" fixture-secret", "A room", "fixture-secret");
        valid("12345678 \"secret\\\"quoted\\\\end\"", "12345678", "secret\"quoted\\end");
        valid("邮箱@example.com 密码测试", "邮箱@example.com", "密码测试");
        valid("player@example.com literal\\secret", "player@example.com", "literal\\secret");
        invalid("", 2); invalid("only-one", 2); invalid("one two three", 2);
        invalid("\"unterminated secret", 2); invalid("\"one\"two secret", 2);
        invalid("one \"invalid\\escape\"", 2); invalid("one \"\"", 2);
        invalid("one\nsecret", 2); invalid("one two\nsecret", 2); invalid("one two\u0000secret", 2);
        invalid("x".repeat(8193), 1); invalid(null, 1);
        invalid("one \ud800", 2); invalid("one \udc00", 2);
        valid("one \ud83d\ude00-secret", "one", "\ud83d\ude00-secret");
        System.out.println("PASS console command input: " + checks + " grammar/privacy checks");
    }
}
