package io.p2wlan.mc.legacybootstrap;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Read-only installed acceptance edge. It never starts a helper or game. */
public final class LegacyResourceVerifier {
    private record Resource(Path path, String name, byte[] bytes) {}
    public record Resources(URL nativeManifest, URL jnaNative) {}
    public record Provenance(String sourceSha256, String sourceRevision, String nativeSourceSha256,
                             Map<String,String> childSha256) {
        public Provenance { childSha256 = Map.copyOf(childSha256); }
    }
    private LegacyResourceVerifier() {}
    /** Inventory selection for provenance only; production platform selection stays in common. */
    public static Resources resources(ClassLoader loader) {
        if (loader == null) throw new LegacyFailure(Code.PROVENANCE);
        return new Resources(firstResource(loader,true),firstResource(loader,false));
    }
    private static URL firstResource(ClassLoader loader, boolean nativeManifest) {
        for (String name : LegacyInventory.RESOURCES.stream().sorted().toList()) {
            if (!(nativeManifest ? name.startsWith("p2wlan/native/") && name.endsWith("/manifest.properties")
                    : name.startsWith(LegacyInventory.JNA_PREFIX))) continue;
            try {
                var values = loader.getResources(name);
                if (!values.hasMoreElements()) continue;
                URL found = values.nextElement();
                if (values.hasMoreElements()) throw new LegacyFailure(Code.PROVENANCE);
                return found;
            } catch (IOException failure) { throw new LegacyFailure(Code.PROVENANCE); }
        }
        throw new LegacyFailure(Code.RESOURCE_MISSING);
    }
    public static Provenance verify(Path expectedOuter, Class<?> commonAnchor, Class<?> transportAnchor,
                                    Class<?> jnaAnchor, URL nativeManifest, URL jnaNative) {
        OwnerFile source = OwnerFile.capture(expectedOuter,LegacyArchive.OUTER_MAXIMUM,null);
        var archive = LegacyArchive.read(source.bytes,LegacyArchive.OUTER_MAXIMUM);
        LegacyIndex index = LegacyIndex.parse(LegacyArchive.required(archive,LegacyIndex.PATH));
        Map<String,Path> children = new HashMap<>();
        children.put("common",classSource(commonAnchor,"io.p2wlan.mc.common."));
        children.put("transport",classSource(transportAnchor,"io.p2wlan.mc.transport."));
        children.put("jna",classSource(jnaAnchor,"com.sun.jna."));
        Resource nativeResource = resourceSource(nativeManifest,"p2wlan/native/",true);
        Resource jnaResource = resourceSource(jnaNative,LegacyInventory.JNA_PREFIX,false);
        if (!children.get("jna").equals(jnaResource.path)) throw new LegacyFailure(Code.PROVENANCE);
        children.put("native",nativeResource.path);
        Path directory = children.get("common").getParent();
        OwnerFile ownerRecord = OwnerFile.capture(directory.resolve(LegacyOwner.OWNER_RECORD),4096,null);
        var record = LegacyCache.fields(ownerRecord);
        var lease = LegacyCache.lease(record);
        if (!source.path.toUri().toASCIIString().equals(record.get("source-uri")) || !source.sha256.equals(record.get("source-sha256"))
                || lease.pid() != ProcessHandle.current().pid()
                || !ProcessHandle.current().info().startInstant().orElseThrow(() -> new LegacyFailure(Code.PROVENANCE)).equals(lease.started())) {
            throw new LegacyFailure(Code.PROVENANCE);
        }
        Map<String,String> hashes = new HashMap<>();
        for (var child : children.entrySet()) {
            Path path = child.getValue();
            if (!directory.equals(path.getParent()) || !path.getFileName().toString().equals(child.getKey()+".jar")) throw new LegacyFailure(Code.PROVENANCE);
            OwnerFile actual = OwnerFile.capture(path,index.ownerSize(child.getKey()),index.digest(child.getKey()));
            if (!record.get(child.getKey()+".size").equals(Long.toString(actual.size))
                    || !record.get(child.getKey()+".sha256").equals(actual.sha256)) throw new LegacyFailure(Code.PROVENANCE);
            if (child.getKey().equals("native")) requireResource(actual,nativeResource);
            if (child.getKey().equals("jna")) requireResource(actual,jnaResource);
            hashes.put(child.getKey(),actual.sha256);
            actual.requireCurrent(true);
        }
        ownerRecord.requireCurrent(true); source.requireCurrent(true);
        return new Provenance(source.sha256,index.values.get("source-revision"),index.values.get("native-source-sha256"),hashes);
    }
    private static Path classSource(Class<?> type, String prefix) {
        if (type == null || !type.getName().startsWith(prefix)) throw new LegacyFailure(Code.PROVENANCE);
        if (type.getProtectionDomain().getCodeSource() != null) return localJar(type.getProtectionDomain().getCodeSource().getLocation());
        // ML8 deliberately gives unsigned libraries a null CodeSource. Admit only
        // its byte-bound concrete loader and a single class resource in that loader.
        ClassLoader loader = type.getClassLoader();
        if (loader == null || !"cpw.mods.modlauncher.TransformingClassLoader".equals(loader.getClass().getName())
                || !"17".equals(System.getProperty("java.specification.version"))) throw new LegacyFailure(Code.PROVENANCE);
        try {
            var launcher = loader.getClass().getProtectionDomain().getCodeSource();
            if (launcher == null) throw new LegacyFailure(Code.PROVENANCE);
            OwnerFile sdk = OwnerFile.capture(localJar(launcher.getLocation()),256 * 1024,null);
            if (!OwnerFile.digest(sdk.bytes,"SHA-1").equals("6d0bfd78e025a87d43985e30907aa9ecbc51b2d6")
                    || loader.getClass().getMethod("getLoadedClass",String.class).invoke(loader,type.getName()) != type) {
                throw new LegacyFailure(Code.PROVENANCE);
            }
            String resource = type.getName().replace('.','/') + ".class";
            var resources = loader.getResources(resource);
            URL url = null;
            int count = 0, ancestors = 0;
            while (resources.hasMoreElements()) {
                if (++count > 2) throw new LegacyFailure(Code.PROVENANCE);
                URL candidate = resources.nextElement();
                if (prefix.equals("com.sun.jna.") && officialJnaAncestor(candidate,resource)) { ancestors++; continue; }
                if (url != null) throw new LegacyFailure(Code.PROVENANCE);
                url = candidate;
            }
            if (url == null || ancestors > 1 || count > 1 && !prefix.equals("com.sun.jna.")) throw new LegacyFailure(Code.PROVENANCE);
            if (prefix.equals("com.sun.jna.")) {
                if (!type.getName().equals("com.sun.jna.Native")) throw new LegacyFailure(Code.PROVENANCE);
                // ML8 getPackage inherits parent metadata, so package version is
                // not a class-definition fact. JNA4 lacks this 5.x API. The
                // private Version interface must share the concrete loader.
                type.getDeclaredMethod("load",String.class,Class.class);
                var version = type.getField("VERSION");
                if (version.getDeclaringClass().getClassLoader() != loader) {
                    throw new LegacyFailure(Code.PROVENANCE);
                }
            }
            var opened = url.openConnection(); opened.setUseCaches(false);
            if (!(opened instanceof JarURLConnection jar) || !resource.equals(jar.getEntryName())) throw new LegacyFailure(Code.PROVENANCE);
            Path child = localJar(jar.getJarFileURL());
            OwnerFile actual = OwnerFile.capture(child,32 * 1024 * 1024,null);
            byte[] expected = LegacyArchive.required(LegacyArchive.read(actual.bytes,32 * 1024 * 1024),resource);
            try (var input = opened.getInputStream()) {
                if (!java.util.Arrays.equals(expected,input.readNBytes(expected.length + 1))) throw new LegacyFailure(Code.PROVENANCE);
            }
            actual.requireCurrent(true); sdk.requireCurrent(true);
            return child;
        } catch (IOException | ReflectiveOperationException failure) { throw new LegacyFailure(Code.PROVENANCE,failure); }
    }
    private static boolean officialJnaAncestor(URL url, String resource) throws IOException {
        var opened = url.openConnection(); opened.setUseCaches(false);
        if (!(opened instanceof JarURLConnection jar) || !resource.equals(jar.getEntryName())) throw new LegacyFailure(Code.PROVENANCE);
        OwnerFile file = OwnerFile.capture(localJar(jar.getJarFileURL()),4 * 1024 * 1024,null);
        if (!file.sha256.equals("c4dadeeecaa90c8847902082aee5eb107fcf59c5d0e63a17fcaf273c0e2d2bd1")) return false;
        byte[] expected = LegacyArchive.required(LegacyArchive.read(file.bytes,8 * 1024 * 1024),resource);
        try (var input = opened.getInputStream()) {
            if (!java.util.Arrays.equals(expected,input.readNBytes(expected.length + 1))) throw new LegacyFailure(Code.PROVENANCE);
        }
        file.requireCurrent(true);
        return true;
    }
    private static void requireResource(OwnerFile file, Resource resource) {
        byte[] expected = LegacyArchive.required(LegacyArchive.read(file.bytes,LegacyArchive.MEMBER_MAXIMUM),resource.name);
        if (!java.util.Arrays.equals(expected,resource.bytes)) throw new LegacyFailure(Code.PROVENANCE);
    }
    private static Resource resourceSource(URL url, String prefix, boolean manifest) {
        try {
            if (url == null || !"jar".equals(url.getProtocol())) throw new LegacyFailure(Code.PROVENANCE);
            var opened = url.openConnection(); opened.setUseCaches(false);
            if (!(opened instanceof JarURLConnection jar) || !LegacyInventory.RESOURCES.contains(jar.getEntryName())
                    || !jar.getEntryName().startsWith(prefix) || (manifest && !jar.getEntryName().endsWith("/manifest.properties"))) {
                throw new LegacyFailure(Code.PROVENANCE);
            }
            Path child = localJar(jar.getJarFileURL());
            int maximum = manifest ? 4096 : 1024 * 1024;
            try (var input = opened.getInputStream()) {
                byte[] bytes = input.readNBytes(maximum + 1);
                if (bytes.length == 0 || bytes.length > maximum) throw new LegacyFailure(Code.PROVENANCE);
                return new Resource(child,jar.getEntryName(),bytes);
            }
        } catch (IOException failure) { throw new LegacyFailure(Code.PROVENANCE); }
    }
    private static Path localJar(URL url) {
        try {
            if (url == null) throw new LegacyFailure(Code.PROVENANCE);
            if ("jar".equals(url.getProtocol())) {
                var opened = url.openConnection(); opened.setUseCaches(false);
                if (!(opened instanceof JarURLConnection jar)) throw new LegacyFailure(Code.PROVENANCE);
                url = jar.getJarFileURL();
            }
            if (!"file".equals(url.getProtocol())) throw new LegacyFailure(Code.PROVENANCE);
            return OwnerFile.local(Path.of(url.toURI()));
        } catch (IOException | java.net.URISyntaxException | IllegalArgumentException failure) { throw new LegacyFailure(Code.PROVENANCE); }
    }
}
