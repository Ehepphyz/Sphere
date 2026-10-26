package com.sphere.components.vault;

import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.imaging.ImagingTheme;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemePalette;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/**
 * The vault's colors, type and glyphs, all taken from the active palette at
 * paint time, so a theme switched while Sphere runs reaches the tab too.
 * The glyphs are drawn rather than loaded: sharp at any scale, in any color.
 */
final class VaultLook {

    private VaultLook() {
    }

    // ---- colors ---------------------------------------------------------------------

    static ThemePalette palette() {
        return ImagingTheme.palette();
    }

    static Color panel() {
        return ImagingTheme.panel();
    }

    static Color surface() {
        return ImagingTheme.surface();
    }

    static Color text() {
        return ImagingTheme.text();
    }

    static Color muted() {
        return ImagingTheme.subduedText();
    }

    static Color faint() {
        return mix(muted(), panel(), 0.45f);
    }

    static Color accent() {
        return ImagingTheme.accent();
    }

    static Color border() {
        return ImagingTheme.border();
    }

    static Color hover() {
        return mix(text(), panel(), 0.06f);
    }

    static Color selection() {
        return mix(accent(), panel(), 0.22f);
    }

    static Color field() {
        return ImagingTheme.isDark() ? shift(panel(), -0.18f) : Color.WHITE;
    }

    static Color warning() {
        return palette().getPyMinor();
    }

    static Color error() {
        return palette().getPyDanger();
    }

    static Color info() {
        return palette().getPyInfo();
    }

    static Color ok() {
        return palette().getPyOk();
    }

    static Color severity(VaultAnalysis.Severity severity) {
        if (severity == null) return muted();
        return switch (severity) {
            case ERROR -> error();
            case WARNING -> warning();
            case INFO -> info();
        };
    }

    /** The color a family is known by: the editor's language colors, ROOT its own blue. */
    static Color family(String family) {
        return switch (family) {
            case "ROOT" -> new Color(42, 147, 214);
            case "H" -> EditorTheme.languageColor(LanguageSpec.CPP).darker();
            case "TXT" -> muted();
            default -> {
                for (LanguageSpec spec : LanguageSpec.all()) {
                    if (spec.tag.equals(family)) yield EditorTheme.languageColor(spec);
                }
                yield muted();
            }
        };
    }

    static Color mix(Color a, Color b, float t) {
        final float u = 1f - t;
        return new Color(
            clamp(a.getRed() * t + b.getRed() * u),
            clamp(a.getGreen() * t + b.getGreen() * u),
            clamp(a.getBlue() * t + b.getBlue() * u));
    }

    static Color shift(Color c, float amount) {
        if (amount >= 0) return mix(Color.WHITE, c, amount);
        return mix(Color.BLACK, c, -amount);
    }

    static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    static Color onColor(Color fill) {
        return EditorTheme.onColor(fill);
    }

    // ---- type ------------------------------------------------------------------------

    static Font ui(int style, float size) {
        return FontLoader.getGlobalFont(style, Math.round(size)).deriveFont(style, size);
    }

    static Font mono(float size) {
        return FontLoader.getTerminalFont(Font.PLAIN, Math.round(size)).deriveFont(size);
    }

    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** The text cut to a width, with an ellipsis when it had to be. */
    static String fit(String text, FontMetrics fm, int width) {
        if (text == null) return "";
        if (fm.stringWidth(text) <= width) return text;
        final String dots = "...";
        int n = text.length();
        while (n > 0 && fm.stringWidth(text.substring(0, n)) + fm.stringWidth(dots) > width) n--;
        return n <= 0 ? "" : text.substring(0, n).stripTrailing() + dots;
    }

