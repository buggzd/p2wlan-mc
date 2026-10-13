package io.p2wlan.mc.legacybootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Real inode/content tests run against the packaged carrier, no source outputs. */
public final class LegacyCacheChecks {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("cache check " + checks); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        Instant original = Instant.parse("2020-01-01T00:00:00Z");
        var lease = new LegacyCache.Lease(100,original);
        check(LegacyCache.incarnation(lease,new LegacyCache.Observed(true,original)) == LegacyCache.Reason.LIVE);
        check(LegacyCache.incarnation(lease,new LegacyCache.Observed(true,original.plusSeconds(1))) == LegacyCache.Reason.REMOVED);
        check(LegacyCache.incarnation(lease,new LegacyCache.Observed(true,null)) == LegacyCache.Reason.UNCERTAIN);
        check(LegacyCache.incarnation(lease,new LegacyCache.Observed(false,null)) == LegacyCache.Reason.REMOVED);
        check(LegacyCache.incarnation(lease,new LegacyCache.Observed(true,original.minusSeconds(1))) == LegacyCache.Reason.UNCERTAIN);
        var expired = snapshot(root.resolve("expired"));
        check(LegacyCache.reclaim(expired,pid -> new LegacyCache.Observed(false,null),Files::delete) == LegacyCache.Reason.REMOVED);
        check(!Files.exists(expired.directory()));
        var reused = snapshot(root.resolve("reused"));
        check(LegacyCache.reclaim(reused,pid -> new LegacyCache.Observed(true,original.plusSeconds(1)),Files::delete) == LegacyCache.Reason.REMOVED);
        var swapped = snapshot(root.resolve("record-swap"));
        byte[] record = swapped.record().bytes;
        Files.move(swapped.record().path,root.resolve("old-record")); Files.write(swapped.record().path,record);
        check(LegacyCache.reclaim(swapped,pid -> new LegacyCache.Observed(false,null),Files::delete) == LegacyCache.Reason.CHANGED);
        check(Files.exists(swapped.children().get("common").path));
        var changed = snapshot(root.resolve("content-change"));
        OwnerFile child = changed.children().get("common");
        byte[] bytes = child.bytes.clone(); bytes[0] ^= 1;
        Files.write(child.path,bytes); Files.setLastModifiedTime(child.path,child.modified);
        check(LegacyCache.reclaim(changed,pid -> new LegacyCache.Observed(false,null),Files::delete) == LegacyCache.Reason.CHANGED);
        check(Files.exists(changed.children().get("transport").path));
        var directory = snapshot(root.resolve("directory-swap"));
        Files.move(directory.directory(),root.resolve("original-directory")); Files.createDirectory(directory.directory());
        check(LegacyCache.reclaim(directory,pid -> new LegacyCache.Observed(false,null),Files::delete) == LegacyCache.Reason.CHANGED);
        var locked = snapshot(root.resolve("windows-open-handle"));
        check(LegacyCache.reclaim(locked,pid -> new LegacyCache.Observed(false,null),path -> {
            throw new IOException("simulated Windows sharing violation");
        }) == LegacyCache.Reason.IO_RETAINED);
        try (var retained = Files.list(locked.directory())) { check(retained.count() == 5); }
        var live = snapshot(root.resolve("live"));
        check(LegacyCache.reclaim(live,pid -> new LegacyCache.Observed(true,original),Files::delete) == LegacyCache.Reason.LIVE);
        check(Files.exists(live.record().path));
        var uncertain = snapshot(root.resolve("uncertain"));
        check(LegacyCache.reclaim(uncertain,pid -> new LegacyCache.Observed(true,null),Files::delete) == LegacyCache.Reason.UNCERTAIN);
        Path full = root.resolve("capacity"); Files.createDirectory(full);
        for (int slot = 0; slot < LegacyCache.CAPACITY; slot++) Files.createDirectory(full.resolve(LegacyCache.PREFIX + slot));
        try { LegacyCache.reserve(full); throw new AssertionError("capacity exceeded"); }
        catch (LegacyFailure failure) { check(failure.code() == LegacyFailure.Code.CAPACITY); }
        try (var slots = Files.list(full)) { check(slots.count() == 8); }
        Path atomic = root.resolve("atomic"); Files.createDirectory(atomic);
        Path first = LegacyCache.reserve(atomic), second = LegacyCache.reserve(atomic);
        check(!first.equals(second));
        System.out.println("{\"schema\":1,\"cache_checks\":" + checks + ",\"capacity\":8}");
    }
    private static LegacyCache.Snapshot snapshot(Path directory) throws Exception {
        Files.createDirectory(directory);
        StringBuilder record = new StringBuilder("schema=2\nsource-uri=" + directory.resolve("outer.jar").toUri().toASCIIString()
                + "\nsource-sha256=" + "a".repeat(64) + "\nowner-token=" + UUID.randomUUID()
                + "\npid=100\nstarted=2020-01-01T00:00:00Z\n");
        for (String owner : LegacyInventory.OWNERS.keySet()) {
            byte[] value = (owner + "-snapshot").getBytes(StandardCharsets.US_ASCII);
            Files.write(directory.resolve(owner+".jar"),value);
            record.append(owner).append(".size=").append(value.length).append('\n');
            record.append(owner).append(".sha256=").append(OwnerFile.digest(value,"SHA-256")).append('\n');
        }
        Files.writeString(directory.resolve(LegacyOwner.OWNER_RECORD),record,StandardCharsets.US_ASCII);
        return LegacyCache.snapshot(directory);
    }
}
