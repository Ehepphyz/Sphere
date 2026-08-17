package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;

/**
 * Premium flat-styled ComboBox UI manager.
 * Features elegant vector rendering, customized rounded geometry, and isolated hover tracking.
 */
public class SPComboBoxUI extends BasicComboBoxUI {

    private ThemePalette palette;
    private boolean isHovered = false;

    public static ComponentUI createUI(JComponent c) {
        return new SPComboBoxUI();
    }

    @Override
    public void installUI(JComponent c) {
        this.palette = ThemeManager.getCurrentPalette();
        super.installUI(c);

        if (comboBox != null) {
            comboBox.setOpaque(false);
            comboBox.setBackground(palette.getBackgroundSurface());
            comboBox.setForeground(palette.getTextPrimary());
            // Clean empty padding border so the background geometry can be manually painted instead
            comboBox.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 4));

            // Attach clean instance hover tracking to the primary component frame
            comboBox.addMouseListener(new MouseAdapter() {
                @Override 
                public void mouseEntered(MouseEvent e) { 
                    isHovered = true; 
                    e.getComponent().repaint(); 
                }
                @Override 
                public void mouseExited(MouseEvent e) { 
                    isHovered = false; 
                    e.getComponent().repaint(); 
                }
            });

            // Modern customized item renderer
            comboBox.setRenderer(new ListCellRenderer<Object>() {
                private final JLabel label = new JLabel();

                {
                    label.setOpaque(true);
                    label.setBorder(new EmptyBorder(6, 12, 6, 12));
                    label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
                }

                @Override
                public Component getListCellRendererComponent(
                        JList<? extends Object> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {

                    label.setText(value == null ? "" : value.toString());

                    // Resolve background colors contextually using safe internal client properties
                    Integer internalHover = (Integer) list.getClientProperty("popupHoverIndex");
                    boolean itemHovered = (internalHover != null && internalHover == index);

                    if (isSelected) {
                        label.setBackground(palette.getButtonPressed());
                        label.setForeground(palette.getTextPrimary());
                    } else if (itemHovered) {
                        label.setBackground(palette.getButtonHover());
                        label.setForeground(palette.getTextPrimary());
                    } else {
                        label.setBackground(palette.getBackgroundSurface());
                        label.setForeground(palette.getTextPrimary());
                    }

                    return label;
                }
            });
        }
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        // Enforce active palette sync before rendering pipeline starts
        this.palette = ThemeManager.getCurrentPalette();
        
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = c.getWidth();
        int height = c.getHeight();
        int arc = 8; // Modern curve geometry

        // Fill background area smoothly
        g2.setColor(isHovered ? palette.getButtonHover() : palette.getBackgroundSurface());
        g2.fillRoundRect(0, 0, width, height, arc, arc);

        // Draw flat modern outline border rim
        g2.setColor(isHovered ? palette.getButtonHover().brighter() : palette.getBorder());
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);

        g2.dispose();

        // Let standard Swing layers map child hierarchy views
        //super.paint(g, c);
        Rectangle bounds = rectangleForCurrentValue();

        g.setColor(palette.getBackgroundSurface());
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

        paintCurrentValue(g, bounds, false);
    }

    @Override
    protected ComboPopup createPopup() {
        return new BasicComboPopup(comboBox) {
            @Override
            protected void configurePopup() {
                super.configurePopup();
                if (scroller != null) {
                    scroller.setBorder(BorderFactory.createEmptyBorder());
                    scroller.setViewportBorder(BorderFactory.createEmptyBorder());
                    scroller.setOpaque(false);
                    scroller.getViewport().setOpaque(false);
                }
                // Wrap the drop down panel window structure in a modern flat frame border
                setBorder(BorderFactory.createLineBorder(palette != null ? palette.getBorder() : Color.GRAY, 1));
                setBackground(palette != null ? palette.getBackgroundSurface() : Color.BLACK);
            }

            @Override
            protected MouseMotionListener createListMouseMotionListener() {
                return new MouseAdapter() {
                    @Override public void mouseDragged(MouseEvent e) { updateHoverIndex(e); }
                    @Override public void mouseMoved(MouseEvent e) { updateHoverIndex(e); }

                    private void updateHoverIndex(MouseEvent e) {
                        int index = list.locationToIndex(e.getPoint());
                        // Thread-safe hover setting via custom client property mapping instead of forceful index manipulation
                        list.putClientProperty("popupHoverIndex", index);
                        list.repaint();
                    }
                };
            }

            @Override
            protected JList<Object> createList() {
                JList<Object> customList = super.createList();
                customList.setOpaque(false);
                customList.setBackground(palette != null ? palette.getBackgroundSurface() : Color.DARK_GRAY);
                customList.setSelectionBackground(palette != null ? palette.getButtonPressed() : Color.GRAY);
                customList.setSelectionForeground(palette != null ? palette.getTextPrimary() : Color.WHITE);
                customList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

                // Detach aggressive focus rectangle rings
                customList.putClientProperty("List.focusCellHighlightBorder", BorderFactory.createEmptyBorder());
                
                // Add list exit tracking to clean mouse hover states seamlessly
                customList.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseExited(MouseEvent e) {
                        customList.putClientProperty("popupHoverIndex", -1);
                        customList.repaint();
                    }
                });

                return customList;
            }
        };
    }

    @Override
    protected JButton createArrowButton() {
        // Beautiful procedural geometry button replacement instead of raw string characters
        JButton button = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                
                // Draw a sleek custom chevron arrow vector centered inside the button bounds
                g2.setColor(palette != null ? palette.getTextPrimary() : Color.WHITE);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                
                int cx = w / 2;
                int cy = h / 2 + 1;
                int size = 3;

                g2.drawLine(cx - size, cy - size + 1, cx, cy + 1);
                g2.drawLine(cx, cy + 1, cx + size, cy - size + 1);

                g2.dispose();
            }
        };

        button.setBorder(BorderFactory.createEmptyBorder());
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setPreferredSize(new Dimension(28, 28));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        return button;
    }
}
