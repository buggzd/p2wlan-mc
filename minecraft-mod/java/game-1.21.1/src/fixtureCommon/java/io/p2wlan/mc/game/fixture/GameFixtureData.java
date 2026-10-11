package io.p2wlan.mc.game.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.UiCompletionGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Private, run-bound test inputs/results only; never packaged or a network owner. */
final class GameFixtureData {
    static final int ROUNDS = 20;
    final Path root;
    final String run, role, control, identifier, password, room, roomPassword;
    final Set<String> cases = new LinkedHashSet<>();
    int checks;
    volatile String stage = "START", reason = "NOT_COMPLETED";
    long deadline;
    GameFixtureData(String role) throws Exception {
        Path config = Path.of(System.getProperty("p2wlan.gameFixture.config")).toAbsolutePath();
        if (Files.isSymbolicLink(config) || !Files.isRegularFile(config) || Files.size(config) > 8192) throw new IllegalArgumentException("FIXTURE_CONFIG");
        JsonObject value = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        if (!value.keySet().equals(Set.of("schema", "run", "role", "control", "identifier", "password", "room", "room_password"))
                || value.get("schema").getAsInt() != 1 || !role.equals(value.get("role").getAsString())) throw new IllegalArgumentException("FIXTURE_CONFIG");
        root = config.getParent(); this.role = role; run = value.get("run").getAsString();
        control = value.get("control").getAsString(); identifier = value.get("identifier").getAsString(); password = value.get("password").getAsString();
        room = value.get("room").getAsString(); roomPassword = value.get("room_password").getAsString();
        if (!run.matches("[a-f0-9]{32}") || !room.matches("[0-9]{8}") || !control.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}")) throw new IllegalArgumentException("FIXTURE_CONFIG");
        advance("START", 45);
    }
    void check(boolean condition, String code) {
        checks++;
        if (!condition) throw new IllegalStateException(code);
    }
    void advance(String next, int seconds) {
        stage = next; deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        // Diagnostic only: the runner never interprets text/logs as readiness.
        System.out.println("LOCAL_GAME_FIXTURE_STAGE " + role + " " + next);
    }
    void withinDeadline() {
        if (System.nanoTime() >= deadline) throw new IllegalStateException("DEADLINE_" + stage);
    }
    void fail(Throwable error) {
        error = UiCompletionGate.unwrap(error);
        String code = error instanceof AgentException agent ? agent.code().name()
                : error instanceof IllegalStateException && error.getMessage() != null && error.getMessage().matches("[A-Z0-9_]{1,100}") ? error.getMessage() : "FIXTURE_EXECUTION";
        reason = stage + "_" + code;
    }
    static void write(Path path, JsonObject value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, value.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    }
    void result(boolean helperCleanup, boolean worldCleanup, int joins, int rounds) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("schema", 1); result.addProperty("run", run); result.addProperty("role", role);
        result.addProperty("status", "OK".equals(reason) && helperCleanup && worldCleanup ? "pass" : "fail");
        result.addProperty("reason", reason); result.addProperty("checks", checks); result.addProperty("minecraft", "1.21.1");
        result.addProperty("helper_cleanup", helperCleanup); result.addProperty("world_cleanup", worldCleanup);
        result.addProperty("joins", joins); result.addProperty("rounds", rounds);
        JsonArray completed = new JsonArray(); cases.forEach(completed::add); result.add("cases", completed);
        write(root.resolve(role + "-result.json"), result);
    }
}
