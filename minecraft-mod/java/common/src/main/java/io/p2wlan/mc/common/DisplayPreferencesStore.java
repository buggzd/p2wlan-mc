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
    public enum SaveOperation { CHECK_PATH, CREATE_DIRECTORY, CREATE_TEMPORARY, WRITE_FILE, REPLACE_FILE, REMOVE_TEMPORARY, COMPLETE }
    public enum FileFailure { NONE, PATH_UNSAFE, ACCESS_DENIED, PATH_EXISTS, PATH_MISSING, NOT_DIRECTORY, FILE_SYSTEM_ERROR, IO_ERROR, SECURITY_REJECTED }
    /** Fixed labels only; never retain a path, exception or filesystem message. */
    public record Saved(Reason reason, SaveOperation operation, FileFailure failure, FileFailure cleanupFailure) {
        public Saved {
            java.util.Objects.requireNonNull(reason); java.util.Objects.requireNonNull(operation);
            java.util.Objects.requireNonNull(failure); java.util.Objects.requireNonNull(cleanupFailure);
        }
    }
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
    public Reason save(DisplayPreferences preferences) { return saveDetailed(preferences).reason(); }
    public Saved saveDetailed(DisplayPreferences preferences) {
        Path temporary = null;
        SaveOperation operation = SaveOperation.CHECK_PATH;
        Saved outcome = null;
        FileFailure cleanup = FileFailure.NONE;
        try {
            if (unsafe()) return unsafeSave();
            operation = SaveOperation.CREATE_DIRECTORY;
            Files.createDirectories(file.getParent());
            if (unsafe()) return unsafeSave();
            var json = new JsonObject(); json.addProperty("schema",1); json.addProperty("hud_visible",preferences.hudVisible());
            json.addProperty("detail",preferences.detail().name()); json.addProperty("corner",preferences.corner().name());
            operation = SaveOperation.CREATE_TEMPORARY;
            temporary = Files.createTempFile(file.getParent(), ".p2wlan-display-", ".tmp");
            operation = SaveOperation.WRITE_FILE;
            Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8);
            if (unsafe()) outcome = unsafeSave();
            else {
                operation = SaveOperation.REPLACE_FILE;
                try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
            }
        } catch (IOException | SecurityException error) { outcome = failed(operation, error); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException | SecurityException error) {
                cleanup = failureKind(error);
                if (outcome == null) outcome = failed(SaveOperation.REMOVE_TEMPORARY, error);
            }
        }
        return outcome == null ? new Saved(Reason.OK, SaveOperation.COMPLETE, FileFailure.NONE, FileFailure.NONE)
                : new Saved(outcome.reason(), outcome.operation(), outcome.failure(), cleanup);
    }
    private static Saved unsafeSave() {
        return new Saved(Reason.DISPLAY_PATH_UNSAFE, SaveOperation.CHECK_PATH, FileFailure.PATH_UNSAFE, FileFailure.NONE);
    }
    private static Saved failed(SaveOperation operation, Exception error) {
        return new Saved(Reason.DISPLAY_SAVE_FAILED, operation, failureKind(error), FileFailure.NONE);
    }
    private static FileFailure failureKind(Exception error) {
        if (error instanceof SecurityException) return FileFailure.SECURITY_REJECTED;
        if (error instanceof AccessDeniedException) return FileFailure.ACCESS_DENIED;
        if (error instanceof FileAlreadyExistsException) return FileFailure.PATH_EXISTS;
        if (error instanceof NoSuchFileException) return FileFailure.PATH_MISSING;
        if (error instanceof NotDirectoryException) return FileFailure.NOT_DIRECTORY;
        if (error instanceof FileSystemException) return FileFailure.FILE_SYSTEM_ERROR;
        return FileFailure.IO_ERROR;
    }
    private static Loaded fallback(Reason reason) { return new Loaded(DisplayPreferences.defaults(),reason); }
}
