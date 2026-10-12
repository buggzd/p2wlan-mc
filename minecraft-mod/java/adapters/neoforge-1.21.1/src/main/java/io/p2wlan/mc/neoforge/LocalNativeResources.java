package io.p2wlan.mc.neoforge;

import cpw.mods.niofs.union.UnionFileSystem;
import net.neoforged.jarjar.nio.pathfs.PathFileSystem;
import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.LayerNativeResources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Typed filesystem edges only; the common resolver owns locality/read policy. */
public final class LocalNativeResources implements BundledAgent.Resources {
    private final LayerNativeResources resources;
    public LocalNativeResources(ClassLoader loader) { this(loader, null); }
    public LocalNativeResources(ClassLoader loader, ModuleLayer layer) {
        resources = new LayerNativeResources(loader, layer, LocalNativeResources::backing);
    }
    @Override public InputStream open(String name) throws IOException { return resources.open(name); }
    public static Path backing(Path path) {
        if (path.getFileSystem() instanceof UnionFileSystem union) return union.getPrimaryPath();
        if (path.getFileSystem() instanceof PathFileSystem nested) return nested.getTarget();
        return null;
    }
    static boolean localBacking(Path path) { return LayerNativeResources.localBacking(path, LocalNativeResources::backing); }
}
