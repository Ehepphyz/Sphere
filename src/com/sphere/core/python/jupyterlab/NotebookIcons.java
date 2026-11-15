package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.SemanticStyle;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.AlphaComposite;
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
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The notebook's glyphs, drawn from shapes rather than loaded from pixels: sharp at
 * any scale, in the theme's colors read at paint time (a theme switch recolors them),
 * dimmed when their button is disabled. A badge (a plus, a count) is cut out of the
 * glyph it sits on, so both stay readable where they overlap.
 */
final class NotebookIcons {

    private NotebookIcons() {
    }

    /** What a glyph's color says. */
    enum Tone { NORMAL, MUTED, RUN, STOP, STALE, ACCENT, INFO }

    static ThemePalette palette() {
        return ThemeManager.getCurrentPalette();
    }

    static boolean dark() {
        return luminance(palette().getBackgroundSurface()) < 0.5;
    }

    static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
    }

    static Color mix(Color a, Color b, float t) {
        float u = 1f - t;
        return new Color(Math.round(a.getRed() * u + b.getRed() * t),
                         Math.round(a.getGreen() * u + b.getGreen() * t),
                         Math.round(a.getBlue() * u + b.getBlue() * t));
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** Text that reads on a fill of this color. */
    static Color onColor(Color fill) {
        return luminance(fill) > 0.6 ? new Color(30, 30, 32) : Color.WHITE;
    }

    /** A tone's color, lifted on dark surfaces where the palette's own would sink into the background. */
    static Color color(Tone tone) {
        ThemePalette p = palette();
        boolean dark = dark();
        return switch (tone) {
            case NORMAL -> p.getTextPrimary();
            case MUTED -> p.getTextSecondary();
            case RUN -> dark ? mix(p.getLogSuccessPrefix(), Color.WHITE, 0.30f) : p.getLogSuccessPrefix();
            case STOP -> dark ? mix(p.getError(), Color.WHITE, 0.12f) : p.getError();
            case STALE -> SemanticStyle.staleColor();
            case ACCENT -> dark ? mix(p.getAccent(), new Color(120, 180, 255), 0.62f) : p.getAccent();
            case INFO -> dark ? mix(p.getLogInfoPrefix(), Color.WHITE, 0.18f) : p.getLogInfoPrefix();
        };
    }

    // ---- Glyphs ------------------------------------------------------------

    /** Paints in a box of side s at the origin, in the current color and stroke. */
    interface Painter {
        void paint(Graphics2D g, float s);
    }

    /** A mark over a glyph's corner: cut out of the glyph, then drawn. */
    interface Badge {
        boolean visible();

        /** Its outline, in the glyph's box. */
        Shape shape(Graphics2D g, float s);

        void paint(Graphics2D g, float s);
    }

    /** One glyph and its badge, drawn at the device's resolution. */
    static final class Glyph implements Icon {
        private final int size;
        private final Supplier<Tone> tone;
        private final Painter painter;
        private final Badge badge;

        Glyph(int size, Supplier<Tone> tone, Painter painter, Badge badge) {
            this.size = size;
            this.tone = tone;
            this.painter = painter;
            this.badge = badge;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D target = (Graphics2D) g;
            double scale = Math.max(1.0, Math.abs(target.getTransform().getScaleX()));
            int px = (int) Math.ceil(size * scale);
            BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            ig.scale(px / (double) size, px / (double) size);
            ig.setColor(color(tone.get()));
            ig.setStroke(new BasicStroke(Math.max(1.3f, size / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            painter.paint(ig, size);
            if (badge != null && badge.visible()) {
                Shape outline = badge.shape(ig, size);
                ig.setComposite(AlphaComposite.Clear);
                ig.setStroke(new BasicStroke(size * 0.13f));
                ig.fill(outline);
                ig.draw(outline);
                ig.setComposite(AlphaComposite.SrcOver);
                badge.paint(ig, size);
            }
            ig.dispose();
            Graphics2D g2 = (Graphics2D) g.create();
            if (c != null && !c.isEnabled()) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.36f));
            }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, x, y, size, size, null);
            g2.dispose();
        }
    }

    private static Path2D triangle(float s, float... xy) {
        Path2D p = new Path2D.Float();
        p.moveTo(xy[0] * s, xy[1] * s);
        for (int i = 2; i < xy.length; i += 2) {
            p.lineTo(xy[i] * s, xy[i + 1] * s);
        }
        p.closePath();
        return p;
    }

    /** A filled shape with rounded corners: the fill, then its own outline in a round join. */
    private static void soft(Graphics2D g, Shape shape) {
        g.fill(shape);
        g.draw(shape);
    }

    /** A plus in a disc at the bottom right: something is added. */
    private static Badge plus() {
        return new Badge() {
            @Override
            public boolean visible() {
                return true;
            }

            @Override
            public Shape shape(Graphics2D g, float s) {
                float r = s * 0.25f;
                return new Ellipse2D.Float(s * 0.75f - r, s * 0.75f - r, 2 * r, 2 * r);
            }

            @Override
            public void paint(Graphics2D g, float s) {
                Color fill = color(Tone.ACCENT);
                g.setColor(fill);
                g.fill(shape(g, s));
                g.setColor(onColor(fill));
                g.setStroke(new BasicStroke(Math.max(1.2f, s * 0.085f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float c = s * 0.75f;
                float a = s * 0.12f;
                g.draw(new Line2D.Float(c - a, c, c + a, c));
                g.draw(new Line2D.Float(c, c - a, c, c + a));
            }
        };
    }

    /** A count in a pill at the top right, while there is one. */
    private static Badge count(IntSupplier count, Supplier<Tone> tone) {
        return new Badge() {
            @Override
            public boolean visible() {
                return count.getAsInt() > 0;
            }

            private String text() {
                int n = count.getAsInt();
                return n > 99 ? "99+" : Integer.toString(n);
            }

            private Font font(float s) {
                return FontLoader.getGlobalFont(Font.BOLD, 10).deriveFont(Font.BOLD, s * 0.50f);
            }

            @Override
            public Shape shape(Graphics2D g, float s) {
                FontMetrics fm = g.getFontMetrics(font(s));
                float h = s * 0.54f;
                float w = Math.max(h, fm.stringWidth(text()) + s * 0.22f);
                return new RoundRectangle2D.Float(s - w, 0, w, h, h, h);
            }

            @Override
            public void paint(Graphics2D g, float s) {
                Color fill = color(tone.get());
                Shape pill = shape(g, s);
                g.setColor(fill);
                g.fill(pill);
                g.setFont(font(s));
                FontMetrics fm = g.getFontMetrics();
                java.awt.geom.Rectangle2D b = pill.getBounds2D();
                String t = text();
                float tx = (float) (b.getCenterX() - fm.stringWidth(t) / 2.0);
                float ty = (float) (b.getCenterY() + (fm.getAscent() - fm.getDescent()) / 2.0);
                g.setColor(onColor(fill));
                g.drawString(t, tx, ty);
            }
        };
    }

    /** "</>" and a plus: a code cell, added. */
    static Icon addCode(int size) {
        return new Glyph(size, () -> Tone.NORMAL, (g, s) -> {
            Path2D p = new Path2D.Float();
            p.moveTo(0.27f * s, 0.12f * s);
            p.lineTo(0.06f * s, 0.35f * s);
            p.lineTo(0.27f * s, 0.58f * s);
            p.moveTo(0.53f * s, 0.12f * s);
            p.lineTo(0.74f * s, 0.35f * s);
            p.lineTo(0.53f * s, 0.58f * s);
            g.draw(p);
            g.draw(new Line2D.Float(0.46f * s, 0.06f * s, 0.34f * s, 0.64f * s));
        }, plus());
    }

    /** Lines of a paragraph and a plus: a Markdown cell, added. */
    static Icon addText(int size) {
        return new Glyph(size, () -> Tone.NORMAL, (g, s) -> {
            g.draw(new Line2D.Float(0.08f * s, 0.14f * s, 0.80f * s, 0.14f * s));
            g.draw(new Line2D.Float(0.08f * s, 0.34f * s, 0.80f * s, 0.34f * s));
            g.draw(new Line2D.Float(0.08f * s, 0.54f * s, 0.42f * s, 0.54f * s));
            g.draw(new Line2D.Float(0.08f * s, 0.74f * s, 0.30f * s, 0.74f * s));
        }, plus());
    }

    /** Two play triangles: every cell. */
    static Icon runAll(int size) {
        return new Glyph(size, () -> Tone.RUN, (g, s) -> {
            g.setStroke(new BasicStroke(Math.max(1f, s / 16f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            soft(g, triangle(s, 0.08f, 0.22f, 0.50f, 0.50f, 0.08f, 0.78f));
            soft(g, triangle(s, 0.50f, 0.22f, 0.92f, 0.50f, 0.50f, 0.78f));
        }, null);
    }

    /** A play triangle and how many cells are out of date: amber while some are. */
    static Icon runStale(int size, IntSupplier stale) {
        Supplier<Tone> tone = () -> stale.getAsInt() > 0 ? Tone.STALE : Tone.NORMAL;
        return new Glyph(size, tone, (g, s) -> {
            g.setStroke(new BasicStroke(Math.max(1f, s / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            soft(g, triangle(s, 0.12f, 0.24f, 0.70f, 0.57f, 0.12f, 0.90f));
        }, count(stale, tone));
    }

    /** A square: red while a cell runs, quiet otherwise. */
    static Icon stop(int size, BooleanSupplier busy) {
        return new Glyph(size, () -> busy.getAsBoolean() ? Tone.STOP : Tone.MUTED,
            (g, s) -> g.fill(new RoundRectangle2D.Float(0.22f * s, 0.22f * s, 0.56f * s, 0.56f * s, 0.2f * s, 0.2f * s)),
            null);
    }

    /** A circle that comes round to an arrow, clockwise: start over. */
    static Icon restart(int size) {
        return new Glyph(size, () -> Tone.NORMAL, (g, s) -> {
            float cx = 0.50f * s;
            float cy = 0.52f * s;
            float r = 0.31f * s;
            g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 66, 264, Arc2D.OPEN));
            double t = Math.toRadians(60);
            float px = (float) (cx + r * Math.cos(t));
            float py = (float) (cy - r * Math.sin(t));
            float tx = (float) Math.sin(t);    // clockwise tangent
            float ty = (float) Math.cos(t);
            float nx = (float) Math.cos(t);    // outward normal
            float ny = (float) -Math.sin(t);
            float h = 0.25f * s;
            float w = 0.17f * s;
            Path2D head = new Path2D.Float();
            head.moveTo(px + tx * h, py + ty * h);
            head.lineTo(px + nx * w, py + ny * w);
            head.lineTo(px - nx * w, py - ny * w);
            head.closePath();
            g.setStroke(new BasicStroke(Math.max(1f, s / 16f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            soft(g, head);
        }, null);
    }

    /** A funnel: the cells one cell needs, and no others. */
    static Icon gather(int size, Supplier<Tone> tone) {
        return new Glyph(size, tone, (g, s) -> {
            Path2D f = new Path2D.Float();
            f.moveTo(0.10f * s, 0.16f * s);
            f.lineTo(0.90f * s, 0.16f * s);
            f.lineTo(0.59f * s, 0.52f * s);
            f.lineTo(0.59f * s, 0.84f * s);
            f.lineTo(0.41f * s, 0.74f * s);
            f.lineTo(0.41f * s, 0.52f * s);
            f.closePath();
            Color line = g.getColor();
            g.setColor(alpha(line, 60));
            g.fill(f);
            g.setColor(line);
            g.draw(f);
        }, null);
    }

    /** A light bulb: an idea for the error above. */
    static Icon doctor(int size) {
        return new Glyph(size, () -> Tone.ACCENT, (g, s) -> {
            float cx = 0.50f * s;
            float cy = 0.38f * s;
            float r = 0.27f * s;
            Path2D bulb = new Path2D.Float();
            bulb.append(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, -35, 250, Arc2D.OPEN), false);
            bulb.lineTo(0.39f * s, 0.68f * s);
            bulb.lineTo(0.61f * s, 0.68f * s);
            bulb.closePath();
            Color line = g.getColor();
            g.setColor(alpha(line, 70));
            g.fill(bulb);
            g.setColor(line);
            g.draw(bulb);
            g.draw(new Line2D.Float(0.40f * s, 0.80f * s, 0.60f * s, 0.80f * s));
            g.draw(new Line2D.Float(0.44f * s, 0.91f * s, 0.56f * s, 0.91f * s));
        }, null);
    }

    /** A delta: what changed since the cell last ran. */
    static Icon delta(int size, Supplier<Tone> tone) {
        return new Glyph(size, tone, (g, s) -> g.draw(triangle(s, 0.50f, 0.14f, 0.88f, 0.84f, 0.12f, 0.84f)), null);
    }

    /** Two equal bars: nothing changed. */
    static Icon same(int size) {
        return new Glyph(size, () -> Tone.MUTED, (g, s) -> {
            g.draw(new Line2D.Float(0.20f * s, 0.38f * s, 0.80f * s, 0.38f * s));
            g.draw(new Line2D.Float(0.20f * s, 0.62f * s, 0.80f * s, 0.62f * s));
        }, null);
    }

    /** A tick: done, or the same as before. */
    static Icon check(int size, Supplier<Tone> tone) {
        return new Glyph(size, tone, (g, s) -> {
            Path2D p = new Path2D.Float();
            p.moveTo(0.18f * s, 0.52f * s);
            p.lineTo(0.41f * s, 0.75f * s);
            p.lineTo(0.84f * s, 0.27f * s);
            g.draw(p);
        }, null);
    }

    /** A cross: put away. */
    static Icon close(int size) {
        return new Glyph(size, () -> Tone.MUTED, (g, s) -> {
            g.draw(new Line2D.Float(0.28f * s, 0.28f * s, 0.72f * s, 0.72f * s));
            g.draw(new Line2D.Float(0.28f * s, 0.72f * s, 0.72f * s, 0.28f * s));
        }, null);
    }

    /**
     * A small rounded action: its label in the tone's color on a light wash of it, deeper
     * under the mouse. What the doctor and the delta offer under a cell.
     */
    static final class Chip extends JButton {
        private static final long serialVersionUID = 1L;
        private final transient Supplier<Tone> tone;

        Chip(String label, String tip, Supplier<Tone> tone) {
            super(label);
            this.tone = tone;
            setFont(FontLoader.getGlobalFont(Font.BOLD, 11));
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tip);
            getAccessibleContext().setAccessibleName(label);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(getText()) + 20, fm.getHeight() + 6);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintBorder(Graphics g) {
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color c = color(tone.get());
            ButtonModel m = getModel();
            int a = !isEnabled() ? 22 : m.isPressed() && m.isArmed() ? 100 : m.isRollover() ? 70 : 40;
            g2.setColor(alpha(c, a));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            Color text = dark() ? mix(c, Color.WHITE, 0.50f) : mix(c, Color.BLACK, 0.30f);
            g2.setColor(isEnabled() ? text : alpha(text, 110));
            int tx = (getWidth() - fm.stringWidth(getText())) / 2;
            int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(getText(), tx, ty);
            g2.dispose();
        }
    }

    // ---- Buttons -----------------------------------------------------------

    /**
     * A square button showing one glyph: a rounded wash under the mouse, deeper when pressed;
     * the glyph dims when the button is disabled. Its tooltip is read again on every hover, so
     * it can say what a click would do now (how many cells, how long).
     */
    static final class ToolButton extends JButton {
        private static final long serialVersionUID = 1L;
        private final Dimension size;
        private final transient Supplier<String> tip;

        ToolButton(Icon icon, String name, Supplier<String> tip) {
            this(icon, name, tip, new Dimension(30, 28));
        }

        ToolButton(Icon icon, String name, Supplier<String> tip, Dimension size) {
            super(icon);
            this.tip = tip;
            this.size = size;
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setMargin(new Insets(0, 0, 0, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tip.get()); // registers it with the tooltip manager
            getAccessibleContext().setAccessibleName(name);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            return tip.get();
        }

        @Override
        public Dimension getPreferredSize() {
            return size;
        }

        @Override
        public Dimension getMinimumSize() {
            return size;
        }

        @Override
        public Dimension getMaximumSize() {
            return size;
        }

        /** A theme switch gives every button an outline (ThemeManager): not this one. */
        @Override
        protected void paintBorder(Graphics g) {
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel m = getModel();
            if (isEnabled() && (m.isRollover() || m.isPressed())) {
                Color wash = palette().getTextPrimary();
                g2.setColor(alpha(wash, m.isPressed() && m.isArmed() ? 52 : 26));
                g2.fill(new RoundRectangle2D.Float(1, 1, getWidth() - 2f, getHeight() - 2f, 8, 8));
            }
            Icon icon = getIcon();
            if (icon != null) {
                int x = (getWidth() - icon.getIconWidth()) / 2;
                int y = (getHeight() - icon.getIconHeight()) / 2;
                icon.paintIcon(this, g2, x, y);
            }
            g2.dispose();
        }
    }
}
