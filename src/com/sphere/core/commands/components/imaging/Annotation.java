package com.sphere.components.imaging;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * One annotation, kept as geometry rather than pixels so it stays editable and
 * stays sharp at any zoom. Coordinates are in image space.
 */
public final class Annotation {

    public enum Kind { ARROW, LINE, RECTANGLE, ELLIPSE, TEXT, MEASURE, FREEHAND }

    public Kind kind;
    public double x1;
    public double y1;
    public double x2;
    public double y2;
    public Color color = new Color(0xE11D48);
    public Color fillColor;
    public double strokeWidth = 2.0;
    public String text = "";
    public int fontSize = 16;
    public boolean bold;
    public final Path2D.Double freehand = new Path2D.Double();

    /** Pixels per unit and the unit's name, for a measurement's label. */
    public double unitsPerPixel = 1.0;
    public String unitName = "px";

    public Annotation(Kind kind) {
        this.kind = kind;
    }

    public Annotation copy() {
        Annotation a = new Annotation(kind);
        a.x1 = x1;
        a.y1 = y1;
        a.x2 = x2;
        a.y2 = y2;
        a.color = color;
        a.fillColor = fillColor;
        a.strokeWidth = strokeWidth;
        a.text = text;
        a.fontSize = fontSize;
        a.bold = bold;
        a.unitsPerPixel = unitsPerPixel;
        a.unitName = unitName;
        a.freehand.append(freehand, false);
        return a;
    }

    public Rectangle2D.Double bounds() {
        if (kind == Kind.FREEHAND) {
            Rectangle2D b = freehand.getBounds2D();
            return new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
        }
        final double x = Math.min(x1, x2);
        final double y = Math.min(y1, y2);
        return new Rectangle2D.Double(x, y, Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    public boolean hits(double px, double py, double tolerance) {
        Rectangle2D.Double b = bounds();
        b.setRect(b.x - tolerance, b.y - tolerance,
                  b.width + tolerance * 2, b.height + tolerance * 2);
        return b.contains(px, py);
    }

    public void moveBy(double dx, double dy) {
        x1 += dx;
        y1 += dy;
        x2 += dx;
        y2 += dy;
        if (kind == Kind.FREEHAND) {
            freehand.transform(AffineTransform.getTranslateInstance(dx, dy));
        }
    }

    /** Draws in image space; the caller has already applied the zoom. */
    public void paint(Graphics2D g, double zoom) {
        // Keeping the stroke at its image-space width means an annotation looks
        // the same at every zoom, which is what makes it usable while zoomed in.
        final float width = (float) Math.max(strokeWidth, 0.1);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);

        switch (kind) {
            case LINE:
                g.draw(new Line2D.Double(x1, y1, x2, y2));
                break;
            case ARROW:
                paintArrow(g, width);
                break;
            case RECTANGLE: {
                Rectangle2D.Double b = bounds();
                if (fillColor != null) {
                    g.setColor(fillColor);
                    g.fill(b);
                    g.setColor(color);
                }
                g.draw(b);
                break;
            }
            case ELLIPSE: {
                Rectangle2D.Double b = bounds();
                Ellipse2D.Double e = new Ellipse2D.Double(b.x, b.y, b.width, b.height);
                if (fillColor != null) {
                    g.setColor(fillColor);
                    g.fill(e);
                    g.setColor(color);
                }
                g.draw(e);
                break;
            }
            case TEXT:
                paintText(g);
                break;
            case MEASURE:
                paintMeasure(g, width, zoom);
                break;
            case FREEHAND:
                g.draw(freehand);
                break;
            default:
                break;
        }
    }

    private void paintArrow(Graphics2D g, float width) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double length = Math.hypot(dx, dy);
        if (length < 1e-6) {
            return;
        }
        final double head = Math.max(width * 4.0, 8.0);
        final double angle = Math.atan2(dy, dx);

        // The shaft stops short of the tip so the head is not drawn over twice.
        final double shaft = Math.max(0, length - head * 0.8);
        g.draw(new Line2D.Double(x1, y1,
                                 x1 + Math.cos(angle) * shaft,
                                 y1 + Math.sin(angle) * shaft));

        Path2D.Double tip = new Path2D.Double();
        tip.moveTo(x2, y2);
        tip.lineTo(x2 - head * Math.cos(angle - Math.PI / 7),
                   y2 - head * Math.sin(angle - Math.PI / 7));
        tip.lineTo(x2 - head * Math.cos(angle + Math.PI / 7),
                   y2 - head * Math.sin(angle + Math.PI / 7));
        tip.closePath();
        g.fill(tip);
    }

    private void paintText(Graphics2D g) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Font font = new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN,
                             Math.max(fontSize, 1));
        g.setFont(font);
        String[] lines = text.split("\n", -1);
        final int lineHeight = g.getFontMetrics(font).getHeight();
        double y = y1;
        for (String line : lines) {
            g.drawString(line, (float) x1, (float) y);
            y += lineHeight;
        }
    }

    private void paintMeasure(Graphics2D g, float width, double zoom) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));

        // End caps, drawn across the line so the endpoints are unambiguous.
        final double angle = Math.atan2(y2 - y1, x2 - x1) + Math.PI / 2;
        final double cap = Math.max(width * 3, 6);
        g.draw(new Line2D.Double(x1 - Math.cos(angle) * cap, y1 - Math.sin(angle) * cap,
                                 x1 + Math.cos(angle) * cap, y1 + Math.sin(angle) * cap));
        g.draw(new Line2D.Double(x2 - Math.cos(angle) * cap, y2 - Math.sin(angle) * cap,
                                 x2 + Math.cos(angle) * cap, y2 + Math.sin(angle) * cap));

        final double pixels = Math.hypot(x2 - x1, y2 - y1);
        final double value = pixels * unitsPerPixel;
        final String label = String.format(java.util.Locale.ROOT, "%.2f %s", value, unitName);

        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(fontSize, 1));
        g.setFont(font);
        final int textWidth = g.getFontMetrics(font).stringWidth(label);
        final double mx = (x1 + x2) / 2 - textWidth / 2.0;
        final double my = (y1 + y2) / 2 - Math.max(width * 2, 4);

        // A plate behind the label keeps it readable over a busy image.
        Color plate = new Color(255, 255, 255, 205);
        g.setColor(plate);
        g.fill(new Rectangle2D.Double(mx - 3, my - fontSize, textWidth + 6, fontSize + 6));
        g.setColor(color);
        g.drawString(label, (float) mx, (float) my);
    }

    public Point2D.Double start() {
        return new Point2D.Double(x1, y1);
    }

    public Point2D.Double end() {
        return new Point2D.Double(x2, y2);
    }

    public String describe() {
        return switch (kind) {
            case ARROW -> "Arrow";
            case LINE -> "Line";
            case RECTANGLE -> "Rectangle";
            case ELLIPSE -> "Ellipse";
            case TEXT -> "Text: " + (text.length() > 18 ? text.substring(0, 18) + "..." : text);
            case MEASURE -> "Measurement";
            case FREEHAND -> "Freehand";
        };
    }
}
