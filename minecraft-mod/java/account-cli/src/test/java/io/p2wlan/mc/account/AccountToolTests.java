package io.p2wlan.mc.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.ControlTransport;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static io.p2wlan.mc.account.AccountCommand.Action.*;

/** Explicit no-Minecraft, no-network input/privacy/schema executable tests. */
public final class AccountToolTests {
    private static int checks;
    private static final Path PROFILE = Path.of(System.getProperty("java.io.tmpdir"), "p2wlan-account-unit-unused");
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("account check " + checks); }
    private interface Throwing { void run() throws Exception; }
    private static void failure(String code, Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("expected failure"); }
        catch (AccountCommand.Failure known) { check(known.getMessage().equals(code)); }
    }
    private static void invalid(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("expected invalid reply"); }
        catch (AgentException known) { check(known.code() == AgentException.Code.INVALID_REPLY); }
    }
    private static final class Input implements AccountCommand.Input {
        final List<char[]> values = new ArrayList<>();
        final List<Boolean> masked = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int index;
        boolean available = true;
        Input(String... texts) { for (String text : texts) values.add(text == null ? null : text.toCharArray()); }
        public boolean available() { return available; }
        public char[] read(String prompt, boolean secret) {
            masked.add(secret);
            check(!prompt.contains("fixture-secret"));
            return values.get(index++);
        }
        void cleared(int count) {
            for (int i = 0; i < count; i++) if (values.get(i) != null) {
                check(Arrays.equals(values.get(i), new char[values.get(i).length]));
            }
        }
    }
    private static AccountCommand prepare(AccountCommand.Action action, Input input) throws AccountCommand.Failure {
        return AccountCommand.prepare(action, input, warning -> {
            check(input.index == 1); // Warn before identifier/password collection.
            input.warnings.add(warning);
        });
    }
    private static AgentReply reply(String json) {
        return new AgentReply("0123456789abcdef0123456789abcdef", 1, JsonParser.parseString(json).getAsJsonObject());
    }
    private static String room() {
        return "{\"id\":\"room-fixture\",\"code\":\"12345678\",\"name\":\"\\u001b[2Jfixture-secret\",\"cidr\":\"10.0.0.0/24\",\"role\":\"\\u001b[2J\"}";
    }
    private static String[] args(String command) { return new String[]{command, "--profile-dir", PROFILE.toString()}; }
    private static void options() throws Exception {
        for (var action : AccountCommand.Action.values()) {
            var options = AccountCommand.Options.parse(args(action.operation.replace('_', '-')));
            check(options.action() == action && options.profile().isAbsolute() && !options.development());
        }
        var development = AccountCommand.Options.parse(new String[]{"status", "--allow-development-native", "--profile-dir", PROFILE.toString()});
        check(development.development());
        for (String[] bad : List.of(new String[]{}, new String[]{"status"}, new String[]{"status", "--profile-dir", "relative"},
                new String[]{"login", "--password", "fixture-secret"}, new String[]{"connect-room", "--profile-dir", PROFILE.toString()},
                new String[]{"status", "--profile-dir", PROFILE.toString(), "--profile-dir"},
                new String[]{"status", "--profile-dir", PROFILE.toString(), "fixture-secret"},
                new String[]{"status", "--profile-dir", PROFILE.toString() + "\n"},
                new String[]{"status", "--profile-dir", PROFILE.toString(), "--allow-development-native", "--allow-development-native"},
                new String[]{"status", "--profile-dir", null})) {
            failure("INVALID_OPTIONS", () -> AccountCommand.Options.parse(bad));
        }
        failure("INVALID_OPTIONS", () -> AccountCommand.Options.parse(null));
    }
    private static void input() throws Exception {
        Input login = new Input("https://example.com", "fixture-account", "fixture-secret");
        try (var command = prepare(LOGIN, login)) {
            check(command.arguments().keySet().equals(java.util.Set.of("server", "identifier", "password")));
            check(command.arguments().get("password").getAsString().equals("fixture-secret"));
            check(login.masked.equals(List.of(false, false, true)));
            login.cleared(3);
            command.close(); check(command.arguments().size() == 0);
        }
        Input register = new Input("https://example.com", "fixture-account", "fixture-secret");
        try (var command = prepare(REGISTER, register)) { check(command.action() == REGISTER); register.cleared(3); }
        check(login.warnings.isEmpty() && register.warnings.isEmpty());
        for (var action : List.of(LOGIN, REGISTER)) {
            Input plaintext = new Input("http://control.example.com:18080", "fixture-account", "fixture-secret");
            try (var command = prepare(action, plaintext)) {
                check(command.arguments().get("server").getAsString().equals("http://control.example.com:18080"));
                check(command.arguments().get("password").getAsString().equals("fixture-secret"));
                check(plaintext.warnings.equals(List.of(ControlTransport.WARNING)));
                check(plaintext.masked.equals(List.of(false, false, true))); plaintext.cleared(3);
            }
        }
        Input create = new Input("fixture room", "");
        try (var command = prepare(CREATE_ROOM, create)) {
            check(command.arguments().get("password").getAsString().isEmpty()); create.cleared(2);
        }
        Input join = new Input("12345678", "fixture-secret");
        try (var command = prepare(JOIN_ROOM, join)) { check(command.action() == JOIN_ROOM); join.cleared(2); }
        Input badCode = new Input("1234567x", "fixture-secret");
        failure("INVALID_INPUT", () -> prepare(JOIN_ROOM, badCode));
        check(badCode.index == 1); badCode.cleared(1);
        for (int end = 0; end < 3; end++) {
            String[] values = {"https://example.com", "fixture-account", "fixture-secret"}; values[end] = null;
            Input cancelled = new Input(values);
            failure("INPUT_CANCELLED", () -> prepare(LOGIN, cancelled));
            check(cancelled.index == end + 1); cancelled.cleared(end + 1);
        }
        Input unavailable = new Input(); unavailable.available = false;
        for (var action : List.of(LOGIN, REGISTER, CREATE_ROOM, JOIN_ROOM)) {
            failure("CONSOLE_REQUIRED", () -> prepare(action, unavailable));
            check(unavailable.index == 0);
        }
        for (var action : List.of(STATUS, ROOMS, LOGOUT)) {
            try (var command = prepare(action, null)) { check(command.arguments().size() == 0); }
        }
        AccountCommand.validate("x".repeat(256).toCharArray(), false); checks++;
        AccountCommand.validate("界".repeat(85).toCharArray(), false); checks++;
        AccountCommand.validate("😀".repeat(64).toCharArray(), false); checks++;
        for (String value : List.of("", "x".repeat(257), "界".repeat(86), "😀".repeat(65), "\uD800", "\uDC00", "\0", "\r", "\n")) {
            failure("INVALID_INPUT", () -> AccountCommand.validate(value.toCharArray(), false));
        }
        Input oversized = new Input("https://example.com", "fixture-account", "😀".repeat(65));
        failure("INVALID_INPUT", () -> prepare(LOGIN, oversized)); oversized.cleared(3);
    }
    private static void receipts() throws Exception {
        String signedIn = "{\"authenticated\":true,\"server\":\"https://example.com\",\"user_id\":\"fixture-secret\"}";
        String signedOut = "{\"authenticated\":false}";
        check(AccountReceipt.parse(LOGIN, reply(signedIn)).equals(List.of("LOGIN_OK")));
        check(AccountReceipt.parse(REGISTER, reply(signedIn)).equals(List.of("REGISTER_OK")));
        check(AccountReceipt.parse(LOGOUT, reply(signedOut)).equals(List.of("LOGOUT_OK")));
        String plaintext = signedIn.replace("https://example.com", "http://example.com:18080");
        check(AccountReceipt.parse(LOGIN, reply(plaintext)).equals(List.of(ControlTransport.WARNING, "LOGIN_OK")));
        check(AccountReceipt.parse(REGISTER, reply(plaintext)).equals(List.of(ControlTransport.WARNING, "REGISTER_OK")));
        check(AccountReceipt.parse(STATUS, reply("{\"phase\":\"idle\",\"failure\":null,\"account\":" + plaintext + "}"))
                .equals(List.of(ControlTransport.WARNING, "STATUS AUTHENTICATED")));
        check(AccountReceipt.parse(LOGIN, reply(plaintext)).stream().noneMatch(line -> line.contains("example.com") || line.contains("fixture-secret")));
        check(AccountReceipt.parse(STATUS, reply("{\"phase\":\"idle\",\"failure\":null,\"account\":" + signedOut + "}"))
                .equals(List.of("STATUS SIGNED_OUT")));
        check(AccountReceipt.parse(STATUS, reply("{\"phase\":\"idle\",\"failure\":\"CONTROL_TIMEOUT\",\"account\":" + signedIn + "}"))
                .equals(List.of("STATUS AUTHENTICATED")));
        check(AccountReceipt.parse(ROOMS, reply("{\"rooms\":[]}")).equals(List.of("NO_ROOMS")));
        check(AccountReceipt.parse(ROOMS, reply("{\"rooms\":[" + room() + "]}")).equals(List.of("ROOM 12345678")));
        check(AccountReceipt.parse(CREATE_ROOM, reply("{\"room\":" + room() + "}")).equals(List.of("ROOM_CREATED 12345678")));
        check(AccountReceipt.parse(JOIN_ROOM, reply("{\"room\":" + room() + "}")).equals(List.of("ROOM_JOINED 12345678")));
        for (String bad : List.of("{}", signedOut, "{\"authenticated\":\"true\"}",
                "{\"authenticated\":true,\"server\":12,\"user_id\":\"a\"}",
                signedIn.replace("\"https://example.com\"", "\"\""), signedIn.replace("\"fixture-secret\"", "\"" + "x".repeat(129) + "\""),
                signedIn.replace("}", ",\"token\":\"fixture-secret\"}"))) invalid(() -> AccountReceipt.parse(LOGIN, reply(bad)));
        invalid(() -> AccountReceipt.parse(LOGOUT, reply(signedIn)));
        for (String bad : List.of("{}", "{\"phase\":\"ready\",\"failure\":null,\"account\":" + signedOut + "}",
                "{\"phase\":\"idle\",\"failure\":\"\\u001b[2J\",\"account\":" + signedOut + "}",
                "{\"phase\":\"idle\",\"failure\":null,\"account\":null}")) invalid(() -> AccountReceipt.parse(STATUS, reply(bad)));
        for (String bad : List.of("{}", "{\"room\":null}", "{\"room\":" + room().replace("12345678", "1234\\u001b[2J") + "}",
                "{\"room\":" + room() + ",\"token\":\"fixture-secret\"}")) invalid(() -> AccountReceipt.parse(CREATE_ROOM, reply(bad)));
        invalid(() -> AccountReceipt.parse(ROOMS, reply("{\"rooms\":[" + String.join(",", java.util.Collections.nCopies(65, room())) + "]}")));
        invalid(() -> AccountReceipt.parse(ROOMS, reply("{\"rooms\":[" + room() + "," + room() + "]}")));
        invalid(() -> AccountReceipt.parse(LOGIN, null));
        var owner = new AgentLifecycle(PROFILE, false);
        failure("RECEIPT_SCOPE_CHANGED", () -> AccountReceipt.capture(LOGIN, owner, null, reply(signedIn)));
        owner.close();
        failure("RECEIPT_SCOPE_CHANGED", () -> AccountReceipt.capture(LOGIN, owner, null, reply(signedIn)));
    }
    private static void runAndDelivery() throws Exception {
        for (String[] values : List.of(new String[]{"--help"}, new String[]{"login", "--password", "fixture-secret"}, args("login"))) {
            StringWriter text = new StringWriter(); Input input = new Input(); input.available = false;
            int result = AccountTool.run(values, input, new PrintWriter(text, true), (path, development) -> { throw new AssertionError("unexpected launch"); });
            check(result == (values[0].equals("--help") ? 0 : 1)); check(!text.toString().contains("fixture-secret"));
        }
        Input cancelled = new Input("https://example.com", "fixture-account", null);
        StringWriter text = new StringWriter();
        check(AccountTool.run(args("login"), cancelled, new PrintWriter(text, true), (p, d) -> { throw new AssertionError("cancelled launch"); }) == 1);
        check(text.toString().equals("ERROR INPUT_CANCELLED\n")); cancelled.cleared(3);
        Input unexpected = new Input("https://example.com", "fixture-account", "fixture-secret");
        text = new StringWriter();
        check(AccountTool.run(args("login"), unexpected, new PrintWriter(text, true), (p, d) -> { throw new IllegalStateException("fixture-secret"); }) == 1);
        check(text.toString().equals("ERROR ACCOUNT_TOOL_IO\n")); unexpected.cleared(3);
        StringWriter warned = new StringWriter();
        Input http = new Input("http://control.example.com", "fixture-account", "fixture-secret");
        check(AccountTool.run(args("login"), http, new PrintWriter(warned, true), (p, d) -> {
            check(warned.toString().equals(ControlTransport.WARNING + "\n"));
            throw new IllegalStateException("fixture-secret");
        }) == 1);
        check(warned.toString().equals(ControlTransport.WARNING + "\nERROR ACCOUNT_TOOL_IO\n"));
        check(!warned.toString().contains("control.example.com") && !warned.toString().contains("fixture-secret"));
        http.cleared(3);
        Input cannotWarn = new Input("http://control.example.com", "fixture-account", "fixture-secret");
        var blockedOutput = new PrintWriter(new java.io.Writer() {
            public void write(char[] value, int offset, int length) throws java.io.IOException { throw new java.io.IOException("unwritable"); }
            public void flush() {} public void close() {}
        });
        check(AccountTool.run(args("login"), cannotWarn, blockedOutput, (p, d) -> { throw new AssertionError("warning failure launched helper"); }) == 1);
        check(cannotWarn.index == 1); cannotWarn.cleared(1);
        // Resources are intentionally absent. Verify failure cleanup completes,
        // no receipt/unknown delivery is printed, and no helper starts.
        text = new StringWriter();
        check(AccountTool.run(args("status"), null, new PrintWriter(text, true), AgentLifecycle::new) == 1);
        check(text.toString().equals("ERROR RESOURCE_MISSING\n"));
        for (var action : List.of(LOGIN, REGISTER, CREATE_ROOM, JOIN_ROOM, LOGOUT)) {
            for (var code : List.of(AgentException.Code.INVALID_REQUEST, AgentException.Code.SCOPE_CHANGED,
                    AgentException.Code.CAPACITY, AgentException.Code.NOT_READY)) {
                check(!AccountTool.unknown(new AgentException(code), action, true));
            }
            for (var code : List.of(AgentException.Code.REQUEST_TIMEOUT, AgentException.Code.UNEXPECTED_EXIT,
                    AgentException.Code.CLOSED, AgentException.Code.INVALID_REPLY, AgentException.Code.IO)) {
                check(AccountTool.unknown(new AgentException(code), action, true));
            }
            check(AccountTool.unknown(new AccountCommand.Failure("RECEIPT_SCOPE_CHANGED"), action, true));
            check(AccountTool.unknown(new AgentException(AgentException.Code.REMOTE_REJECTED, "CONTROL_TIMEOUT"), action, true));
            check(AccountTool.unknown(new AgentException(AgentException.Code.REMOTE_REJECTED), action, true));
            check(!AccountTool.unknown(new AgentException(AgentException.Code.REMOTE_REJECTED, "AUTH_REQUIRED"), action, true));
        }
        check(!AccountTool.unknown(new AgentException(AgentException.Code.IO), STATUS, true));
        check(!AccountTool.unknown(new AgentException(AgentException.Code.IO), LOGIN, false));
        check(!AccountTool.unknown(new AgentException(AgentException.Code.IO), null, true));
    }
    public static void main(String[] arguments) throws Exception {
        options(); input(); receipts(); runAndDelivery();
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("p2wlan-agent-")));
        System.out.println("PASS account tool input/privacy/schema/delivery: " + checks + " explicit checks");
    }
}
