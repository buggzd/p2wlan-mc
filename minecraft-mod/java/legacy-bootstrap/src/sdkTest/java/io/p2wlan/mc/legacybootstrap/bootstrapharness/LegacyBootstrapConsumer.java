package io.p2wlan.mc.legacybootstrap.bootstrapharness;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** SDK-only consumer entered by the unmodified official BootstrapLauncher. */
public final class LegacyBootstrapConsumer implements Consumer<String[]> {
    private record Layer(ModuleLayer layer, ClassLoader loader) {}
    private int checks;

    @Override public void accept(String[] args) {
        try { exercise(args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("SDK_BOOTSTRAP_PROBE", cause);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("SDK_BOOTSTRAP_PROBE", failure);
        }
    }

    private void check(boolean accepted, String reason) {
        checks++;
        if (!accepted) throw new AssertionError(reason);
    }

    private void exercise(String[] args) throws ReflectiveOperationException, java.io.IOException {
        if (args.length < 6 || args.length > 8 || args.length == 8 && !args[7].equals("official")) {
            throw new IllegalArgumentException("SDK_BOOTSTRAP_ARGUMENTS");
        }
        Path classpath = local(args[0]);
        Path probe = local(args[1]);
        Path outer = local(args[2]);
        if (Files.size(classpath) > 128 * 1024) throw new IllegalArgumentException("SDK_BOOTSTRAP_CAPACITY");
        check(getClass().getModule().isNamed(), "HARNESS_NOT_NAMED");
        check(getClass().getClassLoader().getClass().getName().equals("cpw.mods.cl.ModuleClassLoader"), "HARNESS_NOT_BOOTSTRAP_LOADER");
        Class<?> secure = Class.forName("cpw.mods.jarhandling.SecureJar");
        check(secure.getModule().getLayer() == ModuleLayer.boot(), "SECURE_NOT_INITIAL_BOOT_MODULE");
        check(secure.getProtectionDomain().getCodeSource().getLocation().getProtocol().equals("file"), "SECURE_BOOT_SOURCE_NOT_FILE");
        List<Path> libraries = new ArrayList<>();
        for (String value : Files.readString(classpath).strip().split(java.io.File.pathSeparator)) {
            Path path = local(value);
            if (!path.equals(probe) && !path.equals(outer) && !libraries.contains(path)) libraries.add(path);
        }
        if (libraries.isEmpty() || libraries.size() > 256) throw new IllegalArgumentException("SDK_BOOTSTRAP_CAPACITY");
        Layer sdk = layer("SDK-BOOTSTRAP", libraries, ModuleLayer.boot(), true);
        for (String anchor : List.of("net.minecraftforge.fml.loading.FMLLoader", "cpw.mods.modlauncher.api.IEnvironment",
                                    "net.minecraftforge.forgespi.locating.IModLocator")) {
            Class<?> type = Class.forName(anchor, false, sdk.loader());
            check(type.getModule().isNamed() && type.getModule().getLayer() == sdk.layer(), "SDK_ANCHOR_LAYER");
            check(type.getClassLoader() == sdk.loader(), "SDK_ANCHOR_LOADER");
            check(type.getProtectionDomain().getCodeSource().getLocation().getProtocol().equals("union"), "SDK_ANCHOR_NOT_UNION");
        }
        Layer service = layer("SDK-SERVICE", List.of(outer, probe), sdk.layer(), false);
        Class<?> carrier = Class.forName("io.p2wlan.mc.legacybootstrap.LegacyModLocator", false, service.loader());
        Class<?> test = Class.forName("io.p2wlan.mc.legacybootstrap.tests.LegacySdkProbe", false, service.loader());
        for (Class<?> type : List.of(carrier, test)) {
            check(type.getModule().isNamed() && type.getModule().getLayer() == service.layer(), "SERVICE_ANCHOR_LAYER");
            check(type.getClassLoader() == service.loader(), "SERVICE_ANCHOR_LOADER");
            check(type.getProtectionDomain().getCodeSource().getLocation().getProtocol().equals("union"), "SERVICE_ANCHOR_NOT_UNION");
        }
        System.out.println("SDK_BOOTSTRAP_LAYERS checks=" + checks + " SDK=named SERVICE=named loader=ModuleClassLoader origins=union");
        Thread.currentThread().setContextClassLoader(service.loader());
        test.getMethod("main", String[].class).invoke(null, (Object)new String[]{outer.toString(), args[3], args[4], args[5], "modules"});
        if (args.length > 6) LegacyGameLayerProbe.run(sdk.layer(), sdk.loader(), service.layer(), service.loader(),
                outer, local(args[6]), args[3], args[4], args[5], args.length == 8 && args[7].equals("official"));
    }

    private Layer layer(String name, List<Path> paths, ModuleLayer parent, boolean omitInitialModules)
            throws ReflectiveOperationException {
        Class<?> secure = Class.forName("cpw.mods.jarhandling.SecureJar");
        Class<?> finderType = Class.forName("cpw.mods.cl.JarModuleFinder");
        Class<?> loaderType = Class.forName("cpw.mods.cl.ModuleClassLoader");
        Set<String> initial = new HashSet<>();
        for (Module module : ModuleLayer.boot().modules()) initial.add(module.getName());
        List<Object> jars = new ArrayList<>();
        for (Path path : paths) {
            Object jar = secure.getMethod("from", Path[].class).invoke(null, (Object)new Path[]{path});
            if (omitInitialModules) {
                Object one = Array.newInstance(secure, 1); Array.set(one, 0, jar);
                ModuleFinder candidate = (ModuleFinder)finderType.getMethod("of", one.getClass()).invoke(null, one);
                String moduleName = candidate.findAll().iterator().next().descriptor().name();
                if (initial.contains(moduleName)) continue;
            }
            jars.add(jar);
        }
        Object array = Array.newInstance(secure, jars.size());
        for (int index = 0; index < jars.size(); index++) Array.set(array, index, jars.get(index));
        ModuleFinder finder = (ModuleFinder)finderType.getMethod("of", array.getClass()).invoke(null, array);
        Set<String> roots = finder.findAll().stream().map(ModuleReference::descriptor)
                .map(java.lang.module.ModuleDescriptor::name).collect(java.util.stream.Collectors.toSet());
        if (roots.size() != jars.size()) throw new IllegalArgumentException("SDK_BOOTSTRAP_MODULE_DUPLICATE");
        Configuration configuration = parent.configuration().resolveAndBind(finder, ModuleFinder.of(), roots);
        ClassLoader loader = (ClassLoader)loaderType.getConstructor(String.class, Configuration.class, List.class)
                .newInstance(name, configuration, List.of(parent));
        ModuleLayer layer = ModuleLayer.defineModules(configuration, List.of(parent), ignored -> loader).layer();
        return new Layer(layer, loader);
    }

    private static Path local(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("SDK_BOOTSTRAP_PATH");
        }
        return path;
    }
}
