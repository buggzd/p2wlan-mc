package io.p2wlan.mc.forge.fixture;

import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.forge.ForgeP2wlan;
import io.p2wlan.mc.transport.LoopbackBind;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Test-only provenance checks before shared game assertions begin. */
public final class InstalledArchiveChecks {
    private static final long MAX_BINARY = 128L * 1024 * 1024;
    private InstalledArchiveChecks() {}
    public static void verify() {
        String archiveValue = System.getProperty("p2wlan.gameFixture.installedArchive");
        String expected = System.getProperty("p2wlan.gameFixture.installedSha256");
        if (archiveValue == null && expected == null) return;
        if (archiveValue == null || expected == null || !expected.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_ARGUMENTS");
        }
        try {
            Path archive = Path.of(archiveValue);
            Path mods = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve("mods");
            if (!archive.isAbsolute() || !archive.normalize().equals(archive)
                    || !mods.equals(archive.getParent()) || !archive.getFileName().toString().endsWith(".jar")) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_PATH");
            }
            if (Files.isSymbolicLink(archive) || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_FILE");
            }
            var file = ModList.get().getModFileById("p2wlan");
            if (file == null || !file.getFile().getFilePath().toAbsolutePath().normalize().equals(archive)) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_MOD_SOURCE");
            }
            var before = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = FileChannel.open(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = input.size();
                if (size < 1 || size > MAX_BINARY || size != before.size()) {
                    throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_SIZE");
                }
                var buffer = ByteBuffer.allocate(32768);
                long count = 0;
                while (count < size) {
                    buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), size - count));
                    int read = input.read(buffer);
                    if (read <= 0) throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_CHANGED");
                    count += read; buffer.flip(); digest.update(buffer);
                }
                if (input.size() != size) throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_CHANGED");
            }
            var after = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size()
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_CHANGED");
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_SHA256");
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
                    || !"netty.transport".equals(transport.getName()) || transport.getLayer() != layer
                    || !WorldHost.Target.class.isAssignableFrom(LoopbackBind.class)
                    || !commonBacking || !transportBacking) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_MODULES");
            }
            var nativeModule = layer.findModule("io.p2wlan.mc.nativebundle");
            if (nativeModule.isEmpty() || !nativeModule.get().isNamed() || nativeModule.get().getLayer() != layer
                    || !insideArchive(nativeModule.get(), archive, new com.google.gson.JsonArray())) {
                throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_NATIVE_MODULE");
            }
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException | FileSystemNotFoundException | SecurityException failure) {
            throw new IllegalStateException("FORGE_INSTALLED_ARCHIVE_IO");
        }
    }
    private static String moduleName(Module module) {
        String name = module.getName();
        return name != null && name.matches("[A-Za-z0-9_.]{1,80}") ? name : "OTHER";
    }
    private static boolean insideArchive(Module module, Path archive, com.google.gson.JsonArray trace) {
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
