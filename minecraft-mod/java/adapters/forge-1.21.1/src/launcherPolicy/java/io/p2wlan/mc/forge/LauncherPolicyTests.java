package io.p2wlan.mc.forge;

import java.nio.file.Path;
import java.util.Arrays;
import net.minecraftforge.launcher.p2wlan.P2wlanAssetsLauncher;

public final class LauncherPolicyTests {
    public static void main(String[] arguments) {
        String[] original = {"--main", "net.minecraftforge.bootstrap.ForgeBootstrap", "--", "--launchTarget", "forge_userdev_client"};
        String assets = Path.of("build/assets").toAbsolutePath().toString();
        String[] selected = P2wlanAssetsLauncher.withAssets(original, assets);
        int checks = 0;
        if (!selected[0].equals("--assets")) throw new AssertionError("ASSET_FLAG"); checks++;
        if (!selected[1].equals(assets)) throw new AssertionError("ASSET_PATH"); checks++;
        if (!Arrays.equals(Arrays.copyOfRange(selected, 2, selected.length), original)) throw new AssertionError("ARGUMENT_ORDER"); checks++;
        if (!original[0].equals("--main")) throw new AssertionError("ORIGINAL_MUTATED"); checks++;
        for (String invalid : new String[] {null, "relative/assets"}) {
            try { P2wlanAssetsLauncher.withAssets(original, invalid); throw new AssertionError("INVALID_DIRECTORY"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        if (checks != 6) throw new AssertionError("LAUNCHER_CHECK_COUNT");
        System.out.println("PASS build-only Slime asset argument policy: " + checks + " checks");
    }
}
