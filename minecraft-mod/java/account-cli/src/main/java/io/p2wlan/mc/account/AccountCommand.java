package io.p2wlan.mc.account;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.ControlTransport;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

/** One explicit intent. No native child exists while a human enters input;
 * credentials are never command-line/environment options or echoed by us. */
final class AccountCommand implements AutoCloseable {
    enum Action {
        STATUS("status", false, false), LOGIN("login", true, true), REGISTER("register", true, true),
        ROOMS("rooms", false, false), CREATE_ROOM("create_room", true, true),
        JOIN_ROOM("join_room", true, true), LOGOUT("logout", false, true);
        final String operation;
        final boolean input, mutation;
        Action(String operation, boolean input, boolean mutation) {
            this.operation = operation; this.input = input; this.mutation = mutation;
        }
        static Action parse(String value) throws Failure {
            for (Action action : values()) if (action.operation.replace('_', '-').equals(value)) return action;
            throw new Failure("INVALID_OPTIONS");
        }
    }
    record Options(Action action, Path profile, boolean development) {
        static Options parse(String[] arguments) throws Failure {
            if (arguments == null || arguments.length < 3 || arguments.length > 4) throw new Failure("INVALID_OPTIONS");
            for (String argument : arguments) if (argument == null) throw new Failure("INVALID_OPTIONS");
            Action action = Action.parse(arguments[0]);
            Path profile = null;
            boolean development = false;
            for (int index = 1; index < arguments.length; index++) {
                if (arguments[index].equals("--profile-dir") && profile == null && index + 1 < arguments.length) {
                    String value = arguments[++index];
                    if (value.isEmpty() || value.length() > 4096 || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                        throw new Failure("INVALID_OPTIONS");
                    }
                    try { profile = Path.of(value); }
                    catch (InvalidPathException error) { throw new Failure("INVALID_OPTIONS"); }
                    if (!profile.isAbsolute()) throw new Failure("INVALID_OPTIONS");
                    profile = profile.normalize();
                } else if (arguments[index].equals("--allow-development-native") && !development) development = true;
                else throw new Failure("INVALID_OPTIONS");
            }
            if (profile == null) throw new Failure("INVALID_OPTIONS");
            return new Options(action, profile, development);
        }
    }
    interface Input {
        boolean available();
        char[] read(String prompt, boolean secret);
    }
    interface Warnings { void show(String message) throws Failure; }
    static final class Failure extends Exception {
        Failure(String code) { super(code); }
    }
    private final Action action;
    private final JsonObject arguments = new JsonObject();
    private AccountCommand(Action action) { this.action = action; }
    static AccountCommand prepare(Action action, Input input, Warnings warnings) throws Failure {
        AccountCommand command = new AccountCommand(action);
        try {
            if (action.input && (input == null || !input.available())) throw new Failure("CONSOLE_REQUIRED");
            switch (action) {
                case LOGIN, REGISTER -> {
                    command.field(input, "server", "Control base URL: ", false, false);
                    if (ControlTransport.isPlaintext(command.arguments.get("server").getAsString())) warnings.show(ControlTransport.WARNING);
                    command.field(input, "identifier", "Account: ", false, false);
                    command.field(input, "password", "Password: ", true, false);
                }
                case CREATE_ROOM -> {
                    command.field(input, "name", "Room name: ", false, false);
                    command.field(input, "password", "Room password (empty for none): ", true, true);
                }
                case JOIN_ROOM -> {
                    command.field(input, "code", "Eight-digit room code: ", false, false);
                    if (!command.arguments.get("code").getAsString().matches("[0-9]{8}")) throw new Failure("INVALID_INPUT");
                    command.field(input, "password", "Room password (empty for none): ", true, true);
                }
                default -> { }
            }
            return command;
        } catch (Failure | RuntimeException error) {
            command.close();
            if (error instanceof Failure known) throw known;
            throw new Failure("INPUT_IO");
        }
    }
    private void field(Input input, String key, String prompt, boolean secret, boolean empty) throws Failure {
        char[] value = input.read(prompt, secret);
        if (value == null) throw new Failure("INPUT_CANCELLED");
        try {
            validate(value, empty);
            arguments.addProperty(key, new String(value));
        } finally { Arrays.fill(value, '\0'); }
    }
    static void validate(char[] value, boolean empty) throws Failure {
        if (value.length > 256 || !empty && value.length == 0) throw new Failure("INVALID_INPUT");
        int bytes = 0;
        for (int index = 0; index < value.length; index++) {
            char current = value[index];
            if (current == 0 || current == '\n' || current == '\r') throw new Failure("INVALID_INPUT");
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length || !Character.isLowSurrogate(value[index])) throw new Failure("INVALID_INPUT");
                bytes += 4;
            } else if (Character.isLowSurrogate(current)) throw new Failure("INVALID_INPUT");
            else bytes += current < 128 ? 1 : current < 2048 ? 2 : 3;
            if (bytes > 256) throw new Failure("INVALID_INPUT");
        }
    }
    Action action() { return action; }
    // AgentClient encodes the arguments synchronously before returning a future.
    // Java Strings/IPC buffers cannot be securely zeroized; discard this copy as
    // soon as encoding ends, in addition to clearing every console char array.
    JsonObject arguments() { return arguments; }
    @Override public void close() {
        for (String key : Set.copyOf(arguments.keySet())) arguments.remove(key);
    }
}
