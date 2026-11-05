package com.sphere.components.spdf;

import com.sphere.components.editor.EditorTheme;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

/**
 * The look of :spdf, taken from SphereCoder's theme so the PDF tab sits among
 * the code tabs, light or dark; and its icons, drawn as vectors in the
 * theme's colors, sharp at any display scale.
 */
public final class SpdfTheme {

    private SpdfTheme() {
    }

    public static Color bg() {
        return EditorTheme.background();
    }

    public static Color chrome() {
        return EditorTheme.chrome();
    }

    public static Color fg() {
        return EditorTheme.foreground();
    }

    public static Color muted() {
        return EditorTheme.textMuted();
    }

    public static Color faint() {
        return EditorTheme.textFaint();
    }

    public static Color accent() {
        return EditorTheme.focus();
    }

    public static Color border() {
        return EditorTheme.chromeBorder();
    }

    public static Color hover() {
        return EditorTheme.chromeHover();
    }

    public static Color widget() {
        return EditorTheme.widgetBackground();
    }

    public static Color input() {
        return EditorTheme.widgetInput();
    }

    public static Color popup() {
        return EditorTheme.popupBackground();
    }

    public static boolean dark() {
        return EditorTheme.isDarkSurface();
    }

    /** Behind the pages: a step darker than the chrome, so white pages stand out without glare. */
    public static Color canvas() {
        Color c = chrome();
        return dark() ? mix(c, Color.BLACK, 0.25f) : mix(c, new Color(120, 124, 132), 0.22f);
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    public static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                         Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                         Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static Font ui(int style, float size) {
        try {
            return com.sphere.fonts.FontLoader.getGlobalFont(style, Math.round(size));
        } catch (Throwable e) {
            return new Font(Font.SANS_SERIF, style, Math.round(size));
        }
    }

    public static Font mono(int style, float size) {
        try {
            return com.sphere.fonts.FontLoader.getTerminalFont(style, Math.round(size));
        } catch (Throwable e) {
            return new Font(Font.MONOSPACED, style, Math.round(size));
        }
    }

    /* ------------------------------------------------------------- icons */

    /** An icon by name, drawn in a color (the foreground when null) at a size. */
    public static Icon icon(String name, int size) {
        return new Glyph(name, size, null);
    }

    public static Icon icon(String name, int size, Color color) {
        return new Glyph(name, size, color);
    }

    private static final class Glyph implements Icon {
        final String name;
        final int size;
        final Color color;

        Glyph(String name, int size, Color color) {
            this.name = name;
            this.size = size;
            this.color = color;
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
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.scale(size / 24.0, size / 24.0);
            Color col = color != null ? color : (c != null && !c.isEnabled() ? faint() : fg());
            g.setColor(col);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g, name, col);
            g.dispose();
        }
    }

