package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

/**
 * Icons drawn from shapes rather than loaded from pixels, so they stay sharp at
 * any scale and follow the theme: language badges, completion kinds, and the
 * small action glyphs of the editor's bars.
 */
public final class EditorIcons {

    private EditorIcons() {
    }

    private static Font badgeFont;

    static Font badgeFont() {
        if (badgeFont == null) {
            badgeFont = FontLoader.getGlobalFont(Font.BOLD, 9);
        }
        return badgeFont;
    }

    private static Graphics2D prepare(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    // ---- Language badge ----------------------------------------------------

    /** A rounded tag in the language's color: "PY", "C++", "CM". */
    public static final class LanguageBadge implements Icon {
        private final String tag;
        private final Supplier<Color> color;
        private final int height;

        public LanguageBadge(String tag, Supplier<Color> color, int height) {
            this.tag = tag;
            this.color = color;
            this.height = height;
        }

        public static LanguageBadge of(LanguageSpec spec, java.io.File file) {
            String tag = LanguageSpec.badgeFor(spec, file);
            return new LanguageBadge(tag, () -> EditorTheme.languageColor(spec), 14);
        }

        private int width = -1;

        @Override
        public int getIconWidth() {
            if (width < 0) {
                java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
                int text = (int) Math.ceil(badgeFont().getStringBounds(tag, frc).getWidth());
                width = Math.max(height + 2, text + 8);
            }
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = prepare(g);
            Color fill = color.get();
            int w = getIconWidth();
            g2.setColor(EditorTheme.alpha(fill, 235));
            g2.fill(new RoundRectangle2D.Float(x, y, w, height, 5, 5));
            g2.setFont(badgeFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(EditorTheme.onColor(fill));
            int tx = x + (w - fm.stringWidth(tag)) / 2;
            int ty = y + (height + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(tag, tx, ty);
            g2.dispose();
        }
    }

    // ---- Kinds -------------------------------------------------------------

    public enum Kind {
        KEYWORD("k", new Color(197, 134, 192)),
        TYPE("T", new Color(78, 201, 176)),
        CLASS("C", new Color(238, 157, 40)),
        FUNCTION("ƒ", new Color(180, 128, 215)),
        METHOD("m", new Color(180, 128, 215)),
        VARIABLE("v", new Color(117, 190, 255)),
        FIELD("f", new Color(117, 190, 255)),
        CONSTANT("K", new Color(79, 193, 255)),
        MODULE("M", new Color(220, 200, 120)),
        SNIPPET("⌘", new Color(190, 190, 190)),
        WORD("w", new Color(140, 140, 150)),
        MACRO("#", new Color(155, 155, 214)),
        TARGET("T", new Color(38, 176, 160)),
        PROPERTY("p", new Color(117, 190, 255)),
        HEADING("H", new Color(225, 110, 0)),
        FILE("F", new Color(150, 156, 166)),
        COMMAND(">", new Color(0, 122, 204)),
        FOLDER("D", new Color(220, 180, 90));

        public final String glyph;
        public final Color color;

        Kind(String glyph, Color color) {
            this.glyph = glyph;
            this.color = color;
        }

        public static Kind of(DocSymbol.Kind k) {
            switch (k) {
                case NAMESPACE:
                case MODULE:
                case PACKAGE:     return MODULE;
                case CLASS:
                case STRUCT:
                case INTERFACE:
                case ENUM:        return CLASS;
                case FUNCTION:    return FUNCTION;
                case METHOD:
                case CONSTRUCTOR: return METHOD;
                case FIELD:       return FIELD;
                case VARIABLE:    return VARIABLE;
                case CONSTANT:    return CONSTANT;
                case MACRO:       return MACRO;
                case TARGET:      return TARGET;
                case HEADING:
                case SECTION:     return HEADING;
                case PROPERTY:    return PROPERTY;
                default:          return WORD;
            }
        }
    }

    /** A small rounded square with the kind's letter. */
    public static final class KindIcon implements Icon {
        private final Kind kind;
        private final int size;

        public KindIcon(Kind kind, int size) {
            this.kind = kind;
            this.size = size;
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
            Graphics2D g2 = prepare(g);
            Color col = EditorTheme.readable(kind.color);
            g2.setColor(EditorTheme.alpha(col, 46));
            g2.fill(new RoundRectangle2D.Float(x, y, size, size, 4, 4));
            g2.setColor(EditorTheme.alpha(col, 150));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, size - 1, size - 1, 4, 4));
            Font f = badgeFont().deriveFont(Font.BOLD, size * 0.68f);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(col);
            int tx = x + (size - fm.stringWidth(kind.glyph)) / 2;
            int ty = y + (size + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(kind.glyph, tx, ty);
            g2.dispose();
        }
    }

    // ---- Glyphs ------------------------------------------------------------

    public interface Painter {
        void paint(Graphics2D g, float x, float y, float s);
    }

    /** A vector glyph in a color read at paint time. */
    public static final class Glyph implements Icon {
        private final Painter painter;
        private final int size;
        private final Supplier<Color> color;

        public Glyph(int size, Supplier<Color> color, Painter painter) {
            this.size = size;
            this.color = color;
            this.painter = painter;
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
            Graphics2D g2 = prepare(g);
            Color col = color.get();
            if (c != null && !c.isEnabled()) {
                col = EditorTheme.alpha(col, 90);
            }
            g2.setColor(col);
            g2.setStroke(new BasicStroke(Math.max(1.2f, size / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            painter.paint(g2, x, y, size);
            g2.dispose();
        }
    }

    public static Icon play(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.28f, y + s * 0.18f);
            p.lineTo(x + s * 0.80f, y + s * 0.50f);
            p.lineTo(x + s * 0.28f, y + s * 0.82f);
            p.closePath();
            g.fill(p);
        });
    }

    public static Icon close(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            float a = s * 0.28f;
            float b = s * 0.72f;
            g.draw(new Line2D.Float(x + a, y + a, x + b, y + b));
            g.draw(new Line2D.Float(x + a, y + b, x + b, y + a));
        });
    }

