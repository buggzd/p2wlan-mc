package io.p2wlan.mc.legacybootstrap.bootstrapharness;

import java.lang.module.ModuleDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** SDK-only GAME construction using the unmodified official layer handler. */
public final class LegacyGameLayerProbe {
    private LegacyGameLayerProbe() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void run(ModuleLayer sdk, ClassLoader sdkLoader, ModuleLayer service, ClassLoader serviceLoader,
                    Path installed, Path fixture, String minecraft, String forge, String group, boolean official)
            throws ReflectiveOperationException {
        Class<?> secure = Class.forName("cpw.mods.jarhandling.SecureJar", false, sdkLoader);
        Class<?> locatorType = Class.forName("net.minecraftforge.forgespi.locating.IModLocator", false, sdkLoader);
        Class<?> fileType = Class.forName("net.minecraftforge.forgespi.locating.IModFile", false, sdkLoader);
        Object locator = ServiceLoader.load(service, locatorType).stream().map(provider -> ((ServiceLoader.Provider)provider).get())
                .filter(value -> value.getClass().getName().equals("io.p2wlan.mc.legacybootstrap.LegacyModLocator"))
                .findFirst().orElseThrow();
        locatorType.getMethod("initArguments", Map.class).invoke(locator, group.equals("net.neoforged") ?
                Map.of("mcVersion", minecraft, "forgeVersion", forge, "fmlVersion", "47.2.2") :
                Map.of("mcVersion", minecraft, "forgeVersion", forge, "forgeGroup", group));
        Object discovery = ((List<?>)locatorType.getMethod("scanMods").invoke(locator)).get(0);
        Object located = fileType.isInstance(discovery) ? discovery : discovery.getClass().getMethod("file").invoke(discovery);
        Object gameJar = official ? officialFile(sdkLoader, installed) : located;
        Object fixtureFile = officialFile(sdkLoader, fixture);
        gameJar = fileType.getMethod("getSecureJar").invoke(gameJar);
        Object fixtureJar = fileType.getMethod("getSecureJar").invoke(fixtureFile);
        String module = (String)secure.getMethod("name").invoke(gameJar);
        Object jarArray = java.lang.reflect.Array.newInstance(secure, 1);
        java.lang.reflect.Array.set(jarArray, 0, gameJar);
        var finder = (java.lang.module.ModuleFinder)Class.forName("cpw.mods.cl.JarModuleFinder", false, sdkLoader)
                .getMethod("of", jarArray.getClass()).invoke(null, jarArray);
        ModuleDescriptor descriptor = finder.find(module).orElseThrow().descriptor();
        Module serviceModule = locator.getClass().getModule();
        System.out.println("SDK_GAME_INPUT name=" + module + " automatic=" + descriptor.isAutomatic()
                + " packages=" + descriptor.packages().size() + " metadata=" + (official ? "official-FML" : "production-carrier"));
        System.out.println("SDK_SERVICE_INPUT name=" + serviceModule.getName() + " automatic="
                + serviceModule.getDescriptor().isAutomatic() + " packages=" + serviceModule.getDescriptor().packages().size()
                + " exports=" + serviceModule.getDescriptor().exports().size());

        Class<?> handlerType = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler", false, sdkLoader);
        Class<?> layerType = Class.forName("cpw.mods.modlauncher.api.IModuleLayerManager$Layer", false, sdkLoader);
        Class<?> infoType = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler$LayerInfo", false, sdkLoader);
        Class<?> loaderType = Class.forName("cpw.mods.cl.ModuleClassLoader", false, sdkLoader);
        var constructor = handlerType.getDeclaredConstructor(); constructor.setAccessible(true);
        Object handler = constructor.newInstance();
        var completed = handlerType.getDeclaredField("completedLayers"); completed.setAccessible(true);
        Map layers = (Map)completed.get(handler);
        Object boot = layerType.getField("BOOT").get(null), plugin = layerType.getField("PLUGIN").get(null);
        Object serviceKey = layerType.getField("SERVICE").get(null), game = layerType.getField("GAME").get(null);
        // Seed only SDK test setup. The official GAME parent enum and resolver
        // remain unchanged: PLUGIN and SERVICE are both direct parents.
        var infoConstructor = infoType.getDeclaredConstructor(ModuleLayer.class, loaderType);
        infoConstructor.setAccessible(true);
        layers.put(boot, infoConstructor.newInstance(sdk, sdkLoader));
        layers.put(serviceKey, infoConstructor.newInstance(service, serviceLoader));
        handlerType.getMethod("buildLayer", layerType).invoke(handler, plugin);
        Method add = handlerType.getDeclaredMethod("addToLayer", layerType, secure); add.setAccessible(true);
        add.invoke(handler, game, gameJar); add.invoke(handler, game, fixtureJar);
        Object result;
        try { result = handlerType.getMethod("buildLayer", layerType).invoke(handler, game); }
        catch (InvocationTargetException failure) {
            System.out.println("SDK_GAME_CONFIGURATION_FAILURE " + failure.getCause());
            throw failure;
        }
        Method layerAccessor = infoType.getDeclaredMethod("layer"); layerAccessor.setAccessible(true);
        ModuleLayer built = (ModuleLayer)layerAccessor.invoke(result);
        if (built.findModule(module).orElseThrow().getLayer() != built || built.parents().size() != 2) {
            throw new AssertionError("SDK_GAME_LAYER_IDENTITY");
        }
        Method loaderAccessor = infoType.getDeclaredMethod("cl"); loaderAccessor.setAccessible(true);
        ClassLoader gameLoader = (ClassLoader)loaderAccessor.invoke(result);
        String shared = "io.p2wlan.mc.bootstrap.BootstrapFailure";
        Class<?> gameClass = Class.forName(shared, false, gameLoader);
        Class<?> serviceClass = Class.forName(shared, false, serviceLoader);
        if (gameClass == serviceClass || gameClass.getClassLoader() != gameLoader || gameClass.getModule().getLayer() != built
                || !gameClass.getModule().getName().equals(module) || serviceClass.getClassLoader() != serviceLoader
                || serviceClass.getModule() != serviceModule) throw new AssertionError("SDK_GAME_PACKAGE_MAPPING");
        System.out.println("SDK_GAME_CONFIGURATION_PASS modules=" + built.modules().size() + " parents=PLUGIN,SERVICE");
        System.out.println("SDK_GAME_PACKAGE_MAPPING_PASS owner=" + gameClass.getModule().getName()
                + " service=" + serviceClass.getModule().getName() + " distinct=true");
    }

    private static Object officialFile(ClassLoader loader, Path path) throws ReflectiveOperationException {
        // Exact protected official factory, accessed only in this SDK test.
        // It selects ModJarMetadata when mods.toml exists, even if a root
        // module-info.class is present. Production cannot use this reflection.
        Class<?> base;
        try { base = Class.forName("net.minecraftforge.fml.loading.moddiscovery.AbstractModLocator", false, loader); }
        catch (ClassNotFoundException modern) {
            base = Class.forName("net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider", false, loader);
        }
        Object locator = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModsFolderLocator", false, loader)
                .getConstructor().newInstance();
        Method create = base.getDeclaredMethod("createMod", Path[].class); create.setAccessible(true);
        Object value = create.invoke(locator, (Object)new Path[]{path});
        return value instanceof java.util.Optional<?> optional ? optional.orElseThrow() :
                value.getClass().getMethod("file").invoke(value);
    }
}
