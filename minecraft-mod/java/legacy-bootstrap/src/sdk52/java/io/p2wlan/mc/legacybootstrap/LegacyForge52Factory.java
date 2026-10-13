package io.p2wlan.mc.legacybootstrap;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

/** Public Forge52 factories with GAME metadata owned by the parsed ModFile. */
public final class LegacyForge52Factory extends AbstractModProvider implements IModLocator {
    private final LegacyModLocator owner;

    public LegacyForge52Factory(IModLocator owner) {
        if (!(owner instanceof LegacyModLocator carrier)) throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE);
        this.owner = carrier;
    }

    public IModFile create(Path path) {
        if (path == null) throw new LegacyFailure(LegacyFailure.Code.SOURCE_NOT_LOCAL);
        GameMetadata metadata = new GameMetadata();
        // The official protected factory prefers the outer SERVICE module-info.
        // Supply GAME metadata through the public SecureJar factory instead.
        SecureJar jar = SecureJar.from(ignored -> metadata, path);
        IModFile file = new ModFile(jar, this, ModFileParser::modsTomlParser, "MOD");
        IModFileInfo info = file.getModFileInfo();
        if (info == null || info.getFileProperties().containsKey("__FORGE__not_a_forge_mod")) {
            throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE);
        }
        metadata.bind(file);
        return file;
    }

    @Override public String name() { return owner.name(); }
    @Override public void initArguments(Map<String, ?> arguments) { owner.initArguments(arguments); }
    @Override public boolean isValid(IModFile file) { return owner.isValid(file); }
    @Override public void scanFile(IModFile file, Consumer<Path> consumer) { owner.scanFile(file, consumer); }
    @Override @SuppressWarnings("unchecked") public List<ModFileOrException> scanMods() { return owner.scanMods(); }
    public Optional<Manifest> findManifest(Path path) { return owner.findManifest(path); }
    public Path findPath(IModFile file, String... parts) { return owner.findPath(file, parts); }

    private static final class GameMetadata implements JarMetadata {
        private IModFile file;

        void bind(IModFile value) {
            if (file != null || value == null) throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE);
            file = value;
            info();
        }

        private IModFileInfo info() {
            IModFileInfo value = file == null ? null : file.getModFileInfo();
            if (value == null || value.getMods().isEmpty() || value.getMods().get(0).getModId() == null) {
                throw new LegacyFailure(LegacyFailure.Code.RUNTIME_PROFILE);
            }
            return value;
        }

        // Forge52 ModFileInfo.moduleName() delegates back to SecureJar.name().
        // Read the first parsed ModInfo directly, as its official metadata does.
        @Override public String name() { return info().getMods().get(0).getModId(); }
        @Override public String version() { return info().versionString(); }
        @Override public ModuleDescriptor descriptor() {
            IModFileInfo authority = info();
            SecureJar jar = file.getSecureJar();
            var builder = ModuleDescriptor.newAutomaticModule(authority.getMods().get(0).getModId())
                    .version(authority.versionString()).packages(jar.getPackages());
            for (SecureJar.Provider provider : jar.getProviders()) {
                if (!provider.providers().isEmpty()) builder.provides(provider.serviceName(), provider.providers());
            }
            authority.usesServices().forEach(builder::uses);
            return builder.build();
        }
    }
}
