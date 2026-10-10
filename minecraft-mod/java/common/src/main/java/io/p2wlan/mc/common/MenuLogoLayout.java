package io.p2wlan.mc.common;

import java.util.List;
import java.util.Optional;

/** Adjacency only, never a detached screen-corner shortcut. If a Vanilla row
 * fills the viewport, reserve a 24px slot inside a >=100px navigation button. */
public final class MenuLogoLayout {
    public static final int SIZE = 20, GAP = 4;
    public record Rect(int x, int y, int width, int height) {
        public boolean overlaps(Rect other) { return x < other.x + other.width && x + width > other.x && y < other.y + other.height && y + height > other.y; }
    }
    public record Placement(Rect icon, int anchorWidth) {}
    private MenuLogoLayout() {}
    public static Optional<Placement> beside(Rect anchor, List<Rect> otherButtons, int width, int height) {
        if (anchor.height() != SIZE) return Optional.empty();
        for (int x : new int[]{anchor.x + anchor.width + GAP, anchor.x - SIZE - GAP}) {
            var icon = new Rect(x, anchor.y, SIZE, SIZE);
            if (fits(icon,otherButtons,width,height)) return Optional.of(new Placement(icon,anchor.width));
        }
        if (anchor.width >= 100) {
            var icon = new Rect(anchor.x + anchor.width - SIZE, anchor.y, SIZE, SIZE);
            if (fits(icon,otherButtons,width,height)) return Optional.of(new Placement(icon,anchor.width - SIZE - GAP));
        }
        return Optional.empty();
    }
    private static boolean fits(Rect icon,List<Rect> other,int width,int height) {
        return icon.x >= 4 && icon.y >= 4 && icon.x + SIZE <= width - 4 && icon.y + SIZE <= height - 4
                && other.stream().noneMatch(icon::overlaps);
    }
}
