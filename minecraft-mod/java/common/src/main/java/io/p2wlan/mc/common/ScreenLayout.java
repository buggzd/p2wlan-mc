package io.p2wlan.mc.common;

/** Pure logical-pixel layout. Navigation and feedback never consume list rows.
 * Minecraft's 320 x 240 logical viewport fits the three-row control groups. */
public record ScreenLayout(int left, int width, int top, int bodyBottom, int footerY, int messageY) {
    public static final int BUTTON_HEIGHT = 20, STEP = 26, GAP = 8;
    public static ScreenLayout of(int viewportWidth, int viewportHeight) {
        int width = Math.min(420, Math.max(0, viewportWidth - 32));
        int panelHeight = Math.min(300, viewportHeight), offset = Math.max(0, (viewportHeight - panelHeight) / 2);
        int footer = Math.max(0, offset + panelHeight - 62);
        return new ScreenLayout(Math.max(0, (viewportWidth - width) / 2), width,
                offset + 66, Math.max(offset + 66, footer - GAP), footer, Math.max(0, offset + panelHeight - 36));
    }
    public int row(int index) { return top + index * STEP; }
    public int halfWidth() { return Math.max(0, (width - GAP) / 2); }
    public int right() { return left + halfWidth() + GAP; }
    public int listRows(int controlRows, int maximum) {
        return Math.max(1, Math.min(maximum, (bodyBottom - top - controlRows * STEP) / STEP));
    }
    public int controls(int rows) { return row(rows); }
    public boolean inBody(int y, int height) { return y >= top && y + height <= bodyBottom; }
}
