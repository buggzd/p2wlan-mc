package io.p2wlan.mc.legacybootstrap.forge52harness;

import cpw.mods.jarhandling.SecureJar;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import net.minecraftforge.securemodules.SecureModuleClassLoader;
import net.minecraftforge.securemodules.SecureModuleFinder;

/** Public SDK resolver probe with the official PLUGIN, SERVICE GAME parents. */
final class Forge52GameProbe {
    private static int checks;
    private Forge52GameProbe() {}

    private static void check(boolean accepted) {
        checks++;
        if (!accepted) throw new IllegalStateException("FORGE52_GAME_CHECK_" + checks);
    }

    static void run(ModuleLayer service, ClassLoader serviceLoader, Path installed) throws Exception {
        Class<?> locatorType = Class.forName("net.minecraftforge.forgespi.locating.IModLocator", false, serviceLoader);
        Object locator = ServiceLoader.load(service, locatorType).stream()
                .filter(provider -> provider.type().getName().equals("io.p2wlan.mc.legacybootstrap.LegacyModLocator"))
                .findFirst().orElseThrow().get();
        locatorType.getMethod("initArguments", Map.class).invoke(locator, Map.of());
        var located = (List<?>) locatorType.getMethod("scanMods").invoke(locator);
        check(located.size() == 1);
        Object file = located.get(0).getClass().getMethod("file").invoke(located.get(0));
        check(file.getClass().getMethod("getFilePath").invoke(file).equals(installed));
        SecureJar jar = (SecureJar) file.getClass().getMethod("getSecureJar").invoke(file);
        var finder = SecureModuleFinder.of(jar);
        var descriptor = finder.find(jar.name()).orElseThrow().descriptor();
        check(descriptor.isAutomatic() && descriptor.name().equals("p2wlan"));
        check(descriptor.packages().contains("io.p2wlan.mc.legacybootstrap"));
        Class<?> layerType = Class.forName("cpw.mods.modlauncher.api.IModuleLayerManager$Layer", false, serviceLoader);
        Object gameKind = layerType.getField("GAME").get(null);
        var parentKinds = (List<?>) layerType.getMethod("getParents").invoke(gameKind);
        check(parentKinds.stream().map(Object::toString).toList().equals(List.of("PLUGIN", "SERVICE")));

        // These are the public resolver and default concrete loader used by
        // ModuleLayerHandler. No private handler state or entry point is read.
        var pluginConfiguration = service.configuration().resolveAndBind(ModuleFinder.of(), ModuleFinder.of(), List.of());
        var pluginLoader = new SecureModuleClassLoader("P2WLAN-FORGE52-PLUGIN", null,
                pluginConfiguration, List.of(service), List.of(serviceLoader));
        ModuleLayer plugin = service.defineModules(pluginConfiguration, ignored -> pluginLoader);
        List<ModuleLayer> parents = List.of(plugin, service);
        Configuration gameConfiguration = Configuration.resolveAndBind(finder,
                parents.stream().map(ModuleLayer::configuration).toList(), ModuleFinder.of(), List.of(jar.name()));
        var gameLoader = new SecureModuleClassLoader("P2WLAN-FORGE52-GAME", null, gameConfiguration,
                parents, List.of(pluginLoader, serviceLoader));
        ModuleLayer game = ModuleLayer.defineModules(gameConfiguration, parents, ignored -> gameLoader).layer();
        check(game.parents().equals(parents));
        check(game.modules().size() == 1 && game.findModule(jar.name()).orElseThrow().getLayer() == game);
        for (String name : List.of("io.p2wlan.mc.legacybootstrap.LegacyModLocator",
                "io.p2wlan.mc.bootstrap.BootstrapFailure")) {
            Class<?> gameClass = Class.forName(name, false, gameLoader);
            Class<?> serviceClass = Class.forName(name, false, serviceLoader);
            check(gameClass != serviceClass && gameClass.getClassLoader() == gameLoader);
            check(gameClass.getModule().getLayer() == game && gameClass.getModule().getName().equals(jar.name()));
            check(serviceClass.getClassLoader() == serviceLoader && serviceClass.getModule().getLayer() == service);
        }
        System.out.println("{\"schema\":1,\"checks\":" + checks
                + ",\"verification\":\"official-SDK/public-GAME-resolver\",\"parents\":[\"PLUGIN\",\"SERVICE\"]}");
    }
}
