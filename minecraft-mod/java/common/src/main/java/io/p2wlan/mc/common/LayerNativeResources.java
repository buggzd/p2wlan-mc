package io.p2wlan.mc.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Bounded local/module resource policy. Loaders supply only typed filesystem
 * backing edges; this resolver owns the returned stream, not those filesystems. */
public final class LayerNativeResources implements BundledAgent.Resources {
    private final ClassLoader loader;
    private final ModuleLayer layer;
    private final java.util.function.Function<Path, Path> unwrap;
    public LayerNativeResources(ClassLoader loader, ModuleLayer layer, java.util.function.Function<Path, Path> unwrap) {
        this.loader = java.util.Objects.requireNonNull(loader);
        this.layer = layer;
        this.unwrap = java.util.Objects.requireNonNull(unwrap);
    }
    @Override public InputStream open(String name) throws IOException {
        if (layer != null) {
            var module = layer.configuration().findModule("io.p2wlan.mc.nativebundle");
            if (module.isPresent()) {
                var reference = module.get().reference();
                try {
                    Path location = Path.of(reference.location().orElseThrow(() -> new NativeBundleException(Code.RESOURCE_NOT_LOCAL)));
                    if (!localBacking(location, unwrap)) throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
                    // Validate locality before opening a module reader. Resource-only
                    // JarJar modules cannot be found by package-based classloader lookup.
                    try (var reader = reference.open()) {
                        var uri = reader.find(name).orElseThrow(() -> new NativeBundleException(Code.RESOURCE_MISSING));
                        return openLocalURI(uri);
                    }
                } catch (java.nio.file.FileSystemNotFoundException | IllegalArgumentException error) {
                    throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
                }
            }
        }
        var candidates = loader.getResources(name);
        if (!candidates.hasMoreElements()) throw new NativeBundleException(Code.RESOURCE_MISSING);
        var url = candidates.nextElement();
        if (candidates.hasMoreElements()) throw new NativeBundleException(Code.RESOURCE_AMBIGUOUS);
        if (!url.getProtocol().equals("union")) return BundledAgent.openLocal(url);
        try { return openLocalURI(url.toURI()); }
        catch (java.net.URISyntaxException error) { throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL); }
    }
    private InputStream openLocalURI(java.net.URI uri) throws IOException {
        if (!"union".equals(uri.getScheme())) return BundledAgent.openLocal(uri.toURL());
        try {
            Path resource = Path.of(uri);
            if (unwrap.apply(resource) == null || !localBacking(resource, unwrap)) {
                throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
            }
            // NIO reads from the proven local backing, never a URL connection.
            return Files.newInputStream(resource);
        } catch (java.nio.file.FileSystemNotFoundException | IllegalArgumentException error) {
            throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
        }
    }
    public static boolean localBacking(Path resource, java.util.function.Function<Path, Path> unwrap) {
        Path backing = resource;
        for (int depth = 0; depth < 8; depth++) {
            Path next = unwrap.apply(backing);
            if (next != null) {
                backing = next;
            } else {
                var uri = backing.toUri();
                if ("jar".equals(uri.getScheme())) {
                    // JarJar's union may be backed by an entry of a standard
                    // local ZIP filesystem, rather than directly by a file.
                    String nested = uri.getRawSchemeSpecificPart();
                    int boundary = nested.indexOf("!/");
                    if (boundary < 0) return false;
                    uri = java.net.URI.create(nested.substring(0, boundary));
                    try { backing = Path.of(uri); }
                    catch (java.nio.file.FileSystemNotFoundException | IllegalArgumentException error) { return false; }
                    continue;
                }
                return "file".equals(uri.getScheme()) && uri.getAuthority() == null && Path.of(uri).isAbsolute();
            }
        }
        return false;
    }
}
