package io.p2wlan.mc.forge.fixture;

import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.game.fixture.InstalledArtifact;
import io.p2wlan.mc.legacybootstrap.LegacyResourceVerifier;
import io.p2wlan.mc.transport.LoopbackBind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/** Test-only old-loader provenance before shared gameplay checks begin. */
public final class InstalledArchiveChecks {
    private InstalledArchiveChecks() {}
    public static void verify() {
        Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path archive = InstalledArtifact.checked(game);
        if (archive == null) return;
        var file = ModList.get().getModFileById("p2wlan");
        if (file == null || !file.getFile().getFilePath().toAbsolutePath().normalize().equals(archive)) {
            throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_MOD_SOURCE");
        }
        var resources = LegacyResourceVerifier.resources(InstalledArchiveChecks.class.getClassLoader());
        var provenance = LegacyResourceVerifier.verify(archive, WorldHost.Target.class, LoopbackBind.class,
                com.sun.jna.Native.class, resources.nativeManifest(), resources.jnaNative());
        if (!WorldHost.Target.class.isAssignableFrom(LoopbackBind.class)
                || !provenance.childSha256().keySet().equals(java.util.Set.of("common", "transport", "native", "jna"))
                || !provenance.sourceSha256().equals(System.getProperty("p2wlan.gameFixture.installedSha256"))) {
            throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_PROVENANCE");
        }
        String role = game.getFileName().toString();
        if (!role.equals("server") && !role.equals("client")) throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_PATH");
        var evidence = new com.google.gson.JsonObject();
        evidence.addProperty("schema", 1);
        evidence.addProperty("minecraft", "1.16.5");
        evidence.addProperty("loader", "forge");
        evidence.addProperty("artifact_sha256", provenance.sourceSha256());
        evidence.addProperty("source_revision", provenance.sourceRevision());
        evidence.addProperty("native_source_sha256", provenance.nativeSourceSha256());
        evidence.addProperty("jna_resource_prefix", "p2wlan/jna/");
        var children = new com.google.gson.JsonObject();
        new java.util.TreeMap<>(provenance.childSha256()).forEach(children::addProperty);
        evidence.add("children", children);
        // Revalidate the checked outer at the report's persistence boundary.
        if (!archive.equals(InstalledArtifact.checked(game))) throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_CHANGED");
        try {
            Files.writeString(game.getParent().resolve(role + "-installed-modules.fixture.json"),
                    evidence.toString(), StandardOpenOption.CREATE_NEW);
        } catch (IOException | SecurityException failure) {
            throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_IO", failure);
        }
    }
}
