package io.p2wlan.mc.legacybootstrap.forge52harness;

import cpw.mods.jarhandling.SecureJar;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraftforge.bootstrap.api.BootstrapEntryPoint;
import net.minecraftforge.securemodules.SecureModuleClassLoader;
import net.minecraftforge.securemodules.SecureModuleFinder;

/** Test-only public SPI terminal. ForgeBootstrap supplies the SERVICE layer;
 * the verified SDK lives in a separate named layer whose game entry is never run. */
public final class Forge52BootstrapEntry implements BootstrapEntryPoint {
    private static final String SECURE = "d8402fa81935b1a6440240f98079b14d0462ccac";
    private static final String LOADER = "net.minecraftforge.securemodules.SecureModuleClassLoader";
    private static final String PROBE = "io.p2wlan.mc.legacybootstrap.tests.LegacySdkProbe";
    private static int checks;

    @Override public String name() { return "P2WLAN_FORGE52_SDK_ONLY"; }

    @Override public void main(String... arguments) {
        try { run(arguments); }
        catch (Exception failure) { throw new IllegalStateException("FORGE52_BOOTSTRAP_PROBE_FAILED", failure); }
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new IllegalStateException("FORGE52_BOOTSTRAP_CHECK_" + checks);
    }

    private static void run(String[] arguments) throws Exception {
        // The dependencies file is generated from the pinned official userdev
        // config. It contains one absolute downloaded JAR path per line.
        check(arguments.length == 3);
        Path dependencies = regular(arguments[0], 64 * 1024, false);
        Path testJar = regular(arguments[1], 1024 * 1024, true);
        Path installed = regular(arguments[2], 128 * 1024 * 1024, true);
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        check(installed.getParent().getFileName().toString().equals("mods")
                && installed.getParent().getParent().equals(temporary.getParent()));
        Module service = Forge52BootstrapEntry.class.getModule();
        ModuleLayer serviceLayer = service.getLayer();
        check(service.isNamed() && serviceLayer != null);
        check(Forge52BootstrapEntry.class.getClassLoader().getClass().getName().equals(LOADER));
        check(serviceLayer.findModule("cpw.mods.securejarhandler").isPresent());
        var providers = FileSystemProvider.installedProviders().stream()
                .filter(provider -> provider.getScheme().equals("union")).toList();
        check(providers.size() == 1);
        var provider = providers.get(0);
        check(provider.getClass().getName().equals("cpw.mods.niofs.union.UnionFileSystemProvider"));
        // The installed NIO provider retains the local artifact owner while
        // ForgeBootstrap's named modules use a jar CodeSource for that owner.
        Path providerOwner = fileOwner(provider.getClass(), SECURE, 101247);
        check(SecureJar.class.getModule().getName().equals("cpw.mods.securejarhandler"));
        jarOwner(SecureJar.class, providerOwner);
        Class<?> initialLoaderType = Class.forName(LOADER, false, provider.getClass().getClassLoader());
        check(initialLoaderType != SecureModuleClassLoader.class && !initialLoaderType.getModule().isNamed());
        fileOwner(initialLoaderType, SECURE, 101247);
        jarOwner(SecureModuleClassLoader.class, providerOwner);

        var paths = new ArrayList<Path>();
        for (String line : Files.readAllLines(dependencies, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            check(paths.size() < 128);
            Path path = regular(line, 128 * 1024 * 1024, true);
            check(!paths.contains(path)); paths.add(path);
        }
        check(!paths.isEmpty());
        paths.add(testJar); paths.add(installed);
        Map<String, SecureJar> selected = new LinkedHashMap<>();
        Map<String, Path> owners = new LinkedHashMap<>();
        for (Path path : paths) {
            SecureJar jar = SecureJar.from(path);
            String module = jar.name();
            // Reuse the real bootstrap modules rather than defining a second
            // SecureModules/ASM/API copy with the same package names.
            if (serviceLayer.configuration().findModule(module).isPresent()) continue;
            check(selected.putIfAbsent(module, jar) == null);
            owners.put(module, path);
        }
        check(selected.size() >= 4 && selected.size() <= 130);
        var finder = SecureModuleFinder.of(selected.values());
        var configuration = serviceLayer.configuration().resolveAndBind(
                finder, ModuleFinder.ofSystem(), selected.keySet());
        var sdk = new SecureModuleClassLoader("P2WLAN-FORGE52-SDK", null, configuration,
                List.of(ModuleLayer.boot(), serviceLayer), List.of());
        ModuleLayer layer = serviceLayer.defineModules(configuration, ignored -> sdk);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(sdk);
        try {
            for (String name : List.of("net.minecraftforge.fml.loading.FMLLoader",
                    "cpw.mods.modlauncher.api.IEnvironment", "net.minecraftforge.forgespi.locating.IModLocator",
                    "io.p2wlan.mc.legacybootstrap.LegacyModLocator", PROBE)) {
                Class<?> type = Class.forName(name, false, sdk);
                check(type.getClassLoader() == sdk && type.getModule().isNamed() && type.getModule().getLayer() == layer);
                Path owner = owners.get(type.getModule().getName());
                check(owner != null);
                jarOwner(type, owner);
            }
            Class<?> probe = Class.forName(PROBE, true, sdk);
            try {
                probe.getMethod("main", String[].class).invoke(null,
                        (Object) new String[] {installed.toString(), "1.21.1", "52.1.16", "net.minecraftforge", "modules"});
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("FORGE52_SDK_ASSERTIONS_FAILED", failure.getCause());
            }
            Forge52GameProbe.run(layer, sdk, installed);
        } finally { Thread.currentThread().setContextClassLoader(original); }
        System.out.println("{\"schema\":1,\"checks\":" + checks
                + ",\"verification\":\"ForgeBootstrap/SERVICE+SDK-named-layers\",\"provider\":\"file\",\"sdk\":\"jar\"}");
    }

    private static Path regular(String value, long maximum, boolean jar) throws Exception {
        Path path = Path.of(value);
        check(path.isAbsolute() && !Files.isSymbolicLink(path) && Files.isRegularFile(path)
                && Files.size(path) > 0 && Files.size(path) <= maximum);
        path = path.normalize();
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) check(!Files.isSymbolicLink(parent));
        if (jar) check(path.getFileName().toString().endsWith(".jar"));
        return path;
    }

    private static Path fileOwner(Class<?> type, String expected, long size) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        check(source != null && source.getLocation().getProtocol().equals("file"));
        var uri = source.getLocation().toURI();
        check(uri.getAuthority() == null && uri.getQuery() == null && uri.getFragment() == null);
        Path path = regular(Path.of(uri).toString(), 512 * 1024, true);
        byte[] value = Files.readAllBytes(path);
        check(value.length == size && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value)).equals(expected));
        return path;
    }

    private static void jarOwner(Class<?> type, Path owner) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        check(type.getModule().isNamed());
        check(source != null && source.getLocation().toURI().equals(URI.create("jar:" + owner.toUri() + "!/")));
        String resource = type.getName().replace('.', '/') + ".class";
        try (ZipFile archive = new ZipFile(owner.toFile());
                var expected = archive.getInputStream(archive.getEntry(resource));
                var actual = type.getResourceAsStream("/" + resource)) {
            check(actual != null && Arrays.equals(expected.readAllBytes(), actual.readAllBytes()));
        }
    }
}
