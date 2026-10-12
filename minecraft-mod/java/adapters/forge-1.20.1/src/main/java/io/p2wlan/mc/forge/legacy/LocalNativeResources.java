package io.p2wlan.mc.forge.legacy;

import cpw.mods.niofs.union.UnionFileSystem;
import net.minecraftforge.jarjar.nio.pathfs.PathFileSystem;
import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.LayerNativeResources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Forge 1.20.1 filesystem edges; the common resolver owns locality/read policy. */
public final class LocalNativeResources implements BundledAgent.Resources {
    private final LayerNativeResources resources;
    public LocalNativeResources(ClassLoader loader) { this(loader, null); }
    public LocalNativeResources(ClassLoader loader, ModuleLayer layer) {
        resources = new LayerNativeResources(loader, layer, LocalNativeResources::backing);
    }
    @Override public InputStream open(String name) throws IOException { return resources.open(name); }
    public static Path backing(Path path) {
        try {
            var filesystem = path.getFileSystem();
            if (filesystem instanceof UnionFileSystem union) return union.getPrimaryPath();
            if (filesystem instanceof PathFileSystem nested) return nested.getTarget();
            // Installed providers can belong to a different loader from this
            // delegate. Only the exact public, zero-argument SDK getters qualify.
            var type = filesystem.getClass();
            String getter = switch (type.getName()) {
                case "cpw.mods.niofs.union.UnionFileSystem" -> "getPrimaryPath";
                case "net.minecraftforge.jarjar.nio.pathfs.PathFileSystem" -> "getTarget";
                default -> null;
            };
            if (getter == null) return null;
            var method = type.getDeclaredMethod(getter);
            if (method.getDeclaringClass() != type || method.getReturnType() != Path.class
                    || !java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) return null;
            return (Path) method.invoke(filesystem);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
    }
    static boolean localBacking(Path path) { return LayerNativeResources.localBacking(path, LocalNativeResources::backing); }
}
