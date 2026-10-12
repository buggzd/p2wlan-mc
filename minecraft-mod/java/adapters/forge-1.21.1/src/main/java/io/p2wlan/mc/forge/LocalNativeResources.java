package io.p2wlan.mc.forge;

import cpw.mods.niofs.union.UnionFileSystem;
import net.minecraftforge.jarjar.nio.pathfs.PathFileSystem;
import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.LayerNativeResources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Pinned loader filesystem edges; the common resolver owns locality/read policy. */
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
            // Forge may create the installed NIO provider in its bootstrap
            // loader while resolving this adapter against a GAME-layer copy.
            // Read only the exact pinned SDK edge; no reflective URL access.
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
