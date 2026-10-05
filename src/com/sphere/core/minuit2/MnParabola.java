package com.sphere.core.minuit2;

/** The parabola a x^2 + b x + c, and its making through three points (MnParabolaFactory). */
public record MnParabola(double a, double b, double c) {

    /** A point (x, y) (MnPoint). */
    public record Point(double x, double y) {
    }

    public double y(double x) {
        return a * x * x + b * x + c;
    }

    /** Where the parabola is extreme. */
    public double min() {
        return -b / (2. * a);
    }

    public double yMin() {
        return -b * b / (4. * a) + c;
    }

    /** Through three points, computed about their mean abscissa. */
    public static MnParabola through(Point p1, Point p2, Point p3) {
        double x1 = p1.x();
        double x2 = p2.x();
        double x3 = p3.x();
        final double dx12 = x1 - x2;
        final double dx13 = x1 - x3;
        final double dx23 = x2 - x3;
        final double xm = (x1 + x2 + x3) / 3.;
        x1 -= xm;
        x2 -= xm;
        x3 -= xm;
        final double y1 = p1.y();
        final double y2 = p2.y();
        final double y3 = p3.y();
        final double a = y1 / (dx12 * dx13) - y2 / (dx12 * dx23) + y3 / (dx13 * dx23);
        double b = -y1 * (x2 + x3) / (dx12 * dx13) + y2 * (x1 + x3) / (dx12 * dx23) - y3 * (x1 + x2) / (dx13 * dx23);
        double c = y1 - a * x1 * x1 - b * x1;
        c += xm * (xm * a - b);
        b -= 2. * xm * a;
        return new MnParabola(a, b, c);
    }

    /** Through two points and the slope at the first. */
    public static MnParabola through(Point p1, double dxdy1, Point p2) {
        final double x1 = p1.x();
        final double xx1 = x1 * x1;
        final double x2 = p2.x();
        final double xx2 = x2 * x2;
        final double y1 = p1.y();
        final double y12 = p1.y() - p2.y();
        final double det = xx1 - xx2 - 2. * x1 * (x1 - x2);
        final double a = -(y12 + (x2 - x1) * dxdy1) / det;
        final double b = -(-2. * x1 * y12 + (xx1 - xx2) * dxdy1) / det;
        final double c = y1 - a * xx1 - b * x1;
        return new MnParabola(a, b, c);
    }
}
