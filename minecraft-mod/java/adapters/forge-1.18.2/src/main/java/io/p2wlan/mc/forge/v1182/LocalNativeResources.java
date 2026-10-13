package io.p2wlan.mc.forge.v1182;

import cpw.mods.niofs.union.UnionFileSystem;
import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.LayerNativeResources;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

/** The shared resolver owns native resource locality and bounded reads. */
public final class LocalNativeResources implements BundledAgent.Resources {
    private final LayerNativeResources resources;
    public LocalNativeResources(ClassLoader loader, ModuleLayer layer) {
        resources = new LayerNativeResources(loader, layer, LocalNativeResources::backing);
    }
    @Override public InputStream open(String name) throws IOException { return resources.open(name); }
    public static Path backing(Path path) {
        try {
            var filesystem = path.getFileSystem();
            if (filesystem instanceof UnionFileSystem union) return union.getPrimaryPath();
            var type = filesystem.getClass();
            String getter = switch (type.getName()) {
                case "cpw.mods.niofs.union.UnionFileSystem" -> "getPrimaryPath";
                case "net.minecraftforge.jarjar.nio.pathfs.PathFileSystem" -> "getTarget";
                default -> null;
            };
            if (getter == null) return null;
            var method = type.getDeclaredMethod(getter);
            if (method.getDeclaringClass() != type || method.getReturnType() != Path.class
                    || !Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) return null;
            return (Path) method.invoke(filesystem);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return null; }
    }
}
