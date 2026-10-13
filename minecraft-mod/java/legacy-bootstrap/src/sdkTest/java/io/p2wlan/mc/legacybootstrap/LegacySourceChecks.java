package io.p2wlan.mc.legacybootstrap;

import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.spi.FileSystemProvider;
import java.util.function.BiPredicate;

/** Negative origin cases against packaged classes and disposable SDK copies. */
public final class LegacySourceChecks {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("source check " + checks); }
    private static void reject(Runnable action, LegacyFailure.Code code) {
        try { action.run(); throw new AssertionError("origin accepted"); }
        catch (LegacyFailure failure) { check(failure.code() == code); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path sdk = Path.of(args[1]).toAbsolutePath();
        Path carrier = Path.of(args[2]).toAbsolutePath();
        check(root.equals(sdk.getParent()) && root.equals(carrier.getParent()));
        String sha1 = OwnerFile.digest(Files.readAllBytes(sdk),"SHA-1");
        LegacySource source = LegacySource.capture(sha1);
        var provider = FileSystemProvider.installedProviders().stream()
                .filter(candidate -> candidate.getClass().getName().equals("cpw.mods.niofs.union.UnionFileSystemProvider")).findFirst().orElseThrow();
        check(Path.of(provider.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).equals(sdk));
        check(source.local(carrier.toUri().toURL()).equals(carrier));
        reject(() -> LegacySource.capture("0".repeat(40)),LegacyFailure.Code.RUNTIME_PROFILE);
        reject(() -> source.snapshot(Object.class,512 * 1024),LegacyFailure.Code.RUNTIME_PROFILE);
        reject(() -> source.local(url(URI.create("https://example.com/source.jar"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        reject(() -> source.local(url(URI.create("file://example.com/source.jar"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        reject(() -> source.local(url(URI.create(carrier.toUri() + "#fragment"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        reject(() -> source.local(url(URI.create(carrier.toUri() + "?query"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        reject(() -> source.local(url(root.toUri())),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        Path link = root.resolve("carrier-link.jar"); Files.createSymbolicLink(link,carrier);
        reject(() -> source.local(url(link.toUri())),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        reject(() -> source.local(null),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        jarOrigins(source,sha1,carrier,link);
        try (FileSystem filesystem = (FileSystem)provider.getClass()
                .getMethod("newFileSystem",BiPredicate.class,Path[].class)
                .invoke(provider,(BiPredicate<String,String>)(path,base) -> true,(Object)new Path[]{carrier})) {
            URI origin = filesystem.getPath("/").toUri();
            check(source.local(url(origin)).equals(carrier));
            reject(() -> LegacySource.capture(null).local(url(origin)),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(filesystem.getPath("/META-INF/mods.toml").toUri())),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(URI.create(origin + "#fragment"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(URI.create(origin + "?query"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            byte[] bytes = Files.readAllBytes(sdk); var modified = Files.getLastModifiedTime(sdk);
            try {
                Files.write(sdk,new byte[]{1},StandardOpenOption.APPEND);
                reject(() -> source.local(url(origin)),LegacyFailure.Code.OWNER_CHANGED);
            } finally { Files.write(sdk,bytes); Files.setLastModifiedTime(sdk,modified); }
        }
        OwnerFile owner = source.snapshot(LegacySource.class,LegacyArchive.OUTER_MAXIMUM);
        check(owner.path.equals(carrier));
        byte[] original = owner.bytes; var modified = Files.getLastModifiedTime(carrier);
        try {
            Files.write(carrier,new byte[]{1},StandardOpenOption.APPEND);
            reject(() -> owner.requireCurrent(true),LegacyFailure.Code.OWNER_CHANGED);
        } finally { Files.write(carrier,original); Files.setLastModifiedTime(carrier,modified); }
        System.out.println("{\"schema\":1,\"source_checks\":" + checks + ",\"verification\":\"packaged-origin/provider/source-fencing\"}");
    }
    private static void jarOrigins(LegacySource source, String sha1, Path carrier, Path link) throws Exception {
        URI root = URI.create("jar:" + carrier.toUri() + "!/");
        reject(() -> source.local(url(root)),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        try (FileSystem filesystem = java.nio.file.FileSystems.newFileSystem(root,java.util.Map.of())) {
            if (!"d8402fa81935b1a6440240f98079b14d0462ccac".equals(sha1)) {
                reject(() -> source.local(url(root)),LegacyFailure.Code.SOURCE_NOT_LOCAL);
                return;
            }
            check(source.local(url(root)).equals(carrier));
            check(filesystem.getPath("/").toUri().equals(root));
            reject(() -> source.local(url(filesystem.getPath("/META-INF/MANIFEST.MF").toUri())),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(URI.create(root + "#fragment"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(URI.create(root + "?query"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(URI.create("jar:" + root + "!/"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> source.local(url(URI.create("jar:" + link.toUri() + "!/"))),LegacyFailure.Code.SOURCE_NOT_LOCAL);
            reject(() -> LegacySource.capture(null).local(url(root)),LegacyFailure.Code.SOURCE_NOT_LOCAL);
        }
        reject(() -> source.local(url(root)),LegacyFailure.Code.SOURCE_NOT_LOCAL);
    }
    private static URL url(URI uri) {
        try { return new URL(null,uri.toString(),new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) { throw new AssertionError("URL I/O forbidden"); }
        }); }
        catch (java.net.MalformedURLException failure) { throw new AssertionError(failure); }
    }
}
