package com.sphere.theme;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;

public class FlatButtonUI extends BasicButtonUI {

    // Required by Swing's UIManager instantiation matrix
    public static ComponentUI createUI(JComponent c) {
        return new FlatButtonUI();
    }

    @Override
    public void installDefaults(AbstractButton b) {
        super.installDefaults(b);
        b.setOpaque(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);

        // --- DYNAMIC ADAPTIVE PADDING ---
        if (b.getMargin() == null || b.getMargin().top == 0) {
            boolean hasText = b.getText() != null && !b.getText().isEmpty();
            boolean hasIcon = b.getIcon() != null;

            if (hasText && !hasIcon) {
                // Regular text-only buttons get breathing room
                b.setMargin(new Insets(4, 10, 4, 10));
            } else if (hasText && hasIcon) {
                // Mixed buttons get standard tight scaling
                b.setMargin(new Insets(3, 6, 3, 6));
            } else {
                // Pure icon buttons (like table cell actions) get zero padding to prevent "..."
                b.setMargin(new Insets(0, 0, 0, 0));
            }
        }
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        AbstractButton b = (AbstractButton) c;
        FontMetrics fm = c.getFontMetrics(b.getFont());
        
        int width = 0;
        int height = 0;

        // 1. Calculate text size
        String text = b.getText();
        if (text != null && !text.isEmpty()) {
            width += fm.stringWidth(text);
            height = Math.max(height, fm.getHeight());
        }

        // 2. Calculate icon size
        Icon icon = b.getIcon();
        if (icon != null) {
            width += icon.getIconWidth();
            height = Math.max(height, icon.getIconHeight());
            if (text != null && !text.isEmpty()) {
                width += b.getIconTextGap();
            }
        }

        // 3. ADD INTENTIONAL BUTTON PADDING
        // We add 20px total padding (10px left, 10px right) for text buttons
        // to prevent labels from hugging the edges.
        if (text != null && !text.isEmpty()) {
            width += 20; 
            height += 8; // 4px top, 4px bottom
        } else {
            // Square bounds for icon-only table actions
            width += 6;
            height += 6;
        }

        return new Dimension(width, height);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        Graphics2D g2 = (Graphics2D) g.create();
        
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        boolean isEnabled = b.isEnabled();
        Color bg;

        // 1. BACKGROUND HANDLING
        if (c.getParent() instanceof JPanel && c.getParent().getParent() instanceof JTable) {
            bg = c.getParent().getBackground();
        } else {
            ThemePalette palette = ThemeManager.getCurrentPalette();
            if (!isEnabled) {
                bg = palette.getButtonBase();
            } else {
                boolean hovered = b.getModel().isRollover();
                boolean pressed = b.getModel().isPressed();

                bg = pressed ? palette.getButtonPressed()
                             : hovered ? palette.getButtonHover()
                                       : palette.getButtonBase();
            }
        }

        g2.setColor(bg);
        g2.fillRect(0, 0, c.getWidth(), c.getHeight());

        // 2. ICON RENDERING (IF PRESENT)
        int iconWidth = 0;
        int iconGap = 0;
        if (b.getIcon() != null) {
            iconWidth = b.getIcon().getIconWidth();
            iconGap = b.getText() != null && !b.getText().isEmpty() ? b.getIconTextGap() : 0;
            
            // Vertically center the icon
            int iconX = (b.getText() == null || b.getText().isEmpty()) 
                        ? (c.getWidth() - iconWidth) / 2 
                        : 6; // Standard left alignment when text is present
            int iconY = (c.getHeight() - b.getIcon().getIconHeight()) / 2;

            if (!isEnabled) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
            }
            b.getIcon().paintIcon(c, g2, iconX, iconY);
            if (!isEnabled) {
                g2.setComposite(AlphaComposite.SrcOver);
            }
        }

        // 3. FORCE REAL-TEXT DRAWING
        String rawText = b.getText();
        if (rawText != null && !rawText.isEmpty()) {
            g2.setFont(b.getFont());
            FontMetrics fm = g2.getFontMetrics(b.getFont());

            // Directly calculate centering without asking Swing's layout if it fits
            int textWidth = fm.stringWidth(rawText);
            int textX;
            
            if (iconWidth > 0) {
                // Balance space alongside the icon
                textX = iconWidth + iconGap + ((c.getWidth() - iconWidth - iconGap - textWidth) / 2);
                if (textX < iconWidth + iconGap) textX = iconWidth + iconGap + 2; // Fail-safe
            } else {
                // Pure centered text text calculation
                textX = (c.getWidth() - textWidth) / 2;
            }
            
            int textY = (c.getHeight() + fm.getAscent() - fm.getDescent()) / 2;

            if (!isEnabled) {
                g2.setColor(new Color(110, 113, 118));
            } else {
                g2.setColor(b.getForeground());
            }

            g2.drawString(rawText, textX, textY);
        }

        g2.dispose();
    }
}
