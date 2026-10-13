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

/** Forge47's protected official factory; the carrier owns every callback. */
public final class LegacyForge47Factory extends AbstractJarFileModLocator {
    private final LegacyModLocator owner;

    public LegacyForge47Factory(IModLocator owner) {
        if (!(owner instanceof LegacyModLocator carrier)) throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE);
        this.owner = carrier;
    }

    public IModFile create(Path path) {
        ModFileOrException result = createMod(path);
        if (result.ex() != null || result.file() == null) {
            throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE, result.ex());
        }
        return result.file();
    }

    @Override protected String getDefaultJarModType() { return "MOD"; }
    @Override public String name() { return owner.name(); }
    @Override public void initArguments(Map<String, ?> arguments) { owner.initArguments(arguments); }
    @Override public boolean isValid(IModFile file) { return owner.isValid(file); }
    @Override public void scanFile(IModFile file, Consumer<Path> consumer) { owner.scanFile(file, consumer); }
    @Override @SuppressWarnings("unchecked") public List<ModFileOrException> scanMods() { return owner.scanMods(); }
    @Override public Stream<Path> scanCandidates() { throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE); }
    public Optional<Manifest> findManifest(Path path) { return owner.findManifest(path); }
    public Path findPath(IModFile file, String... parts) { return owner.findPath(file, parts); }
}