    private static void draw(Graphics2D g, String name, Color col) {
        switch (name) {
            case "open" -> {
                path(g, 3, 7, 3, 19, 21, 19, 21, 9, 12, 9, 10, 7);
                g.draw(closed(3, 7, 10, 7, 12, 9, 21, 9, 21, 19, 3, 19));
            }
            case "save" -> {
                g.draw(new RoundRectangle2D.Float(4, 4, 16, 16, 3, 3));
                g.draw(new Rectangle2D.Float(8, 4, 8, 5));
                g.draw(new Rectangle2D.Float(7, 13, 10, 7));
            }
            case "undo" -> {
                arc(g, 6, 7, 14, 11, 90, -220);
                path(g, 4, 6, 8, 3, 8, 9);
            }
            case "redo" -> {
                arc(g, 4, 7, 14, 11, 90, 220);
                path(g, 20, 6, 16, 3, 16, 9);
            }
            case "select" -> g.draw(closed(6, 3, 6, 19, 10, 15, 13, 21, 15, 20, 12, 14, 18, 14));
            case "edit" -> {
                g.draw(new Line2D.Float(4, 20, 9, 20));
                g.draw(closed(14, 4, 19, 9, 9, 19, 4, 20, 5, 15));
                g.draw(new Line2D.Float(12, 6, 17, 11));
            }
            case "text" -> {
                path(g, 5, 7, 5, 4, 19, 4, 19, 7);
                g.draw(new Line2D.Float(12, 4, 12, 20));
                g.draw(new Line2D.Float(9, 20, 15, 20));
            }
            case "addtext" -> {
                path(g, 3, 6, 3, 4, 13, 4, 13, 6);
                g.draw(new Line2D.Float(8, 4, 8, 15));
                g.draw(new Line2D.Float(6, 15, 10, 15));
                g.draw(new Line2D.Float(18, 13, 18, 21));
                g.draw(new Line2D.Float(14, 17, 22, 17));
            }
            case "highlight" -> {
                g.draw(closed(14, 3, 20, 9, 11, 18, 5, 12));
                g.draw(closed(5, 12, 11, 18, 8, 21, 3, 21, 3, 16));
                g.setColor(alpha(new Color(255, 214, 0), 200));
                g.fill(new Rectangle2D.Float(12, 19, 10, 3));
            }
            case "note" -> {
                g.draw(closed(4, 4, 20, 4, 20, 16, 13, 16, 8, 20, 8, 16, 4, 16));
                g.draw(new Line2D.Float(8, 8, 16, 8));
                g.draw(new Line2D.Float(8, 12, 14, 12));
            }
            case "ink" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(3, 17);
                p.curveTo(7, 9, 9, 21, 13, 13);
                p.curveTo(15, 9, 18, 9, 21, 11);
                g.draw(p);
            }
            case "shape" -> {
                g.draw(new Rectangle2D.Float(3, 9, 11, 11));
                g.draw(new Ellipse2D.Float(10, 3, 11, 11));
            }
            case "rect" -> g.draw(new RoundRectangle2D.Float(4, 6, 16, 12, 2, 2));
            case "ellipse" -> g.draw(new Ellipse2D.Float(3, 6, 18, 12));
            case "line" -> g.draw(new Line2D.Float(4, 20, 20, 4));
            case "arrow" -> {
                g.draw(new Line2D.Float(4, 20, 19, 5));
                path(g, 11, 5, 19, 5, 19, 13);
            }
            case "redact" -> {
                g.fill(new RoundRectangle2D.Float(3, 8, 18, 8, 2, 2));
                g.draw(new Line2D.Float(3, 4, 12, 4));
                g.draw(new Line2D.Float(3, 20, 15, 20));
            }
            case "hand" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(8, 13);
                p.lineTo(8, 5);
                p.curveTo(8, 3.5, 10.5, 3.5, 10.5, 5);
                p.lineTo(10.5, 11);
                p.moveTo(10.5, 5);
                p.lineTo(10.5, 4);
                p.curveTo(10.5, 2.5, 13, 2.5, 13, 4);
                p.lineTo(13, 11);
                p.moveTo(13, 5);
                p.curveTo(13, 3.5, 15.5, 3.5, 15.5, 5);
                p.lineTo(15.5, 11);
                p.moveTo(15.5, 7);
                p.curveTo(15.5, 5.5, 18, 5.5, 18, 7);
                p.lineTo(18, 15);
                p.curveTo(18, 19, 15, 21, 12, 21);
                p.curveTo(9, 21, 7, 19, 5, 16);
                p.lineTo(4, 13.5);
                p.curveTo(3.5, 12, 6, 11, 8, 13);
                g.draw(p);
            }
            case "zoomin", "zoomout" -> {
                g.draw(new Ellipse2D.Float(4, 4, 12, 12));
                g.draw(new Line2D.Float(14.5f, 14.5f, 20, 20));
                g.draw(new Line2D.Float(7, 10, 13, 10));
                if (name.equals("zoomin")) {
                    g.draw(new Line2D.Float(10, 7, 10, 13));
                }
            }
            case "fitwidth" -> {
                g.draw(new Rectangle2D.Float(6, 3, 12, 18));
                path(g, 2, 12, 5, 12);
                path(g, 22, 12, 19, 12);
                path(g, 3.5f, 10.5f, 2, 12, 3.5f, 13.5f);
                path(g, 20.5f, 10.5f, 22, 12, 20.5f, 13.5f);
            }
            case "fitpage" -> {
                g.draw(new Rectangle2D.Float(6, 4, 12, 16));
                path(g, 3, 7, 3, 3, 7, 3);
                path(g, 21, 17, 21, 21, 17, 21);
            }
            case "rotate" -> {
                arc(g, 5, 5, 14, 14, 60, 270);
                path(g, 17, 3, 18, 7, 14, 8);
            }
            case "rotateleft" -> {
                arc(g, 5, 5, 14, 14, 120, -270);
                path(g, 7, 3, 6, 7, 10, 8);
            }
            case "search" -> {
                g.draw(new Ellipse2D.Float(4, 4, 12, 12));
                g.draw(new Line2D.Float(14.5f, 14.5f, 20, 20));
            }
            case "spark" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(12, 3);
                p.curveTo(12.8, 8.5, 15.5, 11.2, 21, 12);
                p.curveTo(15.5, 12.8, 12.8, 15.5, 12, 21);
                p.curveTo(11.2, 15.5, 8.5, 12.8, 3, 12);
                p.curveTo(8.5, 11.2, 11.2, 8.5, 12, 3);
                p.closePath();
                g.setColor(alpha(accent(), 230));
                g.fill(p);
                g.setColor(col);
                g.fill(new Ellipse2D.Float(18, 2, 4, 4));
            }
            case "pages" -> {
                g.draw(new RoundRectangle2D.Float(7, 3, 12, 15, 2, 2));
                path(g, 5, 7, 5, 21, 15, 21);
            }
            case "outline" -> {
                for (int i = 0; i < 4; i++) {
                    float yy = 5 + i * 4.7f;
                    float xx = i == 1 || i == 2 ? 9 : 4;
                    g.fill(new Ellipse2D.Float(xx - 1.2f, yy - 1.2f, 2.4f, 2.4f));
                    g.draw(new Line2D.Float(xx + 3, yy, 20, yy));
                }
            }
            case "insights" -> {
                g.draw(new Line2D.Float(5, 20, 5, 13));
                g.draw(new Line2D.Float(10, 20, 10, 8));
                g.draw(new Line2D.Float(15, 20, 15, 11));
                g.draw(new Line2D.Float(20, 20, 20, 4));
            }
            case "history" -> {
                g.draw(new Ellipse2D.Float(4, 4, 16, 16));
                path(g, 12, 7, 12, 12, 15, 14);
            }
            case "bold" -> {
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                GeneralPath p = new GeneralPath();
                p.moveTo(7, 4);
                p.lineTo(13, 4);
                p.curveTo(17, 4, 17, 11, 13, 11);
                p.lineTo(7, 11);
                p.moveTo(13, 11);
                p.curveTo(18, 11, 18, 20, 13, 20);
                p.lineTo(7, 20);
                p.lineTo(7, 4);
                g.draw(p);
            }
            case "italic" -> {
                g.draw(new Line2D.Float(10, 4, 18, 4));
                g.draw(new Line2D.Float(6, 20, 14, 20));
                g.draw(new Line2D.Float(14, 4, 10, 20));
            }
            case "underline" -> {
                path(g, 7, 4, 7, 11, 17, 11, 17, 4);
                g.draw(new Line2D.Float(5, 20, 19, 20));
            }
            case "strike" -> {
                g.draw(new Line2D.Float(4, 12, 20, 12));
                GeneralPath p = new GeneralPath();
                p.moveTo(16, 6);
                p.curveTo(14, 3, 7, 4, 8, 8);
                p.moveTo(8, 16);
                p.curveTo(9, 21, 17, 20, 16, 16);
                g.draw(p);
            }
            case "alignleft", "aligncenter", "alignright", "alignjustify" -> {
                float[][] w = { { 16, 10, 16, 8 }, { 16, 10, 14, 8 }, { 16, 10, 16, 8 }, { 16, 16, 16, 9 } };
                int k = name.equals("alignleft") ? 0 : name.equals("aligncenter") ? 1 : name.equals("alignright") ? 2 : 3;
                for (int i = 0; i < 4; i++) {
                    float len = w[k][i];
                    float yy = 5 + i * 4.6f;
                    float x0 = switch (k) {
                        case 1 -> 12 - len / 2;
                        case 2 -> 20 - len;
                        default -> 4;
                    };
                    g.draw(new Line2D.Float(x0, yy, x0 + len, yy));
                }
            }
            case "superscript", "subscript" -> {
                g.draw(new Line2D.Float(4, 8, 12, 18));
                g.draw(new Line2D.Float(12, 8, 4, 18));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                g.drawString("2", 15, name.equals("superscript") ? 9 : 21);
            }
            case "color" -> {
                g.draw(closed(12, 3, 18, 15, 6, 15));
            }
            case "trash" -> {
                g.draw(new Line2D.Float(4, 6, 20, 6));
                path(g, 9, 6, 9, 3, 15, 3, 15, 6);
                g.draw(closed(6, 6, 7, 21, 17, 21, 18, 6));
            }
            case "duplicate" -> {
                g.draw(new RoundRectangle2D.Float(8, 8, 12, 12, 2, 2));
                path(g, 16, 5, 16, 4, 4, 4, 4, 16, 5, 16);
            }
            case "insert" -> {
                g.draw(new RoundRectangle2D.Float(5, 3, 14, 18, 2, 2));
                g.draw(new Line2D.Float(12, 8, 12, 16));
                g.draw(new Line2D.Float(8, 12, 16, 12));
            }
            case "extract" -> {
                g.draw(new RoundRectangle2D.Float(4, 4, 12, 16, 2, 2));
                g.draw(new Line2D.Float(10, 12, 21, 12));
                path(g, 18, 9, 21, 12, 18, 15);
            }
            case "merge" -> {
                g.draw(new RoundRectangle2D.Float(3, 3, 10, 13, 2, 2));
                g.draw(new RoundRectangle2D.Float(11, 8, 10, 13, 2, 2));
            }
            case "image" -> {
                g.draw(new RoundRectangle2D.Float(3, 5, 18, 14, 2, 2));
                path(g, 4, 17, 9, 11, 13, 15, 16, 12, 20, 17);
                g.draw(new Ellipse2D.Float(14, 7.5f, 3, 3));
            }
            case "moon" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(15, 3);
                p.curveTo(7, 4, 5, 13, 9, 18);
                p.curveTo(13, 22, 19, 20, 21, 15);
                p.curveTo(14, 17, 10, 10, 15, 3);
                p.closePath();
                g.draw(p);
            }
            case "close" -> {
                g.draw(new Line2D.Float(6, 6, 18, 18));
                g.draw(new Line2D.Float(18, 6, 6, 18));
            }
            case "check" -> path(g, 5, 12, 10, 17, 19, 7);
            case "up" -> path(g, 6, 15, 12, 9, 18, 15);
            case "down" -> path(g, 6, 9, 12, 15, 18, 9);
            case "left" -> path(g, 15, 6, 9, 12, 15, 18);
            case "right" -> path(g, 9, 6, 15, 12, 9, 18);
            case "print" -> {
                g.draw(new Rectangle2D.Float(7, 3, 10, 5));
                g.draw(new RoundRectangle2D.Float(3, 8, 18, 9, 2, 2));
                g.draw(new Rectangle2D.Float(7, 14, 10, 7));
            }
            case "export" -> {
                g.draw(new Line2D.Float(12, 3, 12, 15));
                path(g, 8, 7, 12, 3, 16, 7);
                path(g, 5, 13, 5, 20, 19, 20, 19, 13);
            }
            case "info" -> {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                g.draw(new Line2D.Float(12, 11, 12, 17));
                g.fill(new Ellipse2D.Float(10.8f, 6.8f, 2.4f, 2.4f));
            }
            case "fit" -> {
                path(g, 3, 8, 3, 3, 8, 3);
                path(g, 16, 3, 21, 3, 21, 8);
                path(g, 21, 16, 21, 21, 16, 21);
                path(g, 8, 21, 3, 21, 3, 16);
            }
            case "sidebar" -> {
                g.draw(new RoundRectangle2D.Float(3, 4, 18, 16, 2, 2));
                g.draw(new Line2D.Float(9, 4, 9, 20));
            }
            default -> g.draw(new Rectangle2D.Float(5, 5, 14, 14));
        }
    }

    private static void path(Graphics2D g, float... xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        g.draw(p);
    }

    private static Path2D.Float closed(float... xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        p.closePath();
        return p;
    }

    private static void arc(Graphics2D g, float x, float y, float w, float h, float start, float extent) {
        g.draw(new java.awt.geom.Arc2D.Float(x, y, w, h, start, extent, java.awt.geom.Arc2D.OPEN));
    }
}
