package io.p2wlan.mc.legacybootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileFactory;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Public, pinned FML factories only; the ForgeSPI 3/4/7 ABI differs by profile. */
final class LegacyFmlBridge {
    private static final String DISCOVERY = "net.minecraftforge.fml.loading.moddiscovery.";
    private final LegacyEligibility.Binding binding;
    private final ClassLoader loader;
    private final Object scanner;
    private final Method scan;
    LegacyFmlBridge(LegacyEligibility.Binding binding, ClassLoader loader) {
        this.binding = binding; this.loader = loader;
        if (binding.legacy()) { scanner = null; scan = null; return; }
        try {
            Class<?> type = Class.forName(DISCOVERY + "ModsFolderLocator",true,loader);
            scanner = type.getConstructor().newInstance();
            scan = type.getMethod("scanFile",IModFile.class,Consumer.class);
        } catch (ReflectiveOperationException failure) { throw failure(failure); }
    }
    IModFile create(Path path, IModLocator locator) {
        binding.requireCurrent();
        try {
            Class<?> type = Class.forName(DISCOVERY + "ModFile",true,loader);
            Class<?> parser = Class.forName(DISCOVERY + "ModFileParser",true,loader);
            Method parse = parser.getMethod("modsTomlParser",IModFile.class);
            ModFileFactory.ModFileInfoParser info = file -> (IModFileInfo) invoke(parse,null,file);
            if (binding.legacy()) return (IModFile) type.getConstructor(Path.class,IModLocator.class,ModFileFactory.ModFileInfoParser.class)
                    .newInstance(path,locator,info);
            Class<?> secure = Class.forName("cpw.mods.jarhandling.SecureJar",true,loader);
            Object jar = secure.getMethod("from",Path[].class).invoke(null,(Object)new Path[]{path});
            Class<?> provider = binding.wrapped() ? Class.forName("net.minecraftforge.forgespi.locating.IModProvider",false,loader) : IModLocator.class;
            return (IModFile) type.getConstructor(secure,provider,ModFileFactory.ModFileInfoParser.class).newInstance(jar,locator,info);
        } catch (ReflectiveOperationException failure) { throw failure(failure); }
    }
    Object discoveryValue(IModFile file) {
        if (!binding.wrapped()) return file;
        try {
            Class<?> exception = Class.forName("net.minecraftforge.forgespi.locating.ModFileLoadingException",false,loader);
            return Class.forName("net.minecraftforge.forgespi.locating.IModLocator$ModFileOrException",true,loader)
                    .getConstructor(IModFile.class,exception).newInstance(file,null);
        } catch (ReflectiveOperationException failure) { throw failure(failure); }
    }
    void scan(IModFile file, Consumer<Path> consumer) {
        binding.requireCurrent(); invoke(scan,scanner,file,consumer);
    }
    private static Object invoke(Method method, Object target, Object... values) {
        try { return method.invoke(target,values); }
        catch (ReflectiveOperationException failure) { throw failure(failure); }
    }
    private static LegacyFailure failure(ReflectiveOperationException failure) {
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() instanceof LegacyFailure typed) return typed;
        return new LegacyFailure(Code.RUNTIME_PROFILE,failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure);
    }
}
