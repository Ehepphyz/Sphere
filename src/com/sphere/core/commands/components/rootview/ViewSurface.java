package com.sphere.components.rootview;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

/**
 * A panel that paints its own background.
 *
 * The look and feel derives its painter colors when it is installed, so a
 * background set afterwards never reaches them; painting here is what keeps the
 * viewer the same shade as the rest of Sphere.
 */
class ViewSurface extends JPanel {

    private final boolean raised;

    ViewSurface(LayoutManager layout, boolean raised) {
        super(layout);
        this.raised = raised;
        setOpaque(true);
        setBackground(raised ? ImagingTheme.surface() : ImagingTheme.panel());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(raised ? ImagingTheme.surface() : ImagingTheme.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }
}
