package com.sphere.theme;

import javax.swing.*;
import java.awt.*;

/**
 * Factory for theme-aware icons (arrows, tree expand/collapse, etc.).
 */
public final class ThemeIcons {

    private ThemeIcons() {}

    public static Icon createTreeArrowIcon(Color color, boolean expanded) {
        return new TreeArrowIcon(color, expanded);
    }

    private static class TreeArrowIcon implements Icon {
        private final Color color;
        private final boolean expanded;

        TreeArrowIcon(Color color, boolean expanded) {
            this.color = color;
            this.expanded = expanded;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);

            int size = 6;
            int offset = 5;

            if (expanded) {
                int[] xs = {x + offset, x + size + offset, x + (size / 2) + offset};
                int[] ys = {y + offset + 1, y + offset + 1, y + size + offset - 1};
                g2.fillPolygon(xs, ys, 3);
            } else {
                int[] xs = {x + offset + 1, x + size + offset - 1, x + offset + 1};
                int[] ys = {y + offset, y + (size / 2) + offset, y + size + offset};
                g2.fillPolygon(xs, ys, 3);
            }

            g2.dispose();
        }

        @Override public int getIconWidth()  { return 16; }
        @Override public int getIconHeight() { return 16; }
    }
}

