package com.sphere.components.g4editor;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * The editor's icons, drawn in code so they stay sharp at any scale and need
 * no image file: solids in a light isometric style, tools as glyphs.
 */
final class Icons {

    private Icons() {
    }

    static final Color BLUE = new Color(0x4A7FD0), GREEN = new Color(0x34A853), ORANGE = new Color(0xE8833A),
        RED = new Color(0xD64545), GREY = new Color(0x6B7480), PURPLE = new Color(0x8B6CC9), TEAL = new Color(0x2AA198);

    static Icon of(int size, Consumer<Graphics2D> painter) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                final Graphics2D g = (Graphics2D) g0.create();
                g.translate(x, y);
                g.scale(size / 20.0, size / 20.0);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (c != null && !c.isEnabled()) {
                    g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
                }
                painter.accept(g);
                g.dispose();
            }

            public int getIconWidth() {
                return size;
            }

            public int getIconHeight() {
                return size;
            }
        };
    }

    private static void face(Graphics2D g, Color c, double shade, int... xy) {
        final Polygon p = new Polygon();
        for (int i = 0; i < xy.length; i += 2) {
            p.addPoint(xy[i], xy[i + 1]);
        }
        g.setColor(shade(c, shade));
        g.fill(p);
        g.setColor(shade(c, 0.55));
        g.draw(p);
    }

    static Color shade(Color c, double f) {
        return new Color(clamp(c.getRed() * f), clamp(c.getGreen() * f), clamp(c.getBlue() * f), c.getAlpha());
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    static Icon box(int s, Color c) {
        return of(s, g -> {
            face(g, c, 1.15, 3, 6, 10, 2, 17, 6, 10, 10);
            face(g, c, 0.95, 3, 6, 10, 10, 10, 18, 3, 14);
            face(g, c, 0.75, 10, 10, 17, 6, 17, 14, 10, 18);
        });
    }

    static Icon tube(int s, Color c) {
        return of(s, g -> {
            g.setPaint(new GradientPaint(3, 0, shade(c, 0.75), 17, 0, shade(c, 1.2)));
            g.fill(new java.awt.Rectangle(3, 5, 14, 11));
            g.fill(new Ellipse2D.Double(3, 12.5, 14, 6));
            g.setColor(shade(c, 1.25));
            g.fill(new Ellipse2D.Double(3, 2, 14, 6));
            g.setColor(shade(c, 0.55));
            g.draw(new Ellipse2D.Double(3, 2, 14, 6));
            g.draw(new Arc2D.Double(3, 12.5, 14, 6, 180, 180, Arc2D.OPEN));
            g.drawLine(3, 5, 3, 15);
            g.drawLine(17, 5, 17, 15);
        });
    }

    static Icon sphere(int s, Color c) {
        return of(s, g -> {
            g.setPaint(new java.awt.RadialGradientPaint(7.5f, 7f, 10f, new float[] {0f, 1f},
                new Color[] {shade(c, 1.45), shade(c, 0.7)}));
            g.fill(new Ellipse2D.Double(2.5, 2.5, 15, 15));
            g.setColor(shade(c, 0.55));
            g.draw(new Ellipse2D.Double(2.5, 2.5, 15, 15));
        });
    }

    static Icon cone(int s, Color c) {
        return of(s, g -> {
            final Path2D p = new Path2D.Double();
            p.moveTo(10, 2);
            p.lineTo(17, 15);
            p.quadTo(10, 20, 3, 15);
            p.closePath();
            g.setPaint(new GradientPaint(3, 0, shade(c, 0.75), 17, 0, shade(c, 1.25)));
            g.fill(p);
            g.setColor(shade(c, 0.55));
            g.draw(p);
            g.draw(new Arc2D.Double(3, 12.5, 14, 5, 0, 180, Arc2D.OPEN));
        });
    }

    static Icon torus(int s, Color c) {
        return of(s, g -> {
            g.setStroke(new BasicStroke(4.2f));
            g.setColor(shade(c, 0.6));
            g.draw(new Ellipse2D.Double(3.5, 6, 13, 8.5));
            g.setStroke(new BasicStroke(2.6f));
            g.setColor(shade(c, 1.2));
            g.draw(new Ellipse2D.Double(3.5, 6, 13, 8.5));
        });
    }

    static Icon extrude(int s, Color c) {
        return of(s, g -> {
            face(g, c, 0.85, 3, 15, 3, 9, 8, 5, 12, 9, 12, 15);
            face(g, c, 1.2, 3, 9, 8, 5, 12, 9, 17, 5, 13, 2, 8, 2);
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine(16, 18, 16, 10);
            g.drawLine(14, 12, 16, 9);
            g.drawLine(18, 12, 16, 9);
        });
    }

    static Icon revolve(int s, Color c) {
        return of(s, g -> {
            g.setColor(shade(c, 1.1));
            final Path2D p = new Path2D.Double();
            p.moveTo(10, 3);
            p.lineTo(15, 5);
            p.lineTo(13, 10);
            p.lineTo(16, 16);
            p.lineTo(10, 17);
            p.closePath();
            g.fill(p);
            g.setColor(shade(c, 0.55));
            g.draw(p);
            g.setColor(GREY);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {2f, 2f}, 0));
            g.drawLine(10, 1, 10, 19);
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(GREEN);
            g.draw(new Arc2D.Double(3, 8, 8, 6, 90, 230, Arc2D.OPEN));
        });
    }

    static Icon select(int s) {
        return of(s, g -> {
            final Polygon p = new Polygon(new int[] {5, 5, 9, 11, 13, 11, 15}, new int[] {2, 16, 12, 17, 16, 11, 11}, 7);
            g.setColor(Color.WHITE);
            g.fill(p);
            g.setColor(new Color(0x30343C));
            g.draw(p);
        });
    }

    static Icon move(int s) {
        return of(s, g -> {
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(RED);
            arrow(g, 10, 10, 18, 10);
            g.setColor(GREEN);
            arrow(g, 10, 10, 4, 15);
            g.setColor(BLUE);
            arrow(g, 10, 10, 10, 2);
        });
    }

    static Icon rotate(int s) {
        return of(s, g -> {
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(BLUE);
            g.draw(new Arc2D.Double(3, 3, 14, 14, 40, 280, Arc2D.OPEN));
            final Polygon h = new Polygon(new int[] {14, 18, 17}, new int[] {2, 3, 7}, 3);
            g.fill(h);
        });
    }

    static Icon measure(int s) {
        return of(s, g -> {
            g.setColor(new Color(0xE0B040));
            g.fill(new RoundRectangle2D.Double(1.5, 7, 17, 6, 1.5, 1.5));
            g.setColor(new Color(0x7A5A10));
            g.draw(new RoundRectangle2D.Double(1.5, 7, 17, 6, 1.5, 1.5));
            for (int x = 4; x < 18; x += 3) {
                g.drawLine(x, 7, x, x % 2 == 0 ? 10 : 9);
            }
        });
    }

    static Icon bool(int s, int kind) {
        return of(s, g -> {
            final Ellipse2D a = new Ellipse2D.Double(2, 4, 11, 11), b = new Ellipse2D.Double(7, 4, 11, 11);
            final java.awt.geom.Area aa = new java.awt.geom.Area(a), bb = new java.awt.geom.Area(b);
            switch (kind) {
                case 0 -> aa.add(bb);
                case 1 -> aa.subtract(bb);
                default -> aa.intersect(bb);
            }
            g.setColor(shade(BLUE, 1.2));
            g.fill(aa);
            g.setColor(shade(BLUE, 0.6));
            g.draw(a);
            g.draw(b);
        });
    }

    static Icon check(int s) {
        return of(s, g -> {
            g.setColor(new Color(0xF0F0F0));
            g.setColor(ORANGE);
            g.fill(new java.awt.Rectangle(3, 4, 9, 9));
            g.setColor(new Color(0x4A7FD0, false));
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.8f));
            g.fill(new java.awt.Rectangle(8, 8, 9, 9));
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.setColor(RED);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(8, 8, 4, 5);
        });
    }

    static Icon budget(int s) {
        return of(s, g -> {
            final Color[] cs = {new Color(0x2E3F8F), new Color(0x2E8FA0), new Color(0x7FC060), new Color(0xF0C040), new Color(0xE04030)};
            final int[] h = {4, 8, 13, 9, 6};
            for (int i = 0; i < 5; i++) {
                g.setColor(cs[i]);
                g.fillRect(2 + i * 3 + i / 2, 18 - h[i], 3, h[i]);
            }
            g.setColor(GREY);
            g.drawLine(1, 18, 19, 18);
        });
    }

    static Icon advise(int s) {
        return of(s, g -> {
            g.setColor(new Color(0xF4C842));
            g.fill(new Ellipse2D.Double(5, 2, 10, 10));
            g.fill(new java.awt.Rectangle(7, 10, 6, 4));
            g.setColor(new Color(0x8A6A10));
            g.draw(new Ellipse2D.Double(5, 2, 10, 10));
            g.fillRect(7, 15, 6, 2);
            g.fillRect(8, 17, 4, 1);
        });
    }

    static Icon section(int s) {
        return of(s, g -> {
            face(g, BLUE, 1.0, 3, 6, 10, 2, 17, 6, 10, 10);
            face(g, BLUE, 0.85, 3, 6, 10, 10, 10, 18, 3, 14);
            g.setColor(RED);
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine(1, 12, 19, 4);
            g.setColor(new Color(0xD04848));
            final Polygon cut = new Polygon(new int[] {10, 17, 17, 10}, new int[] {10, 6, 14, 18}, 4);
            g.fill(cut);
        });
    }

    static Icon grid(int s) {
        return of(s, g -> {
            g.setColor(GREY);
            for (int i = 3; i <= 17; i += 4) {
                g.drawLine(i, 2, i, 18);
                g.drawLine(2, i, 18, i);
            }
            g.setColor(ORANGE);
            g.fill(new Ellipse2D.Double(9, 9, 4, 4));
        });
    }

    static Icon fit(int s) {
        return of(s, g -> {
            g.setColor(GREY);
            g.setStroke(new BasicStroke(1.6f));
            g.drawPolyline(new int[] {2, 2, 6}, new int[] {6, 2, 2}, 3);
            g.drawPolyline(new int[] {14, 18, 18}, new int[] {2, 2, 6}, 3);
            g.drawPolyline(new int[] {2, 2, 6}, new int[] {14, 18, 18}, 3);
            g.drawPolyline(new int[] {14, 18, 18}, new int[] {18, 18, 14}, 3);
            g.setColor(BLUE);
            g.fillRect(6, 6, 8, 8);
        });
    }

    static Icon view(int s, String which) {
        return of(s, g -> {
            final boolean top = which.equals("top"), front = which.equals("front"), right = which.equals("right");
            face(g, top ? ORANGE : new Color(0xC8D0DC), 1.0, 3, 6, 10, 2, 17, 6, 10, 10);
            face(g, front ? ORANGE : new Color(0xC8D0DC), 0.9, 3, 6, 10, 10, 10, 18, 3, 14);
            face(g, right ? ORANGE : new Color(0xC8D0DC), 0.78, 10, 10, 17, 6, 17, 14, 10, 18);
        });
    }

    static Icon file(int s, String kind) {
        return of(s, g -> {
            final Polygon p = new Polygon(new int[] {4, 12, 16, 16, 4}, new int[] {2, 2, 6, 18, 18}, 5);
            g.setColor(Color.WHITE);
            g.fill(p);
            g.setColor(GREY);
            g.draw(p);
            g.drawPolyline(new int[] {12, 12, 16}, new int[] {2, 6, 6}, 3);
            switch (kind) {
                case "new" -> {
                    g.setColor(GREEN);
                    g.setStroke(new BasicStroke(2f));
                    g.drawLine(10, 9, 10, 15);
                    g.drawLine(7, 12, 13, 12);
                }
                case "open" -> {
                    g.setColor(new Color(0xE8B030));
                    g.fill(new Polygon(new int[] {2, 7, 9, 18, 15, 2}, new int[] {8, 8, 10, 10, 18, 18}, 6));
                }
                case "save" -> {
                    g.setColor(BLUE);
                    g.fillRect(6, 11, 8, 6);
                    g.setColor(Color.WHITE);
                    g.fillRect(8, 12, 4, 2);
                }
                default -> {
                    g.setColor(PURPLE);
                    g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 6f));
                    g.drawString(kind, 5, 15);
                }
            }
        });
    }

    static Icon undo(int s, boolean redo) {
        return of(s, g -> {
            if (redo) {
                g.scale(-1, 1);
                g.translate(-20, 0);
            }
            g.setColor(BLUE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(5, 5, 12, 11, 90, -200, Arc2D.OPEN));
            g.fill(new Polygon(new int[] {2, 8, 8}, new int[] {6, 2, 10}, 3));
        });
    }

    static Icon run(int s) {
        return of(s, g -> {
            g.setColor(GREEN);
            g.fill(new Polygon(new int[] {5, 17, 5}, new int[] {3, 10, 17}, 3));
            g.setColor(shade(GREEN, 0.6));
            g.draw(new Polygon(new int[] {5, 17, 5}, new int[] {3, 10, 17}, 3));
        });
    }

    static Icon geantino(int s) {
        return of(s, g -> {
            g.setColor(TEAL);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {2.5f, 2f}, 0));
            g.drawLine(2, 17, 18, 3);
            g.setColor(PURPLE);
            g.fillRect(8, 7, 4, 6);
        });
    }

    static Icon template(int s) {
        return of(s, g -> {
            for (int i = 0; i < 5; i++) {
                g.setColor(i % 2 == 0 ? new Color(0x5A6070) : new Color(0x40C8D8));
                g.fillRect(3 + i * 3, 4, 2 + (i % 2), 12);
            }
            g.setColor(GREY);
            g.drawRect(2, 3, 16, 14);
        });
    }

    static Icon volume(int s, String tag, Color c) {
        return switch (tag) {
            case "tube", "cutTube", "eltube", "polycone", "genericPolycone", "hype", "paraboloid" -> tube(s, c);
            case "sphere", "orb", "ellipsoid" -> sphere(s, c);
            case "cone", "elcone" -> cone(s, c);
            case "torus" -> torus(s, c);
            case "xtru" -> extrude(s, c);
            case "union" -> bool(s, 0);
            case "subtraction" -> bool(s, 1);
            case "intersection" -> bool(s, 2);
            default -> box(s, c);
        };
    }

    static Icon dot(int s, Color c) {
        return of(s, g -> {
            g.setColor(c);
            g.fill(new Ellipse2D.Double(5, 5, 10, 10));
            g.setColor(shade(c, 0.6));
            g.draw(new Ellipse2D.Double(5, 5, 10, 10));
        });
    }

    static Icon eye(int s, boolean open) {
        return of(s, g -> {
            g.setColor(open ? new Color(0x4A5568) : new Color(0xB0B8C4));
            final Path2D p = new Path2D.Double();
            p.moveTo(2, 10);
            p.quadTo(10, 2, 18, 10);
            p.quadTo(10, 18, 2, 10);
            g.draw(p);
            if (open) {
                g.fill(new Ellipse2D.Double(7.5, 7.5, 5, 5));
            } else {
                g.drawLine(3, 16, 17, 4);
            }
        });
    }

    private static void arrow(Graphics2D g, int x0, int y0, int x1, int y1) {
        g.drawLine(x0, y0, x1, y1);
        final double a = Math.atan2(y1 - y0, x1 - x0);
        final int ax = (int) (x1 - 4 * Math.cos(a - 0.5)), ay = (int) (y1 - 4 * Math.sin(a - 0.5));
        final int bx = (int) (x1 - 4 * Math.cos(a + 0.5)), by = (int) (y1 - 4 * Math.sin(a + 0.5));
        g.fill(new Polygon(new int[] {x1, ax, bx}, new int[] {y1, ay, by}, 3));
    }
}
