package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Restart intent persistence without Minecraft, credentials or native runtime state. */
public final class DedicatedServerSettingsTests {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("dedicated settings " + checks); }
    private static DedicatedServerSettings.Value selected() {
        return new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "room-fixture", true);
    }
    private static String json(DedicatedServerSettings.Value value) {
        JsonObject json = new JsonObject(); json.addProperty("schema", 1); json.addProperty("server", value.server());
        json.addProperty("user_id", value.userId()); json.addProperty("room", value.room()); json.addProperty("share", value.share());
        return json.toString();
    }
    private static void invalidSave(DedicatedServerSettings store, DedicatedServerSettings.Value value, Path file) throws Exception {
        String before = Files.readString(file);
        try { store.save(value); throw new AssertionError("invalid restart intent admitted"); }
        catch (AgentException error) {
            check(error.code() == AgentException.Code.INVALID_REQUEST);
            check("DEDICATED_CONFIG_INVALID".equals(error.remoteCode()));
        }
        check(Files.readString(file).equals(before));
        check(store.load().value().equals(selected()));
    }
    private static void canonical() throws Exception {
        String[][] cases = {
            {"https://control.example.com", "https://control.example.com"},
            {"HTTPS://CONTROL.EXAMPLE.COM:443/", "https://control.example.com"},
            {"HTTP://CONTROL.EXAMPLE.COM:80", "http://control.example.com"},
            {" \tHTTP://control.example.com\r\n", "http://control.example.com"},
            {"h\tt\nt\rp://control.example.com", "http://control.example.com"},
            {"http://203.0.113.1:18080/", "http://203.0.113.1:18080"},
            {"http://[::1]:12345", "http://[::1]:12345"},
            {"http://localhost:0", "http://localhost:0"},
        };
        for (String[] value : cases) check(DedicatedServerSettings.canonicalServer(value[0]).equals(value[1]));
        for (String input : List.of("", "https://user:password@example.com", "https://@example.com", "https://example.com/api",
                "https://example.com?token=x", "https://example.com#x", "https://example.com?", "https://example.com#",
                "http://example.com:65536", "http://example.com:-1", "ftp://example.com", "https:", "https:///example.com",
                "https://" + "x".repeat(2048), "https://example.com/\uD800")) {
            try { DedicatedServerSettings.canonicalServer(input); throw new AssertionError("invalid origin admitted"); }
            catch (AgentException error) { check(error.code() == AgentException.Code.INVALID_REQUEST); }
        }
    }
    private static void persistence(Path directory) throws Exception {
        Path file = directory.resolve("config/dedicated.json");
        var store = new DedicatedServerSettings(file);
        check(store.load().reason().equals("OK")); check(store.load().value().equals(DedicatedServerSettings.defaults()));
        for (var value : List.of(DedicatedServerSettings.defaults(), new DedicatedServerSettings.Value("http://control.example.com:18080", "", "", false),
                new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "", false),
                new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "room-fixture", false), selected())) {
            store.save(value); var reloaded = new DedicatedServerSettings(file).load();
            check(reloaded.reason().equals("OK")); check(reloaded.value().equals(value));
            try (var files = Files.list(file.getParent())) { check(files.count() == 1); }
            check(Files.size(file) <= 4096);
        }
        String valid = json(selected());
        for (String malformed : List.of("{}", "[]", "null", "x", " ".repeat(4097), valid + "{}",
                valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                valid.replace("\"schema\":1", "\"schema\":2"), valid.replace("\"schema\":1", "\"schema\":1.0"),
                valid.replace("\"schema\":1", "\"schema\":\"1\""), valid.replace("\"share\":true", "\"share\":1"),
                valid.replace("\"share\":true", "\"share\":\"true\""), valid.replace("\"user_id\":\"fixture-user\"", "\"user_id\":null"),
                valid.replace("\"server\":", "\"extra\":true,\"server\":"), valid.replace("\"room-fixture\"", "\"12345678\""),
                valid.replace("https://control.example.com", "https://user:password@control.example.com"),
                valid.replace("https://control.example.com", "HTTPS://CONTROL.EXAMPLE.COM/"),
                valid.replace("fixture-user", "界".repeat(43)))) {
            Files.writeString(file, malformed);
            check(store.load().reason().equals("DEDICATED_CONFIG_INVALID"));
            check(store.load().value().equals(DedicatedServerSettings.defaults())); check(Files.readString(file).equals(malformed));
        }
        Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
        check(store.load().reason().equals("DEDICATED_CONFIG_INVALID"));
        check(Files.readAllBytes(file).length == 2);
        Files.writeString(file, valid);
        for (var value : List.of(new DedicatedServerSettings.Value("", "", "", true),
                new DedicatedServerSettings.Value("", "fixture-user", "", false),
                new DedicatedServerSettings.Value("https://control.example.com", "", "room-fixture", true),
                new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "12345678", false),
                new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "room-", false),
                new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "room-" + "x".repeat(121), false),
                new DedicatedServerSettings.Value("https://control.example.com", "fixture-user", "room-a_b", false),
                new DedicatedServerSettings.Value("https://control.example.com", "界".repeat(43), "", false),
                new DedicatedServerSettings.Value("https://control.example.com", "x".repeat(129), "", false),
                new DedicatedServerSettings.Value("https://control.example.com", "\uD800", "", false),
                new DedicatedServerSettings.Value("https://control.example.com/", "fixture-user", "room-fixture", true),
                new DedicatedServerSettings.Value(null, "", "", false))) invalidSave(store, value, file);
        invalidSave(store, null, file);
        var maximum = new DedicatedServerSettings.Value("https://control.example.com", "x".repeat(128), "room-" + "x".repeat(120), true);
        store.save(maximum); check(store.load().value().equals(maximum));
        var unicode = new DedicatedServerSettings.Value("https://control.example.com", "界".repeat(42) + "ab", "", false);
        store.save(unicode); check(store.load().value().equals(unicode));
    }
    private static void unsafePaths(Path directory) throws Exception {
        Path marker = directory.resolve("original.json"); String original = json(selected()); Files.writeString(marker, original);
        Path symlink = directory.resolve("linked.json"); Files.createSymbolicLink(symlink, marker);
        var linked = new DedicatedServerSettings(symlink);
        check(linked.load().reason().equals("DEDICATED_PATH_UNSAFE"));
        try { linked.save(DedicatedServerSettings.defaults()); throw new AssertionError("symlink replaced"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.IO); check("DEDICATED_PATH_UNSAFE".equals(error.remoteCode())); }
        check(Files.isSymbolicLink(symlink)); check(Files.readString(marker).equals(original));
        Path parent = directory.resolve("linked-parent"); Files.createSymbolicLink(parent, directory.resolve("config"));
        var nested = new DedicatedServerSettings(parent.resolve("nested/file.json"));
        check(nested.load().reason().equals("DEDICATED_PATH_UNSAFE"));
        try { nested.save(selected()); throw new AssertionError("symlink ancestor traversed"); }
        catch (AgentException error) { check("DEDICATED_PATH_UNSAFE".equals(error.remoteCode())); }
        check(!Files.exists(directory.resolve("config/nested")));
        Path occupied = directory.resolve("directory.json"); Files.createDirectory(occupied);
        var nonRegular = new DedicatedServerSettings(occupied);
        check(nonRegular.load().reason().equals("DEDICATED_PATH_UNSAFE"));
        try { nonRegular.save(selected()); throw new AssertionError("directory replaced"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.IO); }
        check(Files.isDirectory(occupied));
        var blocked = new DedicatedServerSettings(marker.resolve("settings.json"));
        check(blocked.load().reason().equals("DEDICATED_PATH_UNSAFE"));
        try { blocked.save(selected()); throw new AssertionError("non-directory parent admitted"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.IO); }
        check(Files.readString(marker).equals(original));
    }
    public static void main(String[] arguments) throws Exception {
        canonical();
        Path directory = Files.createTempDirectory("p2wlan-dedicated-settings").toRealPath();
        try { persistence(directory); unsafePaths(directory); }
        finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS dedicated server settings: " + checks + " explicit checks");
    }
}
