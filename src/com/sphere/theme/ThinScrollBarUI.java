package com.sphere.components.ui;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import com.sphere.theme.ThemeMetrics;

public class ThinScrollBarUI extends BasicScrollBarUI {

    // This is the factory method Swing calls to instantiate the UI
    public static ComponentUI createUI(JComponent c) {
        return new ThinScrollBarUI();
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        // Force the thickness defined in your ThemeMetrics
        if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
            return new Dimension(ThemeMetrics.SCROLLBAR_THICKNESS, 48);
        } else {
            return new Dimension(48, ThemeMetrics.SCROLLBAR_THICKNESS);
        }
    }
    
    // IMPORTANT: BasicScrollBarUI requires these overrides to prevent 
    // the UI from trying to paint with Nimbus assets that don't exist in Basic
    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        g.setColor(com.sphere.theme.ThemeManager.getCurrentPalette().getBackgroundSurface());
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
        g.setColor(com.sphere.theme.ThemeManager.getCurrentPalette().getButtonPressed());
        g.fillRect(thumbBounds.x, thumbBounds.y, thumbBounds.width, thumbBounds.height);
    }
    
    // Optional: Hide the increase/decrease buttons (the arrows) to make it look "Flat"
    @Override
    protected JButton createDecreaseButton(int orientation) { return createZeroButton(); }
    @Override
    protected JButton createIncreaseButton(int orientation) { return createZeroButton(); }

    private JButton createZeroButton() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        return b;
    }
}
