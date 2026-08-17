package com.sphere.theme;

import javax.swing.*;
import java.awt.*;

/**
 * Central painter implementations used by the theme engine.
 * All painters are flat and avoid gradients or bevels.
 * * Note: These painters strictly implement the standard javax.swing.Painter 
 * interface to prevent ClassCastException errors within the Nimbus framework.
 */
public final class ThemePainters {

    private ThemePainters() {}

    public static class FlatBackgroundPainter implements javax.swing.Painter<JComponent> {
        private final Color color;
        
        public FlatBackgroundPainter(Color color) { 
            this.color = color; 
        }
        
        @Override
        public void paint(Graphics2D g2, JComponent c, int w, int h) {
            g2.setColor(color);
            g2.fillRect(0, 0, w, h);
        }
    }

    public static class FlatBorderPainter implements javax.swing.Painter<JComponent> {
        private final Color color;
        
        public FlatBorderPainter(Color color) { 
            this.color = color; 
        }
        
        @Override
        public void paint(Graphics2D g2, JComponent c, int w, int h) {
            g2.setColor(color);
            g2.drawRect(0, 0, w - 1, h - 1);
        }
    }

    public static class ActiveTabPainter implements javax.swing.Painter<JComponent> {
        private final Color background;
        private final Color accent;
        
        public ActiveTabPainter(Color background, Color accent) {
            this.background = background;
            this.accent = accent;
        }
        
        @Override
        public void paint(Graphics2D g2, JComponent c, int w, int h) {
            g2.setColor(background);
            g2.fillRect(0, 0, w, h);
            g2.setColor(accent);
            g2.fillRect(0, h - ThemeMetrics.TAB_UNDERLINE_HEIGHT, w, ThemeMetrics.TAB_UNDERLINE_HEIGHT);
        }
    }

    public static class RectThumbPainter implements javax.swing.Painter<JComponent> {
        private final Color color;
        
        public RectThumbPainter(Color color) { 
            this.color = color; 
        }
        
        @Override
        public void paint(Graphics2D g2, JComponent c, int w, int h) {
            g2.setColor(color);
            g2.fillRect(0, 0, w, h);
        }
    }

    public static class TrackPainter implements javax.swing.Painter<JComponent> {
        private final Color color;
        
        public TrackPainter(Color color) { 
            this.color = color; 
        }
        
        @Override
        public void paint(Graphics2D g2, JComponent c, int w, int h) {
            g2.setColor(color);
            g2.fillRect(0, 0, w, h);
        }
    }
}
