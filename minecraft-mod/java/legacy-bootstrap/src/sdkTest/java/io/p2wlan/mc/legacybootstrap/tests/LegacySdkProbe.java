package io.p2wlan.mc.legacybootstrap.tests;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.TransformStore;
import cpw.mods.modlauncher.TransformingClassLoader;
import cpw.mods.modlauncher.api.IEnvironment;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

/** Executed only with installed JARs and official SDKs, never production outputs. */
public final class LegacySdkProbe {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        Path installed = Path.of(args[0]).toAbsolutePath();
        boolean legacy = args[1].equals("1.16.5");
        boolean detected = args[1].equals("1.21.1");
        boolean neo47 = args[3].equals("net.neoforged");
        boolean modular = args.length > 4 && args[4].equals("modules");
        if (modular) {
            check(LegacySdkProbe.class.getModule().isNamed());
            check(LegacySdkProbe.class.getModule().getLayer() != ModuleLayer.boot());
            check(Thread.currentThread().getContextClassLoader() == LegacySdkProbe.class.getClassLoader());
            for (Class<?> anchor : new Class<?>[]{IEnvironment.class,IModLocator.class,
                    Class.forName("net.minecraftforge.fml.loading.FMLLoader",false,LegacySdkProbe.class.getClassLoader())}) {
                check(anchor.getModule().isNamed() && anchor.getModule().getLayer() != ModuleLayer.boot());
                check(anchor.getProtectionDomain().getCodeSource().getLocation().getProtocol().equals(detected ? "jar" : "union"));
            }
        }
        if (!legacy && !detected) versionFixture(args);
        var discoverer = Class.forName("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer").getConstructor().newInstance();
        discoverer.getClass().getMethod("candidates",Path.class).invoke(discoverer,installed.getParent().getParent());
        var excluded = (List<?>) discoverer.getClass().getMethod("allExcluded").invoke(null);
        check(excluded.equals(List.of(installed)));
        var service = modular ? ServiceLoader.load(LegacySdkProbe.class.getModule().getLayer(),IModLocator.class) : ServiceLoader.load(IModLocator.class);
        var providers = service.stream().filter(provider ->
                provider.type().getName().equals("io.p2wlan.mc.legacybootstrap.LegacyModLocator")).toList();
        check(providers.size() == 1);
        if (modular) {
            Class<?> carrier = providers.get(0).type();
            check(carrier.getModule().isNamed() && carrier.getModule().getLayer() == LegacySdkProbe.class.getModule().getLayer());
            check(carrier.getClassLoader() == LegacySdkProbe.class.getClassLoader());
            check(carrier.getProtectionDomain().getCodeSource().getLocation().getProtocol().equals(detected ? "jar" : "union"));
        }
        Map<String,?> arguments = detected ? forge52Arguments() : neo47 ?
                Map.of("mcVersion",args[1],"forgeVersion",args[2],"fmlVersion","47.2.2") :
                Map.of("mcVersion",args[1],"forgeVersion",args[2],"forgeGroup",args[3]);
        if (neo47) {
            check(!arguments.containsKey("forgeGroup"));
            try { providers.get(0).get().initArguments(Map.of("mcVersion",args[1],"forgeVersion",args[2],"fmlVersion","47.2.1")); throw new AssertionError("conflicting FML version accepted"); }
            catch (RuntimeException expected) { check(expected.getMessage().equals("P2WLAN_LEGACY_RUNTIME_PROFILE")); }
        }
        if (detected) {
            try { providers.get(0).get().initArguments(Map.of("forgeVersion","52.1.15")); throw new AssertionError("conflicting version argument accepted"); }
            catch (RuntimeException expected) { check(expected.getMessage().equals("P2WLAN_LEGACY_RUNTIME_PROFILE")); }
        }
        IModLocator locator = providers.get(0).get();
        locator.initArguments(arguments);
        List<?> located = locator.scanMods();
        check(located.size() == (legacy ? 5 : 1));
        List<IModFile> files = new ArrayList<>();
        for (Object value : located) files.add(value instanceof IModFile file ? file :
                (IModFile)value.getClass().getMethod("file").invoke(value));
        check(files.get(0).getFilePath().equals(installed));
        check(files.get(0).getType() == IModFile.Type.MOD);
        IModLocator fileProvider = null;
        if (!legacy) {
            fileProvider = (IModLocator)files.get(0).getClass().getMethod(args[1].equals("1.18.2") ? "getLocator" : "getProvider")
                    .invoke(files.get(0));
            check(fileProvider.getClass().getName().equals("io.p2wlan.mc.legacybootstrap." +
                    (args[1].equals("1.18.2") ? "LegacyForge40Factory" : detected ? "LegacyForge52Factory" : "LegacyForge47Factory")));
            check(fileProvider.name().equals(locator.name()));
            check(fileProvider.isValid(files.get(0)));
            Path metadata = (Path)files.get(0).getClass().getMethod("findResource",String[].class)
                    .invoke(files.get(0),(Object)new String[]{"META-INF","mods.toml"});
            check(Files.isRegularFile(metadata));
        }
        for (IModFile file : files) check(locator.isValid(file));
        List<Path> classes = new ArrayList<>();
        locator.scanFile(files.get(0),classes::add);
        check(classes.size() >= 100 && classes.size() <= 4096);
        if (fileProvider != null) {
            List<Path> delegated = new ArrayList<>(); fileProvider.scanFile(files.get(0),delegated::add);
            check(delegated.equals(classes));
        }
        if (legacy) legacy(installed,files,locator);
        else {
            try (var temporary = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) { check(temporary.findAny().isEmpty()); }
        }
        try { locator.scanMods(); throw new AssertionError("duplicate scan accepted"); }
        catch (RuntimeException expected) { check(expected.getMessage().equals("P2WLAN_LEGACY_RUNTIME_PROFILE")); }
        byte[] original = Files.readAllBytes(installed);
        var modified = Files.getLastModifiedTime(installed);
        Files.write(installed,new byte[]{1},StandardOpenOption.APPEND);
        if (legacy) {
            check(!Files.exists(locator.findPath(files.get(0),"net/minecraft/Unowned.class")));
            try { locator.findPath(files.get(0),"META-INF/mods.toml"); throw new AssertionError("owned path source mutation accepted"); }
            catch (RuntimeException expected) { check(expected.getMessage().equals("P2WLAN_LEGACY_OWNER_CHANGED")); }
        }
        try { locator.isValid(files.get(0)); throw new AssertionError("source mutation accepted"); }
        catch (RuntimeException expected) { check(expected.getMessage().equals("P2WLAN_LEGACY_OWNER_CHANGED")); }
        finally { Files.write(installed,original); Files.setLastModifiedTime(installed,modified); }
        if (fileProvider != null) {
            Files.write(installed,new byte[]{1},StandardOpenOption.APPEND);
            try { fileProvider.isValid(files.get(0)); throw new AssertionError("provider source mutation accepted"); }
            catch (RuntimeException expected) { check(expected.getMessage().equals("P2WLAN_LEGACY_OWNER_CHANGED")); }
            finally { Files.write(installed,original); Files.setLastModifiedTime(installed,modified); }
        }
        System.out.println("{\"schema\":1,\"checks\":" + checks + ",\"minecraft\":\"" + args[1]
                + "\",\"forge\":\"" + args[2] + "\",\"libraries\":" + (files.size()-1)
                + ",\"verification\":\"official-SDK/discovery-factory-class-resource\"}");
    }
    private static Map<String,?> forge52Arguments() throws Exception {
        Class<?> service = Class.forName("net.minecraftforge.fml.loading.FMLServiceProvider");
        // Read-only SDK evidence: this exact service has no version arguments.
        var field = service.getDeclaredField("arguments"); field.setAccessible(true);
        var arguments = (Map<String,?>)field.get(service.getConstructor().newInstance());
        check(arguments.isEmpty());
        check(service.getMethods().length > 0 && java.util.Arrays.stream(service.getDeclaredMethods()).noneMatch(method ->
                method.getName().equals("arguments") || method.getName().equals("argumentValues")));
        return Map.copyOf(arguments);
    }
    private static void versionFixture(String[] args) throws Exception {
        // Factory API test only: formal FML bootstrap owns this field in games.
        // Supply its exact SDK VersionInfo record, without loading game classes.
        Class<?> fml = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
        Class<?> info = Class.forName("net.minecraftforge.fml.loading.VersionInfo");
        String mcp = args[1].equals("1.18.2") ? "20220404.173914" : args[1].equals("1.20.1") ? "20230612.114412" : "20240808.144430";
        String fmlVersion = args[1].equals("1.20.1") ? args[3].equals("net.neoforged") ? "47.2.2" : "47.3.2" : args[2];
        var fields = Map.of("mcVersion",args[1],"forgeVersion",args[2],"forgeGroup",args[3],"mcpVersion",mcp,"fmlVersion",fmlVersion);
        var components = info.getRecordComponents();
        check(components.length == 4);
        Object[] values = java.util.Arrays.stream(components).map(component -> fields.get(component.getName())).toArray();
        check(java.util.Arrays.stream(values).allMatch(java.util.Objects::nonNull));
        Object value = info.getConstructor(String.class,String.class,String.class,String.class).newInstance(values);
        var field = fml.getDeclaredField("versionInfo");
        if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) value = fml.getMethod("versionInfo").invoke(null);
        else { field.setAccessible(true); field.set(null,value); }
        check(info.getMethod("mcVersion").invoke(value).equals(args[1]));
    }
    private static void legacy(Path installed, List<IModFile> files, IModLocator locator) throws Exception {
        List<Path> children = new ArrayList<>();
        for (int index = 1; index < files.size(); index++) {
            IModFile file = files.get(index); children.add(file.getFilePath());
            check(file.getType() == IModFile.Type.LIBRARY);
            check(file.getFilePath().getParent().equals(files.get(1).getFilePath().getParent()));
            check(locator.findManifest(file.getFilePath()).orElseThrow().getMainAttributes().getValue("FMLModType").equals("LIBRARY"));
        }
        TransformingClassLoader game = new TransformingClassLoader(new TransformStore(),new LaunchPluginHandler(),children.toArray(Path[]::new));
        Thread.currentThread().setContextClassLoader(game);
        Class<?> common = Class.forName("io.p2wlan.mc.common.WorldSessions",false,game);
        Class<?> transport = Class.forName("io.p2wlan.mc.transport.ProxyPrelude",false,game);
        Class<?> jna = Class.forName("com.sun.jna.Native",false,game);
        check(common.getClassLoader() == game); check(transport.getClassLoader() == game); check(jna.getClassLoader() == game);
        URL manifest = sole(game,"p2wlan/native/macos-aarch64/manifest.properties");
        URL dispatch = sole(game,"p2wlan/jna/darwin-aarch64/libjnidispatch.jnilib");
        var verifier = Class.forName("io.p2wlan.mc.legacybootstrap.LegacyResourceVerifier");
        var verify = verifier.getMethod("verify",Path.class,Class.class,Class.class,Class.class,URL.class,URL.class);
        Object provenance = verify.invoke(null,installed,common,transport,jna,manifest,dispatch);
        check(((Map<?,?>)provenance.getClass().getMethod("childSha256").invoke(provenance)).size() == 4);
        URL windows = sole(game,"p2wlan/jna/win32-x86-64/jnidispatch.dll");
        verify.invoke(null,installed,common,transport,jna,manifest,windows);
        check(jna.getResource("/p2wlan/jna/win32-x86-64/jnidispatch.dll").equals(windows));
        var javaVersion = jna.getField("VERSION");
        // Read-only test evidence: Version is an upstream private interface.
        javaVersion.setAccessible(true); check(javaVersion.get(null).equals("5.14.0"));
        check(jna.getField("POINTER_SIZE").getInt(null) == 8);
        Object approved = verifier.getMethod("resources",ClassLoader.class).invoke(null,game);
        verify.invoke(null,installed,common,transport,jna,(URL)approved.getClass().getMethod("nativeManifest").invoke(approved),
                (URL)approved.getClass().getMethod("jnaNative").invoke(approved));
        URL spoof = new URL(null,manifest.toString(),new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws java.io.IOException {
                return new JarURLConnection(url) {
                    @Override public void connect() {}
                    @Override public java.util.jar.JarFile getJarFile() throws java.io.IOException { return new java.util.jar.JarFile(Path.of(getJarFileURL().getPath()).toFile()); }
                    @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[]{1}); }
                };
            }
        });
        try { verify.invoke(null,installed,common,transport,jna,spoof,dispatch); throw new AssertionError("resource stream spoof accepted"); }
        catch (java.lang.reflect.InvocationTargetException failure) { check(failure.getCause().getMessage().equals("P2WLAN_LEGACY_PROVENANCE")); }
    }
    private static URL sole(ClassLoader loader, String resource) throws Exception {
        var resources = java.util.Collections.list(loader.getResources(resource)); check(resources.size() == 1); return resources.get(0);
    }
}
