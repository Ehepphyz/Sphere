package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.theme.ThemeIcons;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Premium flat-styled TreeUI manager.
 * Removes legacy hierarchical hash lines and structures modern spacing paradigms.
 */
public class SPTreeUI extends BasicTreeUI {

    private ThemePalette palette;

    public static ComponentUI createUI(JComponent c) {
        return new SPTreeUI();
    }

    @Override
    protected void installDefaults() {
        this.palette = ThemeManager.getCurrentPalette();
        super.installDefaults();

        // 1. ELIMINATE CLUTTER: Explicitly disable old-school dotted guide lines
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.setOpaque(false);
        
        // Give tree rows professional breathing room vertical spacing
        setRowHeight(24);

        // Map vector-drawn chevron arrows from your icons framework
        setExpandedIcon(ThemeIcons.createTreeArrowIcon(palette.getTextPrimary(), true));
        setCollapsedIcon(ThemeIcons.createTreeArrowIcon(palette.getTextPrimary(), false));

        // Adjust default cell renderer colors to blend seamlessly into backgrounds
        if (tree.getCellRenderer() instanceof DefaultTreeCellRenderer) {
            DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) tree.getCellRenderer();
            renderer.setOpaque(false);
            renderer.setBackgroundNonSelectionColor(new Color(0, 0, 0, 0));
            renderer.setTextNonSelectionColor(palette.getTextPrimary());
            renderer.setTextSelectionColor(palette.getTextPrimary());
            renderer.setBackgroundSelectionColor(palette.getButtonPressed());
            renderer.setBorderSelectionColor(new Color(0, 0, 0, 0)); // Strip thin border ring
        }

        // Attach safe inline hover tracking to trigger clean row repaints
        tree.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                // SAFETY CHECK: Prevent crash if Swing detaches the tree during UI updates
                if (tree == null) {
                    return;
                }

                int row = tree.getRowForLocation(e.getX(), e.getY());
                Object currentHover = tree.getClientProperty("treeHoverRow");
                if (currentHover == null || (int) currentHover != row) {
                    tree.putClientProperty("treeHoverRow", row);
                    tree.repaint();
                }
            }
        });

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                // SAFETY CHECK: Prevent crash if mouse exits while component is unbinding
                if (tree != null) {
                    tree.putClientProperty("treeHoverRow", -1);
                    tree.repaint();
                }
            }
        });
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        // Enforce active palette validation on every paint pass
        this.palette = ThemeManager.getCurrentPalette();
        
        // Let the engine draw full-width selection backgrounds behind nodes
        Graphics2D g2 = (Graphics2D) g.create();
        int width = c.getWidth();
        Integer hoverRow = (Integer) tree.getClientProperty("treeHoverRow");

        // Loop through visible rows to render custom background layers
        int rowCount = tree.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            Rectangle bounds = tree.getRowBounds(i);
            if (bounds != null && g.getClipBounds().intersects(bounds)) {
                if (tree.isRowSelected(i)) {
                    g2.setColor(palette.getButtonPressed());
                    g2.fillRect(0, bounds.y, width, bounds.height);
                } else if (hoverRow != null && hoverRow == i) {
                    g2.setColor(palette.getButtonHover());
                    g2.fillRect(0, bounds.y, width, bounds.height);
                }
            }
        }
        g2.dispose();

        // Let standard Swing render text labels and expanded icons on top
        super.paint(g, c);
    }

    // Explicitly bypass drawing vertical or horizontal grid hash links completely
    @Override
    protected void paintHorizontalLine(Graphics g, JComponent c, int y, int left, int right) {}

    @Override
    protected void paintVerticalLine(Graphics g, JComponent c, int x, int top, int bottom) {}
}
