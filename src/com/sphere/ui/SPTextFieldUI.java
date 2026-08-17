package com.sphere.ui;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Premium flat-styled TextField UI manager.
 * Features rounded canvas geometry, padded inner margins, and interactive focus states.
 */
public class SPTextFieldUI extends BasicTextFieldUI {

    private ThemePalette palette;
    private boolean isHovered = false;
    private boolean isFocused = false;

    // Listeners stored to clean up references safely on uninstallcycles
    private MouseAdapter mouseListener;
    private FocusListener focusListener;

    public static ComponentUI createUI(JComponent c) {
        return new SPTextFieldUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        this.palette = ThemeManager.getCurrentPalette();

        javax.swing.text.JTextComponent field = getComponent();
        field.setOpaque(false); 
        
        field.setForeground(palette.getTextPrimary());
        field.setCaretColor(palette.getAccent());
        
        field.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        javax.swing.text.JTextComponent field = getComponent();

        mouseListener = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { isHovered = true; field.repaint(); }
            @Override public void mouseExited(MouseEvent e) { isHovered = false; field.repaint(); }
        };
        field.addMouseListener(mouseListener);

        focusListener = new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) { isFocused = true; field.repaint(); }
            @Override
            public void focusLost(FocusEvent e) { isFocused = false; field.repaint(); }
        };
        field.addFocusListener(focusListener);
    }

    @Override
    protected void uninstallListeners() {
        super.uninstallListeners();
        javax.swing.text.JTextComponent field = getComponent();
        if (mouseListener != null) field.removeMouseListener(mouseListener);
        if (focusListener != null) field.removeFocusListener(focusListener);
    }

    @Override
    public void update(Graphics g, JComponent c) {
        this.palette = ThemeManager.getCurrentPalette();
        javax.swing.text.JTextComponent field = (javax.swing.text.JTextComponent) c;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = field.getWidth();
        int height = field.getHeight();
        int arc = 8; 

        g2.setColor(palette.getBackgroundTrack());
        g2.fillRoundRect(0, 0, width, height, arc, arc);

        if (isFocused) {
            g2.setColor(palette.getAccent());
            g2.setStroke(new BasicStroke(1.5f));
        } else if (isHovered) {
            g2.setColor(palette.getButtonHover());
            g2.setStroke(new BasicStroke(1.0f));
        } else {
            g2.setColor(palette.getBorder());
            g2.setStroke(new BasicStroke(1.0f));
        }
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
        g2.dispose();

        paint(g, c);
    }
}
