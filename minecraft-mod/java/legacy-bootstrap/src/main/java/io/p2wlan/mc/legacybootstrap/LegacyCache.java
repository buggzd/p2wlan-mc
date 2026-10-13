package io.p2wlan.mc.legacybootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Eight atomic directory slots; only exited immutable process snapshots are reclaimed. */
final class LegacyCache {
    static final int CAPACITY = 8;
    static final String PREFIX = "p2wlan-legacy-core-slot-";
    enum Reason { LIVE, UNCERTAIN, FOREIGN, CHANGED, IO_RETAINED, REMOVED }
    record Lease(long pid, Instant started) {}
    record Observed(boolean alive, Instant started) {}
    @FunctionalInterface interface Delete { void delete(Path path) throws IOException; }
    record Snapshot(Path directory, Object directoryKey, OwnerFile record, Map<String,OwnerFile> children, Lease lease) {
        Snapshot { children = Map.copyOf(children); }
        void requireCurrent(Map<String,OwnerFile> remaining) throws IOException {
            var attributes = Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || !Objects.equals(directoryKey,attributes.fileKey())) {
                throw new LegacyFailure(Code.OWNER_CHANGED);
            }
            record.requireCurrent(true);
            remaining.values().forEach(file -> file.requireCurrent(true));
        }
    }
    private LegacyCache() {}
    static Path reserve(Path temporary) {
        Map<Reason,Integer> retained = new HashMap<>();
        for (int slot = 0; slot < CAPACITY; slot++) {
            Path directory = temporary.resolve(PREFIX + slot);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (temporary.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                        Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    } else Files.createDirectory(directory);
                    return directory;
                } catch (FileAlreadyExistsException occupied) {
                    Reason reason;
                    try { reason = reclaim(snapshot(directory),LegacyCache::observe,Files::delete); }
                    catch (IOException | LegacyFailure failure) { reason = Reason.FOREIGN; }
                    if (reason == Reason.REMOVED && attempt == 0) continue;
                    retained.merge(reason,1,Integer::sum); break;
                } catch (IOException failure) { throw new LegacyFailure(Code.EXTRACTION_IO,failure); }
            }
        }
        // Fixed reason/count diagnostics contain no paths, account or owner token.
        for (var entry : retained.entrySet()) System.err.println("P2WLAN_LEGACY_CACHE_" + entry.getKey() + "=" + entry.getValue());
        throw new LegacyFailure(Code.CAPACITY);
    }
    static byte[] record(OwnerFile source, LegacyIndex index) {
        var current = ProcessHandle.current();
        Instant started = current.info().startInstant().orElseThrow(() -> new LegacyFailure(Code.SOURCE_IDENTITY));
        StringBuilder value = new StringBuilder("schema=2\nsource-uri=" + source.path.toUri().toASCIIString()
                + "\nsource-sha256=" + source.sha256 + "\nowner-token=" + UUID.randomUUID()
                + "\npid=" + current.pid() + "\nstarted=" + started + "\n");
        for (String owner : LegacyInventory.OWNERS.keySet().stream().sorted().toList()) {
            value.append(owner).append(".size=").append(index.ownerSize(owner)).append('\n');
            value.append(owner).append(".sha256=").append(index.digest(owner)).append('\n');
        }
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }
    static Map<String,String> fields(OwnerFile record) {
        var fields = LegacyIndex.lines(record.bytes,4096);
        Set<String> keys = new HashSet<>(Set.of("schema","source-uri","source-sha256","owner-token","pid","started"));
        for (String owner : LegacyInventory.OWNERS.keySet()) { keys.add(owner+".size"); keys.add(owner+".sha256"); }
        if (!fields.keySet().equals(keys) || !"2".equals(fields.get("schema"))
                || !fields.get("source-sha256").matches("[0-9a-f]{64}")
                || !fields.get("owner-token").matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")) throw new LegacyFailure(Code.PROVENANCE);
        lease(fields);
        try {
            var uri = java.net.URI.create(fields.get("source-uri"));
            if (!"file".equals(uri.getScheme()) || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !Path.of(uri).isAbsolute()) throw new LegacyFailure(Code.PROVENANCE);
        } catch (IllegalArgumentException failure) { throw new LegacyFailure(Code.PROVENANCE); }
        for (String owner : LegacyInventory.OWNERS.keySet()) {
            if (!fields.get(owner+".size").matches("[1-9][0-9]{0,8}")
                    || Long.parseLong(fields.get(owner+".size")) > LegacyArchive.MEMBER_MAXIMUM
                    || !fields.get(owner+".sha256").matches("[0-9a-f]{64}")) throw new LegacyFailure(Code.PROVENANCE);
        }
        return fields;
    }
    static Snapshot snapshot(Path directory) throws IOException {
        OwnerFile.local(directory);
        var attributes = Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null) throw new LegacyFailure(Code.PROVENANCE);
        Set<String> expected = new HashSet<>(Set.of(LegacyOwner.OWNER_RECORD));
        LegacyInventory.OWNERS.keySet().forEach(owner -> expected.add(owner+".jar"));
        try (var actual = Files.list(directory)) {
            var names = actual.limit(6).map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
            if (!names.equals(expected)) throw new LegacyFailure(Code.PROVENANCE);
        }
        OwnerFile record = OwnerFile.capture(directory.resolve(LegacyOwner.OWNER_RECORD),4096,null);
        var fields = fields(record);
        Map<String,OwnerFile> children = new HashMap<>(); long total = 0;
        for (String owner : LegacyInventory.OWNERS.keySet()) {
            long size = Long.parseLong(fields.get(owner+".size")); total += size;
            if (total > LegacyArchive.OUTER_MAXIMUM) throw new LegacyFailure(Code.CAPACITY);
            OwnerFile file = OwnerFile.capture(directory.resolve(owner+".jar"),size,fields.get(owner+".sha256"));
            if (file.size != size || file.fileKey == null) throw new LegacyFailure(Code.PROVENANCE);
            children.put(owner,file);
        }
        if (record.fileKey == null) throw new LegacyFailure(Code.PROVENANCE);
        Snapshot snapshot = new Snapshot(directory,attributes.fileKey(),record,children,lease(fields));
        snapshot.requireCurrent(children);
        return snapshot;
    }
    static Lease lease(Map<String,String> fields) {
        try {
            if (!fields.get("pid").matches("[1-9][0-9]{0,18}")) throw new LegacyFailure(Code.PROVENANCE);
            return new Lease(Long.parseLong(fields.get("pid")),Instant.parse(fields.get("started")));
        } catch (NumberFormatException | java.time.format.DateTimeParseException failure) { throw new LegacyFailure(Code.PROVENANCE); }
    }
    static Reason incarnation(Lease lease, Observed current) {
        if (lease.started.isAfter(Instant.now())) return Reason.UNCERTAIN;
        if (!current.alive) return Reason.REMOVED;
        if (current.started == null) return Reason.UNCERTAIN;
        if (lease.started.equals(current.started)) return Reason.LIVE;
        return current.started.isAfter(lease.started) ? Reason.REMOVED : Reason.UNCERTAIN;
    }
    private static Observed observe(long pid) {
        var process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) return new Observed(false,null);
        return new Observed(true,process.get().info().startInstant().orElse(null));
    }
    static Reason reclaim(Snapshot snapshot, LongFunction<Observed> lookup, Delete delete) {
        Map<String,OwnerFile> remaining = new HashMap<>(snapshot.children);
        try {
            Reason current = incarnation(snapshot.lease,lookup.apply(snapshot.lease.pid));
            if (current != Reason.REMOVED) return current;
            for (String name : remaining.keySet().stream().sorted().toList()) {
                snapshot.requireCurrent(remaining);
                if (incarnation(snapshot.lease,lookup.apply(snapshot.lease.pid)) != Reason.REMOVED) return Reason.UNCERTAIN;
                delete.delete(remaining.remove(name).path);
            }
            snapshot.requireCurrent(remaining);
            if (incarnation(snapshot.lease,lookup.apply(snapshot.lease.pid)) != Reason.REMOVED) return Reason.UNCERTAIN;
            delete.delete(snapshot.record.path); delete.delete(snapshot.directory);
            return Reason.REMOVED;
        } catch (LegacyFailure changed) { return Reason.CHANGED; }
        catch (IOException retained) { return Reason.IO_RETAINED; }
    }
}
