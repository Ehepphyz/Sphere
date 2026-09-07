package com.sphere.components.imaging.svg;

import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Turns the `d` attribute of an SVG path into a Path2D.
 *
 * The grammar allows numbers to run together without separators, so the scanner
 * reads them itself rather than splitting on whitespace.
 */
public final class SvgPathParser {

    private final String data;
    private int pos;

    private double currentX;
    private double currentY;
    private double startX;
    private double startY;

    // Kept so a smooth curve can mirror the control point of the previous one.
    private double lastControlX;
    private double lastControlY;
    private char lastCommand;

    private SvgPathParser(String data) {
        this.data = data == null ? "" : data;
    }

    public static Path2D.Double parse(String data) {
        return new SvgPathParser(data).run();
    }

    private Path2D.Double run() {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        char command = 0;

        while (true) {
            skipSeparators();
            if (pos >= data.length()) {
                break;
            }
            char c = data.charAt(pos);
            if (Character.isLetter(c)) {
                command = c;
                pos++;
            } else if (command == 0) {
                break; // numbers before any command: nothing to attach them to
            } else if (command == 'M') {
                command = 'L'; // repeated coordinates after a moveto are linetos
            } else if (command == 'm') {
                command = 'l';
            }

            if (!step(path, command)) {
                break;
            }
        }
        return path;
    }

    private boolean step(Path2D.Double path, char command) {
        final boolean relative = Character.isLowerCase(command);
        final char op = Character.toUpperCase(command);

        switch (op) {
            case 'M': {
                Double x = number();
                Double y = number();
                if (x == null || y == null) {
                    return false;
                }
                currentX = relative ? currentX + x : x;
                currentY = relative ? currentY + y : y;
                startX = currentX;
                startY = currentY;
                path.moveTo(currentX, currentY);
                break;
            }
            case 'L': {
                Double x = number();
                Double y = number();
                if (x == null || y == null) {
                    return false;
                }
                currentX = relative ? currentX + x : x;
                currentY = relative ? currentY + y : y;
                lineOrMove(path);
                break;
            }
            case 'H': {
                Double x = number();
                if (x == null) {
                    return false;
                }
                currentX = relative ? currentX + x : x;
                lineOrMove(path);
                break;
            }
            case 'V': {
                Double y = number();
                if (y == null) {
                    return false;
                }
                currentY = relative ? currentY + y : y;
                lineOrMove(path);
                break;
            }
            case 'C': {
                Double x1 = number(), y1 = number();
                Double x2 = number(), y2 = number();
                Double x = number(), y = number();
                if (y == null) {
                    return false;
                }
                double cx1 = relative ? currentX + x1 : x1;
                double cy1 = relative ? currentY + y1 : y1;
                double cx2 = relative ? currentX + x2 : x2;
                double cy2 = relative ? currentY + y2 : y2;
                currentX = relative ? currentX + x : x;
                currentY = relative ? currentY + y : y;
                ensureStarted(path);
                path.curveTo(cx1, cy1, cx2, cy2, currentX, currentY);
                lastControlX = cx2;
                lastControlY = cy2;
                break;
            }
            case 'S': {
                Double x2 = number(), y2 = number();
                Double x = number(), y = number();
                if (y == null) {
                    return false;
                }
                double cx1 = currentX;
                double cy1 = currentY;
                if (lastCommand == 'C' || lastCommand == 'S') {
                    cx1 = 2 * currentX - lastControlX;
                    cy1 = 2 * currentY - lastControlY;
                }
                double cx2 = relative ? currentX + x2 : x2;
                double cy2 = relative ? currentY + y2 : y2;
                currentX = relative ? currentX + x : x;
                currentY = relative ? currentY + y : y;
                ensureStarted(path);
                path.curveTo(cx1, cy1, cx2, cy2, currentX, currentY);
                lastControlX = cx2;
                lastControlY = cy2;
                break;
            }
            case 'Q': {
                Double x1 = number(), y1 = number();
                Double x = number(), y = number();
                if (y == null) {
                    return false;
                }
                double cx = relative ? currentX + x1 : x1;
                double cy = relative ? currentY + y1 : y1;
                currentX = relative ? currentX + x : x;
                currentY = relative ? currentY + y : y;
                ensureStarted(path);
                path.quadTo(cx, cy, currentX, currentY);
                lastControlX = cx;
                lastControlY = cy;
                break;
            }
            case 'T': {
                Double x = number(), y = number();
                if (y == null) {
                    return false;
                }
                double cx = currentX;
                double cy = currentY;
                if (lastCommand == 'Q' || lastCommand == 'T') {
                    cx = 2 * currentX - lastControlX;
                    cy = 2 * currentY - lastControlY;
                }
                currentX = relative ? currentX + x : x;
                currentY = relative ? currentY + y : y;
                ensureStarted(path);
                path.quadTo(cx, cy, currentX, currentY);
                lastControlX = cx;
                lastControlY = cy;
                break;
            }
            case 'A': {
                Double rx = number(), ry = number();
                Double rotation = number();
                Double largeArc = flag(), sweep = flag();
                Double x = number(), y = number();
                if (y == null) {
                    return false;
                }
                double endX = relative ? currentX + x : x;
                double endY = relative ? currentY + y : y;
                arc(path, rx, ry, rotation, largeArc != 0, sweep != 0, endX, endY);
                currentX = endX;
                currentY = endY;
                break;
            }
            case 'Z': {
                if (hasGeometry(path)) {
                    path.closePath();
                }
                currentX = startX;
                currentY = startY;
                break;
            }
            default:
                return false;
        }

        lastCommand = op;
        return true;
    }

