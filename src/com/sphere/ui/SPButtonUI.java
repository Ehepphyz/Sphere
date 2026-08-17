package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.ComponentUI;

/**
 * Premium flat-styled button UI wrapper.
 * Features customizable corner geometry, multi-state color blending, 
 * and explicit context styling for accent actions.
 */
public class SPButtonUI extends BasicButtonUI {

    private ThemePalette palette;
    private MouseAdapter hoverListener;
    
    // Client property key to easily flag primary/accent buttons across the application
    public static final String IS_PRIMARY_KEY = "SPButton.isPrimary";

    public static ComponentUI createUI(JComponent c) {
        return new SPButtonUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        this.palette = ThemeManager.getCurrentPalette();

        AbstractButton b = (AbstractButton) c;
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Use empty borders to give padding, leaving background and frame geometry to the paint layer
        b.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
    }

    @Override
    protected void installListeners(AbstractButton b) {
        super.installListeners(b);
        hoverListener = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.repaint(); }
            @Override public void mouseExited(MouseEvent e) { b.repaint(); }
            @Override public void mousePressed(MouseEvent e) { b.repaint(); }
            @Override public void mouseReleased(MouseEvent e) { b.repaint(); }
        };
        b.addMouseListener(hoverListener);
    }

    @Override
    protected void uninstallListeners(AbstractButton b) {
        super.uninstallListeners(b);
        if (hoverListener != null) {
            b.removeMouseListener(hoverListener);
        }
    }

    @Override
    public void update(Graphics g, JComponent c) {
        // Refresh palette on update cycles to ensure real-time theme swapping support
        this.palette = ThemeManager.getCurrentPalette();
        paint(g, c);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        Graphics2D g2 = (Graphics2D) g.create();
        
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        ButtonModel m = b.getModel();
        boolean isPrimary = Boolean.TRUE.equals(c.getClientProperty(IS_PRIMARY_KEY));
        
        int width = c.getWidth();
        int height = c.getHeight();
        int arc = 10; // High-end premium corner radius matching modern desktop standards

        // 1. Resolve background color state architecture
        Color bg;
        if (isPrimary) {
            // Contextual brand accent states (e.g., modern blue accents)
            bg = m.isPressed() && m.isArmed() ? new Color(14, 85, 166) :
                 m.isRollover()               ? new Color(25, 115, 215) : new Color(16, 100, 194);
        } else {
            // Standard generic fallback palette states
            bg = m.isPressed() && m.isArmed() ? palette.getButtonPressed() :
                 m.isRollover()               ? palette.getButtonHover()   : palette.getButtonBase();
        }

        // Fill background canvas safely with crisp antialiased rounded rectangles
        g2.setColor(bg);
        g2.fillRoundRect(0, 0, width, height, arc, arc);

        // 2. Draw border line profile
        if (isPrimary) {
            g2.setColor(new Color(255, 255, 255, 30));
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
        } else {
            g2.setColor(palette.getBorder());
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
        }

        g2.dispose();

        // 3. Setup typography forecolor pipelines contextually
        if (!b.isEnabled()) {
            b.setForeground(palette.getTextPrimary().darker());
        } else if (isPrimary) {
            b.setForeground(Color.WHITE); // Primary focus targets require clean high-contrast text layers
        } else {
            b.setForeground(palette.getTextPrimary());
        }

        super.paint(g, c);
    }
}
