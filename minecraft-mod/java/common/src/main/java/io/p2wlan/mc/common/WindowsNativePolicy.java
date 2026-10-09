package io.p2wlan.mc.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Pure bounds/identity/security decisions. No Win32 calls or mutable state. */
final class WindowsNativePolicy {
    static final int FULL_ACCESS = 0x001f01ff;
    static final int EXECUTE_ACCESS = 0x001300a9; // read + execute + delete, not write
    static final int MAX_SECURITY = 65536;
    static final int MAX_ANCESTORS = 64;
    record FileId(int volume, int high, int low) { }
    record Info(FileId id, int attributes, long links, long size) { }

    static String drive(String path) throws NativeBundleException {
        if (path == null || path.length() < 3 || path.length() > 32700
                || !path.substring(0, 3).matches("[A-Za-z]:\\\\") || path.indexOf('\0') >= 0
                || path.indexOf('/') >= 0 || path.substring(3).indexOf(':') >= 0) fail(Code.FILESYSTEM_UNSUPPORTED);
        String[] names = path.substring(3).split("\\\\", -1);
        if (names.length > MAX_ANCESTORS || path.length() > 3 && names[names.length - 1].isEmpty()) fail(Code.FILESYSTEM_UNSUPPORTED);
        for (String name : names) {
            if (path.length() == 3 && name.isEmpty()) continue;
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")) fail(Code.FILESYSTEM_UNSUPPORTED);
            String stem = name.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT);
            if (stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) fail(Code.FILESYSTEM_UNSUPPORTED);
        }
        return path.substring(0, 3);
    }
    static Info information(byte[] bytes) throws NativeBundleException {
        if (bytes.length != 52) fail(Code.FILE_IDENTITY_CHANGED);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long size = Integer.toUnsignedLong(data.getInt(36)) | Integer.toUnsignedLong(data.getInt(32)) << 32;
        return new Info(new FileId(data.getInt(28), data.getInt(44), data.getInt(48)), data.getInt(0),
                Integer.toUnsignedLong(data.getInt(40)), size);
    }
    static void kind(Info value, boolean directory) throws NativeBundleException {
        if ((value.attributes & (0x400 | 0x40)) != 0 || ((value.attributes & 0x10) != 0) != directory
                || !directory && (value.links != 1 || value.size < 0)) fail(Code.FILE_IDENTITY_CHANGED);
    }
    static void same(Info expected, Info actual, boolean directory) throws NativeBundleException {
        kind(actual, directory);
        if (expected == null || !expected.id.equals(actual.id)) fail(Code.FILE_IDENTITY_CHANGED);
    }
    static byte[] sid(byte[] bytes, int offset) throws NativeBundleException {
        range(bytes, offset, 8);
        int count = Byte.toUnsignedInt(bytes[offset + 1]);
        if (bytes[offset] != 1 || count > 15) fail(Code.FILE_IDENTITY_CHANGED);
        int size = 8 + count * 4;
        range(bytes, offset, size);
        return Arrays.copyOfRange(bytes, offset, offset + size);
    }
    static void security(byte[] descriptor, byte[] owner, int mask) throws NativeBundleException {
        if (descriptor.length < 20 || descriptor.length > MAX_SECURITY || descriptor[0] != 1) fail(Code.FILE_IDENTITY_CHANGED);
        ByteBuffer data = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        int control = Short.toUnsignedInt(data.getShort(2));
        // GetSecurityInfo gives a self-relative descriptor. A null, absent,
        // inherited/unprotected DACL or non-current owner never grants trust.
        if ((control & (0x8000 | 0x1000 | 4)) != (0x8000 | 0x1000 | 4)) fail(Code.FILE_IDENTITY_CHANGED);
        int user = data.getInt(4), acl = data.getInt(16);
        if (user < 20 || acl < 20 || (user & 3) != 0 || (acl & 3) != 0 || !Arrays.equals(sid(descriptor, user), owner)) fail(Code.FILE_IDENTITY_CHANGED);
        range(descriptor, acl, 8);
        int revision = Byte.toUnsignedInt(descriptor[acl]);
        int length = Short.toUnsignedInt(data.getShort(acl + 2));
        if ((revision != 2 && revision != 4) || length < 8 || Short.toUnsignedInt(data.getShort(acl + 4)) != 1) fail(Code.FILE_IDENTITY_CHANGED);
        range(descriptor, acl, length);
        int ace = acl + 8;
        range(descriptor, ace, 8);
        int aceSize = Short.toUnsignedInt(data.getShort(ace + 2));
        if (descriptor[ace] != 0 || descriptor[ace + 1] != 0 || aceSize < 16 || aceSize > length - 8
                || data.getInt(ace + 4) != mask) fail(Code.FILE_IDENTITY_CHANGED);
        byte[] allowed = sid(descriptor, ace + 8);
        if (aceSize != 8 + allowed.length || !Arrays.equals(allowed, owner)) fail(Code.FILE_IDENTITY_CHANGED);
    }
    private static void range(byte[] value, int offset, int count) throws NativeBundleException {
        if (offset < 0 || count < 0 || offset > value.length - count) fail(Code.FILE_IDENTITY_CHANGED);
    }
    static void fail(Code code) throws NativeBundleException { throw new NativeBundleException(code); }
}
