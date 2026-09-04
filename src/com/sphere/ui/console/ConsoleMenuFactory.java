package com.sphere.ui.console;

import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JComponent;
import javax.swing.JSeparator;
import javax.swing.Painter;
import javax.swing.UIDefaults;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Every painted piece of the console context menu, in one place. All colours come
 * from the active ThemePalette, so changing a palette entry moves the whole menu
 * with it.
 */
public final class ConsoleMenuFactory {

    private static final int ARC = 8;
    private static final int TEXT_X = 12;
    private static final int CHECK_X = 31;

    private ConsoleMenuFactory() {
    }

    private static ThemePalette palette() {
        return ThemeManager.getCurrentPalette();
    }

    /** Rounded popup surface shared by the root menu and every submenu. */
    public static JPopupMenu popup() {
        JPopupMenu popup = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ThemePalette p = palette();
                if (p != null) {
                    g2.setColor(p.getPopupBackground());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
                    g2.setColor(p.getPopupBorder());
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
                }
                g2.dispose();
            }
        };
        popup.setOpaque(false);
        popup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return popup;
    }

    /**
     * A clickable entry. settings.conf states that a disabled target renders in
     * muted italics, so the painter needs the disabled branch the previous one
     * lacked: without it setEnabled(false) changed behaviour but not appearance.
     */
    public static JMenuItem item(String text) {
        JMenuItem item = new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                paintEntry(g, this, getModel(), getText(), isEnabled(), false, TEXT_X);
            }
        };
        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, TEXT_X, 6, TEXT_X));
        return item;
    }

    /** A submenu header, painted like an item plus the chevron on the right. */
    public static JMenu submenu(String text) {
        JMenu menu = new JMenu(text) {
            @Override
            protected void paintComponent(Graphics g) {
                paintEntry(g, this, getModel(), getText(), isEnabled(), true, TEXT_X);
            }
        };
        menu.setOpaque(false);
        menu.setBorder(BorderFactory.createEmptyBorder(6, TEXT_X, 6, TEXT_X));
        // A JMenu carries its own popup, which would otherwise be the platform one.
        stylePopupOf(menu);
        return menu;
    }

    /**
     * A submenu carries its own popup, which Swing creates for it. It cannot be
     * replaced, so it is given the popup colours directly: left alone it fell back
     * to the look and feel's surface grey, a visibly different shade from the menu
     * it hangs off.
     */
    private static void stylePopupOf(JMenu menu) {
        JPopupMenu inner = menu.getPopupMenu();
        inner.setOpaque(true);
        inner.setBackground(palette().getPopupBackground());
        inner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(palette().getPopupBorder(), 1),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        // Nimbus paints popups through its own painter and ignores setBackground,
        // which is why the submenu came out in the look and feel's surface grey
        // while the menu above it used the palette's popup colour. Nimbus.Overrides
        // is the documented way to redirect that painter for one component.
        UIDefaults overrides = new UIDefaults();
        overrides.put("PopupMenu[Enabled].backgroundPainter",
            (Painter<JComponent>) (g2, c, w, h) -> {
                g2.setColor(palette().getPopupBackground());
                g2.fillRect(0, 0, w, h);
            });
        inner.putClientProperty("Nimbus.Overrides", overrides);
        inner.putClientProperty("Nimbus.Overrides.InheritDefaults", Boolean.TRUE);
    }

    /**
     * Toggle entry. Painted here rather than through a UI delegate so it shares the
     * font, the highlight and the disabled italics of every other entry: the
     * delegate filled its whole row whenever the box was ticked, which read as a
     * permanent hover on a menu whose default state is ticked.
     */
    public static JCheckBoxMenuItem check(String text) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                paintEntry(g, this, getModel(), getText(), isEnabled(), false, CHECK_X);
                paintCheckBox(g, this, isSelected(), isEnabled());
            }
        };
        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, CHECK_X, 6, TEXT_X));
        return item;
    }

    private static void paintCheckBox(Graphics g, Component owner,
                                      boolean selected, boolean enabled) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ThemePalette p = palette();
        int size = 11;
        int x = TEXT_X;
        int y = (owner.getHeight() - size) / 2;

        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(enabled ? p.getTextSecondary() : p.getPopupBorder());
        g2.drawRoundRect(x, y, size, size, 3, 3);

        if (selected) {
            g2.setColor(enabled ? p.getAccent() : p.getTextSecondary());
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawLine(x + 2, y + size / 2, x + size / 2 - 1, y + size - 3);
            g2.drawLine(x + size / 2 - 1, y + size - 3, x + size - 2, y + 3);
        }
        g2.dispose();
    }

    /**
     * A non-clickable line reporting state. It is a disabled item on purpose: it
     * then reads in the same muted tone as anything else that cannot be acted on.
     */
    public static JMenuItem status(String text) {
        JMenuItem item = item(text);
        item.setEnabled(false);
        return item;
    }

    public static JSeparator separator() {
        return new JSeparator() {
            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(palette().getPopupBorder());
                g2.drawLine(6, 0, getWidth() - 6, 0);
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(0, 9);
            }
        };
    }

    private static void paintEntry(Graphics g, Component owner, ButtonModel model,
                                   String text, boolean enabled, boolean chevron, int textX) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        ThemePalette p = palette();
        int w = owner.getWidth();
        int h = owner.getHeight();

        // A submenu reports itself selected while its popup is open, and that band is
        // wanted. A ticked checkbox also reports selected, and that band is not: it
        // would sit there permanently on a toggle that defaults to on.
        boolean highlighted = enabled && (model.isArmed()
            || (model.isSelected() && !(owner instanceof JCheckBoxMenuItem)));
        if (highlighted) {
            g2.setColor(p.getButtonPressed());
            g2.fillRoundRect(4, 2, w - 8, h - 4, 6, 6);
        }

        g2.setColor(!enabled ? p.getTextSecondary()
                             : highlighted ? p.getTextWhite() : p.getTextPrimary());
        g2.setFont(FontLoader.getGlobalFont(enabled ? Font.PLAIN : Font.ITALIC, 12));

        FontMetrics fm = g2.getFontMetrics();
        int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, textX, textY);

        if (chevron) {
            int cx = w - 14;
            int cy = h / 2;
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawLine(cx, cy - 4, cx + 4, cy);
            g2.drawLine(cx + 4, cy, cx, cy + 4);
        }
        g2.dispose();
    }

    /**
     * Widens a popup to its widest label. The painter draws the text itself, so the
     * layout manager sees an empty item and would otherwise clip every line.
     */
    public static void fitWidth(JPopupMenu popup) {
        Font font = FontLoader.getGlobalFont(Font.PLAIN, 12);
        FontMetrics fm = popup.getFontMetrics(font);
        int widest = 0;
        for (Component c : popup.getComponents()) {
            if (c instanceof JMenuItem && c.isVisible()) {
                widest = Math.max(widest, fm.stringWidth(((JMenuItem) c).getText()));
            }
        }
        if (widest == 0) {
            return;
        }
        int target = widest + CHECK_X + TEXT_X + 16;
        popup.setPreferredSize(new Dimension(target, popup.getPreferredSize().height));
        popup.revalidate();
    }
}
