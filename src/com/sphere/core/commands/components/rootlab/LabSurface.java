package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

/**
 * A panel of the ROOT Lab that fills its own background from the theme, as
 * ImagingTheme.Surface does (that one is final): Nimbus never repaints a
 * colour set after it was installed, so the panel paints it itself.
 */
class LabSurface extends JPanel {

    private final boolean raised;

    LabSurface(LayoutManager layout, boolean raised) {
        super(layout);
        this.raised = raised;
        setOpaque(true);
        setBackground(raised ? ImagingTheme.surface() : ImagingTheme.panel());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(raised ? ImagingTheme.surface() : ImagingTheme.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }
}
