package io.p2wlan.mc.forge.fixture;

import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.forge.ForgeP2wlan;
import io.p2wlan.mc.game.fixture.InstalledArtifact;
import io.p2wlan.mc.transport.LoopbackBind;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Test-only provenance checks before shared game assertions begin. */
public final class InstalledArchiveChecks {
    private InstalledArchiveChecks() {}
    public static void verify() {
        Path archive = InstalledArtifact.checked(FMLPaths.GAMEDIR.get());
        if (archive == null) return;
        try {
            Path mods = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve("mods");
            var file = ModList.get().getModFileById("p2wlan");
            if (file == null || !file.getFile().getFilePath().toAbsolutePath().normalize().equals(archive)) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_MOD_SOURCE");
            }
            Module production = ForgeP2wlan.class.getModule();
            Module common = WorldHost.Target.class.getModule();
            Module transport = LoopbackBind.class.getModule();
            ModuleLayer layer = production.getLayer();
            var evidence = new com.google.gson.JsonObject();
            evidence.addProperty("production", moduleName(production));
            evidence.addProperty("common", moduleName(common));
            evidence.addProperty("transport", moduleName(transport));
            evidence.addProperty("common_layer", common.getLayer() == layer);
            evidence.addProperty("transport_layer", transport.getLayer() == layer);
            evidence.addProperty("native_module", layer != null && layer.findModule("io.p2wlan.mc.nativebundle").isPresent());
            var commonTrace = new com.google.gson.JsonArray();
            var transportTrace = new com.google.gson.JsonArray();
            boolean commonBacking = insideArchive(common, archive, commonTrace);
            boolean transportBacking = insideArchive(transport, archive, transportTrace);
            evidence.add("common_backing", commonTrace);
            evidence.add("transport_backing", transportTrace);
            String role = mods.getParent().getFileName().toString();
            if (!role.equals("server") && !role.equals("client")) throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_PATH");
            Files.writeString(mods.getParent().getParent().resolve(role + "-installed-modules.fixture.json"), evidence.toString(), StandardOpenOption.CREATE_NEW);
            if (!production.isNamed() || layer == null
                    || !"common".equals(common.getName()) || common.getLayer() != layer
                    || !(Boolean.getBoolean("p2wlan.gameFixture.installedUniversal") ? "transport" : "netty.transport").equals(transport.getName()) || transport.getLayer() != layer
                    || !WorldHost.Target.class.isAssignableFrom(LoopbackBind.class)
                    || !insideArchive(production, archive, new com.google.gson.JsonArray()) || !commonBacking || !transportBacking) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_MODULES");
            }
            var nativeModule = layer.findModule("io.p2wlan.mc.nativebundle");
            if (nativeModule.isEmpty() || !nativeModule.get().isNamed() || nativeModule.get().getLayer() != layer
                    || !insideArchive(nativeModule.get(), archive, new com.google.gson.JsonArray())) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_NATIVE_MODULE");
            }
        } catch (IOException | IllegalArgumentException | FileSystemNotFoundException | SecurityException failure) {
            throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_IO");
        }
    }
    private static String moduleName(Module module) {
        String name = module.getName();
        return name != null && name.matches("[A-Za-z0-9_.]{1,80}") ? name : "OTHER";
    }
    private static boolean insideArchive(Module module, Path archive, com.google.gson.JsonArray trace) {
        if (!module.isNamed() || module.getLayer() == null) return false;
        var reference = module.getLayer().configuration().findModule(module.getName()).orElseThrow().reference();
        Path backing = Path.of(reference.location().orElseThrow());
        for (int depth = 0; depth < 8; depth++) {
            var step = new com.google.gson.JsonObject();
            String scheme = backing.toUri().getScheme();
            String filesystem = backing.getFileSystem().getClass().getSimpleName();
            step.addProperty("scheme", scheme != null && scheme.matches("[a-z]{1,20}") ? scheme : "OTHER");
            step.addProperty("filesystem", filesystem.matches("[A-Za-z0-9_$]{1,80}") ? filesystem : "OTHER");
            String type = backing.getFileSystem().getClass().getName();
            step.addProperty("type", type.matches("[A-Za-z0-9_.$]{1,160}") ? type : "OTHER");
            step.addProperty("provider_module", moduleName(backing.getFileSystem().getClass().getModule()));
            step.addProperty("typed_module", moduleName(cpw.mods.niofs.union.UnionFileSystem.class.getModule()));
            step.addProperty("expected", backing.toAbsolutePath().normalize().equals(archive));
            trace.add(step);
            Path next = io.p2wlan.mc.forge.LocalNativeResources.backing(backing);
            if (next != null) {
                backing = next;
            } else {
                var uri = backing.toUri();
                if ("jar".equals(uri.getScheme())) {
                    String raw = uri.getRawSchemeSpecificPart();
                    int boundary = raw.indexOf("!/");
                    if (boundary < 0) return false;
                    backing = Path.of(java.net.URI.create(raw.substring(0, boundary)));
                } else {
                    return "file".equals(uri.getScheme()) && uri.getAuthority() == null
                            && backing.toAbsolutePath().normalize().equals(archive);
                }
            }
        }
        return false;
    }
}
