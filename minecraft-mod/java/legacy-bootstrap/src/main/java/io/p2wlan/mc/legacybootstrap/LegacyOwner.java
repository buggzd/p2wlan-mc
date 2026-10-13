package io.p2wlan.mc.legacybootstrap;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Sole owner of four extracted snapshots; no application or network state. */
final class LegacyOwner implements AutoCloseable {
    static final String OWNER_RECORD = "owner.properties";
    static final Set<String> PREFIXES = Set.of("io.p2wlan.mc.common.", "io.p2wlan.mc.transport.", "com.sun.jna.");
    final OwnerFile source;
    final LegacyIndex index;
    final Path directory;
    final Object directoryKey;
    final OwnerFile ownerRecord;
    final Map<String,VerifiedJar> jars;
    final Map<String,VerifiedJar> resourceOwners;
    private volatile boolean closed;
    private LegacyOwner(OwnerFile source, LegacyIndex index, Path directory, OwnerFile record, Map<String,VerifiedJar> jars) throws IOException {
        this.source = source; this.index = index; this.directory = directory; ownerRecord = record; this.jars = Map.copyOf(jars);
        directoryKey = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        Map<String,VerifiedJar> resources = new HashMap<>();
        for (VerifiedJar jar : jars.values()) for (String name : jar.entries.keySet()) if (LegacyInventory.RESOURCES.contains(name)) {
            if (resources.putIfAbsent(name,jar) != null) throw new LegacyFailure(Code.OWNER_NAMESPACE);
        }
        resourceOwners = Map.copyOf(resources);
    }
    static LegacyOwner capture(Path sourcePath) {
        OwnerFile source = OwnerFile.capture(sourcePath, LegacyArchive.OUTER_MAXIMUM, null);
        Map<String,byte[]> outer = LegacyArchive.read(source.bytes, LegacyArchive.OUTER_MAXIMUM);
        for (String name : outer.keySet()) if (classOwner(name) != null) throw new LegacyFailure(Code.OWNER_NAMESPACE);
        LegacyIndex index = LegacyIndex.parse(LegacyArchive.required(outer,LegacyIndex.PATH));
        Map<String,Map<String,byte[]>> contents = new HashMap<>();
        for (var owner : LegacyInventory.OWNERS.entrySet()) {
            byte[] value = LegacyArchive.required(outer,owner.getValue());
            if (value.length != index.ownerSize(owner.getKey()) || !OwnerFile.digest(value,"SHA-256").equals(index.digest(owner.getKey()))) {
                throw new LegacyFailure(Code.CONTENT_IDENTITY);
            }
            Map<String,byte[]> entries = LegacyArchive.read(value, owner.getKey().equals("native") ? LegacyArchive.MEMBER_MAXIMUM : 32 * 1024 * 1024);
            validate(owner.getKey(),entries,index); contents.put(owner.getKey(),entries);
        }
        source.requireCurrent(true);
        Path directory = null;
        Object createdDirectoryKey = null;
        Map<String,OwnerFile> created = new HashMap<>();
        try {
            Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            directory = LegacyCache.reserve(temporary);
            createdDirectoryKey = Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            requireDirectory(directory,createdDirectoryKey);
            privatePermissions(directory,true);
            Map<String,VerifiedJar> jars = new HashMap<>();
            for (var owner : LegacyInventory.OWNERS.entrySet()) {
                Path target = directory.resolve(owner.getKey() + ".jar");
                requireDirectory(directory,createdDirectoryKey);
                Files.write(target,LegacyArchive.required(outer,owner.getValue()),StandardOpenOption.CREATE_NEW);
                privatePermissions(target,false);
                OwnerFile file = OwnerFile.capture(target,index.ownerSize(owner.getKey()),index.digest(owner.getKey()));
                created.put(owner.getKey(),file);
                jars.put(owner.getKey(),new VerifiedJar(owner.getKey(),file,contents.get(owner.getKey())));
            }
            Path path = directory.resolve(OWNER_RECORD);
            requireDirectory(directory,createdDirectoryKey);
            Files.write(path,LegacyCache.record(source,index),StandardOpenOption.CREATE_NEW); privatePermissions(path,false);
            OwnerFile ownerRecord = OwnerFile.capture(path,4096,null);
            created.put("record",ownerRecord);
            requireDirectory(directory,createdDirectoryKey);
            source.requireCurrent(true);
            return new LegacyOwner(source,index,directory,ownerRecord,jars);
        } catch (IOException | RuntimeException failure) {
            if (directory != null) discardUnpublished(directory,createdDirectoryKey,created);
            if (failure instanceof LegacyFailure typed) throw typed;
            throw new LegacyFailure(Code.EXTRACTION_IO);
        }
    }
    private static void validate(String owner, Map<String,byte[]> entries, LegacyIndex index) {
        int classes = 0, nativeManifests = 0;
        for (var entry : entries.entrySet()) {
            String name = entry.getKey(); byte[] bytes = entry.getValue();
            if (name.endsWith("/")) continue;
            if (name.endsWith(".class")) {
                if (owner.equals("native") || !owner.equals(classOwner(name)) || ++classes > 512 || bytes.length > 1024 * 1024
                        || bytes.length < 10 || bytes[0] != (byte)0xca || bytes[1] != (byte)0xfe || bytes[2] != (byte)0xba || bytes[3] != (byte)0xbe
                        || ((bytes[6] & 255) << 8 | bytes[7] & 255) > 61) {
                    throw new LegacyFailure(Code.OWNER_NAMESPACE);
                }
            } else if (LegacyInventory.RESOURCES.contains(name)) {
                if (!owner.equals(name.startsWith("p2wlan/native/") ? "native" : "jna")) throw new LegacyFailure(Code.OWNER_NAMESPACE);
                if (owner.equals("jna") && !OwnerFile.digest(bytes,"SHA-256").equals(LegacyInventory.JNA_SHA256.get(name))) {
                    throw new LegacyFailure(Code.CONTENT_IDENTITY);
                }
                if (name.endsWith("/manifest.properties")) {
                    nativeManifests++;
                    var fields = LegacyIndex.lines(bytes,4096);
                    if (!index.values.get("source-revision").equals(fields.get("source-revision"))
                            || !index.values.get("native-source-sha256").equals(fields.get("source-sha256"))) throw new LegacyFailure(Code.CONTENT_IDENTITY);
                    String executable = name.substring(0,name.lastIndexOf('/')+1) + (name.contains("/windows-") ? "p2wlan-mc-agent.exe" : "p2wlan-mc-agent");
                    byte[] binary = LegacyArchive.required(entries,executable);
                    if (!Integer.toString(binary.length).equals(fields.get("executable-size"))
                            || !OwnerFile.digest(binary,"SHA-256").equals(fields.get("executable-sha256"))) throw new LegacyFailure(Code.CONTENT_IDENTITY);
                }
            } else if (!Set.of("META-INF/MANIFEST.MF","fabric.mod.json","META-INF/AL2.0","META-INF/LGPL2.1","META-INF/LICENSE").contains(name)) {
                throw new LegacyFailure(Code.OWNER_NAMESPACE);
            }
        }
        if (owner.equals("native") ? nativeManifests == 0 || nativeManifests > 5 : classes == 0) throw new LegacyFailure(Code.OWNER_NAMESPACE);
        if (owner.equals("native")) {
            long payloads = entries.keySet().stream().filter(name -> name.startsWith("p2wlan/native/") && !name.endsWith("/")).count();
            if (payloads != nativeManifests * 2L) throw new LegacyFailure(Code.OWNER_NAMESPACE);
        }
        if (owner.equals("jna")) {
            var actual = entries.keySet().stream().filter(LegacyInventory.RESOURCES::contains).collect(java.util.stream.Collectors.toSet());
            var expected = LegacyInventory.JNA_SHA256.keySet();
            if (!actual.equals(expected)) throw new LegacyFailure(Code.OWNER_NAMESPACE);
        }
    }
    static String classOwner(String resource) {
        if (resource.startsWith("io/p2wlan/mc/common/") && resource.endsWith(".class")) return "common";
        if (resource.startsWith("io/p2wlan/mc/transport/") && resource.endsWith(".class")) return "transport";
        if (resource.startsWith("com/sun/jna/") && resource.endsWith(".class")) return "jna";
        return null;
    }
    java.util.Optional<URL> locate(String name) {
        requireCurrent();
        String owner = classOwner(name);
        VerifiedJar jar = owner == null ? resourceOwners.get(name) : jars.get(owner);
        if (jar == null || !jar.entries.containsKey(name)) return java.util.Optional.empty();
        return java.util.Optional.of(jar.resource(this,name));
    }
    void requireCurrent() {
        if (closed) throw new LegacyFailure(Code.OWNER_CHANGED);
        source.requireCurrent(true); ownerRecord.requireCurrent(true);
        try {
            var actual = Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!actual.isDirectory() || !java.util.Objects.equals(directoryKey,actual.fileKey())) throw new LegacyFailure(Code.OWNER_CHANGED);
        } catch (IOException failure) { throw new LegacyFailure(Code.OWNER_CHANGED); }
    }
    @Override public void close() {
        if (closed) return;
        requireCurrent();
        try {
            for (VerifiedJar jar : jars.values()) { jar.file.requireCurrent(true); Files.delete(jar.file.path); }
            ownerRecord.requireCurrent(true); Files.delete(ownerRecord.path); Files.delete(directory); closed = true;
        } catch (IOException failure) { throw new LegacyFailure(Code.EXTRACTION_IO); }
    }
    private static void privatePermissions(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException unsupported) { /* Windows user TEMP inherits its local ACL. */ }
    }
    private static void requireDirectory(Path directory, Object key) throws IOException {
        OwnerFile.local(directory);
        var actual = Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (key == null || !actual.isDirectory() || actual.isSymbolicLink() || !java.util.Objects.equals(key,actual.fileKey())) {
            throw new LegacyFailure(Code.OWNER_CHANGED);
        }
    }
    private static void discardUnpublished(Path directory, Object key, Map<String,OwnerFile> created) {
        try {
            for (OwnerFile file : created.values()) {
                requireDirectory(directory,key); file.requireCurrent(true); Files.delete(file.path);
            }
            requireDirectory(directory,key); Files.delete(directory);
        } catch (IOException | LegacyFailure retained) {
            System.err.println("P2WLAN_LEGACY_CACHE_UNCERTAIN");
        }
    }
}
