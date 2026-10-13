package io.p2wlan.mc.legacybootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

/** Forge40's protected official factory; the carrier owns every callback. */
public final class LegacyForge40Factory extends AbstractJarFileModLocator {
    private final LegacyModLocator owner;

    public LegacyForge40Factory(IModLocator owner) {
        if (!(owner instanceof LegacyModLocator carrier)) throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE);
        this.owner = carrier;
    }

    public IModFile create(Path path) {
        return createMod("MOD", path).orElseThrow(() -> new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE));
    }

    @Override public String name() { return owner.name(); }
    @Override public void initArguments(Map<String, ?> arguments) { owner.initArguments(arguments); }
    @Override public boolean isValid(IModFile file) { return owner.isValid(file); }
    @Override public void scanFile(IModFile file, Consumer<Path> consumer) { owner.scanFile(file, consumer); }
    @Override @SuppressWarnings("unchecked") public List<IModFile> scanMods() { return owner.scanMods(); }
    @Override public List<IModFile> scanMods(Iterable<IModFile> files) { return owner.scanMods(files); }
    @Override public Stream<Path> scanCandidates() { throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE); }
    public Optional<Manifest> findManifest(Path path) { return owner.findManifest(path); }
    public Path findPath(IModFile file, String... parts) { return owner.findPath(file, parts); }
}
