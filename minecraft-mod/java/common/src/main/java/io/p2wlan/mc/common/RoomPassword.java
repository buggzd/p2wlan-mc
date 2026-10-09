package io.p2wlan.mc.common;

/** Input-only UTF-8 byte policy for the Control password-based room flow.
 * Does not authorize a room or trim/normalize credentials. Native checks again. */
public final class RoomPassword {
    private RoomPassword() {}
    public static boolean isValid(String value) {
        if (value == null || value.length() > 72) return false;
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
                bytes += 4;
            } else if (Character.isLowSurrogate(current)) return false;
            else bytes += current < 128 ? 1 : current < 2048 ? 2 : 3;
            if (bytes > 72) return false;
        }
        return bytes >= 8;
    }
}
