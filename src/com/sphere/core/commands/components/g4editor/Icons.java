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
        RED = new Color(0xD64545), GRAY = new Color(0x6B7480), PURPLE = new Color(0x8B6CC9), TEAL = new Color(0x2AA198);

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
            g.setColor(GRAY);
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

    /** A freehand loop and the pencil that drew it. */
    static Icon sketch(int s) {
        return of(s, g -> {
            final Path2D loop = new Path2D.Double();
            loop.moveTo(4, 12);
            loop.curveTo(1.5, 6, 8, 2.5, 12, 4.5);
            loop.curveTo(16, 6.5, 14.5, 13, 10, 14.5);
            loop.curveTo(6.5, 15.5, 5, 14, 4.6, 12.6);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(PURPLE);
            g.draw(loop);
            // the pencil
            final Polygon body = new Polygon(new int[] {11, 17, 19, 13}, new int[] {16, 10, 12, 18}, 4);
            g.setColor(ORANGE);
            g.fill(body);
            g.setColor(shade(ORANGE, 0.6));
            g.draw(body);
            g.setColor(GRAY);
            g.fill(new Polygon(new int[] {11, 13, 9}, new int[] {16, 18, 19}, 3));
        });
    }

    /** A box whose top face is pulled up. */
    static Icon pushPull(int s) {
        return of(s, g -> {
            final Color c = new Color(0x8AA4C8);
            face(g, c, 0.95, 3, 10, 10, 14, 10, 19, 3, 15);
            face(g, c, 0.75, 10, 14, 17, 10, 17, 15, 10, 19);
            face(g, ORANGE, 1.15, 3, 10, 10, 6, 17, 10, 10, 14);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(shade(ORANGE, 0.8));
            arrow(g, 10, 10, 10, 1);
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
            g.setColor(GRAY);
            g.drawLine(1, 18, 19, 18);
        });
    }

    /** Physics Live: a beam coming in from the left and the shower it opens into, hot at its core. */
    static Icon shower(int s) {
        return of(s, g -> {
            g.setColor(new Color(0x38C8F0));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(1, 10, 7, 10);
            g.fillPolygon(new int[] {8, 5, 5}, new int[] {10, 8, 12}, 3);
            final java.awt.geom.Path2D cone = new java.awt.geom.Path2D.Double();
            cone.moveTo(8, 10);
            cone.curveTo(12, 5, 15, 3.5, 19, 3);
            cone.lineTo(19, 17);
            cone.curveTo(15, 16.5, 12, 15, 8, 10);
            cone.closePath();
            g.setPaint(new java.awt.GradientPaint(8, 10, new Color(0xFFE48A), 19, 10, new Color(0xD6402E)));
            g.fill(cone);
            g.setColor(new Color(0x8A2A20));
            g.setStroke(new BasicStroke(0.8f));
            g.draw(cone);
            g.setColor(new Color(255, 255, 255, 200));
            g.drawLine(10, 10, 18, 7);
            g.drawLine(10, 10, 18, 13);
            g.drawLine(10, 10, 18, 10);
        });
    }

    /** The beam: an arrow through two plates. */
    static Icon beam(int s) {
        return of(s, g -> {
            face(g, GRAY, 1.0, 6, 4, 9, 2, 9, 16, 6, 18);
            face(g, GRAY, 1.0, 12, 4, 15, 2, 15, 16, 12, 18);
            g.setColor(new Color(0x38C8F0));
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(1, 10, 16, 10);
            g.fillPolygon(new int[] {19, 15, 15}, new int[] {10, 7, 13}, 3);
        });
    }

    /** Goal Seek: a target, an arrow in its center. */
    static Icon goal(int s) {
        return of(s, g -> {
            g.setColor(RED);
            g.fill(new Ellipse2D.Double(2, 3, 15, 15));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(4.5, 5.5, 10, 10));
            g.setColor(RED);
            g.fill(new Ellipse2D.Double(7, 8, 5, 5));
            g.setColor(new Color(0x3A4250));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(10, 10, 18, 2);
            g.drawLine(15, 2, 18, 2);
            g.drawLine(18, 2, 18, 5);
        });
    }

    /** Relations: a stack of plates joined by a link. */
    static Icon relations(int s) {
        return of(s, g -> {
            face(g, BLUE, 1.0, 2, 13, 12, 13, 12, 17, 2, 17);
            face(g, TAN, 1.0, 2, 9, 12, 9, 12, 13, 2, 13);
            face(g, BLUE, 1.0, 2, 5, 12, 5, 12, 9, 2, 9);
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(13, 4, 5, 6, -90, 180);
            g.drawArc(13, 10, 5, 6, -90, 180);
            g.drawLine(15, 7, 15, 13);
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
            g.setColor(GRAY);
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
            g.setColor(GRAY);
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
            g.setColor(GRAY);
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
            g.setColor(GRAY);
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

    // ------------------------------------------------------------------
    // The Creo-style interface: ribbon, graphics toolbar, navigator
    // ------------------------------------------------------------------

    /** A mid tone that reads on a dark ribbon as on a light one. */
    static final Color INK = new Color(0x9AA6B8), PAPER = new Color(0xE9EDF2), TAN = new Color(0xD39A5A);

    private static BasicStroke pen(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /** A magnifier, with + or - (zoom), or plain (find a command). */
    static Icon zoom(int s, int sign) {
        return of(s, g -> {
            g.setStroke(pen(1.7f));
            g.setColor(INK);
            g.draw(new Ellipse2D.Double(2.5, 2.5, 11.5, 11.5));
            g.setStroke(pen(2.8f));
            g.drawLine(12, 12, 17, 17);
            g.setStroke(pen(1.8f));
            g.setColor(BLUE);
            if (sign != 0) {
                g.drawLine(5, 8, 11, 8);
                if (sign > 0) {
                    g.drawLine(8, 5, 8, 11);
                }
            }
        });
    }

    /** A painter's palette: color by material, density, X0... */
    static Icon palette(int s) {
        return of(s, g -> {
            final Path2D p = new Path2D.Double();
            p.moveTo(10, 2.5);
            p.curveTo(15.5, 2.5, 18, 6.5, 17.5, 10);
            p.curveTo(17, 13, 13.5, 12, 12.5, 14);
            p.curveTo(11.5, 16.5, 13, 18, 9.5, 17.5);
            p.curveTo(5, 17, 2.5, 13.5, 2.5, 10);
            p.curveTo(2.5, 6, 5.5, 2.5, 10, 2.5);
            g.setColor(PAPER);
            g.fill(p);
            g.setColor(INK);
            g.setStroke(pen(1.1f));
            g.draw(p);
            final Color[] c = {RED, ORANGE, GREEN, BLUE};
            final double[][] at = {{6, 6}, {10, 5}, {13.5, 7.5}, {6, 11}};
            for (int i = 0; i < 4; i++) {
                g.setColor(c[i]);
                g.fill(new Ellipse2D.Double(at[i][0] - 1.7, at[i][1] - 1.7, 3.4, 3.4));
            }
        });
    }

    /** A datum plane, as Creo draws FRONT, TOP and RIGHT. */
    static Icon plane(int s) {
        return of(s, g -> {
            final Polygon p = new Polygon(new int[] {2, 13, 18, 7}, new int[] {13, 13, 6, 6}, 4);
            g.setColor(new Color(TAN.getRed(), TAN.getGreen(), TAN.getBlue(), 70));
            g.fill(p);
            g.setColor(TAN);
            g.setStroke(pen(1.5f));
            g.draw(p);
            g.drawLine(5, 16, 11, 16);
        });
    }

    /** The spin center: three axes and the turn about them. */
    static Icon spin(int s) {
        return of(s, g -> {
            g.setStroke(pen(1.8f));
            g.setColor(RED);
            g.drawLine(10, 11, 16, 13);
            g.setColor(GREEN);
            g.drawLine(10, 11, 10, 4);
            g.setColor(BLUE);
            g.drawLine(10, 11, 4, 14);
            g.setColor(INK);
            g.setStroke(pen(1.2f));
            g.draw(new Arc2D.Double(3, 2, 14, 16, 200, 140, Arc2D.OPEN));
        });
    }

    /** A box seen in perspective. */
    static Icon perspective(int s) {
        return of(s, g -> {
            final Color c = new Color(0x8AA4C8);
            face(g, c, 1.1, 4, 7, 16, 7, 13, 3, 7, 3);
            face(g, c, 0.9, 4, 7, 16, 7, 18, 18, 2, 18);
        });
    }

    /** The selection alone, the rest as outlines. */
    static Icon isolate(int s) {
        return of(s, g -> {
            g.setColor(INK);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {2f, 2f}, 0f));
            g.drawRect(1, 1, 18, 18);
            final Color c = ORANGE;
            face(g, c, 1.15, 5, 8, 10, 5, 15, 8, 10, 11);
            face(g, c, 0.95, 5, 8, 10, 11, 10, 16, 5, 13);
            face(g, c, 0.75, 10, 11, 15, 8, 15, 13, 10, 16);
        });
    }

    /** Code: brackets and a slash (G4code, the C++ project). */
    static Icon code(int s) {
        return of(s, g -> {
            g.setStroke(pen(2f));
            g.setColor(BLUE);
            g.drawPolyline(new int[] {6, 2, 6}, new int[] {5, 10, 15}, 3);
            g.drawPolyline(new int[] {14, 18, 14}, new int[] {5, 10, 15}, 3);
            g.setColor(GREEN);
            g.drawLine(12, 3, 8, 17);
        });
    }

    /** ROOT: a blue disc with its R. */
    static Icon root(int s) {
        return of(s, g -> {
            g.setColor(new Color(0x1F6FB8));
            g.fill(new Ellipse2D.Double(1.5, 1.5, 17, 17));
            g.setColor(Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 13));
            g.drawString("R", 5.6f, 15f);
        });
    }

    /** A document with a tag, and an arrow out of it: export. */
    static Icon export(int s, Color mark) {
        return of(s, g -> {
            final Polygon p = new Polygon(new int[] {3, 10, 13, 13, 3}, new int[] {2, 2, 5, 18, 18}, 5);
            g.setColor(PAPER);
            g.fill(p);
            g.setColor(INK);
            g.setStroke(pen(1f));
            g.draw(p);
            g.setColor(mark);
            g.fillRect(5, 8, 6, 2);
            g.fillRect(5, 12, 4, 2);
            g.setStroke(pen(1.8f));
            g.setColor(GREEN);
            arrow(g, 11, 13, 18, 13);
        });
    }

    /** Two arrows turning: convert. */
    static Icon convert(int s) {
        return of(s, g -> {
            g.setStroke(pen(1.8f));
            g.setColor(BLUE);
            g.draw(new Arc2D.Double(3, 3, 14, 14, 30, 150, Arc2D.OPEN));
            arrow(g, 4, 9, 3, 11);
            g.setColor(GREEN);
            g.draw(new Arc2D.Double(3, 3, 14, 14, 210, 150, Arc2D.OPEN));
            arrow(g, 16, 11, 17, 9);
        });
    }

    /** A picture: export an image. */
    static Icon image(int s) {
        return of(s, g -> {
            g.setColor(PAPER);
            g.fillRect(2, 4, 16, 12);
            g.setColor(INK);
            g.setStroke(pen(1f));
            g.drawRect(2, 4, 16, 12);
            g.setColor(GREEN);
            g.fillPolygon(new int[] {3, 8, 11, 14, 17, 17, 3}, new int[] {15, 9, 12, 8, 12, 15, 15}, 7);
            g.setColor(ORANGE);
            g.fill(new Ellipse2D.Double(5, 6, 3, 3));
        });
    }

    /** A flask: materials, NIST. */
    static Icon flask(int s) {
        return of(s, g -> {
            final Path2D p = new Path2D.Double();
            p.moveTo(8, 2);
            p.lineTo(12, 2);
            p.lineTo(12, 8);
            p.lineTo(17, 17);
            p.lineTo(3, 17);
            p.lineTo(8, 8);
            p.closePath();
            g.setColor(PAPER);
            g.fill(p);
            final Path2D liquid = new Path2D.Double();
            liquid.moveTo(5.2, 13);
            liquid.lineTo(14.8, 13);
            liquid.lineTo(17, 17);
            liquid.lineTo(3, 17);
            liquid.closePath();
            g.setColor(TEAL);
            g.fill(liquid);
            g.setColor(INK);
            g.setStroke(pen(1.2f));
            g.draw(p);
        });
    }

    /** x = : the design's variables. */
    static Icon variables(int s) {
        return of(s, g -> {
            g.setColor(PURPLE);
            g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC | java.awt.Font.BOLD, 13));
            g.drawString("x", 2.5f, 13f);
            g.setColor(INK);
            g.setStroke(pen(1.8f));
            g.drawLine(11, 8, 17, 8);
            g.drawLine(11, 12, 17, 12);
        });
    }

    /** A tree of placements: the model tree. */
    static Icon tree(int s) {
        return of(s, g -> {
            g.setColor(INK);
            g.setStroke(pen(1.2f));
            g.drawLine(5, 6, 5, 16);
            g.drawLine(5, 11, 10, 11);
            g.drawLine(5, 16, 10, 16);
            g.setColor(ORANGE);
            g.fillRect(2, 2, 7, 5);
            g.setColor(BLUE);
            g.fillRect(10, 9, 7, 4);
            g.setColor(GREEN);
            g.fillRect(10, 14, 7, 4);
        });
    }

    /** A weight: masses. */
    static Icon mass(int s) {
        return of(s, g -> {
            final Polygon p = new Polygon(new int[] {5, 15, 18, 2}, new int[] {7, 7, 18, 18}, 4);
            g.setColor(new Color(0x7A8494));
            g.fill(p);
            g.setColor(INK);
            g.setStroke(pen(1.6f));
            g.draw(new Ellipse2D.Double(7, 2, 6, 5));
            g.setColor(Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 8));
            g.drawString("kg", 6f, 15.5f);
        });
    }

    /** A terminal: the console. */
    static Icon console(int s) {
        return of(s, g -> {
            g.setColor(new Color(0x1E2228));
            g.fill(new RoundRectangle2D.Double(1.5, 3, 17, 14, 3, 3));
            g.setColor(INK);
            g.setStroke(pen(1f));
            g.draw(new RoundRectangle2D.Double(1.5, 3, 17, 14, 3, 3));
            g.setColor(GREEN);
            g.setStroke(pen(1.6f));
            g.drawPolyline(new int[] {5, 8, 5}, new int[] {7, 10, 13}, 3);
            g.setColor(PAPER);
            g.drawLine(10, 13, 15, 13);
        });
    }

    /** ? : help. */
    static Icon help(int s) {
        return of(s, g -> {
            g.setColor(BLUE);
            g.fill(new Ellipse2D.Double(2, 2, 16, 16));
            g.setColor(Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 13));
            g.drawString("?", 6.5f, 15f);
        });
    }

    /** Two sheets: duplicate. */
    static Icon copy(int s) {
        return of(s, g -> {
            g.setStroke(pen(1.1f));
            g.setColor(PAPER);
            g.fillRect(3, 3, 10, 11);
            g.setColor(INK);
            g.drawRect(3, 3, 10, 11);
            g.setColor(PAPER);
            g.fillRect(7, 7, 10, 11);
            g.setColor(BLUE);
            g.drawRect(7, 7, 10, 11);
        });
    }

    /** A bin: delete. */
    static Icon trash(int s) {
        return of(s, g -> {
            g.setColor(INK);
            g.setStroke(pen(1.5f));
            g.drawLine(3, 5, 17, 5);
            g.drawLine(8, 5, 8, 3);
            g.drawLine(8, 3, 12, 3);
            g.drawLine(12, 3, 12, 5);
            g.setColor(RED);
            g.fillPolygon(new int[] {5, 15, 14, 6}, new int[] {7, 7, 18, 18}, 4);
            g.setColor(new Color(255, 255, 255, 150));
            g.drawLine(8, 9, 8, 16);
            g.drawLine(12, 9, 12, 16);
        });
    }

    /** A dashed frame around volumes: select all. */
    static Icon selectAll(int s) {
        return of(s, g -> {
            g.setColor(INK);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {2.5f, 2f}, 0f));
            g.drawRect(2, 2, 16, 16);
            g.setColor(BLUE);
            g.fillRect(5, 5, 4, 4);
            g.setColor(GREEN);
            g.fillRect(11, 5, 4, 4);
            g.setColor(ORANGE);
            g.fillRect(5, 11, 10, 4);
        });
    }

    /** G4: Geant4, its installations. */
    static Icon geant4(int s) {
        return of(s, g -> {
            g.setColor(new Color(0x2E6E3A));
            g.fill(new RoundRectangle2D.Double(1, 3, 18, 14, 4, 4));
            g.setColor(Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 10));
            g.drawString("G4", 3.6f, 14f);
        });
    }

    /** Nodes and links: machine learning. */
    static Icon network(int s) {
        return of(s, g -> {
            final double[][] in = {{4, 5}, {4, 15}}, mid = {{10, 3}, {10, 10}, {10, 17}}, out = {{16, 10}};
            g.setColor(INK);
            g.setStroke(pen(0.9f));
            for (double[] a : in) {
                for (double[] b : mid) {
                    g.drawLine((int) a[0], (int) a[1], (int) b[0], (int) b[1]);
                }
            }
            for (double[] a : mid) {
                g.drawLine((int) a[0], (int) a[1], (int) out[0][0], (int) out[0][1]);
            }
            for (double[][] layer : new double[][][] {in, mid, out}) {
                for (double[] p : layer) {
                    g.setColor(layer == mid ? PURPLE : layer == in ? BLUE : GREEN);
                    g.fill(new Ellipse2D.Double(p[0] - 2.2, p[1] - 2.2, 4.4, 4.4));
                }
            }
        });
    }

    /** Two volumes in each other, and the move that clears them: resolve the clash. */
    static Icon clash(int s) {
        return of(s, g -> {
            g.setColor(new Color(RED.getRed(), RED.getGreen(), RED.getBlue(), 170));
            g.fillRect(2, 7, 9, 9);
            g.setColor(new Color(0x8AA4C8));
            g.fillRect(7, 3, 9, 9);
            g.setColor(GREEN);
            g.setStroke(pen(1.8f));
            arrow(g, 12, 15, 18, 15);
        });
    }

    /** OK: a green disc with a check, as Creo's dashboard has. */
    static Icon ok(int s) {
        return of(s, g -> {
            g.setColor(new Color(0x4CAF50));
            g.fill(new Ellipse2D.Double(1, 1, 18, 18));
            g.setColor(Color.WHITE);
            g.setStroke(pen(2.4f));
            g.drawPolyline(new int[] {5, 9, 15}, new int[] {10, 14, 6}, 3);
        });
    }

    /** Cancel: a red disc with a cross. */
    static Icon cancel(int s) {
        return of(s, g -> {
            g.setColor(new Color(0xD64545));
            g.fill(new Ellipse2D.Double(1, 1, 18, 18));
            g.setColor(Color.WHITE);
            g.setStroke(pen(2.4f));
            g.drawLine(6, 6, 14, 14);
            g.drawLine(14, 6, 6, 14);
        });
    }

    /** Creo's Regenerate: the model and the arrows that make it again. */
    static Icon regenerate(int s) {
        return of(s, g -> {
            final Color c = new Color(0x8AA4C8);
            face(g, c, 1.15, 6, 8, 10, 6, 14, 8, 10, 10);
            face(g, c, 0.95, 6, 8, 10, 10, 10, 14, 6, 12);
            face(g, c, 0.75, 10, 10, 14, 8, 14, 12, 10, 14);
            g.setStroke(pen(1.8f));
            g.setColor(new Color(0xE8B923));
            g.draw(new Arc2D.Double(1.5, 1.5, 17, 17, 100, 150, Arc2D.OPEN));
            arrow(g, 3, 14, 4, 16);
            g.setColor(GREEN);
            g.draw(new Arc2D.Double(1.5, 1.5, 17, 17, 280, 150, Arc2D.OPEN));
            arrow(g, 17, 6, 16, 4);
        });
    }

    /** A clipboard: paste. */
    static Icon paste(int s) {
        return of(s, g -> {
            g.setColor(new Color(0xB08A5A));
            g.fill(new RoundRectangle2D.Double(3, 3, 14, 16, 3, 3));
            g.setColor(PAPER);
            g.fillRect(5, 6, 10, 11);
            g.setColor(INK);
            g.fillRect(7, 1, 6, 4);
            g.setColor(BLUE);
            g.fillRect(7, 9, 6, 2);
            g.fillRect(7, 13, 4, 2);
        });
    }

    /** Lines over a document: a list. */
    static Icon list(int s) {
        return of(s, g -> {
            g.setStroke(pen(1.8f));
            for (int i = 0; i < 4; i++) {
                g.setColor(i % 2 == 0 ? BLUE : TEAL);
                g.drawLine(3, 4 + i * 4, 4, 4 + i * 4);
                g.setColor(INK);
                g.drawLine(7, 4 + i * 4, 17, 4 + i * 4);
            }
        });
    }

    /** The bottom panel shown: a window with its lower pane. */
    static Icon pane(int s) {
        return of(s, g -> {
            g.setColor(INK);
            g.setStroke(pen(1.2f));
            g.drawRect(2, 3, 16, 14);
            g.setColor(BLUE);
            g.fillRect(3, 12, 15, 5);
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
