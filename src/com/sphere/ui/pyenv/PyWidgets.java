package com.sphere.ui.pyenv;

import com.sphere.core.python.env.Pep440;
import com.sphere.core.python.env.PyAdvisor;
import com.sphere.core.python.env.PyEnv;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The drawn pieces of the Python manager, all in the theme's colours
 * (ThemePalette's Py keys): the coloured badges of an update or a problem,
 * the health gauge, the filter chips, and the table's renderers.
 */
public final class PyWidgets {

    private PyWidgets() {
    }

    static ThemePalette palette() {
        return ThemeManager.getCurrentPalette();
    }

    /** The colour of an update by how far it goes. */
    public static Color jumpColor(Pep440.Jump j) {
        final ThemePalette p = palette();
        return switch (j) {
            case MAJOR -> p.getPyMajor();
            case MINOR -> p.getPyMinor();
            case PATCH -> p.getPyPatch();
            case DOWNGRADE -> p.getPyDanger();
            case NONE -> p.getPyOk();
        };
    }

    public static Color severityColor(PyAdvisor.Severity s) {
        final ThemePalette p = palette();
        return switch (s) {
            case CRITICAL -> p.getPyDanger();
            case HIGH -> p.getPyMajor();
            case MEDIUM -> p.getPyMinor();
            case LOW -> p.getPyPatch();
            case INFO -> p.getPyInfo();
        };
    }

    /** Green above 80, amber above 50, red below. */
    public static Color scoreColor(int score) {
        final ThemePalette p = palette();
        return score >= 80 ? p.getPyOk() : score >= 50 ? p.getPyMinor() : p.getPyDanger();
    }

    /** A rounded badge with a word in it. */
    public static final class Badge extends JComponent {
        private String text = "";
        private Color color;

        public Badge(String text, Color color) {
            set(text, color);
            setFont(FontLoader.getGlobalFont(Font.BOLD, 10));
        }

        public void set(String text, Color color) {
            this.text = text == null ? "" : text;
            this.color = color;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            final FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(text.isEmpty() ? 0 : fm.stringWidth(text) + 14, fm.getHeight() + 4);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (text.isEmpty() || color == null) return;
            final Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final int h = Math.min(getHeight(), getPreferredSize().height);
            final int y = (getHeight() - h) / 2;
            g.setColor(color);
            g.fill(new RoundRectangle2D.Double(0, y, getWidth() - 1, h - 1, h, h));
            g.setColor(palette().getPyBadgeText());
            g.setFont(getFont());
            final FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    /** The health score as a ring that fills, its colour by the score. */
    public static final class Gauge extends JComponent {
        private int score = -1;

        public Gauge() {
            setPreferredSize(new Dimension(54, 54));
            setToolTipText("Health of the environment: vulnerabilities, conflicts, leftovers, updates");
        }

        public void setScore(int score) {
            this.score = score;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final int d = Math.min(getWidth(), getHeight()) - 8;
            final int x = (getWidth() - d) / 2;
            final int y = (getHeight() - d) / 2;
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(palette().getPyChip());
            g.drawOval(x, y, d, d);
            if (score >= 0) {
                g.setColor(scoreColor(score));
                g.draw(new Arc2D.Double(x, y, d, d, 90, -3.6 * score, Arc2D.OPEN));
            }
            g.setColor(palette().getTextPrimary());
            g.setFont(FontLoader.getGlobalFont(Font.BOLD, 14));
            final String s = score < 0 ? "…" : Integer.toString(score);
            final FontMetrics fm = g.getFontMetrics();
            g.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    /** A filter chip: a rounded toggle with a count. */
    public static final class Chip extends JToggleButton {
        private int count = -1;

        public Chip(String text) {
            super(text);
            setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(2, 10, 2, 10));
        }

        public void setCount(int count) {
            this.count = count;
            revalidate();
            repaint();
        }

        private String label() {
            return count < 0 ? getText() : getText() + "  " + count;
        }

        @Override
        public Dimension getPreferredSize() {
            final FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(label()) + 22, fm.getHeight() + 8);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final ThemePalette p = palette();
            final Color ground = isSelected() ? p.getPyChipSelected() : getModel().isRollover() ? p.getButtonHover() : p.getPyChip();
            g.setColor(ground);
            g.fill(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, getHeight() - 2, getHeight() - 2));
            g.setColor(isSelected() ? Color.WHITE : p.getTextPrimary());
            g.setFont(getFont());
            final FontMetrics fm = g.getFontMetrics();
            g.drawString(label(), (getWidth() - fm.stringWidth(label())) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Renderers                                                           */
    /* ------------------------------------------------------------------ */

    /** Striped rows, the selection kept; every cell of the table goes through it. */
    public static class Cell extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            final JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, false, row, col);
            l.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 6));
            l.setFont(t.getFont());
            if (!sel) {
                l.setBackground(row % 2 == 0 ? t.getBackground() : palette().getPyRowStripe());
                l.setForeground(palette().getTextPrimary());
            }
            l.setHorizontalAlignment(SwingConstants.LEFT);
            l.setToolTipText(null);
            return l;
        }
    }

    /** A cell with a coloured badge drawn in it, the text on it. */
    public static final class BadgeCell extends Cell {
        private Color badge;

        public void badge(Color c) {
            this.badge = c;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (badge == null) {
                super.paintComponent(g0);
                return;
            }
            final Graphics2D g = (Graphics2D) g0.create();
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final String text = getText();
            g.setFont(getFont().deriveFont(Font.BOLD));
            final FontMetrics fm = g.getFontMetrics();
            final int w = Math.min(getWidth() - 8, fm.stringWidth(text) + 14);
            final int h = Math.min(getHeight() - 6, fm.getHeight() + 2);
            final int y = (getHeight() - h) / 2;
            g.setColor(badge);
            g.fill(new RoundRectangle2D.Double(4, y, w, h, h, h));
            g.setColor(palette().getPyBadgeText());
            g.drawString(text, 4 + (w - fm.stringWidth(text)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    /** A dot of colour before the text: the state of a package at a glance. */
    public static final class DotCell extends Cell {
        private Color dot;

        public void dot(Color c) {
            this.dot = c;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (dot == null) return;
            final Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(dot);
            final int d = 9;
            g.fillOval((getWidth() - d) / 2, (getHeight() - d) / 2, d, d);
            g.dispose();
        }
    }

    /** The state of a package: red for a vulnerability, a conflict or a yanked version; colour of its update; green. */
    public static Color stateColor(PyEnv env, PyEnv.Pkg p) {
        final ThemePalette pal = palette();
        if (p.vulnerabilities() > 0 || p.yanked() || conflicted(env, p)) return pal.getPyDanger();
        if (p.outdated()) return jumpColor(p.jump);
        if (p.latest == null && p.project == null) return pal.getPyInfo();
        return pal.getPyOk();
    }

    public static boolean conflicted(PyEnv env, PyEnv.Pkg p) {
        for (PyEnv.Conflict c : env.conflicts) {
            if (Pep440.normalize(c.packageName()).equals(p.key) || c.dependency().equals(p.key)) return true;
        }
        return false;
    }
}
