package io.p2wlan.mc.common;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real local disk + pure dispatch/schema tests, not Windows/HMCL acceptance. */
public final class ModDiagnosticsTests {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("diagnostic check " + checks); }
    private static JsonObject frame() {
        JsonObject frame = new JsonObject(); frame.addProperty("version", 1); frame.addProperty("event", "startup_error");
        frame.addProperty("error", "PROFILE_IO");
        JsonObject detail = new JsonObject();
        detail.addProperty("stage", "PROFILE_OPEN"); detail.addProperty("operation", "OPEN_FILE");
        detail.addProperty("target", "ACCOUNT_FILE"); detail.addProperty("os_code", 32); detail.add("path_depth", JsonNull.INSTANCE);
        frame.add("diagnostic", detail); return frame;
    }
    private static void reject(JsonObject frame) throws Exception {
        try { AgentMessages.parse(frame); throw new AssertionError("diagnostic accepted"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.INVALID_REPLY); check(!error.diagnostic().contains("fixture-secret")); }
    }
    private static void closed(ModDiagnostics log) throws Exception { log.close(); log.flushed().get(3, TimeUnit.SECONDS); }
    private static void schema() throws Exception {
        AgentMessages.StartupError result = (AgentMessages.StartupError) AgentMessages.parse(frame());
        check(result.diagnostic().osCode() == 32);
        check(result.diagnostic().operation().equals("OPEN_FILE"));
        check(result.diagnostic().target().equals("ACCOUNT_FILE"));
        for (String key : new String[]{"stage", "operation", "target", "os_code", "path_depth"}) {
            JsonObject frame = frame(); frame.getAsJsonObject("diagnostic").addProperty(key, "fixture-secret"); reject(frame);
            frame = frame(); frame.getAsJsonObject("diagnostic").remove(key); reject(frame);
        }
        for (Number bad : new Number[]{0.5, Long.MAX_VALUE, Long.MIN_VALUE}) {
            JsonObject frame = frame(); frame.getAsJsonObject("diagnostic").addProperty("os_code", bad); reject(frame);
        }
        for (Number bad : new Number[]{-1, 65, 1.5}) {
            JsonObject frame = frame(); frame.getAsJsonObject("diagnostic").addProperty("path_depth", bad); reject(frame);
        }
        JsonObject frame = frame(); frame.getAsJsonObject("diagnostic").addProperty("password", "fixture-secret"); reject(frame);
        frame = frame(); frame.add("diagnostic", JsonNull.INSTANCE); reject(frame);
        frame = frame(); frame.addProperty("owner", "a".repeat(32)); reject(frame);
        frame = frame(); frame.remove("diagnostic"); check(((AgentMessages.StartupError) AgentMessages.parse(frame)).diagnostic() == null);
        frame = frame(); frame.getAsJsonObject("diagnostic").addProperty("os_code", -1);
        frame.getAsJsonObject("diagnostic").addProperty("path_depth", 64);
        check(((AgentMessages.StartupError) AgentMessages.parse(frame)).diagnostic().pathDepth() == 64);
        frame = frame(); frame.getAsJsonObject("diagnostic").addProperty("target", "DRIVE_ROOT");
        frame.getAsJsonObject("diagnostic").addProperty("path_depth", 0);
        var root = ((AgentMessages.StartupError) AgentMessages.parse(frame)).diagnostic();
        check(root.target().equals("DRIVE_ROOT") && root.pathDepth() == 0 && root.osCode() == 32);
        check(root.safeText().contains("target=DRIVE_ROOT"));
    }
    private static void memory() throws Exception {
        ModDiagnostics log = new ModDiagnostics(null); log.start();
        log.failure(AgentException.startup("PROFILE_IO", new StartupDiagnostic("PROFILE_OPEN", "OPEN_FILE", "ACCOUNT_FILE", 32, null)));
        log.failure(new RuntimeException("fixture-secret /private/account.json token=password"));
        check(log.snapshot().failure().reason().equals("INITIALIZATION_FAILED / PROFILE_IO"));
        check(log.snapshot().copyText().contains("os_code=32"));
        check(!log.snapshot().copyText().contains("fixture-secret"));
        check(!log.snapshot().copyText().contains("/private/account.json"));
        check(DiagnosticPresentation.explanation(log.snapshot().failure()).contains("不是账号"));
        check(!DiagnosticPresentation.canOpenLog(log.snapshot()));
        for (int i = 0; i < 100; i++) log.stage(ModDiagnostics.Stage.CHILD_START);
        check(log.snapshot().lines().size() == ModDiagnostics.MEMORY_LIMIT);
        check(log.snapshot().evicted() == 40);
        check(log.snapshot().copyText().contains("os_code=32")); // Primary evidence survives ring eviction.
        check(log.snapshot().dropped() == 0);
        check(log.snapshot().lines().stream().allMatch(line -> line.length() <= ModDiagnostics.LINE_LIMIT));
        closed(log);
        var before = log.snapshot();
        log.start(); log.failure(new AgentException(AgentException.Code.CLOSED)); log.stage(ModDiagnostics.Stage.READY);
        check(before.equals(log.snapshot())); // Late old-owner callbacks cannot change diagnostic state.
    }
    private static void disk(Path root) throws Exception {
        Path directory = root.resolve("ordinary/logs");
        ModDiagnostics log = new ModDiagnostics(directory); log.start();
        log.failure(new java.io.IOException("fixture-secret\nControl URL /private/profile"));
        closed(log);
        check(log.snapshot().diskState().equals("SAVED"));
        check(DiagnosticPresentation.canOpenLog(log.snapshot()));
        String text = Files.readString(directory.resolve("startup.log"));
        check(text.contains("event=ENVIRONMENT")); check(text.contains("event=FAILURE START_FAILED"));
        check(!text.contains("fixture-secret")); check(!text.contains(root.toString()));
        check(!log.snapshot().copyText().contains(root.toString()));
        Files.write(directory.resolve("startup.log"), new byte[(int) ModDiagnostics.FILE_LIMIT - 1]);
        ModDiagnostics rotated = new ModDiagnostics(directory); rotated.start(); closed(rotated);
        check(Files.size(directory.resolve("startup.log")) <= ModDiagnostics.FILE_LIMIT);
        check(Files.size(directory.resolve("startup.log.1")) == ModDiagnostics.FILE_LIMIT - 1);
        try (var entries = Files.list(directory)) { check(entries.count() == 3); } // current, one backup, empty lock only
    }
    private static void faults(Path root) throws Exception {
        Path parentFile = Files.writeString(root.resolve("not-a-directory"), "fixture-secret");
        ModDiagnostics denied = new ModDiagnostics(parentFile.resolve("logs")); denied.start();
        denied.failure(new NativeBundleException(NativeBundleException.Code.RESOURCE_MISSING)); closed(denied);
        check(denied.snapshot().diskState().equals("LOG_IO_FAILED"));
        check(denied.snapshot().failure().reason().equals("RESOURCE_MISSING"));
        check(denied.snapshot().copyText().contains("event=LOG_FAILURE"));
        check(!denied.snapshot().copyText().contains("fixture-secret"));
        Path busyDir = Files.createDirectory(root.resolve("busy"));
        try (FileChannel lock = FileChannel.open(busyDir.resolve("startup.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = lock.lock()) {
            ModDiagnostics busy = new ModDiagnostics(busyDir); busy.start(); closed(busy);
            check(busy.snapshot().diskState().equals("LOG_BUSY"));
            check(!DiagnosticPresentation.canOpenLog(busy.snapshot()));
        }
        AtomicReference<Runnable> pending = new AtomicReference<>();
        Path overflowDir = root.resolve("overflow");
        ModDiagnostics overflow = new ModDiagnostics(overflowDir, pending::set); overflow.start();
        for (int i = 0; i < ModDiagnostics.QUEUE_LIMIT + 10; i++) overflow.stage(ModDiagnostics.Stage.CHILD_START);
        check(overflow.snapshot().dropped() == 12); // two startup/environment entries already queued
        check(overflow.snapshot().lines().size() == ModDiagnostics.MEMORY_LIMIT);
        overflow.close(); pending.get().run(); overflow.flushed().get(3, TimeUnit.SECONDS);
        check(overflow.snapshot().diskState().equals("SAVED"));
        check(overflow.snapshot().copyText().contains("queue_dropped=12"));
        check(Files.readString(overflowDir.resolve("startup.log")).contains("event=LOG_SUMMARY memory_evicted=76 queue_dropped=12"));
        ModDiagnostics worker = new ModDiagnostics(root.resolve("worker"), task -> { throw new IllegalStateException("fixture-secret"); });
        worker.start(); closed(worker);
        check(worker.snapshot().diskState().equals("LOG_WORKER_UNAVAILABLE"));
        check(!worker.snapshot().copyText().contains("fixture-secret"));
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Path linkDir = Files.createDirectory(root.resolve("linked"));
            Files.createSymbolicLink(linkDir.resolve("startup.log"), parentFile);
            ModDiagnostics linked = new ModDiagnostics(linkDir); linked.start(); closed(linked);
            check(linked.snapshot().diskState().equals("LOG_UNSAFE_FILE"));
            check(Files.readString(parentFile).equals("fixture-secret"));
        }
    }
    private static void displaySaves() throws Exception {
        ModDiagnostics log = new ModDiagnostics(null); log.start();
        log.displaySettingsSave(new DisplayPreferencesStore.Saved(DisplayPreferencesStore.Reason.DISPLAY_SAVE_FAILED,
                DisplayPreferencesStore.SaveOperation.REPLACE_FILE, DisplayPreferencesStore.FileFailure.FILE_SYSTEM_ERROR,
                DisplayPreferencesStore.FileFailure.ACCESS_DENIED));
        String text = log.snapshot().copyText();
        check(text.contains("event=DISPLAY_SETTINGS_SAVE target=DISPLAY_SETTINGS"));
        check(text.contains("operation=REPLACE_FILE error_kind=FILE_SYSTEM_ERROR"));
        check(text.contains("cleanup_error_kind=ACCESS_DENIED"));
        check(log.snapshot().failure() == null); // This is not a native startup failure.
        log.displaySettingsSave(new DisplayPreferencesStore.Saved(DisplayPreferencesStore.Reason.OK,
                DisplayPreferencesStore.SaveOperation.COMPLETE, DisplayPreferencesStore.FileFailure.NONE,
                DisplayPreferencesStore.FileFailure.NONE));
        check(log.snapshot().copyText().contains("reason=OK operation=COMPLETE"));
        closed(log); var before = log.snapshot();
        log.displaySettingsSave(new DisplayPreferencesStore.Saved(DisplayPreferencesStore.Reason.OK,
                DisplayPreferencesStore.SaveOperation.COMPLETE, DisplayPreferencesStore.FileFailure.NONE,
                DisplayPreferencesStore.FileFailure.NONE));
        check(before.equals(log.snapshot())); // No late mutation of a closed log owner.
    }
    public static void main(String[] arguments) throws Exception {
        schema(); memory(); displaySaves();
        Path root = Files.createTempDirectory("p2wlan-diagnostic-test-").toAbsolutePath();
        try { disk(root); faults(root); }
        finally { try (var files = Files.walk(root)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } }
        System.out.println("PASS bounded/redacted startup diagnostics: " + checks + " explicit checks");
    }
}
