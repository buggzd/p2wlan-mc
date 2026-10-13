package io.p2wlan.mc.legacybootstrap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Returns its one outer Mod; only Forge36 gets the four canonical libraries. */
public final class LegacyModLocator implements IModLocator {
    private LegacyEligibility.Binding binding;
    private LegacyFmlBridge bridge;
    private OwnerFile source;
    private LegacyOwner owner;
    private final Map<IModFile,OwnerFile> files = new IdentityHashMap<>();
    private final Map<IModFile,FileSystem> systems = new IdentityHashMap<>();
    private final Map<IModFile,Set<String>> entries = new IdentityHashMap<>();
    private boolean initialized, scanned;
    public LegacyModLocator() {}
    @Override public String name() { return "p2wlan_universal"; }
    @Override public void initArguments(Map<String,?> arguments) {
        if (initialized) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        initialized = true;
        ClassLoader loader = getClass().getClassLoader();
        binding = LegacyEligibility.capture(arguments,loader);
        source = binding.source().snapshot(getClass(),LegacyArchive.OUTER_MAXIMUM);
        Path outer = source.path;
        if (binding.legacy()) { owner = LegacyOwner.capture(outer); source = owner.source; }
        bridge = new LegacyFmlBridge(binding,loader);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close,"p2wlan-legacy-core-cleanup"));
    }
    @Override @SuppressWarnings({"rawtypes","unchecked"}) public List scanMods() {
        requireCurrent();
        if (scanned) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        scanned = true;
        List<Object> result = new ArrayList<>();
        IModFile outer = bridge.create(source.path,this); files.put(outer,source);
        if (binding.legacy()) {
            systems.put(outer,open(source));
            entries.put(outer,LegacyArchive.read(source.bytes,LegacyArchive.OUTER_MAXIMUM).keySet());
        }
        result.add(bridge.discoveryValue(outer));
        if (owner != null) for (String name : List.of("common","transport","native","jna")) {
            OwnerFile child = owner.jars.get(name).file;
            child.requireCurrent(true);
            IModFile file = bridge.create(child.path,this);
            if (file.getType() != IModFile.Type.LIBRARY) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            files.put(file,child); systems.put(file,open(child)); entries.put(file,owner.jars.get(name).entries.keySet()); result.add(file);
        }
        requireCurrent();
        return List.copyOf(result);
    }
    @Override public Optional<Manifest> findManifest(Path path) {
        requireCurrent();
        OwnerFile file = source.path.equals(path) ? source : null;
        if (file == null && owner != null) for (VerifiedJar jar : owner.jars.values()) if (jar.file.path.equals(path)) file = jar.file;
        if (file == null) throw new LegacyFailure(Code.PROVENANCE);
        file.requireCurrent(true);
        var entries = LegacyArchive.read(file.bytes,LegacyArchive.OUTER_MAXIMUM);
        try {
            Manifest manifest = new Manifest(new ByteArrayInputStream(LegacyArchive.required(entries,"META-INF/MANIFEST.MF")));
            if (file != source) manifest.getMainAttributes().putValue("FMLModType","LIBRARY");
            return Optional.of(manifest);
        } catch (IOException failure) { throw new LegacyFailure(Code.ARCHIVE_CONTRACT); }
    }
    @Override public Path findPath(IModFile file, String... parts) {
        if (binding == null || !binding.legacy()) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        var names = entries.get(file);
        var filesystem = systems.get(file);
        if (names == null || filesystem == null) throw new LegacyFailure(Code.PROVENANCE);
        String name = String.join("/",parts); LegacyArchive.canonical(name);
        // FML asks each mod about every game class. Immutable inventory misses
        // return only a nonexistent ZIP path, with zero owner reads/hashes.
        Path path = filesystem.getPath("/" + name);
        if (!names.contains(name)) return path;
        currentFile(file);
        return path;
    }
    @Override public boolean isValid(IModFile file) {
        currentFile(file); return true;
    }
    @Override public void scanFile(IModFile file, Consumer<Path> consumer) {
        currentFile(file);
        Consumer<Path> fenced = path -> { currentFile(file); consumer.accept(path); };
        if (!binding.legacy()) { bridge.scan(file,fenced); return; }
        try (var paths = Files.walk(systems.get(file).getPath("/"),64)) {
            List<Path> classes = paths.filter(path -> path.toString().endsWith(".class")).limit(4097).sorted().toList();
            if (classes.size() > 4096) throw new LegacyFailure(Code.CAPACITY);
            classes.forEach(fenced);
        } catch (IOException failure) { throw new LegacyFailure(Code.RESOURCE_IO); }
    }
    private void currentFile(IModFile file) {
        requireCurrent(); OwnerFile current = files.get(file);
        if (current == null) throw new LegacyFailure(Code.PROVENANCE);
        if (current != source) current.requireCurrent(true);
    }
    private void requireCurrent() {
        if (binding == null || source == null) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        binding.requireCurrent();
        if (owner != null) owner.requireCurrent();
        else source.requireCurrent(true);
    }
    private static FileSystem open(OwnerFile file) {
        file.requireCurrent(true);
        try { return FileSystems.newFileSystem(file.path,Map.of()); }
        catch (IOException failure) { throw new LegacyFailure(Code.RESOURCE_IO); }
    }
    private void close() {
        try {
            for (FileSystem system : systems.values()) system.close();
            if (owner != null) owner.close();
        } catch (IOException failure) { System.err.println(new LegacyFailure(Code.RESOURCE_IO).getMessage()); }
        catch (LegacyFailure failure) { System.err.println(failure.getMessage()); }
    }
}
