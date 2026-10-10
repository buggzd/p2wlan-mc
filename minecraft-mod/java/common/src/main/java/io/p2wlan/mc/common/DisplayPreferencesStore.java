package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;

/** One bounded synchronous local-file operation per explicit save. No task,
 * write queue, credential, account or native-session state. */
public final class DisplayPreferencesStore {
    public enum Reason { OK, DISPLAY_CONFIG_INVALID, DISPLAY_PATH_UNSAFE, DISPLAY_LOAD_FAILED, DISPLAY_SAVE_FAILED }
    public record Loaded(DisplayPreferences preferences, Reason reason) {}
    private final Path file;
    public DisplayPreferencesStore(Path file) { this.file = file; }
    private boolean unsafe() { return Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent()); }
    public Loaded load() {
        try {
            if (unsafe()) return fallback(Reason.DISPLAY_PATH_UNSAFE);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return fallback(Reason.OK);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) return fallback(Reason.DISPLAY_CONFIG_INVALID);
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(4097); }
            if (bytes.length > 4096) return fallback(Reason.DISPLAY_CONFIG_INVALID);
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var json = FrameCodec.parse(text);
            if (!json.keySet().equals(Set.of("schema", "hud_visible", "detail", "corner")) || !json.get("schema").isJsonPrimitive()
                    || !json.get("schema").getAsJsonPrimitive().isNumber() || !json.get("schema").getAsString().equals("1")
                    || !json.get("hud_visible").isJsonPrimitive() || !json.get("hud_visible").getAsJsonPrimitive().isBoolean()
                    || !json.get("detail").isJsonPrimitive() || !json.get("detail").getAsJsonPrimitive().isString()
                    || !json.get("corner").isJsonPrimitive() || !json.get("corner").getAsJsonPrimitive().isString()) return fallback(Reason.DISPLAY_CONFIG_INVALID);
            return new Loaded(new DisplayPreferences(json.get("hud_visible").getAsBoolean(), DisplayPreferences.Detail.valueOf(json.get("detail").getAsString()),
                    DisplayPreferences.Corner.valueOf(json.get("corner").getAsString())), Reason.OK);
        } catch (ProtocolException | java.nio.charset.CharacterCodingException invalid) { return fallback(Reason.DISPLAY_CONFIG_INVALID); }
        catch (IOException | SecurityException error) { return fallback(Reason.DISPLAY_LOAD_FAILED); }
        catch (RuntimeException invalid) { return fallback(Reason.DISPLAY_CONFIG_INVALID); }
    }
    public Reason save(DisplayPreferences preferences) {
        Path temporary = null;
        Reason outcome = Reason.OK;
        try {
            if (unsafe()) return Reason.DISPLAY_PATH_UNSAFE;
            Files.createDirectories(file.getParent());
            if (unsafe()) return Reason.DISPLAY_PATH_UNSAFE;
            var json = new JsonObject(); json.addProperty("schema",1); json.addProperty("hud_visible",preferences.hudVisible());
            json.addProperty("detail",preferences.detail().name()); json.addProperty("corner",preferences.corner().name());
            temporary = Files.createTempFile(file.getParent(), ".p2wlan-display-", ".tmp");
            Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8);
            if (unsafe()) outcome = Reason.DISPLAY_PATH_UNSAFE;
            else try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException | SecurityException error) { outcome = Reason.DISPLAY_SAVE_FAILED; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException error) { outcome = Reason.DISPLAY_SAVE_FAILED; } }
        return outcome;
    }
    private static Loaded fallback(Reason reason) { return new Loaded(DisplayPreferences.defaults(),reason); }
}
