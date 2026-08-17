package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Premium flat-styled TabbedPane UI manager.
 * Features an animated accent underline indicator, clean hover transitions, 
 * and zeroed legacy structural borders.
 */
public class SPTabbedPaneUI extends BasicTabbedPaneUI {

    private ThemePalette palette;
    private int hoverTabIndex = -1;

    public static ComponentUI createUI(JComponent c) {
        return new SPTabbedPaneUI();
    }

    @Override
    public void installUI(JComponent c) {
        this.palette = ThemeManager.getCurrentPalette();
        super.installUI(c);
        
        // Remove text clipping and focus ring artifacts completely
        tabPane.setFocusable(false);
        tabPane.setOpaque(false);

        // Attach safe inline hover tracking to individual tabs
        tabPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                // SAFETY CHECK: Prevent crash if Swing unbinds components dynamically
                if (tabPane != null) {
                    hoverTabIndex = -1;
                    tabPane.repaint();
                }
            }
        });
        
        tabPane.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                // SAFETY CHECK: Ensure tabPane context remains valid during mouse movement
                if (tabPane != null) {
                    hoverTabIndex = tabForCoordinate(tabPane, e.getX(), e.getY());
                    tabPane.repaint();
                }
            }
        });
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // Give tabs some elegant modern breathing room padding
        tabInsets = new Insets(8, 16, 8, 16);
        selectedTabPadInsets = new Insets(0, 0, 0, 0);
    }

    @Override
    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
        // Intentionally left empty - eliminates legacy bounding frames completely
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
        // Intentionally left empty - eliminates individual hard edges
    }

    @Override
    protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex, Rectangle iconRect, Rectangle textRect, boolean isSelected) {
        // Intentionally left empty - destroys old dotted focus rings
    }

    @Override
    protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
        this.palette = ThemeManager.getCurrentPalette();
        
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Render soft hover background block if targeted
        if (hoverTabIndex == tabIndex && !isSelected) {
            g2.setColor(palette.getButtonHover());
            g2.fillRoundRect(x + 2, y + 4, w - 4, h - 6, 6, 6);
        }

        if (isSelected) {
            int underlineThickness = 3; // Slimmer, premium aesthetic stroke width
            
            g2.setColor(palette.getAccent());
            
            // MATH FIX: Shift the rendering downward by 1 pixel to align exactly at the bottom threshold
            // of the tab panel boundary, bypassing internal layout row overlaps.
            g2.fillRect(x, y + h - underlineThickness + 1, w, underlineThickness);
        }

        g2.dispose();
    }

    @Override
    protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics, int tabIndex, String title, Rectangle textRect, boolean isSelected) {
        this.palette = ThemeManager.getCurrentPalette();
        
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Premium Typography Shift: Make selected text slightly bolded or highly crisp
        if (isSelected) {
            g2.setFont(font.deriveFont(Font.BOLD));
            g2.setColor(palette.getTextPrimary());
        } else if (hoverTabIndex == tabIndex) {
            g2.setFont(font.deriveFont(Font.PLAIN));
            g2.setColor(palette.getTextPrimary());
        } else {
            g2.setFont(font.deriveFont(Font.PLAIN));
            // De-emphasize unselected headers safely using your theme's muted secondary color context
            g2.setColor(palette.getTextPrimary().darker());
        }

        // Handle text positioning inside the layout rect bounds
        int textX = textRect.x;
        int textY = textRect.y + metrics.getAscent();
        g2.drawString(title, textX, textY);

        g2.dispose();
    }
}
