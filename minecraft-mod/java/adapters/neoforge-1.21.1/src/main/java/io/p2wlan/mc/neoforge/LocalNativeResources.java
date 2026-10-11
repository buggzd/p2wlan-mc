package io.p2wlan.mc.neoforge;

import cpw.mods.niofs.union.UnionFileSystem;
import net.neoforged.jarjar.nio.pathfs.PathFileSystem;
import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.NativeBundleException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** NeoForge's typed local filesystem glue, not a general URL-protocol bypass.
 * The loader owns these filesystems; this resolver owns only its returned stream. */
public final class LocalNativeResources implements BundledAgent.Resources {
    private final ClassLoader loader;
    private final ModuleLayer layer;
    public LocalNativeResources(ClassLoader loader) { this(loader, null); }
    public LocalNativeResources(ClassLoader loader, ModuleLayer layer) { this.loader = loader; this.layer = layer; }
    @Override public InputStream open(String name) throws IOException {
        if (layer != null) {
            var module = layer.configuration().findModule("io.p2wlan.mc.nativebundle");
            if (module.isPresent()) {
                var reference = module.get().reference();
                try {
                    Path location = Path.of(reference.location().orElseThrow(() -> new NativeBundleException(Code.RESOURCE_NOT_LOCAL)));
                    if (!localBacking(location)) throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
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
    private static InputStream openLocalURI(java.net.URI uri) throws IOException {
        if (!uri.getScheme().equals("union")) return BundledAgent.openLocal(uri.toURL());
        try {
            Path resource = Path.of(uri);
            if (!(resource.getFileSystem() instanceof UnionFileSystem) || !localBacking(resource)) {
                throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
            }
            // NIO reads from the proven local backing, never a URL connection.
            return Files.newInputStream(resource);
        } catch (java.nio.file.FileSystemNotFoundException | IllegalArgumentException error) {
            throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
        }
    }
    static boolean localBacking(Path resource) {
        Path backing = resource;
        for (int depth = 0; depth < 8; depth++) {
            if (backing.getFileSystem() instanceof UnionFileSystem union) {
                backing = union.getPrimaryPath();
            } else if (backing.getFileSystem() instanceof PathFileSystem nested) {
                backing = nested.getTarget();
            } else {
                var uri = backing.toUri();
                if (uri.getScheme().equals("jar")) {
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
                return uri.getScheme().equals("file") && uri.getAuthority() == null && Path.of(uri).isAbsolute();
            }
        }
        return false;
    }
}
