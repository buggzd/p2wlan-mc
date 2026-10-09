package io.p2wlan.mc.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Synthetic decisions only. This harness never loads kernel32/advapi32 and
 * cannot establish Windows ACL, HANDLE, image-loader or cleanup acceptance. */
public final class WindowsNativePolicyTests {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void reject(Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (NativeBundleException error) { check(error.code() == code); }
    }
    private static byte[] owner() { return new byte[] {1, 1, 0, 0, 0, 0, 0, 5, 21, 0, 0, 0}; }
    private static byte[] descriptor(int mask) {
        byte[] bytes = new byte[60];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes[0] = 1; b.putShort(2, (short) 0x9004); b.putInt(4, 20); b.putInt(16, 32);
        System.arraycopy(owner(), 0, bytes, 20, 12);
        bytes[32] = 2; b.putShort(34, (short) 28); b.putShort(36, (short) 1);
        b.putShort(42, (short) 20); b.putInt(44, mask);
        System.arraycopy(owner(), 0, bytes, 48, 12);
        return bytes;
    }
    private static byte[] info(int attributes, int links, long size) {
        byte[] bytes = new byte[52];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, attributes); b.putInt(28, 7); b.putInt(32, (int) (size >>> 32)); b.putInt(36, (int) size);
        b.putInt(40, links); b.putInt(44, 3); b.putInt(48, 2);
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        for (String value : new String[] {"C:\\", "D:\\fixture\\space and Unicode 世界\\temp", "c:\\temp"}) {
            check(WindowsNativePolicy.drive(value).equals(value.substring(0, 3)));
        }
        for (String value : new String[] {"", "temp", "C:temp", "C:/temp", "\\\\server\\share", "\\\\?\\C:\\temp",
                "C:\\temp\\..\\other", "C:\\temp\\.", "C:\\temp\\name.", "C:\\temp\\name ", "C:\\temp:stream",
                "C:\\temp\\NUL.txt", "C:\\CON", "C:\\temp\\LPT1", "C:\\temp\\COM¹", "C:\\temp\\\\other",
                "C:\\temp\\", "C:\\" + "part\\".repeat(65) + "temp", "C:\\temp\0bad"}) {
            reject(Code.FILESYSTEM_UNSUPPORTED, () -> WindowsNativePolicy.drive(value));
        }
        for (int mask : new int[] {WindowsNativePolicy.FULL_ACCESS, WindowsNativePolicy.EXECUTE_ACCESS}) {
            WindowsNativePolicy.security(descriptor(mask), owner(), mask); checks++;
            for (int offset : new int[] {0, 2, 4, 16, 20, 32, 34, 36, 40, 41, 42, 44, 48}) {
                byte[] bad = descriptor(mask); bad[offset] ^= (byte) 0xff;
                reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.security(bad, owner(), mask));
            }
            for (int size : new int[] {0, 19, 35, 50, 59, 65537}) {
                byte[] bad = Arrays.copyOf(descriptor(mask), size);
                reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.security(bad, owner(), mask));
            }
            byte[] other = owner(); other[8]++;
            reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.security(descriptor(mask), other, mask));
            reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.security(descriptor(mask), owner(), mask ^ 1));
        }
        WindowsNativePolicy.Info file = WindowsNativePolicy.information(info(0x80, 1, 123));
        check(file.size() == 123 && file.links() == 1);
        WindowsNativePolicy.kind(file, false); checks++;
        WindowsNativePolicy.same(file, WindowsNativePolicy.information(info(0x80, 1, 124)), false); checks++;
        for (byte[] bad : new byte[][] {info(0x80 | 0x400, 1, 123), info(0x80 | 0x40, 1, 123), info(0x10, 1, 123),
                info(0x80, 2, 123), info(0x80, 0, 123), info(0x80, 1, -1)}) {
            reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.kind(WindowsNativePolicy.information(bad), false));
        }
        for (int offset : new int[] {28, 44, 48}) {
            byte[] bad = info(0x80, 1, 123); bad[offset]++;
            reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.same(file, WindowsNativePolicy.information(bad), false));
        }
        reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.same(null, file, false));
        reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.information(new byte[51]));
        reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.information(new byte[53]));
        WindowsNativePolicy.kind(WindowsNativePolicy.information(info(0x10, 1, 0)), true); checks++;
        check(WindowsNativePolicy.information(info(0x80, 1, 0x1ffffffffL)).size() == 0x1ffffffffL);
        for (int count : new int[] {0, 1, 15}) {
            byte[] sid = new byte[8 + count * 4]; sid[0] = 1; sid[1] = (byte) count;
            check(Arrays.equals(WindowsNativePolicy.sid(sid, 0), sid));
        }
        byte[] sid = owner(); sid[1] = 16;
        reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.sid(sid, 0));
        reject(Code.FILE_IDENTITY_CHANGED, () -> WindowsNativePolicy.sid(owner(), -1));
        // Also check the FFI's SECURITY_ATTRIBUTES layout on this host without
        // loading kernel32. This is not evidence for any Windows API call.
        try (var attributes = new WindowsNativeCalls.Attributes(null)) {
            check(com.sun.jna.Native.POINTER_SIZE == 8 && attributes.length == 24);
            check(attributes.getPointer().getInt(0) == 24 && attributes.getPointer().getPointer(8) == null
                    && attributes.getPointer().getInt(16) == 0);
        }
        System.out.println("PASS Windows native policy: " + checks + " synthetic checks (not NTFS/image-loader acceptance)");
    }
}
