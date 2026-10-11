package io.p2wlan.mc.neoforge;

import cpw.mods.niofs.union.UnionFileSystemProvider;
import io.p2wlan.mc.common.NativeBundleException;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Real loader filesystem/locality policy, without Minecraft startup. */
public final class LocalNativeResourcesTests {
    private static int checks;
    private static final AtomicInteger urlReads = new AtomicInteger();
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("RESOURCE_CHECK_" + checks); }
    private static URL url(URI uri) throws Exception {
        return URL.of(uri, new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL selected) {
                return new URLConnection(selected) {
                    @Override public void connect() { throw new AssertionError("UNEXPECTED_NETWORK"); }
                    @Override public java.io.InputStream getInputStream() { urlReads.incrementAndGet(); throw new AssertionError("UNEXPECTED_URL_READ"); }
                };
            }
        });
    }
    private static LocalNativeResources resources(URL... urls) {
        return new LocalNativeResources(new ClassLoader(null) {
            @Override public java.util.Enumeration<URL> getResources(String name) { return Collections.enumeration(List.of(urls)); }
        });
    }
    private static void fails(Code reason, LocalNativeResources resources) throws Exception {
        try (var ignored = resources.open("fixture")) { throw new AssertionError("RESOURCE_EXPECTED_" + reason); }
        catch (NativeBundleException failure) { check(failure.code() == reason); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("p2wlan-local-resources-");
        Path data = root.resolve("fixture"); Files.writeString(data, "local native resource");
        var provider = (UnionFileSystemProvider) FileSystemProvider.installedProviders().stream()
                .filter(UnionFileSystemProvider.class::isInstance).findFirst().orElseThrow();
        try (var union = provider.newFileSystem(null, root)) {
            Path resource = union.getPath("fixture");
            check(LocalNativeResources.localBacking(resource));
            try (var stream = resources(url(resource.toUri())).open("fixture")) {
                check(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).equals("local native resource"));
            }
            fails(Code.RESOURCE_MISSING, resources());
            fails(Code.RESOURCE_AMBIGUOUS, resources(url(resource.toUri()), url(resource.toUri())));
            fails(Code.RESOURCE_NOT_LOCAL, resources(url(URI.create("https://example.com/fixture"))));
            fails(Code.RESOURCE_NOT_LOCAL, resources(url(URI.create("jar:https://example.com/native.jar!/fixture"))));
            check(urlReads.get() == 0);
            var layers = new java.util.ArrayList<cpw.mods.niofs.union.UnionFileSystem>();
            try {
                Path backing = union.getRoot();
                for (int index = 0; index < 8; index++) {
                    var next = provider.newFileSystem(null, backing); layers.add(next); backing = next.getRoot();
                }
                check(!LocalNativeResources.localBacking(backing));
            } finally { for (var layer : layers.reversed()) layer.close(); }
        }
        Path archive = root.resolve("native.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "io.p2wlan.mc.nativebundle");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(archive), manifest)) {
            output.putNextEntry(new java.util.jar.JarEntry("fixture")); output.write(Files.readAllBytes(data)); output.closeEntry();
        }
        Path container = root.resolve("outer.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(container))) {
            output.putNextEntry(new java.util.jar.JarEntry("nested.jar")); output.write(Files.readAllBytes(archive)); output.closeEntry();
        }
        try {
            var nestedURI = URI.create("jij:" + container.toUri().getRawSchemeSpecificPart() + "~nested.jar");
            try (var nestedJar = java.nio.file.FileSystems.newFileSystem(nestedURI, java.util.Map.of());
                 var nested = provider.newFileSystem(null, nestedJar.getPath("/"))) {
                check(LocalNativeResources.localBacking(nested.getPath("fixture")));
                try (var stream = resources(url(nested.getPath("fixture").toUri())).open("fixture")) {
                    check(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).equals("local native resource"));
                }
            }
            var configuration = ModuleLayer.boot().configuration().resolve(java.lang.module.ModuleFinder.of(archive),
                    java.lang.module.ModuleFinder.of(), java.util.Set.of("io.p2wlan.mc.nativebundle"));
            var layer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()), null).layer();
            try (var stream = new LocalNativeResources(new ClassLoader(null) {}, layer).open("fixture")) {
                check(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).equals("local native resource"));
            }
            var opened = new AtomicInteger();
            var descriptor = java.lang.module.ModuleDescriptor.newAutomaticModule("io.p2wlan.mc.nativebundle").build();
            var reference = new java.lang.module.ModuleReference(descriptor, URI.create("https://example.com/native.jar")) {
                @Override public java.lang.module.ModuleReader open() throws IOException {
                    opened.incrementAndGet(); throw new AssertionError("REMOTE_MODULE_READER");
                }
            };
            var finder = new java.lang.module.ModuleFinder() {
                public java.util.Optional<java.lang.module.ModuleReference> find(String name) {
                    return name.equals(descriptor.name()) ? java.util.Optional.of(reference) : java.util.Optional.empty();
                }
                public java.util.Set<java.lang.module.ModuleReference> findAll() { return java.util.Set.of(reference); }
            };
            var remoteConfiguration = ModuleLayer.boot().configuration().resolve(finder, java.lang.module.ModuleFinder.of(), java.util.Set.of(descriptor.name()));
            var remoteLayer = ModuleLayer.defineModulesWithOneLoader(remoteConfiguration, List.of(ModuleLayer.boot()), null).layer();
            fails(Code.RESOURCE_NOT_LOCAL, new LocalNativeResources(new ClassLoader(null) {}, remoteLayer));
            check(opened.get() == 0);
        } finally { Files.delete(container); Files.delete(archive); Files.delete(data); Files.delete(root); }
        System.out.println("PASS NeoForge local native resources: " + checks + " checks");
    }
}
