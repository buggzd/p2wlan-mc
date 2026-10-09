package io.p2wlan.mc.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** NTFS executable identities and handles. Owner-only protected DACLs are
 * installed at creation, not after a permissive write. No recursive cleanup,
 * permission repair, reparse traversal, shared cache or fallback to PATH. */
final class WindowsNativeInstallation extends NativeInstallation {
    private final WindowsNativeParents parents;
    private final WindowsNativeCalls.User user;
    private WindowsNativeCalls.Handle root;
    private final WindowsNativePolicy.Info rootIdentity;
    private WindowsNativeCalls.Handle file;
    private WindowsNativePolicy.Info fileIdentity;
    private WindowsNativeCalls.Handle deleting;
    private boolean ready;
    private boolean fileDeletePending;
    private boolean rootDeletePending;

    private WindowsNativeInstallation(Path directory, NativeManifest manifest, Slot slot, WindowsNativeParents parents,
            WindowsNativeCalls.User user, WindowsNativeCalls.Handle root) throws NativeBundleException {
        super(directory, manifest, slot);
        this.parents = parents; this.user = user; this.root = root;
        rootIdentity = root.information();
        WindowsNativePolicy.kind(rootIdentity, true);
        root.security(user, WindowsNativePolicy.FULL_ACCESS);
        checkRoot();
    }
    static Path supported(Path parent) throws NativeBundleException {
        String drive = WindowsNativePolicy.drive(parent.toString());
        if (!parent.isAbsolute() || !parent.normalize().equals(parent) || parent.getNameCount() >= WindowsNativePolicy.MAX_ANCESTORS) {
            WindowsNativePolicy.fail(Code.FILESYSTEM_UNSUPPORTED);
        }
        WindowsNativeCalls.supported(drive);
        return parent;
    }
    static WindowsNativeInstallation create(Path base, NativeManifest manifest, Slot slot) throws NativeBundleException {
        WindowsNativeParents parents = WindowsNativeParents.pin(base, slot);
        WindowsNativeCalls.Handle root = null;
        try {
            parents.check();
            WindowsNativeCalls.User user = WindowsNativeCalls.currentUser();
            Path directory = base.resolve("p2wlan-native-" + UUID.randomUUID());
            try (var descriptor = new WindowsNativeCalls.Descriptor(user, WindowsNativePolicy.FULL_ACCESS)) {
                WindowsNativeCalls.directory(directory, descriptor);
                slot.created(); // Only an actual directory or uncertain cleanup retains capacity.
            }
            root = WindowsNativeCalls.open(directory, WindowsNativeCalls.DELETE | WindowsNativeCalls.CONTROL | WindowsNativeCalls.ATTRIBUTES,
                    1 | 2, null, false);
            return new WindowsNativeInstallation(directory, manifest, slot, parents, user, root);
        } catch (NativeBundleException | RuntimeException | LinkageError error) {
            // Until root identity is captured, never guess which path to delete.
            // Releasing opened handles doesn't release the retained capacity.
            if (error instanceof NativeBundleException known && known.code() == Code.CLEANUP_FAILED) slot.created();
            try { if (root != null) root.close(); }
            catch (NativeBundleException cleanup) { slot.created(); throw cleanup; }
            finally {
                try { parents.close(); }
                catch (NativeBundleException cleanup) { slot.created(); throw cleanup; }
            }
            if (error instanceof NativeBundleException known) throw known;
            throw new NativeBundleException(error instanceof LinkageError ? Code.FILESYSTEM_UNSUPPORTED : Code.IO);
        }
    }
    @Override void write(InputStream input) throws IOException {
        long deadline = System.nanoTime() + TIMEOUT;
        checkRoot();
        try (var descriptor = new WindowsNativeCalls.Descriptor(user, WindowsNativePolicy.FULL_ACCESS)) {
            file = WindowsNativeCalls.open(executable, WindowsNativeCalls.READ | WindowsNativeCalls.WRITE | WindowsNativeCalls.CONTROL | 0x40000,
                    WindowsNativeCalls.SHARING, descriptor, true); // WRITE_DAC is for this one promotion
        }
        fileIdentity = file.information();
        checkFile(false);
        MessageDigest digest = digest();
        byte[] buffer = new byte[32768];
        long count = 0;
        while (true) {
            deadline(deadline);
            int size = input.read(buffer);
            if (size < 0) break;
            count += size;
            if (count > manifest.size()) throw new NativeBundleException(Code.BINARY_MISMATCH);
            digest.update(buffer, 0, size);
            checkFile(false);
            file.write(buffer, size);
        }
        if (count != manifest.size() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.checksum())) {
            throw new NativeBundleException(Code.BINARY_MISMATCH);
        }
        file.flush();
        checkFile(false);
        try (var descriptor = new WindowsNativeCalls.Descriptor(user, WindowsNativePolicy.EXECUTE_ACCESS)) {
            file.protect(descriptor);
        }
        ready = true;
        file.security(user, WindowsNativePolicy.EXECUTE_ACCESS);
        // A live write handle prevents a Windows image loader's sharing mode.
        // Close it, reacquire read-only, and recheck the exact file identity/hash
        // before launch. Other OS users cannot traverse the private directory.
        file.close();
        file = WindowsNativeCalls.open(executable, WindowsNativeCalls.READ | WindowsNativeCalls.CONTROL,
                WindowsNativeCalls.SHARING, null, false);
        checkFile(true);
    }
    private void checkRoot() throws NativeBundleException {
        if (isClosed() || root == null || rootDeletePending) WindowsNativePolicy.fail(Code.FILE_IDENTITY_CHANGED);
        parents.check();
        WindowsNativePolicy.same(rootIdentity, root.information(), true);
        root.security(user, WindowsNativePolicy.FULL_ACCESS);
        try (var probe = WindowsNativeCalls.open(directory, WindowsNativeCalls.ATTRIBUTES, 1 | 2 | 4, null, false)) {
            WindowsNativePolicy.same(rootIdentity, probe.information(), true);
        }
    }
    private void checkFile(boolean complete) throws NativeBundleException {
        checkRoot();
        if (file == null || fileDeletePending) WindowsNativePolicy.fail(Code.FILE_IDENTITY_CHANGED);
        WindowsNativePolicy.Info actual = file.information();
        WindowsNativePolicy.same(fileIdentity, actual, false);
        file.security(user, ready ? WindowsNativePolicy.EXECUTE_ACCESS : WindowsNativePolicy.FULL_ACCESS);
        if (complete && actual.size() != manifest.size()) WindowsNativePolicy.fail(Code.FILE_IDENTITY_CHANGED);
        try (var probe = WindowsNativeCalls.open(executable, WindowsNativeCalls.ATTRIBUTES, 1 | 2 | 4, null, false)) {
            WindowsNativePolicy.same(fileIdentity, probe.information(), false);
        }
    }
    @Override Path validate() throws IOException {
        checkFile(true);
        long deadline = System.nanoTime() + TIMEOUT;
        MessageDigest digest = digest();
        byte[] buffer = new byte[32768];
        long count = 0;
        file.rewind();
        while (true) {
            deadline(deadline);
            int size = file.read(buffer);
            if (size == 0) break;
            count += size;
            if (count > manifest.size()) throw new NativeBundleException(Code.BINARY_MISMATCH);
            digest.update(buffer, 0, size);
        }
        if (count != manifest.size() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.checksum())) {
            throw new NativeBundleException(Code.BINARY_MISMATCH);
        }
        checkFile(true);
        return executable;
    }
    @Override void checkIdentity() throws IOException { checkFile(true); }
    @Override void cleanup() throws IOException {
        try {
            if (!rootDeletePending) {
                checkRoot();
                if (fileIdentity != null && !fileDeletePending) {
                    checkFile(ready);
                    if (deleting == null) deleting = WindowsNativeCalls.open(executable,
                            WindowsNativeCalls.DELETE | WindowsNativeCalls.CONTROL | WindowsNativeCalls.ATTRIBUTES,
                            1 | 2 | 4, null, false);
                    WindowsNativePolicy.same(fileIdentity, deleting.information(), false);
                    deleting.security(user, ready ? WindowsNativePolicy.EXECUTE_ACCESS : WindowsNativePolicy.FULL_ACCESS);
                    checkRoot();
                    deleting.delete();
                    fileDeletePending = true;
                }
                if (file != null) { file.close(); file = null; }
                if (deleting != null) { deleting.close(); deleting = null; }
                checkRoot();
                root.delete(); // Windows refuses non-empty directories; no enumeration/deletion of foreign contents.
                rootDeletePending = true;
            }
            if (root != null) { root.close(); root = null; }
            parents.close();
        } catch (NativeBundleException error) {
            if (error.code() == Code.FILE_IDENTITY_CHANGED || error.code() == Code.CLEANUP_FAILED) throw error;
            throw new NativeBundleException(Code.CLEANUP_FAILED);
        }
    }
}
