package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicMenuItemUI;
import java.awt.*;

/**
 * Premium flat-styled CheckBox Menu Item UI manager.
 * Implements clean vector boundary indicators synchronized with the active theme profile.
 */
public class SPCheckBoxMenuItemUI extends BasicMenuItemUI {

    private ThemePalette palette;

    public static ComponentUI createUI(JComponent c) {
        return new SPCheckBoxMenuItemUI();
    }

    @Override
    public void installUI(JComponent c) {
        this.palette = ThemeManager.getCurrentPalette();
        super.installUI(c);
        
        if (c instanceof JMenuItem) {
            JMenuItem menuItem = (JMenuItem) c;
            menuItem.setOpaque(false);
            menuItem.setForeground(palette.getTextPrimary());
            // Shift the text label rightwards to give our custom vector box its space
            menuItem.setBorder(BorderFactory.createEmptyBorder(6, 32, 6, 8));
        }
    }

    @Override
    protected void paintBackground(Graphics g, JMenuItem menuItem, Color bgColor) {
        this.palette = ThemeManager.getCurrentPalette();
        ButtonModel model = menuItem.getModel();
        
        // Render background highlight fills cleanly if the user hovers over the item
        if (model.isArmed() || (menuItem instanceof JCheckBoxMenuItem && model.isSelected())) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(palette.getButtonHover());
            g2.fillRect(0, 0, menuItem.getWidth(), menuItem.getHeight());
            g2.dispose();
        }
    }

    @Override
    protected void paintText(Graphics g, JMenuItem menuItem, Rectangle textRect, String text) {
        this.palette = ThemeManager.getCurrentPalette();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        g2.setFont(menuItem.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(palette.getTextPrimary());
        
        // Get natural vertical alignment metrics
        FontMetrics fm = g2.getFontMetrics();
        int y = (menuItem.getHeight() - fm.getHeight()) / 2 + fm.getAscent();
        
        // Draw the text string following the left-padded border baseline
        g2.drawString(text, 32, y);
        g2.dispose();
    }

    @Override
    public void update(Graphics g, JComponent c) {
        // Enforce full vector rendering pipeline flow loops
        paintBackground(g, (JMenuItem) c, null);
        paintText(g, (JMenuItem) c, null, ((JMenuItem) c).getText());
        paintCheckIconBorder(g, (JMenuItem) c);
    }

    /**
     * Renders the custom light gray perimeter container box and choice indicator vectors.
     */
    private void paintCheckIconBorder(Graphics g, JMenuItem menuItem) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color lightGray = palette.getTextLightGray();
        Color accentBlue = palette.getTerminalSelection();

        int boxSize = 12;
        int x = 10; 
        int y = (menuItem.getHeight() - boxSize) / 2;

        // Step 1: Draw the outer box rim framework in light gray
        g2.setColor(lightGray);
        g2.drawRect(x, y, boxSize, boxSize);

        // Step 2: Fill the box with accent selection coloring if active
        if (menuItem.isSelected()) {
            g2.setColor(accentBlue);
            g2.fillRect(x + 2, y + 2, boxSize - 3, boxSize - 3);
        }

        g2.dispose();
    }
}