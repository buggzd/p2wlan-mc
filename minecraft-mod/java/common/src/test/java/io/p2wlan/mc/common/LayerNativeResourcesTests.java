package io.p2wlan.mc.common;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Local file/module policy only; loader tests exercise their real NIO edges. */
public final class LayerNativeResourcesTests {
    private static int checks;
    private static final AtomicInteger urlReads = new AtomicInteger();
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("LAYER_RESOURCE_CHECK_" + checks); }
    private static URL url(URI uri) throws Exception {
        return new URL(null, uri.toASCIIString(), new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL selected) {
                return new URLConnection(selected) {
                    @Override public void connect() { throw new AssertionError("UNEXPECTED_NETWORK"); }
                    @Override public java.io.InputStream getInputStream() { urlReads.incrementAndGet(); throw new AssertionError("UNEXPECTED_URL_READ"); }
                };
            }
        });
    }
    private static LayerNativeResources resources(ModuleLayer layer, URL... urls) {
        return new LayerNativeResources(new ClassLoader(null) {
            @Override public java.util.Enumeration<URL> getResources(String name) { return Collections.enumeration(List.of(urls)); }
        }, layer, path -> null);
    }
    private static void fails(Code reason, LayerNativeResources resources) throws Exception {
        try (var ignored = resources.open("fixture")) { throw new AssertionError("RESOURCE_EXPECTED_" + reason); }
        catch (NativeBundleException failure) { check(failure.code() == reason); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("p2wlan-resource-policy-");
        Path data = root.resolve("fixture"); Files.writeString(data, "local native resource");
        Path archive = root.resolve("native.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "io.p2wlan.mc.nativebundle");
        try {
            check(LayerNativeResources.localBacking(data, path -> null));
            try (var stream = resources(null, data.toUri().toURL()).open("fixture")) {
                check(new String(stream.readAllBytes(), StandardCharsets.UTF_8).equals("local native resource"));
            }
            fails(Code.RESOURCE_MISSING, resources(null));
            fails(Code.RESOURCE_AMBIGUOUS, resources(null, url(data.toUri()), url(data.toUri())));
            fails(Code.RESOURCE_NOT_LOCAL, resources(null, url(URI.create("https://example.com/native"))));
            fails(Code.RESOURCE_NOT_LOCAL, resources(null, url(URI.create("jar:https://example.com/native.jar!/fixture"))));
            // A cyclic/over-depth backing edge has one fixed eight-step budget.
            var edges = new AtomicInteger();
            check(!LayerNativeResources.localBacking(data, path -> { edges.incrementAndGet(); return path; }));
            check(edges.get() == 8);
            try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(archive), manifest)) {
                output.putNextEntry(new java.util.jar.JarEntry("fixture")); output.write(Files.readAllBytes(data)); output.closeEntry();
            }
            try (var stream = resources(null, URI.create("jar:" + archive.toUri() + "!/fixture").toURL()).open("fixture")) {
                check(new String(stream.readAllBytes(), StandardCharsets.UTF_8).equals("local native resource"));
            }
            var configuration = ModuleLayer.boot().configuration().resolve(java.lang.module.ModuleFinder.of(archive),
                    java.lang.module.ModuleFinder.of(), java.util.Set.of("io.p2wlan.mc.nativebundle"));
            var layer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()), null).layer();
            try (var stream = resources(layer).open("fixture")) {
                check(new String(stream.readAllBytes(), StandardCharsets.UTF_8).equals("local native resource"));
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
            var remote = ModuleLayer.boot().configuration().resolve(finder, java.lang.module.ModuleFinder.of(), java.util.Set.of(descriptor.name()));
            var remoteLayer = ModuleLayer.defineModulesWithOneLoader(remote, List.of(ModuleLayer.boot()), null).layer();
            fails(Code.RESOURCE_NOT_LOCAL, resources(remoteLayer));
            check(opened.get() == 0); check(urlReads.get() == 0);
        } finally { Files.deleteIfExists(archive); Files.deleteIfExists(data); Files.delete(root); }
        System.out.println("PASS common local/module resource policy: " + checks + " checks");
    }
}
