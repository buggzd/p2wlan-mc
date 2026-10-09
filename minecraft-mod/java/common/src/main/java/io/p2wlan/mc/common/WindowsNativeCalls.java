package io.p2wlan.mc.common;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Path;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Win32 file/security effects only. The installation owns every live handle;
 * API initialization is lazy, never done by a POSIX installation. No account
 * lookup, external command, system permission change or native path cache. */
final class WindowsNativeCalls {
    private interface Kernel extends StdCallLibrary {
        Pointer GetCurrentProcess();
        int GetLastError();
        boolean CloseHandle(Pointer handle);
        Pointer LocalFree(Pointer value);
        int GetDriveTypeW(WString path);
        boolean GetVolumeInformationW(WString root, char[] volume, int volumeSize, IntByReference serial,
                IntByReference component, IntByReference flags, char[] filesystem, int filesystemSize);
        boolean CreateDirectoryW(WString path, Pointer attributes);
        Pointer CreateFileW(WString path, int access, int sharing, Pointer attributes, int disposition, int flags, Pointer template);
        boolean GetFileInformationByHandle(Pointer handle, Pointer information);
        boolean ReadFile(Pointer handle, byte[] buffer, int size, IntByReference actual, Pointer overlapped);
        boolean WriteFile(Pointer handle, byte[] buffer, int size, IntByReference actual, Pointer overlapped);
        boolean FlushFileBuffers(Pointer handle);
        boolean SetFilePointerEx(Pointer handle, long position, LongByReference actual, int method);
        boolean SetFileInformationByHandle(Pointer handle, int informationClass, Pointer value, int size);
    }
    private interface Security extends StdCallLibrary {
        boolean OpenProcessToken(Pointer process, int access, PointerByReference token);
        boolean GetTokenInformation(Pointer token, int type, Pointer value, int size, IntByReference actual);
        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference text);
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(WString value, int revision, PointerByReference output, IntByReference size);
        int GetSecurityInfo(Pointer handle, int type, int flags, PointerByReference owner, PointerByReference group,
                PointerByReference dacl, PointerByReference sacl, PointerByReference descriptor);
        int GetSecurityDescriptorLength(Pointer descriptor);
        int SetSecurityInfo(Pointer handle, int type, int flags, Pointer owner, Pointer group, Pointer dacl, Pointer sacl);
    }
    private static final class Api {
        static final Kernel K = Native.load("kernel32", Kernel.class);
        static final Security S = Native.load("advapi32", Security.class);
    }
    @Structure.FieldOrder({"length", "descriptor", "inherit"})
    public static final class Attributes extends Structure implements AutoCloseable {
        public int length;
        public Pointer descriptor;
        public int inherit;
        Attributes(Pointer descriptor) { this.descriptor = descriptor; length = size(); write(); }
        @Override public void close() { ((Memory) getPointer()).close(); }
    }
    record User(byte[] sid, String sddlSid) { }
    static final int READ = 0x80000000;
    static final int WRITE = 0x40000000;
    static final int DELETE = 0x10000;
    static final int ATTRIBUTES = 0x80;
    static final int CONTROL = 0x20000;
    static final int SHARING = 1 | 4; // read/delete; no competing file writer

    static void supported(String drive) throws NativeBundleException {
        if (Native.POINTER_SIZE != 8 || Api.K.GetDriveTypeW(new WString(drive)) != 3) WindowsNativePolicy.fail(Code.FILESYSTEM_UNSUPPORTED);
        IntByReference flags = new IntByReference();
        char[] filesystem = new char[64];
        if (!Api.K.GetVolumeInformationW(new WString(drive), null, 0, new IntByReference(), new IntByReference(), flags, filesystem, filesystem.length)
                || (flags.getValue() & 8) == 0 || !Native.toString(filesystem).equals("NTFS")) WindowsNativePolicy.fail(Code.FILESYSTEM_UNSUPPORTED);
    }
    private static WString path(Path value) { return new WString("\\\\?\\" + value); }
    static User currentUser() throws NativeBundleException {
        PointerByReference token = new PointerByReference();
        require(Api.S.OpenProcessToken(Api.K.GetCurrentProcess(), 8, token));
        try (Handle handle = new Handle(token.getValue())) {
            IntByReference size = new IntByReference();
            if (Api.S.GetTokenInformation(handle.value(), 1, null, 0, size) || Api.K.GetLastError() != 122
                    || size.getValue() < 16 || size.getValue() > WindowsNativePolicy.MAX_SECURITY) WindowsNativePolicy.fail(Code.IO);
            try (Memory memory = new Memory(size.getValue())) {
                require(Api.S.GetTokenInformation(handle.value(), 1, memory, (int) memory.size(), size));
                Pointer sid = memory.getPointer(0);
                long offset = Pointer.nativeValue(sid) - Pointer.nativeValue(memory);
                if (offset < 0 || offset > memory.size() - 8) WindowsNativePolicy.fail(Code.IO);
                byte[] bytes = WindowsNativePolicy.sid(memory.getByteArray(0, (int) memory.size()), (int) offset);
                PointerByReference text = new PointerByReference();
                require(Api.S.ConvertSidToStringSidW(sid, text));
                try { return new User(bytes, text.getValue().getWideString(0)); }
                finally { free(text.getValue()); }
            }
        }
    }
    static final class Descriptor implements AutoCloseable {
        private Pointer value;
        private final int length;
        Descriptor(User user, int mask) throws NativeBundleException {
            PointerByReference result = new PointerByReference();
            IntByReference size = new IntByReference();
            String sddl = "O:" + user.sddlSid + "D:P(A;;0x" + Integer.toHexString(mask) + ";;;" + user.sddlSid + ")";
            require(Api.S.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(sddl), 1, result, size));
            value = result.getValue(); length = size.getValue();
            try {
                if (value == null || length < 20 || length > WindowsNativePolicy.MAX_SECURITY) WindowsNativePolicy.fail(Code.IO);
                WindowsNativePolicy.security(value.getByteArray(0, length), user.sid, mask);
            } catch (NativeBundleException error) { close(); throw error; }
        }
        Pointer dacl() throws NativeBundleException {
            int offset = value.getInt(16);
            if (offset < 20 || offset > length - 8) WindowsNativePolicy.fail(Code.IO);
            return value.share(offset);
        }
        Attributes attributes() { return new Attributes(value); }
        @Override public void close() throws NativeBundleException {
            if (value != null) { free(value); value = null; }
        }
    }
    static void directory(Path path, Descriptor descriptor) throws NativeBundleException {
        try (Attributes attributes = descriptor.attributes()) {
            require(Api.K.CreateDirectoryW(path(path), attributes.getPointer()));
        }
    }
    static Handle open(Path path, int access, int sharing, Descriptor descriptor, boolean create) throws NativeBundleException {
        try (Attributes attributes = descriptor == null ? null : descriptor.attributes()) {
            Pointer value = Api.K.CreateFileW(path(path), access, sharing, attributes == null ? null : attributes.getPointer(),
                    create ? 1 : 3, 0x02000000 | 0x00200000, null); // backup semantics + open reparse point
            if (value == null || Pointer.nativeValue(value) == -1) WindowsNativePolicy.fail(Code.IO);
            return new Handle(value);
        }
    }
    static final class Handle implements AutoCloseable {
        private Pointer pointer;
        Handle(Pointer pointer) throws NativeBundleException {
            if (pointer == null || Pointer.nativeValue(pointer) == -1) WindowsNativePolicy.fail(Code.IO);
            this.pointer = pointer;
        }
        Pointer value() throws NativeBundleException {
            if (pointer == null) WindowsNativePolicy.fail(Code.FILE_IDENTITY_CHANGED);
            return pointer;
        }
        synchronized WindowsNativePolicy.Info information() throws NativeBundleException {
            try (Memory bytes = new Memory(52)) {
                require(Api.K.GetFileInformationByHandle(value(), bytes));
                return WindowsNativePolicy.information(bytes.getByteArray(0, 52));
            }
        }
        synchronized void security(User user, int mask) throws NativeBundleException {
            PointerByReference descriptor = new PointerByReference();
            int result = Api.S.GetSecurityInfo(value(), 1, 1 | 4, null, null, null, null, descriptor);
            if (result != 0 || descriptor.getValue() == null) WindowsNativePolicy.fail(Code.FILE_IDENTITY_CHANGED);
            try {
                int size = Api.S.GetSecurityDescriptorLength(descriptor.getValue());
                if (size < 20 || size > WindowsNativePolicy.MAX_SECURITY) WindowsNativePolicy.fail(Code.FILE_IDENTITY_CHANGED);
                WindowsNativePolicy.security(descriptor.getValue().getByteArray(0, size), user.sid, mask);
            } finally { free(descriptor.getValue()); }
        }
        synchronized void protect(Descriptor descriptor) throws NativeBundleException {
            if (Api.S.SetSecurityInfo(value(), 1, 0x80000000 | 4, null, null, descriptor.dacl(), null) != 0) WindowsNativePolicy.fail(Code.IO);
        }
        synchronized void rewind() throws NativeBundleException { require(Api.K.SetFilePointerEx(value(), 0, new LongByReference(), 0)); }
        synchronized int read(byte[] bytes) throws NativeBundleException {
            IntByReference count = new IntByReference();
            require(Api.K.ReadFile(value(), bytes, bytes.length, count, null));
            if (count.getValue() < 0 || count.getValue() > bytes.length) WindowsNativePolicy.fail(Code.IO);
            return count.getValue();
        }
        synchronized void write(byte[] bytes, int size) throws NativeBundleException {
            IntByReference count = new IntByReference();
            require(Api.K.WriteFile(value(), bytes, size, count, null));
            // A short synchronous disk write has uncertain progress. Fail
            // closed; don't replay an imagined unsent suffix or grow a queue.
            if (count.getValue() != size) WindowsNativePolicy.fail(Code.IO);
        }
        synchronized void flush() throws NativeBundleException { require(Api.K.FlushFileBuffers(value())); }
        synchronized void delete() throws NativeBundleException {
            try (Memory disposition = new Memory(1)) {
                disposition.setByte(0, (byte) 1); // FILE_DISPOSITION_INFO.BOOLEAN, not a four-byte BOOL
                if (!Api.K.SetFileInformationByHandle(value(), 4, disposition, 1)) WindowsNativePolicy.fail(Code.CLEANUP_FAILED);
            }
        }
        // Serialize individual bounded-size local calls with close so Windows
        // cannot recycle a HANDLE mid-call. No network I/O, callback or wait.
        @Override public synchronized void close() throws NativeBundleException {
            if (pointer != null) {
                if (!Api.K.CloseHandle(pointer)) WindowsNativePolicy.fail(Code.CLEANUP_FAILED);
                pointer = null;
            }
        }
    }
    private static void require(boolean success) throws NativeBundleException { if (!success) WindowsNativePolicy.fail(Code.IO); }
    private static void free(Pointer pointer) throws NativeBundleException {
        if (pointer == null || Api.K.LocalFree(pointer) != null) WindowsNativePolicy.fail(Code.CLEANUP_FAILED);
    }
}