    public static Icon chevron(int size, Supplier<Color> color, boolean down) {
        return new Glyph(size, color, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            if (down) {
                p.moveTo(x + s * 0.28f, y + s * 0.40f);
                p.lineTo(x + s * 0.50f, y + s * 0.62f);
                p.lineTo(x + s * 0.72f, y + s * 0.40f);
            } else {
                p.moveTo(x + s * 0.40f, y + s * 0.28f);
                p.lineTo(x + s * 0.62f, y + s * 0.50f);
                p.lineTo(x + s * 0.40f, y + s * 0.72f);
            }
            g.draw(p);
        });
    }

    public static Icon arrow(int size, Supplier<Color> color, boolean up) {
        return new Glyph(size, color, (g, x, y, s) -> {
            float cx = x + s / 2f;
            float top = y + s * 0.22f;
            float bottom = y + s * 0.78f;
            g.draw(new Line2D.Float(cx, top, cx, bottom));
            GeneralPath p = new GeneralPath();
            if (up) {
                p.moveTo(cx - s * 0.22f, top + s * 0.22f);
                p.lineTo(cx, top);
                p.lineTo(cx + s * 0.22f, top + s * 0.22f);
            } else {
                p.moveTo(cx - s * 0.22f, bottom - s * 0.22f);
                p.lineTo(cx, bottom);
                p.lineTo(cx + s * 0.22f, bottom - s * 0.22f);
            }
            g.draw(p);
        });
    }

    public static Icon search(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            g.draw(new Ellipse2D.Float(x + s * 0.18f, y + s * 0.16f, s * 0.46f, s * 0.46f));
            g.draw(new Line2D.Float(x + s * 0.57f, y + s * 0.57f, x + s * 0.82f, y + s * 0.82f));
        });
    }

    public static Icon preview(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            g.draw(new RoundRectangle2D.Float(x + s * 0.14f, y + s * 0.2f, s * 0.72f, s * 0.6f, 3, 3));
            g.draw(new Line2D.Float(x + s * 0.5f, y + s * 0.2f, x + s * 0.5f, y + s * 0.8f));
        });
    }

    public static Icon more(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            float r = Math.max(1.6f, s * 0.09f);
            for (int i = 0; i < 3; i++) {
                float cx = x + s * (0.25f + 0.25f * i);
                g.fill(new Ellipse2D.Float(cx - r, y + s / 2f - r, 2 * r, 2 * r));
            }
        });
    }

    public static Icon error(int size) {
        return new Glyph(size, EditorTheme::errorSquiggle, (g, x, y, s) -> {
            g.draw(new Ellipse2D.Float(x + s * 0.12f, y + s * 0.12f, s * 0.76f, s * 0.76f));
            float a = s * 0.36f;
            float b = s * 0.64f;
            g.draw(new Line2D.Float(x + a, y + a, x + b, y + b));
            g.draw(new Line2D.Float(x + a, y + b, x + b, y + a));
        });
    }

    public static Icon warning(int size) {
        return new Glyph(size, EditorTheme::warningSquiggle, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.5f, y + s * 0.12f);
            p.lineTo(x + s * 0.9f, y + s * 0.85f);
            p.lineTo(x + s * 0.1f, y + s * 0.85f);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Float(x + s * 0.5f, y + s * 0.40f, x + s * 0.5f, y + s * 0.60f));
            g.fill(new Ellipse2D.Float(x + s * 0.5f - 1f, y + s * 0.68f, 2f, 2f));
        });
    }

    public static Icon info(int size) {
        return new Glyph(size, EditorTheme::infoSquiggle, (g, x, y, s) -> {
            g.draw(new Ellipse2D.Float(x + s * 0.12f, y + s * 0.12f, s * 0.76f, s * 0.76f));
            g.draw(new Line2D.Float(x + s * 0.5f, y + s * 0.45f, x + s * 0.5f, y + s * 0.68f));
            g.fill(new Ellipse2D.Float(x + s * 0.5f - 1f, y + s * 0.28f, 2f, 2f));
        });
    }

    public static Icon check(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.22f, y + s * 0.52f);
            p.lineTo(x + s * 0.42f, y + s * 0.72f);
            p.lineTo(x + s * 0.78f, y + s * 0.30f);
            g.draw(p);
        });
    }

    public static Icon bolt(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.58f, y + s * 0.08f);
            p.lineTo(x + s * 0.24f, y + s * 0.56f);
            p.lineTo(x + s * 0.48f, y + s * 0.56f);
            p.lineTo(x + s * 0.40f, y + s * 0.92f);
            p.lineTo(x + s * 0.76f, y + s * 0.42f);
            p.lineTo(x + s * 0.52f, y + s * 0.42f);
            p.closePath();
            g.fill(p);
        });
    }

    public static Icon folder(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.1f, y + s * 0.28f);
            p.lineTo(x + s * 0.38f, y + s * 0.28f);
            p.lineTo(x + s * 0.46f, y + s * 0.36f);
            p.lineTo(x + s * 0.9f, y + s * 0.36f);
            p.lineTo(x + s * 0.9f, y + s * 0.78f);
            p.lineTo(x + s * 0.1f, y + s * 0.78f);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon file(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.24f, y + s * 0.12f);
            p.lineTo(x + s * 0.58f, y + s * 0.12f);
            p.lineTo(x + s * 0.78f, y + s * 0.32f);
            p.lineTo(x + s * 0.78f, y + s * 0.88f);
            p.lineTo(x + s * 0.24f, y + s * 0.88f);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon wrap(int size, Supplier<Color> color) {
        return new Glyph(size, color, (g, x, y, s) -> {
            g.draw(new Line2D.Float(x + s * 0.15f, y + s * 0.25f, x + s * 0.85f, y + s * 0.25f));
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.15f, y + s * 0.5f);
            p.lineTo(x + s * 0.72f, y + s * 0.5f);
            p.quadTo(x + s * 0.88f, y + s * 0.5f, x + s * 0.88f, y + s * 0.62f);
            p.quadTo(x + s * 0.88f, y + s * 0.75f, x + s * 0.72f, y + s * 0.75f);
            p.lineTo(x + s * 0.5f, y + s * 0.75f);
            g.draw(p);
            g.draw(new Line2D.Float(x + s * 0.15f, y + s * 0.75f, x + s * 0.32f, y + s * 0.75f));
        });
    }
}
