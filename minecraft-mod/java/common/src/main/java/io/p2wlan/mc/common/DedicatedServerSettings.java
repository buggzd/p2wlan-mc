package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/** Bounded non-secret restart intent. Native runtime remains the room/session authority. */
public final class DedicatedServerSettings {
    public record Value(String server, String userId, String room, boolean share) {}
    public record Loaded(Value value, String reason) {}
    private static final int MAX_BYTES = 4096;
    private static final Set<String> FIELDS = Set.of("schema", "server", "user_id", "room", "share");
    private final Path file;

    public DedicatedServerSettings(Path file) { this.file = file.toAbsolutePath().normalize(); }
    public static Value defaults() { return new Value("", "", "", false); }

    /** HTTP(S) origins use the canonical form returned by the native account API. */
    public static String canonicalServer(String input) throws AgentException {
        if (input == null || input.length() > 2048 || utf8Size(input) > 2048) throw invalid();
        String text = input.trim().replace("\t", "").replace("\r", "").replace("\n", "");
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            String path = uri.getRawPath();
            int port = uri.getPort();
            if (!(scheme.equals("http") || scheme.equals("https")) || host == null || host.isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(path == null || path.isEmpty() || path.equals("/")) || port < -1 || port > 65535) throw invalid();
            host = host.toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            String canonical = scheme + "://" + host + (port < 0 ? "" : ":" + port);
            if (utf8Size(canonical) > 2048) throw invalid();
            return canonical;
        } catch (URISyntaxException error) { throw invalid(); }
    }

    public Loaded load() {
        try {
            if (unsafePath()) return fallback("DEDICATED_PATH_UNSAFE");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return fallback("OK");
            if (Files.size(file) > MAX_BYTES) return fallback("DEDICATED_CONFIG_INVALID");
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) return fallback("DEDICATED_CONFIG_INVALID");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonObject json = FrameCodec.parse(text);
            if (!JsonTrees.fields(json, FIELDS) || !json.get("schema").isJsonPrimitive()
                    || !json.get("schema").getAsJsonPrimitive().isNumber() || !json.get("schema").getAsString().equals("1")
                    || !string(json, "server") || !string(json, "user_id") || !string(json, "room")
                    || !json.get("share").isJsonPrimitive() || !json.get("share").getAsJsonPrimitive().isBoolean()) {
                return fallback("DEDICATED_CONFIG_INVALID");
            }
            Value value = new Value(json.get("server").getAsString(), json.get("user_id").getAsString(),
                    json.get("room").getAsString(), json.get("share").getAsBoolean());
            validate(value);
            return new Loaded(value, "OK");
        } catch (AgentException | ProtocolException | CharacterCodingException error) {
            return fallback("DEDICATED_CONFIG_INVALID");
        } catch (IOException | SecurityException error) { return fallback("DEDICATED_LOAD_FAILED"); }
        catch (RuntimeException error) { return fallback("DEDICATED_CONFIG_INVALID"); }
    }

    /** Atomic replacement preserves the previous intent when validation or a write fails. */
    public void save(Value value) throws AgentException {
        validate(value);
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1); json.addProperty("server", value.server());
        json.addProperty("user_id", value.userId()); json.addProperty("room", value.room()); json.addProperty("share", value.share());
        byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw invalid();
        Path temporary = null;
        AgentException failure = null;
        try {
            if (unsafePath()) throw unsafe();
            Files.createDirectories(file.getParent());
            if (unsafePath()) throw unsafe();
            temporary = Files.createTempFile(file.getParent(), ".p2wlan-server-", ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            if (unsafePath()) throw unsafe();
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null; // Successful replacement leaves no temporary file to clean up.
        } catch (AgentException error) { failure = error; }
        catch (IOException | SecurityException error) { failure = new AgentException(AgentException.Code.IO, "DEDICATED_SAVE_FAILED"); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException | SecurityException error) {
                if (failure == null) failure = new AgentException(AgentException.Code.IO, "DEDICATED_SAVE_FAILED");
            }
        }
        if (failure != null) throw failure;
    }

    private boolean unsafePath() throws IOException {
        Path current = file.getRoot();
        for (int index = 0; index <= file.getNameCount(); index++) {
            try {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !(current.equals(file) ? attributes.isRegularFile() : attributes.isDirectory())) return true;
            } catch (NoSuchFileException missing) { /* Missing directories are created only by an explicit save. */ }
            if (index < file.getNameCount()) current = current.resolve(file.getName(index));
        }
        return false;
    }
    private static void validate(Value value) throws AgentException {
        if (value == null || value.server() == null || value.userId() == null || value.room() == null
                || value.userId().length() > 128 || utf8Size(value.userId()) > 128
                || !value.room().matches("(?:|room-[A-Za-z0-9-]{1,120})")
                || (!value.server().isEmpty() && !canonicalServer(value.server()).equals(value.server()))
                || (value.server().isEmpty() && !value.userId().isEmpty())
                || (!value.room().isEmpty() && (value.server().isEmpty() || value.userId().isEmpty()))
                || (value.share() && value.room().isEmpty())) throw invalid();
    }
    private static int utf8Size(String value) throws AgentException {
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException error) { throw invalid(); }
    }
    private static boolean string(JsonObject json, String key) {
        return json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString();
    }
    private static Loaded fallback(String reason) { return new Loaded(defaults(), reason); }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REQUEST, "DEDICATED_CONFIG_INVALID"); }
    private static AgentException unsafe() { return new AgentException(AgentException.Code.IO, "DEDICATED_PATH_UNSAFE"); }
}
