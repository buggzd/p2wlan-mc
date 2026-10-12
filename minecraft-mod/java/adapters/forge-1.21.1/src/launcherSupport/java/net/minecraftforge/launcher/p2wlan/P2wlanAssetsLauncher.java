package net.minecraftforge.launcher.p2wlan;

import net.minecraftforge.launcher.Main;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

/** Build-only argument adapter. ForgeGradle 7.0.29 recognizes this package
 * prefix when constructing Slime's own arguments. Never included in a Mod JAR. */
public final class P2wlanAssetsLauncher {
    private P2wlanAssetsLauncher() {}
    public static String[] withAssets(String[] arguments, String directory) {
        if (directory == null || !Path.of(directory).isAbsolute()) throw new IllegalArgumentException("FORGE_ASSET_DIRECTORY");
        // Before Slime's first '--', not a Minecraft --assetsDir argument.
        var selected = new ArrayList<String>(arguments.length + 2);
        selected.add("--assets"); selected.add(directory); selected.addAll(Arrays.asList(arguments));
        return selected.toArray(String[]::new);
    }
    public static void main(String[] arguments) throws Throwable {
        Main.main(withAssets(arguments, System.getProperty("p2wlan.forge.assetRoot")));
    }
}