    /** "now", "5m", "3h", "2d", "4w", then the date. */
    static String ago(long millis) {
        if (millis <= 0) return "";
        final long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return "now";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        if (s < 86400L * 14) return s / 86400 + "d";
        if (s < 86400L * 70) return s / (86400 * 7) + "w";
        return new java.text.SimpleDateFormat("MMM d", Locale.ENGLISH).format(new java.util.Date(millis));
    }

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
    }

    // ---- shapes ------------------------------------------------------------------------

    /** The language badge, a rounded tag in its family's color. Returns its width. */
    static int badge(Graphics2D g, String family, int x, int y, int height) {
        final Color fill = family(family);
        final Font font = ui(Font.BOLD, 9f);
        g.setFont(font);
        final FontMetrics fm = g.getFontMetrics();
        final int w = Math.max(height + 6, fm.stringWidth(family) + 10);
        g.setColor(alpha(fill, ImagingTheme.isDark() ? 64 : 46));
        g.fill(new RoundRectangle2D.Float(x, y, w, height, 6, 6));
        g.setColor(alpha(fill, 200));
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, height - 1, 6, 6));
        g.setColor(ImagingTheme.isDark() ? shift(fill, 0.25f) : shift(fill, -0.25f));
        g.drawString(family, x + (w - fm.stringWidth(family)) / 2f, y + (height + fm.getAscent() - fm.getDescent()) / 2f);
        return w;
    }

    // ---- glyphs ----------------------------------------------------------------------------

    static Icon glyph(String name, int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                final Graphics2D g = (Graphics2D) graphics.create();
                try {
                    antialias(g);
                    g.translate(x, y);
                    g.setColor(color);
                    draw(g, name, size);
                } finally {
                    g.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    static void draw(Graphics2D g, String name, float s) {
        g.setStroke(new BasicStroke(Math.max(1.2f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (name) {
            case "search" -> {
                g.draw(new Ellipse2D.Float(s * 0.14f, s * 0.14f, s * 0.52f, s * 0.52f));
                g.draw(new Line2D.Float(s * 0.58f, s * 0.58f, s * 0.86f, s * 0.86f));
            }
            case "plus" -> {
                g.draw(new Line2D.Float(s * 0.5f, s * 0.2f, s * 0.5f, s * 0.8f));
                g.draw(new Line2D.Float(s * 0.2f, s * 0.5f, s * 0.8f, s * 0.5f));
            }
            case "more" -> {
                for (int i = 0; i < 3; i++) {
                    g.fill(new Ellipse2D.Float(s * (0.18f + i * 0.27f), s * 0.43f, s * 0.14f, s * 0.14f));
                }
            }
            case "window", "window-open" -> {
                // A window with its title bar; open, the bar is filled.
                g.draw(new RoundRectangle2D.Float(s * 0.12f, s * 0.18f, s * 0.76f, s * 0.64f, s * 0.12f, s * 0.12f));
                if (name.equals("window-open")) {
                    g.fill(new RoundRectangle2D.Float(s * 0.12f, s * 0.18f, s * 0.76f, s * 0.2f, s * 0.12f, s * 0.12f));
                } else {
                    g.draw(new Line2D.Float(s * 0.12f, s * 0.36f, s * 0.88f, s * 0.36f));
                }
            }
            case "close" -> {
                g.draw(new Line2D.Float(s * 0.28f, s * 0.28f, s * 0.72f, s * 0.72f));
                g.draw(new Line2D.Float(s * 0.72f, s * 0.28f, s * 0.28f, s * 0.72f));
            }
            case "play" -> {
                final Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.30f, s * 0.18f);
                p.lineTo(s * 0.82f, s * 0.5f);
                p.lineTo(s * 0.30f, s * 0.82f);
                p.closePath();
                g.fill(p);
            }
            case "star", "star-filled" -> {
                final Path2D.Float p = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    final double a = -Math.PI / 2 + i * Math.PI / 5;
                    final double r = (i % 2 == 0 ? 0.44 : 0.19) * s;
                    final double px = s * 0.5 + r * Math.cos(a);
                    final double py = s * 0.53 + r * Math.sin(a);
                    if (i == 0) p.moveTo(px, py);
                    else p.lineTo(px, py);
                }
                p.closePath();
                if (name.equals("star-filled")) g.fill(p);
                else g.draw(p);
            }
            case "warning" -> {
                final Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.5f, s * 0.12f);
                p.lineTo(s * 0.92f, s * 0.86f);
                p.lineTo(s * 0.08f, s * 0.86f);
                p.closePath();
                g.draw(p);
                g.draw(new Line2D.Float(s * 0.5f, s * 0.40f, s * 0.5f, s * 0.60f));
                g.fill(new Ellipse2D.Float(s * 0.45f, s * 0.68f, s * 0.1f, s * 0.1f));
            }
            case "error" -> {
                g.draw(new Ellipse2D.Float(s * 0.12f, s * 0.12f, s * 0.76f, s * 0.76f));
                g.draw(new Line2D.Float(s * 0.36f, s * 0.36f, s * 0.64f, s * 0.64f));
                g.draw(new Line2D.Float(s * 0.64f, s * 0.36f, s * 0.36f, s * 0.64f));
            }
            case "info" -> {
                g.draw(new Ellipse2D.Float(s * 0.12f, s * 0.12f, s * 0.76f, s * 0.76f));
                g.draw(new Line2D.Float(s * 0.5f, s * 0.46f, s * 0.5f, s * 0.70f));
                g.fill(new Ellipse2D.Float(s * 0.45f, s * 0.28f, s * 0.1f, s * 0.1f));
            }
            case "twin" -> {
                g.draw(new RoundRectangle2D.Float(s * 0.14f, s * 0.14f, s * 0.48f, s * 0.48f, s * 0.12f, s * 0.12f));
                g.draw(new RoundRectangle2D.Float(s * 0.38f, s * 0.38f, s * 0.48f, s * 0.48f, s * 0.12f, s * 0.12f));
            }
            case "edit" -> {
                g.draw(new Line2D.Float(s * 0.22f, s * 0.78f, s * 0.70f, s * 0.30f));
                g.draw(new Line2D.Float(s * 0.62f, s * 0.22f, s * 0.78f, s * 0.38f));
                g.draw(new Line2D.Float(s * 0.18f, s * 0.84f, s * 0.34f, s * 0.80f));
            }
            case "compose" -> {
                g.draw(new Line2D.Float(s * 0.18f, s * 0.30f, s * 0.40f, s * 0.50f));
                g.draw(new Line2D.Float(s * 0.40f, s * 0.50f, s * 0.18f, s * 0.70f));
                g.draw(new Line2D.Float(s * 0.50f, s * 0.74f, s * 0.84f, s * 0.74f));
            }
            case "folder" -> {
                final Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.12f, s * 0.28f);
                p.lineTo(s * 0.40f, s * 0.28f);
                p.lineTo(s * 0.48f, s * 0.38f);
                p.lineTo(s * 0.88f, s * 0.38f);
                p.lineTo(s * 0.88f, s * 0.80f);
                p.lineTo(s * 0.12f, s * 0.80f);
                p.closePath();
                g.draw(p);
            }
            case "plot" -> {
                g.draw(new Line2D.Float(s * 0.16f, s * 0.14f, s * 0.16f, s * 0.84f));
                g.draw(new Line2D.Float(s * 0.16f, s * 0.84f, s * 0.88f, s * 0.84f));
                final Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.26f, s * 0.70f);
                p.lineTo(s * 0.44f, s * 0.42f);
                p.lineTo(s * 0.60f, s * 0.56f);
                p.lineTo(s * 0.82f, s * 0.24f);
                g.draw(p);
            }
            case "args" -> {
                g.draw(new Line2D.Float(s * 0.30f, s * 0.18f, s * 0.18f, s * 0.5f));
                g.draw(new Line2D.Float(s * 0.18f, s * 0.5f, s * 0.30f, s * 0.82f));
                g.draw(new Line2D.Float(s * 0.70f, s * 0.18f, s * 0.82f, s * 0.5f));
                g.draw(new Line2D.Float(s * 0.82f, s * 0.5f, s * 0.70f, s * 0.82f));
                g.fill(new Ellipse2D.Float(s * 0.44f, s * 0.44f, s * 0.12f, s * 0.12f));
            }
            case "layer" -> {
                final Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.5f, s * 0.18f);
                p.lineTo(s * 0.88f, s * 0.38f);
                p.lineTo(s * 0.5f, s * 0.58f);
                p.lineTo(s * 0.12f, s * 0.38f);
                p.closePath();
                g.draw(p);
                g.draw(new Line2D.Float(s * 0.12f, s * 0.60f, s * 0.5f, s * 0.80f));
                g.draw(new Line2D.Float(s * 0.5f, s * 0.80f, s * 0.88f, s * 0.60f));
            }
            default -> g.draw(new Ellipse2D.Float(s * 0.2f, s * 0.2f, s * 0.6f, s * 0.6f));
        }
    }

    // ---- controls -----------------------------------------------------------------------------

    /** A flat button that paints its own rounded background. */
    static class FlatButton extends JButton {
        private final boolean primary;

        FlatButton(String text, Icon icon, String tip, boolean primary) {
            super(text, icon);
            this.primary = primary;
            setToolTipText(tip);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(ui(primary ? Font.BOLD : Font.PLAIN, 11.5f));
            setIconTextGap(5);
            setBorder(BorderFactory.createEmptyBorder(5, text == null || text.isEmpty() ? 6 : 10, 5,
                text == null || text.isEmpty() ? 6 : 10));
        }

        @Override
        public void updateUI() {
            // Sphere's SPButtonUI draws a box and a fill; this button draws its
            // own, so it keeps Swing's plain UI for its size and its states.
            setUI(new javax.swing.plaf.basic.BasicButtonUI());
            flat(this);
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                antialias(g);
                final boolean over = getModel().isRollover() && isEnabled();
                final boolean down = getModel().isPressed() && isEnabled();
                Color fill;
                if (primary) {
                    fill = isEnabled() ? accent() : mix(accent(), panel(), 0.35f);
                    if (over) fill = shift(fill, 0.10f);
                    if (down) fill = shift(fill, -0.12f);
                } else {
                    fill = down ? mix(text(), panel(), 0.16f) : over ? mix(text(), panel(), 0.10f) : null;
                }
                if (fill != null) {
                    g.setColor(fill);
                    g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
                }
                // The icon and the text, laid out as a label would lay them.
                final java.awt.Rectangle view = new java.awt.Rectangle();
                final Insets in = getInsets();
                view.setBounds(in.left, in.top, getWidth() - in.left - in.right, getHeight() - in.top - in.bottom);
                final java.awt.Rectangle iconR = new java.awt.Rectangle();
                final java.awt.Rectangle textR = new java.awt.Rectangle();
                g.setFont(getFont());
                final FontMetrics fm = g.getFontMetrics();
                final String text = getText() == null ? "" : getText();
                final String shown = javax.swing.SwingUtilities.layoutCompoundLabel(this, fm, text, getIcon(),
                    getVerticalAlignment(), getHorizontalAlignment(), getVerticalTextPosition(),
                    getHorizontalTextPosition(), view, iconR, textR, text.isEmpty() ? 0 : getIconTextGap());
                if (getIcon() != null) getIcon().paintIcon(this, g, iconR.x, iconR.y);
                if (!text.isEmpty()) {
                    final Color own = getForeground();
                    final Color fg = own == null || own instanceof javax.swing.plaf.UIResource ? text() : own;
                    g.setColor(primary ? onColor(accent()) : isEnabled() ? fg : faint());
                    g.drawString(shown, textR.x, textR.y + fm.getAscent());
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        protected void paintBorder(Graphics g) {
            // ThemeManager outlines every button when the theme changes; this one has none.
        }
    }

    static FlatButton button(String text, String glyph, String tip, boolean primary) {
        final Color tint = primary ? onColor(accent()) : muted();
        return new FlatButton(text, glyph == null ? null : glyph(glyph, 13, tint), tip, primary);
    }

    /** A pill that can be turned on and off. */
    static class Chip extends JToggleButton {
        Color dot;
        String glyph;

        Chip(String text, Color dot, String glyph, String tip) {
            super(text);
            this.dot = dot;
            this.glyph = glyph;
            setToolTipText(tip);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(ui(Font.PLAIN, 10.5f));
            setMargin(new Insets(0, 0, 0, 0));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            flat(this);
            setRolloverEnabled(true);
        }

        @Override
        public Dimension getPreferredSize() {
            final FontMetrics fm = getFontMetrics(getFont());
            final int lead = dot != null || glyph != null ? 14 : 0;
            return new Dimension(fm.stringWidth(getText()) + 16 + lead, 20);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintBorder(Graphics g) {
            // The pill is its own outline.
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                antialias(g);
                final boolean on = isSelected();
                final boolean over = getModel().isRollover();
                final Color base = dot != null ? dot : accent();
                final int w = getWidth();
                final int h = getHeight();
                g.setColor(on ? alpha(base, ImagingTheme.isDark() ? 70 : 50) : over ? hover() : alpha(text(), 0));
                g.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h, h));
                g.setColor(on ? alpha(base, 210) : mix(text(), panel(), 0.18f));
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h, h));
                int x = 8;
                if (glyph != null) {
                    final Graphics2D gg = (Graphics2D) g.create();
                    gg.translate(x - 1, (h - 11) / 2);
                    gg.setColor(dot != null ? dot : on ? text() : muted());
                    draw(gg, glyph, 11);
                    gg.dispose();
                    x += 14;
                } else if (dot != null) {
                    g.setColor(dot);
                    g.fill(new Ellipse2D.Float(x, h / 2f - 3.5f, 7, 7));
                    x += 14;
                }
                g.setFont(getFont());
                g.setColor(on ? text() : muted());
                final FontMetrics fm = g.getFontMetrics();
                g.drawString(getText(), x, (h + fm.getAscent() - fm.getDescent()) / 2f);
            } finally {
                g.dispose();
            }
        }
    }

    /** A text field with a ghost text and its own flat frame. */
    static class Field extends JTextField {
        String ghost;
        String glyph;
        boolean invalid;

        Field(String ghost) {
            this.ghost = ghost;
            setOpaque(false);
            setFont(ui(Font.PLAIN, 12f));
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            com.sphere.components.ClipboardBridge.install(this);
            restyle();
        }

        void restyle() {
            setForeground(text());
            setCaretColor(accent());
            setSelectionColor(selection());
            setSelectedTextColor(text());
        }

        void setGlyph(String glyph) {
            this.glyph = glyph;
            setBorder(BorderFactory.createEmptyBorder(5, glyph == null ? 8 : 26, 5, 8));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            // Called by the constructor too, before the fields are set.
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(5, glyph == null ? 8 : 26, 5, 8));
            restyle();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                antialias(g);
                final int w = getWidth();
                final int h = getHeight();
                g.setColor(field());
                g.fill(new RoundRectangle2D.Float(0, 0, w, h, 8, 8));
                g.setColor(invalid ? error() : isFocusOwner() ? mix(accent(), panel(), 0.8f) : mix(text(), panel(), 0.14f));
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 8, 8));
                if (glyph != null) {
                    final Graphics2D gg = (Graphics2D) g.create();
                    gg.translate(8, (h - 13) / 2);
                    gg.setColor(muted());
                    draw(gg, glyph, 13);
                    gg.dispose();
                }
            } finally {
                g.dispose();
            }
            // The text, the selection and the caret alone: Nimbus's update would
            // paint its own field over the one drawn above.
            getUI().paint(graphics, this);
            if (getText().isEmpty() && ghost != null && !ghost.isEmpty()) {
                final Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    antialias(g2);
                    g2.setColor(faint());
                    g2.setFont(getFont().deriveFont(Font.ITALIC));
                    final Insets in = getInsets();
                    final FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(fit(ghost, fm, getWidth() - in.left - in.right),
                        in.left, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    /**
     * A FlowLayout whose preferred height is that of the rows it actually lays
     * out, so a row of chips wraps instead of running off a narrow side panel.
     */
    static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return size(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            final Dimension d = size(target, false);
            d.width -= getHgap() + 1;
            return d;
        }

        private Dimension size(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int width = target.getSize().width;
                Container c = target;
                while (width == 0 && c.getParent() != null) {
                    c = c.getParent();
                    width = c.getSize().width;
                }
                if (width == 0) width = Integer.MAX_VALUE;
                final Insets in = target.getInsets();
                final int max = width - (in.left + in.right + getHgap() * 2);
                int rowW = 0, rowH = 0, w = 0, h = 0;
                for (Component m : target.getComponents()) {
                    if (!m.isVisible()) continue;
                    final Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowW + d.width > max && rowW > 0) {
                        w = Math.max(w, rowW);
                        h += rowH + getVgap();
                        rowW = 0;
                        rowH = 0;
                    }
                    if (rowW != 0) rowW += getHgap();
                    rowW += d.width;
                    rowH = Math.max(rowH, d.height);
                }
                w = Math.max(w, rowW);
                h += rowH;
                return new Dimension(w + in.left + in.right + getHgap() * 2,
                    h + in.top + in.bottom + getVgap() * 2);
            }
        }
    }

    /**
     * Text that wraps and paints only what it is asked to: its own background
     * when it has one, then the text. Nimbus otherwise lays its field color
     * under every text area, whatever setOpaque says.
     */
    static class Text extends javax.swing.JTextArea {
        private final Color fill;

        Text(String text, Font font, Color color, Color fill) {
            super(text);
            this.fill = fill;
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setOpaque(false);
            setFont(font);
            setForeground(color);
            setBorder(BorderFactory.createEmptyBorder());
            setAlignmentX(Component.LEFT_ALIGNMENT);
            // A label that never scrolls its own view when its text changes.
            ((javax.swing.text.DefaultCaret) getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        }

        private int maxLines;

        /** Shows at most this many lines; the rest is in the tooltip. */
        Text limit(int lines) {
            this.maxLines = lines;
            setToolTipText(getText());
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            final Dimension d = super.getPreferredSize();
            if (maxLines > 0) {
                final Insets in = getInsets();
                d.height = Math.min(d.height, maxLines * getFontMetrics(getFont()).getHeight() + in.top + in.bottom);
            }
            return d;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (fill != null) {
                final Graphics2D g = (Graphics2D) graphics.create();
                try {
                    antialias(g);
                    g.setColor(fill);
                    g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
                } finally {
                    g.dispose();
                }
            }
            getUI().paint(graphics, this);
        }
    }

    /**
     * A scroll pane that leaves its ground to what is behind it. ThemeDefaults
     * gives every viewport and scroll pane a background painter, which Nimbus
     * runs whether or not the component is opaque, so neither paints here.
     */
    static final class ClearScroll extends javax.swing.JScrollPane {
        ClearScroll(Component view, int vertical, int horizontal) {
            super(view, vertical, horizontal);
            setOpaque(false);
            getViewport().setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            getVerticalScrollBar().setUnitIncrement(16);
        }

        @Override
        protected javax.swing.JViewport createViewport() {
            return new javax.swing.JViewport() {
                @Override
                protected void paintComponent(Graphics g) {
                    // The panel behind shows through.
                }
            };
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Nothing of its own: the panel behind shows through.
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setOpaque(false);
            if (getViewport() != null) getViewport().setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
        }
    }

    /**
     * The content of a vertical scroll: as wide as the view, as tall as it
     * needs. A plain panel would be laid out at its preferred width, and a
     * wrapping text measured before it had a width would push the fields past
     * the right edge.
     */
    static final class Column extends javax.swing.JPanel implements javax.swing.Scrollable {
        Column(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    static void flat(AbstractButton b) {
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
    }

    static <T extends JComponent> T transparent(T c) {
        c.setOpaque(false);
        return c;
    }
}
