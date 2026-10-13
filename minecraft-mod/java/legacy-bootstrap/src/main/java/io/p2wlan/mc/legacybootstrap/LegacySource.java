package io.p2wlan.mc.legacybootstrap;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Local archives through one exact official bootstrap filesystem provider. */
final class LegacySource {
    private static final String UNION = "cpw.mods.niofs.union.";
    private final OwnerFile providerOwner;
    private final FileSystemProvider provider;
    private final Class<?> pathType, filesystemType;
    private final Class<?>[] moduleLoaderTypes;
    private final Method primary;
    private final boolean jarOrigins;

    private LegacySource(OwnerFile owner, FileSystemProvider provider, Class<?> path, Class<?> filesystem,
                         Class<?>[] moduleLoaders, Method primary, boolean jarOrigins) {
        providerOwner = owner; this.provider = provider; pathType = path;
        filesystemType = filesystem; moduleLoaderTypes = moduleLoaders.clone(); this.primary = primary; this.jarOrigins = jarOrigins;
    }
    static LegacySource capture(String secureSha1) {
        if (secureSha1 == null) return new LegacySource(null,null,null,null,new Class<?>[0],null,false);
        try {
            FileSystemProvider installed = null;
            for (FileSystemProvider candidate : FileSystemProvider.installedProviders()) if (candidate.getClass().getName().equals(UNION + "UnionFileSystemProvider")) {
                if (installed != null) throw new LegacyFailure(Code.RUNTIME_PROFILE);
                installed = candidate;
            }
            if (installed == null) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            Class<?> providerType = installed.getClass();
            // The filesystem implementation is an initial Bootstrap module.
            // The actual installed object, not a service-layer namesake,
            // supplies the direct file origin and nonrecursive trust anchor.
            OwnerFile owner = OwnerFile.capture(file(location(providerType)),512 * 1024,null);
            if (!OwnerFile.digest(owner.bytes,"SHA-1").equals(secureSha1)) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            boolean jars = "d8402fa81935b1a6440240f98079b14d0462ccac".equals(secureSha1);
            ClassLoader loader = providerType.getClassLoader();
            Class<?> path = Class.forName(UNION + "UnionPath",false,loader);
            Class<?> filesystem = Class.forName(UNION + "UnionFileSystem",false,loader);
            Class<?> moduleLoader = Class.forName("cpw.mods.cl.ModuleClassLoader",false,loader);
            Class<?> secure = Class.forName("cpw.mods.jarhandling.SecureJar",false,loader);
            for (Class<?> anchor : new Class<?>[]{providerType,path,filesystem,moduleLoader,secure}) {
                if (anchor.getClassLoader() != providerType.getClassLoader() || !bootstrapOrigin(anchor,owner,jars).equals(owner.path)) throw new LegacyFailure(Code.RUNTIME_PROFILE);
                resource(owner,anchor,2 * 1024 * 1024);
            }
            Class<?>[] moduleLoaders = {moduleLoader};
            if (jars) {
                Class<?> secureLoader = Class.forName("net.minecraftforge.securemodules.SecureModuleClassLoader",false,loader);
                if (secureLoader.getClassLoader() != loader || !bootstrapOrigin(secureLoader,owner,true).equals(owner.path)) throw new LegacyFailure(Code.RUNTIME_PROFILE);
                resource(owner,secureLoader,2 * 1024 * 1024);
                moduleLoaders = new Class<?>[]{moduleLoader,secureLoader};
            }
            if (!"union".equals(installed.getScheme())) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            Method getter = filesystem.getDeclaredMethod("getPrimaryPath");
            if (!Modifier.isPublic(getter.getModifiers()) || Modifier.isStatic(getter.getModifiers())
                    || getter.getDeclaringClass() != filesystem || getter.getParameterCount() != 0
                    || getter.getReturnType() != Path.class) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            owner.requireCurrent(true);
            return new LegacySource(owner,installed,path,filesystem,moduleLoaders,getter,jars);
        } catch (ReflectiveOperationException failure) { throw new LegacyFailure(Code.RUNTIME_PROFILE); }
    }
    OwnerFile pinned(Class<?> anchor, String expectedSha1) {
        OwnerFile owner = snapshot(anchor,512 * 1024);
        if (!OwnerFile.digest(owner.bytes,"SHA-1").equals(expectedSha1)) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        return owner;
    }
    OwnerFile snapshot(Class<?> anchor, long maximum) {
        URL origin = location(anchor);
        if (("union".equals(origin.getProtocol()) || "jar".equals(origin.getProtocol())) &&
                (!anchor.getModule().isNamed() || anchor.getModule().getLayer() == null || anchor.getClassLoader() == null
                || !moduleLoader(anchor) && !bootstrapApi(anchor,origin))) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        OwnerFile owner = OwnerFile.capture(local(origin),maximum,null);
        long inflated = Math.max(maximum,2 * 1024 * 1024);
        if (inflated > LegacyArchive.OUTER_MAXIMUM) throw new LegacyFailure(Code.CAPACITY);
        resource(owner,anchor,(int)inflated);
        requireCurrent(); owner.requireCurrent(true);
        return owner;
    }
    Path local(URL location) {
        if (location == null) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
        if ("file".equals(location.getProtocol())) return file(location);
        if ("jar".equals(location.getProtocol()) && jarOrigins) return jarRoot(location);
        if (!"union".equals(location.getProtocol()) || provider == null) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
        try {
            URI uri = location.toURI();
            if (uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            requireCurrent();
            Path path = provider.getPath(uri);
            FileSystem filesystem = path.getFileSystem();
            if (path.getClass() != pathType || filesystem.getClass() != filesystemType || filesystem.provider() != provider
                    || !filesystem.isOpen() || !filesystem.isReadOnly() || !path.equals(path.getRoot())
                    || !path.toUri().equals(uri)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            Object backing = primary.invoke(filesystem);
            if (!(backing instanceof Path target)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            Path local = OwnerFile.local(target);
            if (!Files.isRegularFile(local,LinkOption.NOFOLLOW_LINKS)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            requireCurrent();
            if (provider.getPath(uri).getFileSystem() != filesystem || !filesystem.isOpen()
                    || !target.equals(primary.invoke(filesystem))) throw new LegacyFailure(Code.OWNER_CHANGED);
            return local;
        } catch (java.net.URISyntaxException | ReflectiveOperationException | IllegalArgumentException
                 | java.nio.file.FileSystemNotFoundException | java.nio.file.ClosedFileSystemException failure) {
            throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
        }
    }
    void requireCurrent() { if (providerOwner != null) providerOwner.requireCurrent(true); }
    private boolean moduleLoader(Class<?> anchor) {
        Class<?> actual = anchor.getClassLoader().getClass();
        if (Arrays.stream(moduleLoaderTypes).anyMatch(type -> type == actual)) return true;
        // Forge52's initial named modules may define the pinned implementation
        // separately from the raw installed NIO provider. Authenticate that
        // exact class and owner, rather than accepting a namesake or subclass.
        if (!jarOrigins || Arrays.stream(moduleLoaderTypes).noneMatch(type -> type.getName().equals(actual.getName()))
                || !actual.getModule().isNamed() || !"cpw.mods.securejarhandler".equals(actual.getModule().getName())) return false;
        if (!bootstrapOrigin(actual,providerOwner,true).equals(providerOwner.path)) return false;
        resource(providerOwner,actual,2 * 1024 * 1024); requireCurrent();
        return true;
    }
    private boolean bootstrapApi(Class<?> anchor, URL origin) {
        if (!jarOrigins || !anchor.getName().equals("cpw.mods.jarhandling.SecureJar")
                || !anchor.getModule().getName().equals("cpw.mods.securejarhandler")
                || !bootstrapOrigin(anchor,providerOwner,true).equals(providerOwner.path)) return false;
        try { return anchor.getClassLoader().getClass() == Class.forName("jdk.internal.loader.Loader",false,null); }
        catch (ClassNotFoundException failure) { throw new LegacyFailure(Code.RUNTIME_PROFILE); }
    }
    private Path jarRoot(URL origin) {
        try {
            requireCurrent();
            URI uri = origin.toURI(); String part = uri.getRawSchemeSpecificPart();
            if (!uri.isOpaque() || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !part.endsWith("!/") || part.indexOf("!/") != part.length()-2) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            Path local = file(new URI(part.substring(0,part.length()-2)).toURL());
            if (!URI.create("jar:" + local.toUri() + "!/").equals(uri)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            ClassLoader loader = ModuleLayer.boot().findModule("jdk.zipfs").orElseThrow(() -> new LegacyFailure(Code.RUNTIME_PROFILE)).getClassLoader();
            Class<?> type = Class.forName("jdk.nio.zipfs.ZipFileSystemProvider",false,loader);
            FileSystemProvider installed = null;
            for (FileSystemProvider candidate : FileSystemProvider.installedProviders()) if (candidate.getClass() == type) {
                if (installed != null) throw new LegacyFailure(Code.RUNTIME_PROFILE);
                installed = candidate;
            }
            if (installed == null) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            Path path = installed.getPath(uri); FileSystem system = path.getFileSystem();
            if (path.getClass() != Class.forName("jdk.nio.zipfs.ZipPath",false,loader)
                    || system.getClass() != Class.forName("jdk.nio.zipfs.ZipFileSystem",false,loader)
                    || system.provider() != installed || !system.isOpen() || !path.equals(path.getRoot())
                    || !path.toUri().equals(uri)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            requireCurrent();
            return local;
        } catch (java.net.URISyntaxException | java.net.MalformedURLException | ClassNotFoundException
                | IllegalArgumentException | java.nio.file.FileSystemNotFoundException | java.nio.file.ClosedFileSystemException failure) {
            throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
        }
    }
    private static Path bootstrapOrigin(Class<?> anchor, OwnerFile owner, boolean jars) {
        URL origin = location(anchor);
        if ("file".equals(origin.getProtocol())) return file(origin);
        try {
            if (jars && "jar".equals(origin.getProtocol()) && URI.create("jar:" + owner.path.toUri() + "!/").equals(origin.toURI())) return owner.path;
        } catch (java.net.URISyntaxException failure) { throw new LegacyFailure(Code.RUNTIME_PROFILE); }
        throw new LegacyFailure(Code.RUNTIME_PROFILE);
    }
    private static URL location(Class<?> anchor) {
        var code = anchor.getProtectionDomain().getCodeSource();
        if (code == null || code.getLocation() == null) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        return code.getLocation();
    }
    private static Path file(URL location) {
        try {
            if (location == null || !"file".equals(location.getProtocol())) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            URI uri = location.toURI();
            if (uri.getQuery() != null || uri.getFragment() != null) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            Path local = OwnerFile.local(Path.of(uri));
            if (!Files.isRegularFile(local,LinkOption.NOFOLLOW_LINKS)) throw new LegacyFailure(Code.SOURCE_NOT_LOCAL);
            return local;
        } catch (java.net.URISyntaxException | IllegalArgumentException failure) { throw new LegacyFailure(Code.SOURCE_NOT_LOCAL); }
    }
    private static void resource(OwnerFile owner, Class<?> anchor, int maximum) {
        String name = anchor.getName().replace('.','/') + ".class";
        byte[] expected = LegacyArchive.required(LegacyArchive.read(owner.bytes,maximum),name);
        try (var stream = anchor.getResourceAsStream("/" + name)) {
            if (stream == null || !Arrays.equals(expected,stream.readNBytes(expected.length + 1))) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        } catch (IOException failure) { throw new LegacyFailure(Code.RUNTIME_PROFILE); }
        owner.requireCurrent(true);
    }
}
