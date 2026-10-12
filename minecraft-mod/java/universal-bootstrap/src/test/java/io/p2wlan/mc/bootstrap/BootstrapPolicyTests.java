package io.p2wlan.mc.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static io.p2wlan.mc.bootstrap.BootstrapFailure.Code;
import static io.p2wlan.mc.bootstrap.UniversalEnvironment.*;

/** Executable startup policy assertions. No game, native helper or socket. */
public final class BootstrapPolicyTests {
    private static int checks;
    private static final List<String> CLIENT_NAMES = List.of("ClientConnectionMixin", "IntegratedServerMixin",
            "MultiplayerListMixin", "MultiplayerListAccessor", "MultiplayerScreenMixin");
    private static final List<String> FABRIC_LEGACY_CLIENT_NAMES = List.of("ClientConnectionMixin", "IntegratedServerMixin",
            "MultiplayerListMixin", "MultiplayerListAccessor", "MultiplayerScreenMixin", "FabricLoginNetworkingMixin");
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("BOOTSTRAP_CHECK_" + checks);
    }
    private static void fails(Code expected, Runnable effect) {
        try { effect.run(); throw new AssertionError("BOOTSTRAP_EXPECTED_" + expected); }
        catch (BootstrapFailure failure) { check(failure.code() == expected); }
    }
    private static void immutable(List<String> values) {
        try { values.add("UnexpectedMixin"); throw new AssertionError("MIXIN_LIST_MUTABLE"); }
        catch (UnsupportedOperationException expected) { check(true); }
    }
    private static String marker(Loader loader) {
        return switch (loader) { case FABRIC -> FABRIC_MARKER; case NEOFORGE -> NEOFORGE_MARKER; case FORGE -> FORGE_MARKER; };
    }
    private static ResourceLookup resources(Set<String> markers, byte[] metadata) {
        return new ResourceLookup() {
            @Override public boolean hasResource(String name) { return markers.contains(name); }
            @Override public InputStream openResource(String name) {
                return metadata == null ? null : new ByteArrayInputStream(metadata);
            }
        };
    }
    private static ResourceLookup json(String value) {
        return resources(Set.of(FABRIC_MARKER), value.getBytes(StandardCharsets.UTF_8));
    }
    private static void selections() {
        for (Loader loader : Loader.values()) {
            Selection selected = select(MINECRAFT_VERSION, Set.of(marker(loader))::contains);
            check(selected.loader() == loader); check(selected.minecraftVersion().equals("1.21.1"));
            check(selected.family() == (loader == Loader.FABRIC ? Family.FABRIC : Family.FML));
        }
        fails(Code.LOADER_MISSING, () -> select(MINECRAFT_VERSION, name -> false));
        List<String> markers = List.of(FABRIC_MARKER, NEOFORGE_MARKER, FORGE_MARKER);
        for (int first = 0; first < markers.size(); first++) {
            for (int next = first + 1; next < markers.size(); next++) {
                Set<String> pair = Set.of(markers.get(first), markers.get(next));
                fails(Code.LOADER_AMBIGUOUS, () -> select(MINECRAFT_VERSION, pair::contains));
            }
        }
        fails(Code.LOADER_AMBIGUOUS, () -> select(MINECRAFT_VERSION, markers::contains));
        fails(Code.GAME_VERSION_UNSUPPORTED, () -> select("1.20.2", Set.of(FABRIC_MARKER)::contains));
        for (Loader loader : Loader.values()) {
            check(select(resources(Set.of(marker(loader)), "{\"id\":\"1.21.1\",\"name\":\"1.21.1\"}".getBytes(StandardCharsets.UTF_8))).loader() == loader);
        }
        fails(Code.VERSION_METADATA_MISSING, () -> select(resources(Set.of(FABRIC_MARKER), null)));
        fails(Code.VERSION_METADATA_INVALID, () -> select(json("{\"id\":\"1.21.1\",\"id\":\"1.21.1\",\"name\":\"1.21.1\"}")));
        fails(Code.VERSION_METADATA_INVALID, () -> select(json("{\"id\":\"1.21.1\",\"name\":\"1.20.1\"}")));
        fails(Code.VERSION_METADATA_INVALID, () -> select(json("{\"id\":1,\"name\":\"1.21.1\"}")));
        fails(Code.VERSION_METADATA_INVALID, () -> select(json("{\"id\":\"1.21.1\",\"name\":\"1.21.1\"} {}")));
        fails(Code.VERSION_METADATA_INVALID, () -> select(resources(Set.of(FABRIC_MARKER), new byte[]{(byte) 0xff})));
        fails(Code.VERSION_METADATA_TOO_LARGE, () -> select(resources(Set.of(FABRIC_MARKER), new byte[8193])));
        fails(Code.GAME_VERSION_UNSUPPORTED, () -> select(json("{\"id\":\"1.20.2\",\"name\":\"1.20.2\"}")));
        check(select(json("{\"id\":\"1.21.1\",\"name\":\"1.21.1\",\"pack_version\":{\"resource\":34,\"data\":48}}"))
                .minecraftVersion().equals("1.21.1"));
        var closed = new AtomicInteger();
        select(new ResourceLookup() {
            @Override public boolean hasResource(String name) { return name.equals(FABRIC_MARKER); }
            @Override public InputStream openResource(String name) {
                return new ByteArrayInputStream("{\"id\":\"1.21.1\",\"name\":\"1.21.1\"}".getBytes(StandardCharsets.UTF_8)) {
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        });
        check(closed.get() == 1);
        fails(Code.RESOURCE_ACCESS_FAILED, () -> select(new ResourceLookup() {
            @Override public boolean hasResource(String name) { return name.equals(FABRIC_MARKER); }
            @Override public InputStream openResource(String name) throws IOException { throw new IOException("ignored fixture detail"); }
        }));
        var opened = new AtomicInteger();
        fails(Code.LOADER_AMBIGUOUS, () -> select(new ResourceLookup() {
            @Override public boolean hasResource(String name) { return true; }
            @Override public InputStream openResource(String name) { opened.incrementAndGet(); return null; }
        }));
        check(opened.get() == 0);
    }
    private static void dispatches() {
        for (Loader loader : Loader.values()) {
            Selection selected = select(MINECRAFT_VERSION, Set.of(marker(loader))::contains);
            String active = loader == Loader.FABRIC ? "io.p2wlan.mc.fabric.game" : "io.p2wlan.mc.game";
            String inactive = loader == Loader.FABRIC ? "io.p2wlan.mc.game" : "io.p2wlan.mc.fabric.game";
            var server = new ServerMixinPlugin(selected); server.onLoad(active + ".server.mixin");
            check(server.getMixins().equals(List.of("ServerConnectionMixin", "ServerLoginConnectionMixin", "ServerLoginMixin", "ServerConfigurationMixin")));
            check(java.util.Objects.equals(server.getRefMapperConfig(), loader == Loader.FABRIC ? SERVER_REFMAP : null));
            var otherServer = new ServerMixinPlugin(selected); otherServer.onLoad(inactive + ".server.mixin");
            check(otherServer.getMixins().isEmpty()); check(otherServer.getRefMapperConfig() == null);
            var client = new ClientMixinPlugin(selected, () -> Side.CLIENT); client.onLoad(active + ".mixin");
            check(client.getMixins().size() == 5); check(client.getMixins().equals(CLIENT_NAMES));
            check(java.util.Objects.equals(client.getRefMapperConfig(), loader == Loader.FABRIC ? CLIENT_REFMAP : null));
            var dedicated = new ClientMixinPlugin(selected, () -> Side.SERVER); dedicated.onLoad(active + ".mixin");
            check(dedicated.getMixins().isEmpty());
            var unknown = new ClientMixinPlugin(selected, () -> Side.UNKNOWN); unknown.onLoad(active + ".mixin");
            fails(Code.CLIENT_SIDE_UNAVAILABLE, unknown::getMixins);
            var otherClient = new ClientMixinPlugin(selected, () -> { throw new AssertionError("INACTIVE_SIDE_READ"); });
            otherClient.onLoad(inactive + ".mixin");
            check(otherClient.getMixins().isEmpty()); check(otherClient.getRefMapperConfig() == null);
            immutable(server.getMixins()); immutable(client.getMixins());
        }
        var fabric = new Selection(Loader.FABRIC, MINECRAFT_VERSION);
        fails(Code.DISPATCH_UNBOUND, () -> new ServerMixinPlugin(fabric).getMixins());
        fails(Code.DISPATCH_UNBOUND, () -> new ClientMixinPlugin(fabric, () -> Side.CLIENT).getMixins());
        fails(Code.MIXIN_PACKAGE_INVALID, () -> new ServerMixinPlugin(fabric).onLoad("io.p2wlan.mc"));
        fails(Code.MIXIN_PACKAGE_INVALID, () -> new ServerMixinPlugin(fabric).onLoad("io.p2wlan.mc.game.mixin"));
        fails(Code.MIXIN_PACKAGE_INVALID, () -> new ClientMixinPlugin(fabric, () -> Side.CLIENT).onLoad("io.p2wlan.mc.game.server.mixin"));
        var server = new ServerMixinPlugin(fabric); server.onLoad("io.p2wlan.mc.fabric.game.server.mixin");
        fails(Code.DISPATCH_CHANGED, () -> server.onLoad("io.p2wlan.mc.game.server.mixin"));
        var client = new ClientMixinPlugin(fabric, () -> Side.CLIENT); client.onLoad("io.p2wlan.mc.fabric.game.mixin");
        fails(Code.DISPATCH_CHANGED, () -> client.onLoad("io.p2wlan.mc.game.mixin"));
        server.onLoad("io.p2wlan.mc.fabric.game.server.mixin."); check(server.getMixins().size() == 4);
        client.onLoad("io.p2wlan.mc.fabric.game.mixin."); check(client.getMixins().size() == 5);
    }
    private static void versionDispatches() {
        check(select(json("{\"id\":\"1.20.1\",\"name\":\"1.20.1\"}" )).minecraftVersion().equals("1.20.1"));
        for (String version : List.of("1.20.1", "1.21.1")) {
            for (Loader loader : Loader.values()) {
                var selected = select(version, Set.of(marker(loader))::contains);
                String base = loader == Loader.FABRIC ? "io.p2wlan.mc.fabric.game" : "io.p2wlan.mc.game";
                String other = loader == Loader.FABRIC ? "io.p2wlan.mc.game" : "io.p2wlan.mc.fabric.game";
                boolean legacy = version.equals("1.20.1");
                String active = base + (legacy ? ".v1_20_1" : "");
                String inactive = base + (legacy ? "" : ".v1_20_1");
                other += legacy ? ".v1_20_1" : "";
                var server = new ServerMixinPlugin(selected); server.onLoad(active + ".server.mixin");
                check(server.getMixins().equals(legacy ? List.of("ServerConnectionMixin", "ServerLoginConnectionMixin", "ServerLoginMixin")
                        : List.of("ServerConnectionMixin", "ServerLoginConnectionMixin", "ServerLoginMixin", "ServerConfigurationMixin")));
                check(java.util.Objects.equals(server.getRefMapperConfig(), loader == Loader.FABRIC
                        ? legacy ? LEGACY_SERVER_REFMAP : SERVER_REFMAP : legacy ? LEGACY_FML_SERVER_REFMAP : null));
                var client = new ClientMixinPlugin(selected, () -> Side.CLIENT); client.onLoad(active + ".mixin");
                check(client.getMixins().equals(loader == Loader.FABRIC && legacy ? FABRIC_LEGACY_CLIENT_NAMES : CLIENT_NAMES));
                check(java.util.Objects.equals(client.getRefMapperConfig(), loader == Loader.FABRIC
                        ? legacy ? LEGACY_CLIENT_REFMAP : CLIENT_REFMAP : legacy ? LEGACY_FML_CLIENT_REFMAP : null));
                var oldServer = new ServerMixinPlugin(selected); oldServer.onLoad(inactive + ".server.mixin");
                check(oldServer.getMixins().isEmpty()); check(oldServer.getRefMapperConfig() == null);
                var oldClient = new ClientMixinPlugin(selected, () -> { throw new AssertionError("INACTIVE_VERSION_SIDE"); });
                oldClient.onLoad(inactive + ".mixin");
                check(oldClient.getMixins().isEmpty()); check(oldClient.getRefMapperConfig() == null);
                var foreignServer = new ServerMixinPlugin(selected); foreignServer.onLoad(other + ".server.mixin");
                check(foreignServer.getMixins().isEmpty()); check(foreignServer.getRefMapperConfig() == null);
                var foreignClient = new ClientMixinPlugin(selected, () -> { throw new AssertionError("INACTIVE_LOADER_SIDE"); });
                foreignClient.onLoad(other + ".mixin");
                check(foreignClient.getMixins().isEmpty()); check(foreignClient.getRefMapperConfig() == null);
                var dedicated = new ClientMixinPlugin(selected, () -> Side.SERVER); dedicated.onLoad(active + ".mixin");
                check(dedicated.getMixins().isEmpty());
                var unknown = new ClientMixinPlugin(selected, () -> Side.UNKNOWN); unknown.onLoad(active + ".mixin");
                fails(Code.CLIENT_SIDE_UNAVAILABLE, unknown::getMixins);
                fails(Code.DISPATCH_CHANGED, () -> server.onLoad(inactive + ".server.mixin"));
                fails(Code.DISPATCH_CHANGED, () -> client.onLoad(inactive + ".mixin"));
                immutable(server.getMixins()); immutable(client.getMixins());
            }
        }
    }
    public static void main(String[] args) {
        selections(); dispatches(); versionDispatches();
        if (checks != 188) throw new AssertionError("BOOTSTRAP_CHECK_COUNT_" + checks);
        System.out.println("PASS universal bootstrap policy: " + checks + " checks");
    }
}
