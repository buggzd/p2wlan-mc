package io.p2wlan.mc.account;

import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.NativeBundleException;
import java.io.Console;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Headless server-account administration, independent of every game/loader.
 * One lifecycle, one request, no retries/runtime/hosting commands. */
public final class AccountTool {
    private AccountTool() {}
    interface Factory { AgentLifecycle create(Path profile, boolean development); }
    static final String USAGE = "Usage: p2wlan-account <status|login|register|rooms|create-room|join-room|logout>"
            + " --profile-dir ABSOLUTE_PATH [--allow-development-native]";
    public static void main(String[] arguments) {
        int result = run(arguments, new ConsoleInput(System.console()), new PrintWriter(System.out, true), AgentLifecycle::new);
        if (result != 0) System.exit(result);
    }
    private record ConsoleInput(Console console) implements AccountCommand.Input {
        public boolean available() { return console != null; }
        public char[] read(String prompt, boolean secret) {
            char[] input;
            if (secret) input = console.readPassword("%s", prompt);
            else {
                String value = console.readLine("%s", prompt);
                input = value == null ? null : value.toCharArray();
            }
            // EOF does not necessarily echo a newline. Keep the stable failure
            // receipt on its own line rather than appending it to a prompt.
            if (input == null) console.printf("%n");
            return input;
        }
    }
    static int run(String[] arguments, AccountCommand.Input input, PrintWriter output, Factory factory) {
        if (arguments != null && arguments.length == 1 && arguments[0].equals("--help")) {
            output.println(USAGE); return 0;
        }
        boolean attempted = false;
        AccountCommand.Action action = null;
        try {
            var options = AccountCommand.Options.parse(arguments);
            action = options.action();
            // Input cancellation/no terminal/invalid UTF-8 is rejected before
            // profile extraction, locking, process creation or shutdown hooks.
            try (AccountCommand command = AccountCommand.prepare(action, input, warning -> {
                output.println(warning); output.flush();
                if (output.checkError()) throw new AccountCommand.Failure("WARNING_OUTPUT_FAILED");
            })) {
                AgentLifecycle owner = factory.create(options.profile(), options.development());
                Thread hook = new Thread(() -> { owner.close(); try { cleanup(owner); } catch (Exception ignored) { } },
                        "p2wlan-account-shutdown");
                Throwable failure = null;
                List<String> receipt = null;
                boolean installed = false;
                try {
                    Runtime.getRuntime().addShutdownHook(hook);
                    installed = true;
                    owner.start();
                    AgentClient client;
                    try { client = owner.ready().get(41, TimeUnit.SECONDS); }
                    catch (TimeoutException timeout) { throw new AccountCommand.Failure("STARTUP_TIMEOUT"); }
                    if (!owner.isCurrent(client)) throw new AgentException(AgentException.Code.SCOPE_CHANGED);
                    attempted = true;
                    java.util.concurrent.CompletableFuture<io.p2wlan.mc.common.AgentReply> future;
                    try { future = client.request(action.operation, command.arguments(), () -> owner.isCurrent(client)); }
                    finally { command.close(); }
                    try { receipt = AccountReceipt.capture(action, owner, client, future.get(6, TimeUnit.SECONDS)); }
                    catch (TimeoutException timeout) { throw new AccountCommand.Failure("COMMAND_TIMEOUT"); }
                } catch (Exception error) { failure = unwrap(error); }
                finally {
                    owner.close();
                    try { cleanup(owner); }
                    catch (Exception error) {
                        Throwable problem = unwrap(error);
                        if (failure == null || problem instanceof NativeBundleException nativeError
                                && Set.of(NativeBundleException.Code.CLEANUP_FAILED, NativeBundleException.Code.CLEANUP_BUSY).contains(nativeError.code())) {
                            failure = problem;
                        }
                        if (problem instanceof AccountCommand.Failure) failure = problem;
                    }
                    if (installed) {
                        try { Runtime.getRuntime().removeShutdownHook(hook); }
                        catch (IllegalStateException shuttingDown) { failure = new AccountCommand.Failure("INTERRUPTED"); }
                    }
                }
                if (failure != null) {
                    fail(output, failure, action, attempted); return 1;
                }
                // This is a verified historical command receipt, not live room
                // truth. Display only AFTER child reaping/extraction cleanup;
                // terminal backpressure cannot keep a native helper alive.
                for (String line : receipt) output.println(line);
                return output.checkError() ? 1 : 0;
            }
        } catch (Exception error) { fail(output, unwrap(error), action, attempted); return 1; }
    }
    private static void cleanup(AgentLifecycle owner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean interrupted = Thread.interrupted();
        try {
            var stopped = owner.stopped();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AccountCommand.Failure("CLEANUP_TIMEOUT");
                try { stopped.get(remaining, TimeUnit.NANOSECONDS); return; }
                catch (InterruptedException error) { interrupted = true; }
                catch (TimeoutException error) { throw new AccountCommand.Failure("CLEANUP_TIMEOUT"); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    static Throwable unwrap(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof java.util.concurrent.CompletionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }
    static boolean unknown(Throwable error, AccountCommand.Action action, boolean attempted) {
        if (!attempted || action == null || !action.mutation) return false;
        if (error instanceof AgentException agent) {
            if (Set.of(AgentException.Code.INVALID_REQUEST, AgentException.Code.SCOPE_CHANGED,
                    AgentException.Code.NOT_READY, AgentException.Code.CAPACITY).contains(agent.code())) return false;
            if (agent.code() == AgentException.Code.REMOTE_REJECTED) {
                return agent.remoteCode() == null || !Set.of("INVALID_ARGUMENTS", "AUTH_REQUIRED", "FORBIDDEN", "ROOM_UNKNOWN", "CONTROL_CONFLICT",
                        "CONTROL_RATE_LIMITED", "RUNTIME_BUSY", "PROFILE_REQUIRED").contains(agent.remoteCode());
            }
        }
        return true;
    }
    private static void fail(PrintWriter output, Throwable error, AccountCommand.Action action, boolean attempted) {
        String reason;
        if (error instanceof AgentException agent) {
            reason = agent.code().name();
            if (agent.code() == AgentException.Code.REMOTE_REJECTED && agent.remoteCode() != null
                    && agent.remoteCode().matches("[A-Z][A-Z0-9_]{0,47}")) reason += " " + agent.remoteCode();
        } else if (error instanceof NativeBundleException nativeError) reason = nativeError.code().name();
        else if (error instanceof AccountCommand.Failure known) reason = known.getMessage();
        else if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); reason = "INTERRUPTED"; }
        else reason = "ACCOUNT_TOOL_IO";
        output.println("ERROR " + reason);
        if (unknown(error, action, attempted)) output.println("DELIVERY_UNKNOWN: inspect status/rooms before retrying; no automatic replay.");
    }
}
