package io.p2wlan.mc.bootstrap;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import static io.p2wlan.mc.bootstrap.BootstrapFailure.Code;

/** Pure loader/version decisions plus bounded, local classpath metadata reads. */
public final class UniversalEnvironment {
    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final String FABRIC_MARKER = "net/fabricmc/loader/api/FabricLoader.class";
    public static final String NEOFORGE_MARKER = "net/neoforged/fml/loading/FMLLoader.class";
    public static final String FORGE_MARKER = "net/minecraftforge/fml/loading/FMLLoader.class";
    public static final String SERVER_REFMAP = "p2wlan-universal-fabric-server-refmap.json";
    public static final String CLIENT_REFMAP = "p2wlan-universal-fabric-client-refmap.json";
    private static final int MAX_METADATA_BYTES = 8192;
    private static final List<String> CLIENT_MIXINS = List.of("ClientConnectionMixin", "IntegratedServerMixin",
            "MultiplayerListMixin", "MultiplayerListAccessor", "MultiplayerScreenMixin");
    public enum Loader { FABRIC, NEOFORGE, FORGE }
    public enum Side { CLIENT, SERVER, UNKNOWN }
    public enum Family { FABRIC, FML }
    public record Selection(Loader loader, String minecraftVersion) {
        public Selection {
            Objects.requireNonNull(loader);
            if (!MINECRAFT_VERSION.equals(minecraftVersion)) throw new BootstrapFailure(Code.GAME_VERSION_UNSUPPORTED);
        }
        public Family family() { return loader == Loader.FABRIC ? Family.FABRIC : Family.FML; }
    }
    public interface ResourceLookup {
        boolean hasResource(String name);
        InputStream openResource(String name) throws IOException;
    }
    private UniversalEnvironment() {}
    public static Selection select(String minecraftVersion, Predicate<String> resources) {
        Objects.requireNonNull(resources);
        Loader selected = null;
        for (Loader loader : Loader.values()) {
            String marker = switch (loader) {
                case FABRIC -> FABRIC_MARKER;
                case NEOFORGE -> NEOFORGE_MARKER;
                case FORGE -> FORGE_MARKER;
            };
            if (resources.test(marker)) {
                if (selected != null) throw new BootstrapFailure(Code.LOADER_AMBIGUOUS);
                selected = loader;
            }
        }
        if (selected == null) throw new BootstrapFailure(Code.LOADER_MISSING);
        return new Selection(selected, minecraftVersion);
    }
    public static Selection select(ResourceLookup resources) {
        // Reject ambiguous loader markers before opening any metadata resource.
        Selection loader = select(MINECRAFT_VERSION, resources::hasResource);
        try (InputStream input = resources.openResource("version.json")) {
            if (input == null) throw new BootstrapFailure(Code.VERSION_METADATA_MISSING);
            return new Selection(loader.loader(), readMinecraftVersion(input));
        } catch (IOException failure) {
            throw new BootstrapFailure(Code.RESOURCE_ACCESS_FAILED);
        }
    }
    public static Selection current() {
        var loaders = new LinkedHashSet<ClassLoader>();
        loaders.add(UniversalEnvironment.class.getClassLoader());
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());
        loaders.remove(null);
        return select(new ResourceLookup() {
            private URL find(String name) {
                for (ClassLoader loader : loaders) {
                    URL value = loader.getResource(name);
                    if (value != null) return value;
                }
                return null;
            }
            @Override public boolean hasResource(String name) { return find(name) != null; }
            @Override public InputStream openResource(String name) throws IOException {
                URL resource = find(name);
                return resource == null ? null : openLocal(resource);
            }
        });
    }
    private static InputStream openLocal(URL resource) throws IOException {
        try {
            URI uri = resource.toURI();
            if ("file".equals(uri.getScheme())) {
                if (uri.getAuthority() != null || !Path.of(uri).isAbsolute()) throw new BootstrapFailure(Code.RESOURCE_NOT_LOCAL);
                return Files.newInputStream(Path.of(uri));
            }
            if ("jar".equals(uri.getScheme())) {
                String raw = uri.getRawSchemeSpecificPart();
                int boundary = raw.indexOf("!/");
                if (boundary < 0) throw new BootstrapFailure(Code.RESOURCE_NOT_LOCAL);
                URI backing = URI.create(raw.substring(0, boundary));
                if (!"file".equals(backing.getScheme()) || backing.getAuthority() != null
                        || !Path.of(backing).isAbsolute()) throw new BootstrapFailure(Code.RESOURCE_NOT_LOCAL);
                var connection = resource.openConnection(); connection.setUseCaches(false);
                return connection.getInputStream();
            }
            if ("union".equals(uri.getScheme()) || "jij".equals(uri.getScheme())) return Files.newInputStream(Path.of(uri));
            throw new BootstrapFailure(Code.RESOURCE_NOT_LOCAL);
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException failure) {
            throw new BootstrapFailure(Code.RESOURCE_NOT_LOCAL);
        }
    }
    private static String readMinecraftVersion(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
        if (bytes.length > MAX_METADATA_BYTES) throw new BootstrapFailure(Code.VERSION_METADATA_TOO_LARGE);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new BootstrapFailure(Code.VERSION_METADATA_INVALID);
        }
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false); reader.beginObject();
            var fields = new HashSet<String>(); String id = null, name = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field) || fields.size() > 64) throw new BootstrapFailure(Code.VERSION_METADATA_INVALID);
                if (field.equals("id") || field.equals("name")) {
                    if (reader.peek() != JsonToken.STRING) throw new BootstrapFailure(Code.VERSION_METADATA_INVALID);
                    String value = reader.nextString();
                    if (field.equals("id")) id = value; else name = value;
                } else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || id == null || name == null || !id.equals(name)) {
                throw new BootstrapFailure(Code.VERSION_METADATA_INVALID);
            }
            return id;
        } catch (IOException | IllegalStateException failure) {
            if (failure instanceof BootstrapFailure typed) throw typed;
            throw new BootstrapFailure(Code.VERSION_METADATA_INVALID);
        }
    }
    public static Family family(String packageName, boolean client) {
        String normalized = packageName != null && packageName.endsWith(".")
                ? packageName.substring(0, packageName.length() - 1) : packageName;
        String suffix = client ? ".mixin" : ".server.mixin";
        if (("io.p2wlan.mc.game" + suffix).equals(normalized)) return Family.FML;
        if (("io.p2wlan.mc.fabric.game" + suffix).equals(normalized)) return Family.FABRIC;
        throw new BootstrapFailure(Code.MIXIN_PACKAGE_INVALID);
    }
    public static List<String> serverMixins(Selection selection, Family family) {
        return selection.family() == family ? List.of("ServerConnectionMixin") : List.of();
    }
    public static List<String> clientMixins(Selection selection, Family family, Side side) {
        if (selection.family() != family) return List.of();
        return switch (Objects.requireNonNull(side)) {
            case CLIENT -> CLIENT_MIXINS;
            case SERVER -> List.of();
            case UNKNOWN -> throw new BootstrapFailure(Code.CLIENT_SIDE_UNAVAILABLE);
        };
    }
}
