package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemeMetrics;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import javax.swing.plaf.ComponentUI;

/**
 * Premium flat floating-pill ScrollBarUI.
 * Features auto-centering thumb math, dynamic state interpolation, and invisible tracks.
 */
public class SPScrollBarUI extends BasicScrollBarUI {

    private ThemePalette palette;

    public static ComponentUI createUI(JComponent c) {
        return new SPScrollBarUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        scrollbar.setOpaque(false);
        // Sync active workspace palette
        this.palette = ThemeManager.getCurrentPalette();
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }

        // Always resolve the real-time theme palette instance dynamically
        this.palette = ThemeManager.getCurrentPalette();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Pick dynamic interactive thumb states
        Color thumbColor;
        if (isDragging) {
            thumbColor = palette.getAccent();
        } else if (isThumbRollover()) {
            thumbColor = palette.getButtonHover();
        } else {
            thumbColor = palette.getScrollbarThumb();
        }

        g2.setColor(thumbColor);

        // MODERN VISUAL UPGRADE: Instead of painting the full rectangle width,
        // we isolate a thin pill floating dead-center in the track container.
        int thickness = 6; // Elegant thin pill thickness
        int arc = 6;       // Smooth completely rounded caps

        if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
            int xOffset = r.x + (r.width - thickness) / 2;
            g2.fillRoundRect(xOffset, r.y + 2, thickness, r.height - 4, arc, arc);
        } else {
            int yOffset = r.y + (r.height - thickness) / 2;
            g2.fillRoundRect(r.x + 2, yOffset, r.width - 4, thickness, arc, arc);
        }
                
        g2.dispose();
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        // Modern UI design principle: We keep the track completely clear and transparent.
        // The floating thumb handles the full layout presence beautifully on its own.
        // If your design demands a track line background, uncomment the block below:
        /*
        this.palette = ThemeManager.getCurrentPalette();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(palette.getBackgroundTrack());
        g2.fillRect(r.x, r.y, r.width, r.height);
        g2.dispose();
        */
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        // Safe check to avoid early initialization null pointers
        int thick = (ThemeMetrics.SCROLLBAR_THICKNESS > 0) ? ThemeMetrics.SCROLLBAR_THICKNESS : 12;
        
        if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
            return new Dimension(thick, 48);
        } else {
            return new Dimension(48, thick);
        }
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return createZeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return createZeroButton();
    }

    private JButton createZeroButton() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder());
        return b;
    }
}
