package io.p2wlan.mc.legacybootstrap;

import cpw.mods.modlauncher.api.IEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraftforge.forgespi.locating.IModLocator;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Exact SDK byte identities; only Forge52's pinned version authority initializes. */
final class LegacyEligibility {
    private static final String FORGE52_FML = "e487fefaa21789cfe0e0fcc2f1b0b7a940a52fb4";
    record Binding(boolean legacy, boolean wrapped, List<OwnerFile> sdk, LegacySource source, String factory) {
        Binding { sdk = List.copyOf(sdk); }
        void requireCurrent() { source.requireCurrent(); sdk.forEach(file -> file.requireCurrent(true)); }
    }
    static Binding capture(Map<String,?> arguments, ClassLoader loader) {
        if ("21".equals(System.getProperty("java.specification.version"))) return forge52(arguments,loader);
        String minecraft = string(arguments,"mcVersion"), forge = string(arguments,"forgeVersion");
        String group = arguments.containsKey("forgeGroup") ? string(arguments,"forgeGroup") : "";
        String java = System.getProperty("java.specification.version");
        // Neo47 supplies fmlVersion, with no forgeGroup key. Its distinct
        // official FancyModLoader bytes establish the loader namespace.
        boolean neo47 = "47.1.106".equals(forge) && "47.2.2".equals(arguments.get("fmlVersion"))
                && (group.isEmpty() || "net.neoforged".equals(group));
        boolean legacy = false, wrapped = false;
        String fml, launcher, spi, secure = null;
        if ("1.16.5".equals(minecraft) && "36.2.42".equals(forge) && "net.minecraftforge".equals(group) && "17".equals(java)) {
            legacy = true;
            fml = "caca08d22543cbe101d64d171738e1ff6eef4de3";
            launcher = "6d0bfd78e025a87d43985e30907aa9ecbc51b2d6";
            spi = "0c6ca4e4e4a0343701407c760e642537b613b543";
        } else if ("1.18.2".equals(minecraft) && "40.3.12".equals(forge) && "net.minecraftforge".equals(group) && "17".equals(java)) {
            fml = "46a77eeaaf7f5633daa8a777b80912bc01f5bfb4";
            launcher = "7339d0e87813c366d706c3fdba479455e2283ecd";
            spi = "1d10ffe779684cb20f2b63bdcc030152e47116bb";
            secure = "94c7b14c960e1b6cb37337d81ad4ec5932f7595c";
        } else if ("1.20.1".equals(minecraft) && "17".equals(java) &&
                (("47.3.2".equals(forge) && "net.minecraftforge".equals(group))
                || neo47)) {
            wrapped = true;
            fml = neo47 ? "e29a838f13be8ba241211b8cb33d007bc5ff7926" : "7d452fbeb088558199ecd20dd4f54169746fc0bf";
            launcher = "06d9443f56f50bb85cea383686ff9c867391458b";
            spi = "3b4972a0cdb135853dba219be61a79b22cff1309";
            secure = "51e6a22c6c716beb11e244bf5b8be480f51dd6b5";
        } else throw new LegacyFailure(Code.RUNTIME_PROFILE);
        try {
            LegacySource source = LegacySource.capture(secure);
            List<OwnerFile> sdk = new ArrayList<>();
            sdk.add(source.pinned(Class.forName("net.minecraftforge.fml.loading.FMLLoader",false,loader),fml));
            sdk.add(source.pinned(IEnvironment.class,launcher));
            sdk.add(source.pinned(IModLocator.class,spi));
            if (secure != null) sdk.add(source.pinned(Class.forName("cpw.mods.jarhandling.SecureJar",false,loader),secure));
            return new Binding(legacy,wrapped,sdk,source,legacy ? "" : wrapped ? "LegacyForge47Factory" : "LegacyForge40Factory");
        } catch (ClassNotFoundException failure) { throw new LegacyFailure(Code.RUNTIME_PROFILE); }
    }
    private static Binding forge52(Map<String,?> arguments, ClassLoader loader) {
        try {
            Class<?> fml = Class.forName("net.minecraftforge.fml.loading.FMLLoader",false,loader);
            LegacySource source = LegacySource.capture("d8402fa81935b1a6440240f98079b14d0462ccac");
            List<OwnerFile> sdk = new ArrayList<>();
            sdk.add(source.pinned(fml,FORGE52_FML));
            sdk.add(source.pinned(IEnvironment.class,"781dde4dcb54a7bd4f66b256d47e82710939b1bf"));
            sdk.add(source.pinned(IModLocator.class,"c01beea469b66cf787614239b7e0f791329b3816"));
            sdk.add(source.pinned(Class.forName("cpw.mods.jarhandling.SecureJar",false,loader),"d8402fa81935b1a6440240f98079b14d0462ccac"));
            Class<?> info = Class.forName("net.minecraftforge.fml.loading.VersionInfo",false,loader);
            OwnerFile versionOwner = source.pinned(info,FORGE52_FML);
            if (!versionOwner.path.equals(sdk.get(0).path) || info.getClassLoader() != fml.getClassLoader()) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            sdk.forEach(file -> file.requireCurrent(true));
            // Forge52 supplies no version arguments. Its public, immutable
            // VersionInfo getter is the authority, after SDK byte verification.
            Object version = fml.getMethod("versionInfo").invoke(null);
            if (version == null || version.getClass() != info) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            for (var expected : Map.of("mcVersion","1.21.1","forgeVersion","52.1.16","forgeGroup","net.minecraftforge").entrySet()) {
                if (!expected.getValue().equals(info.getMethod(expected.getKey()).invoke(version))) throw new LegacyFailure(Code.RUNTIME_PROFILE);
                if (arguments.containsKey(expected.getKey()) && !expected.getValue().equals(arguments.get(expected.getKey()))) throw new LegacyFailure(Code.RUNTIME_PROFILE);
            }
            sdk.forEach(file -> file.requireCurrent(true));
            return new Binding(false,true,sdk,source,"LegacyForge52Factory");
        } catch (ReflectiveOperationException failure) { throw new LegacyFailure(Code.RUNTIME_PROFILE); }
    }
    private static String string(Map<String,?> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String text)) throw new LegacyFailure(Code.RUNTIME_PROFILE);
        return text;
    }
}
