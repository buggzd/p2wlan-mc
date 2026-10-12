package io.p2wlan.mc.neoforge.fixture;

import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.game.fixture.InstalledArtifact;
import io.p2wlan.mc.neoforge.LocalNativeResources;
import io.p2wlan.mc.neoforge.NeoForgeP2wlan;
import io.p2wlan.mc.transport.LoopbackBind;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Installed origins, never development-library visibility, precede game checks. */
public final class InstalledArchiveChecks {
    private InstalledArchiveChecks() {}
    public static void verify() {
        Path archive = InstalledArtifact.checked(FMLPaths.GAMEDIR.get());
        if (archive == null) return;
        var file = ModList.get().getModFileById("p2wlan");
        if (file == null || !file.getFile().getFilePath().toAbsolutePath().normalize().equals(archive)) {
            throw new IllegalStateException("NEOFORGE_INSTALLED_MOD_SOURCE");
        }
        Module production = NeoForgeP2wlan.class.getModule();
        Module common = WorldHost.Target.class.getModule();
        Module transport = LoopbackBind.class.getModule();
        ModuleLayer layer = production.getLayer();
        boolean commonIdentity = resolved(layer, common);
        boolean transportIdentity = resolved(layer, transport);
        boolean commonReadable = production.canRead(common);
        boolean transportReadable = production.canRead(transport);
        Module nativeModule = layer == null ? null : layer.findModule("io.p2wlan.mc.nativebundle").orElse(null);
        boolean nativeIdentity = nativeModule != null && resolved(layer, nativeModule);
        boolean nativeReadable = nativeModule != null && production.canRead(nativeModule);
        var evidence = new com.google.gson.JsonObject();
        evidence.addProperty("production", moduleName(production));
        evidence.addProperty("common", moduleName(common));
        evidence.addProperty("transport", moduleName(transport));
        evidence.addProperty("production_named", production.isNamed());
        evidence.addProperty("common_layer", common.getLayer() == layer);
        evidence.addProperty("transport_layer", transport.getLayer() == layer);
        evidence.addProperty("common_resolved_identity", commonIdentity);
        evidence.addProperty("transport_resolved_identity", transportIdentity);
        evidence.addProperty("common_readable", commonReadable);
        evidence.addProperty("transport_readable", transportReadable);
        evidence.addProperty("native", nativeModule == null ? "MISSING" : moduleName(nativeModule));
        evidence.addProperty("native_layer", nativeModule != null && nativeModule.getLayer() == layer);
        evidence.addProperty("native_resolved_identity", nativeIdentity);
        evidence.addProperty("native_readable", nativeReadable);
        evidence.addProperty("assignable", WorldHost.Target.class.isAssignableFrom(LoopbackBind.class));
        var productionTrace = new com.google.gson.JsonArray();
        var commonTrace = new com.google.gson.JsonArray();
        var transportTrace = new com.google.gson.JsonArray();
        var nativeTrace = new com.google.gson.JsonArray();
        boolean productionBacking = insideArchive(production, archive, productionTrace);
        boolean commonBacking = insideArchive(common, archive, commonTrace);
        boolean transportBacking = insideArchive(transport, archive, transportTrace);
        boolean nativeBacking = nativeModule != null && insideArchive(nativeModule, archive, nativeTrace);
        evidence.add("production_backing", productionTrace);
        evidence.add("common_backing", commonTrace);
        evidence.add("transport_backing", transportTrace);
        evidence.add("native_backing", nativeTrace);
        evidence.addProperty("production_archive", productionBacking);
        evidence.addProperty("common_archive", commonBacking);
        evidence.addProperty("transport_archive", transportBacking);
        evidence.addProperty("native_archive", nativeBacking);
        String role = archive.getParent().getParent().getFileName().toString();
        if (!role.equals("server") && !role.equals("client")) throw new IllegalStateException("NEOFORGE_INSTALLED_MOD_SOURCE");
        try {
            String diagnostic = evidence.toString();
            if (diagnostic.length() > 16384) throw new IllegalStateException("NEOFORGE_INSTALLED_DIAGNOSTIC_CAPACITY");
            Files.writeString(archive.getParent().getParent().getParent().resolve(role + "-installed-modules.fixture.json"),
                    diagnostic, StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException failure) { throw new IllegalStateException("NEOFORGE_INSTALLED_DIAGNOSTIC_IO"); }
        if (!production.isNamed() || layer == null || !common.isNamed() || !transport.isNamed()
                || !commonIdentity || !transportIdentity || !commonReadable || !transportReadable
                || !WorldHost.Target.class.isAssignableFrom(LoopbackBind.class)
                || !productionBacking || !commonBacking || !transportBacking) {
            throw new IllegalStateException("NEOFORGE_INSTALLED_MODULE_SOURCE");
        }
        if (!nativeIdentity || !nativeReadable || !nativeBacking) {
            throw new IllegalStateException("NEOFORGE_INSTALLED_NATIVE_SOURCE");
        }
    }
    private static String moduleName(Module module) {
        String name = module.getName();
        return name != null && name.matches("[A-Za-z0-9_.]{1,80}") ? name : "OTHER";
    }
    private static boolean resolved(ModuleLayer layer, Module module) {
        return layer != null && module.isNamed() && layer.findModule(module.getName()).orElse(null) == module;
    }
    private static boolean insideArchive(Module module, Path archive, com.google.gson.JsonArray trace) {
        if (!module.isNamed() || module.getLayer() == null) return false;
        try {
            var reference = module.getLayer().configuration().findModule(module.getName()).orElseThrow().reference();
            Path backing = Path.of(reference.location().orElseThrow());
            for (int depth = 0; depth < 8; depth++) {
                var step = new com.google.gson.JsonObject();
                String scheme = backing.toUri().getScheme();
                String type = backing.getFileSystem().getClass().getName();
                step.addProperty("scheme", scheme != null && scheme.matches("[a-z]{1,20}") ? scheme : "OTHER");
                step.addProperty("type", type.matches("[A-Za-z0-9_.$]{1,160}") ? type : "OTHER");
                step.addProperty("provider_module", moduleName(backing.getFileSystem().getClass().getModule()));
                step.addProperty("typed_module", moduleName(cpw.mods.niofs.union.UnionFileSystem.class.getModule()));
                step.addProperty("typed_union", backing.getFileSystem() instanceof cpw.mods.niofs.union.UnionFileSystem);
                step.addProperty("typed_nested", backing.getFileSystem() instanceof net.neoforged.jarjar.nio.pathfs.PathFileSystem);
                step.addProperty("archive_match", backing.toAbsolutePath().normalize().equals(archive));
                trace.add(step);
                Path next = LocalNativeResources.backing(backing);
                if (next != null) backing = next;
                else {
                    var uri = backing.toUri();
                    if ("jar".equals(uri.getScheme())) {
                        String raw = uri.getRawSchemeSpecificPart();
                        int boundary = raw.indexOf("!/");
                        if (boundary < 0) return false;
                        backing = Path.of(java.net.URI.create(raw.substring(0, boundary)));
                    } else return "file".equals(uri.getScheme()) && uri.getAuthority() == null
                            && backing.toAbsolutePath().normalize().equals(archive);
                }
            }
        } catch (RuntimeException failure) { return false; }
        return false;
    }
}