    private void lineOrMove(Path2D.Double path) {
        ensureStarted(path);
        path.lineTo(currentX, currentY);
    }

    /** A path that draws before any moveto is invalid; anchor it at the origin. */
    private void ensureStarted(Path2D.Double path) {
        if (!hasGeometry(path)) {
            path.moveTo(currentX, currentY);
        }
    }

    private static boolean hasGeometry(Path2D.Double path) {
        return path.getCurrentPoint() != null;
    }

    /**
     * Endpoint-parameterized elliptical arc, converted to the center form Java's
     * Arc2D needs (SVG 1.1, appendix F.6).
     */
    private void arc(Path2D.Double path, double rx, double ry, double rotationDeg,
                     boolean largeArc, boolean sweep, double endX, double endY) {
        if (rx == 0 || ry == 0) {
            ensureStarted(path);
            path.lineTo(endX, endY);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);

        final double angle = Math.toRadians(rotationDeg % 360.0);
        final double cosA = Math.cos(angle);
        final double sinA = Math.sin(angle);

        final double dx2 = (currentX - endX) / 2.0;
        final double dy2 = (currentY - endY) / 2.0;
        final double x1 = cosA * dx2 + sinA * dy2;
        final double y1 = -sinA * dx2 + cosA * dy2;

        // Radii too small to reach the endpoint are scaled up, as the spec asks.
        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lambda > 1.0) {
            final double scale = Math.sqrt(lambda);
            rx *= scale;
            ry *= scale;
        }

        final double rxSq = rx * rx;
        final double rySq = ry * ry;
        final double x1Sq = x1 * x1;
        final double y1Sq = y1 * y1;

        double factor = (rxSq * rySq - rxSq * y1Sq - rySq * x1Sq)
                      / (rxSq * y1Sq + rySq * x1Sq);
        factor = Math.sqrt(Math.max(0.0, factor));
        if (largeArc == sweep) {
            factor = -factor;
        }
        final double cx1 = factor * rx * y1 / ry;
        final double cy1 = -factor * ry * x1 / rx;

        final double cx = cosA * cx1 - sinA * cy1 + (currentX + endX) / 2.0;
        final double cy = sinA * cx1 + cosA * cy1 + (currentY + endY) / 2.0;

        final double startAngle = angleBetween(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry);
        double extent = angleBetween((x1 - cx1) / rx, (y1 - cy1) / ry,
                                     (-x1 - cx1) / rx, (-y1 - cy1) / ry);
        extent = Math.toDegrees(extent) % 360.0;
        if (!sweep && extent > 0) {
            extent -= 360.0;
        } else if (sweep && extent < 0) {
            extent += 360.0;
        }

        // Arc2D works in a y-up frame, SVG in a y-down one, hence the negations.
        Arc2D.Double shape = new Arc2D.Double(
            cx - rx, cy - ry, rx * 2, ry * 2,
            -Math.toDegrees(startAngle), -extent, Arc2D.OPEN);

        AffineTransform rotate = AffineTransform.getRotateInstance(angle, cx, cy);
        ensureStarted(path);
        path.append(rotate.createTransformedShape(shape), true);
    }

    private static double angleBetween(double ux, double uy, double vx, double vy) {
        final double dot = ux * vx + uy * vy;
        final double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double value = len == 0 ? 0 : dot / len;
        value = Math.max(-1.0, Math.min(1.0, value));
        final double sign = (ux * vy - uy * vx) < 0 ? -1.0 : 1.0;
        return sign * Math.acos(value);
    }

    // ---- scanning ----------------------------------------------------------

    private void skipSeparators() {
        while (pos < data.length()) {
            char c = data.charAt(pos);
            if (c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                pos++;
            } else {
                return;
            }
        }
    }

    /** Reads one number, or null when the next token is not one. */
    private Double number() {
        skipSeparators();
        final int start = pos;
        if (pos < data.length() && (data.charAt(pos) == '+' || data.charAt(pos) == '-')) {
            pos++;
        }
        while (pos < data.length() && Character.isDigit(data.charAt(pos))) {
            pos++;
        }
        if (pos < data.length() && data.charAt(pos) == '.') {
            pos++;
            while (pos < data.length() && Character.isDigit(data.charAt(pos))) {
                pos++;
            }
        }
        if (pos < data.length() && (data.charAt(pos) == 'e' || data.charAt(pos) == 'E')) {
            final int mark = pos;
            pos++;
            if (pos < data.length() && (data.charAt(pos) == '+' || data.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < data.length() && Character.isDigit(data.charAt(pos))) {
                while (pos < data.length() && Character.isDigit(data.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = mark; // an `e` that starts no exponent belongs to the next token
            }
        }
        if (pos == start) {
            return null;
        }
        try {
            return Double.valueOf(data.substring(start, pos));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Arc flags are single digits that may be written without a separator, so
     * "0 0 1" and "001" both mean the same three flags.
     */
    private Double flag() {
        skipSeparators();
        if (pos < data.length()) {
            char c = data.charAt(pos);
            if (c == '0' || c == '1') {
                pos++;
                return c == '1' ? 1.0 : 0.0;
            }
        }
        Double value = number();
        return value == null ? 0.0 : value;
    }

    /** Where the path ends, for callers that chain segments. */
    public static Point2D endPoint(Path2D.Double path) {
        Point2D point = path.getCurrentPoint();
        return point == null ? new Point2D.Double(0, 0) : point;
    }
}
