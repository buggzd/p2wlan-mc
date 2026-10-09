package io.p2wlan.mc.common;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** At most 64 pinned ancestor directories; deny delete sharing, not unrelated
 * child creation. Private executable/DACL authority belongs to the installation. */
final class WindowsNativeParents implements AutoCloseable {
    private record Entry(Path path, WindowsNativeCalls.Handle handle, WindowsNativePolicy.Info identity) { }
    private final List<Entry> entries = new ArrayList<>();
    static WindowsNativeParents pin(Path base, NativeInstallation.Slot slot) throws NativeBundleException {
        WindowsNativeParents result = new WindowsNativeParents();
        try {
            Path current = base.getRoot();
            result.add(current);
            for (Path name : base) { current = current.resolve(name); result.add(current); }
            result.check();
            return result;
        } catch (NativeBundleException | RuntimeException | LinkageError error) {
            if (error instanceof NativeBundleException known && known.code() == Code.CLEANUP_FAILED) slot.created();
            try { result.close(); }
            catch (NativeBundleException cleanup) { slot.created(); throw cleanup; }
            if (error instanceof NativeBundleException known) throw known;
            throw new NativeBundleException(error instanceof LinkageError ? Code.FILESYSTEM_UNSUPPORTED : Code.IO);
        }
    }
    private void add(Path path) throws NativeBundleException {
        if (path == null || entries.size() >= WindowsNativePolicy.MAX_ANCESTORS) WindowsNativePolicy.fail(Code.FILESYSTEM_UNSUPPORTED);
        WindowsNativeCalls.Handle handle = WindowsNativeCalls.open(path, WindowsNativeCalls.ATTRIBUTES, 1 | 2, null, false);
        try {
            WindowsNativePolicy.Info identity = handle.information();
            WindowsNativePolicy.kind(identity, true);
            entries.add(new Entry(path, handle, identity));
        } catch (NativeBundleException | RuntimeException | LinkageError error) {
            handle.close();
            if (error instanceof NativeBundleException known) throw known;
            throw new NativeBundleException(error instanceof LinkageError ? Code.FILESYSTEM_UNSUPPORTED : Code.IO);
        }
    }
    void check() throws NativeBundleException {
        for (Entry entry : entries) {
            WindowsNativePolicy.same(entry.identity, entry.handle.information(), true);
            try (WindowsNativeCalls.Handle probe = WindowsNativeCalls.open(entry.path, WindowsNativeCalls.ATTRIBUTES, 1 | 2 | 4, null, false)) {
                WindowsNativePolicy.same(entry.identity, probe.information(), true);
            }
        }
    }
    @Override public void close() throws NativeBundleException {
        NativeBundleException failure = null;
        for (int index = entries.size() - 1; index >= 0; index--) {
            try { entries.get(index).handle.close(); entries.remove(index); }
            catch (NativeBundleException error) { if (failure == null) failure = error; }
        }
        if (failure != null) throw failure;
    }
}
