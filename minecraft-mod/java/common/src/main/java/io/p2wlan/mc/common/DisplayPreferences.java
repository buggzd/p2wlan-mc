package io.p2wlan.mc.common;

/** Non-sensitive local rendering preferences. Defaults never place a HUD over play. */
public record DisplayPreferences(boolean hudVisible, Detail detail, Corner corner) {
    public enum Detail { BASIC("基础"), DETAILED("详细"), ADVANCED("高级");
        public final String label; Detail(String label) { this.label = label; }
    }
    public enum Corner { TOP_LEFT("左上角"), TOP_RIGHT("右上角"), BOTTOM_RIGHT("右下角"), BOTTOM_LEFT("左下角");
        public final String label; Corner(String label) { this.label = label; }
        public Corner next() { return values()[(ordinal() + 1) % values().length]; }
    }
    public static DisplayPreferences defaults() { return new DisplayPreferences(false, Detail.BASIC, Corner.TOP_LEFT); }
    public DisplayPreferences {
        java.util.Objects.requireNonNull(detail); java.util.Objects.requireNonNull(corner);
    }
}
