package io.p2wlan.mc.fabric.fixture;

import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.fabric.FabricP2wlan;
import io.p2wlan.mc.game.fixture.InstalledArtifact;
import io.p2wlan.mc.transport.LoopbackBind;
import java.net.URI;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Raw installed origin plus actual remapped/nested class and resource owners. */
public final class InstalledArchiveChecks {
    private InstalledArchiveChecks() {}
    public static void verify() {
        var loader = FabricLoader.getInstance();
        Path archive = InstalledArtifact.checked(loader.getGameDir());
        if (archive == null) return;
        try {
            var production = loader.getModContainer("p2wlan").orElseThrow();
            var origin = production.getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1
                    || !origin.getPaths().get(0).toAbsolutePath().normalize().equals(archive)) {
                throw new IllegalStateException("FABRIC_INSTALLED_MOD_SOURCE");
            }
            owns(production, FabricP2wlan.class);
            owns(nested("p2wlan_common", "META-INF/jarjar/common.jar"), WorldHost.Target.class);
            owns(nested("p2wlan_transport", "META-INF/jarjar/transport.jar"), LoopbackBind.class);
            if (!WorldHost.Target.class.isAssignableFrom(LoopbackBind.class)) {
                throw new IllegalStateException("FABRIC_INSTALLED_CORE_IDENTITY");
            }
            if (loader.getEnvironmentType() == EnvType.CLIENT) {
                // This string is relocated with the complete test bytecode,
                // selecting the active Fabric implementation in Universal.
                owns(production, Class.forName("io.p2wlan.mc.game.client.ClientPlatform", false, InstalledArchiveChecks.class.getClassLoader()));
            }
            var jna = loader.getAllMods().stream().filter(mod -> {
                var value = mod.getOrigin();
                return value.getKind() == ModOrigin.Kind.NESTED && "p2wlan".equals(value.getParentModId())
                        && "META-INF/jars/jna.jar".equals(value.getParentSubLocation());
            }).toList();
            if (jna.size() != 1) throw new IllegalStateException("FABRIC_INSTALLED_JNA_SOURCE");
            owns(jna.get(0), Class.forName("com.sun.jna.Native", false, InstalledArchiveChecks.class.getClassLoader()));
            var nativeMod = nested("p2wlan_native", "META-INF/jarjar/native.jar");
            int manifests = 0;
            for (String platform : java.util.List.of("macos-aarch64", "macos-x86_64", "linux-aarch64", "linux-x86_64", "windows-x86_64")) {
                String name = "p2wlan/native/" + platform + "/manifest.properties";
                var expectedPath = nativeMod.findPath(name);
                if (expectedPath.isEmpty()) continue;
                manifests++;
                var candidates = WorldHost.class.getClassLoader().getResources(name);
                if (!candidates.hasMoreElements()) throw new IllegalStateException("FABRIC_INSTALLED_NATIVE_SOURCE");
                URI actual = candidates.nextElement().toURI();
                if (candidates.hasMoreElements() || !java.nio.file.Files.isSameFile(expectedPath.get(), Path.of(actual))) {
                    throw new IllegalStateException("FABRIC_INSTALLED_NATIVE_SOURCE");
                }
            }
            if (manifests == 0) throw new IllegalStateException("FABRIC_INSTALLED_NATIVE_SOURCE");
        } catch (java.io.IOException | ReflectiveOperationException | java.net.URISyntaxException | java.util.NoSuchElementException failure) {
            throw new IllegalStateException("FABRIC_INSTALLED_SOURCE_IO");
        }
    }
    private static ModContainer nested(String id, String path) {
        var container = FabricLoader.getInstance().getModContainer(id).orElseThrow();
        var origin = container.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.NESTED || !"p2wlan".equals(origin.getParentModId())
                || !path.equals(origin.getParentSubLocation())) {
            throw new IllegalStateException("FABRIC_INSTALLED_NESTED_SOURCE");
        }
        return container;
    }
    private static void owns(ModContainer container, Class<?> type) throws java.net.URISyntaxException {
        var domain = type.getProtectionDomain().getCodeSource();
        if (domain == null) throw new IllegalStateException("FABRIC_INSTALLED_CLASS_SOURCE");
        URI source = domain.getLocation().toURI();
        if (!"file".equals(source.getScheme()) || source.getAuthority() != null) {
            throw new IllegalStateException("FABRIC_INSTALLED_CLASS_SOURCE");
        }
        Path actual = Path.of(source).toAbsolutePath().normalize();
        boolean matched = false;
        for (Path root : container.getRootPaths()) {
            URI location = root.toUri();
            if ("jar".equals(location.getScheme())) {
                String raw = location.getRawSchemeSpecificPart();
                int boundary = raw.indexOf("!/");
                if (boundary < 0) continue;
                location = URI.create(raw.substring(0, boundary));
            }
            if ("file".equals(location.getScheme()) && location.getAuthority() == null
                    && Path.of(location).toAbsolutePath().normalize().equals(actual)) matched = true;
        }
        if (!matched) throw new IllegalStateException("FABRIC_INSTALLED_CLASS_SOURCE");
    }
}
