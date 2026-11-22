package com.sphere.components.g4editor;

import com.sphere.core.geant4.Polygon2D;
import com.sphere.core.geant4.Units;

import java.util.ArrayList;
import java.util.List;

/**
 * Freehand strokes read as shapes, the way a pen on a whiteboard draws: a loop that stays round is a
 * circle (or an ellipse), one with three or four sharp turns and straight sides between them a triangle
 * or a rectangle (upright when it is within a few degrees of it), more turns a polygon (made regular when
 * it nearly is: a hexagonal cell drawn by hand comes out a hexagon), any other closed outline a polygon of
 * its main corners, and a straight stroke a line. Everything is in the work plane's (u, v), with the size
 * of a pixel there to judge what is sloppy and what is meant.
 */
final class Sketch {

    private Sketch() {
    }

    enum Kind { LINE, CIRCLE, ELLIPSE, RECTANGLE, TRIANGLE, REGULAR, POLYGON }

    /**
     * A shape read from a stroke. Circle: center and r. Ellipse: center, semi-axes a and b, angle of a.
     * Rectangle: center, sides a and b, angle of a. Polygons: their corners (u, v). Line: its two ends.
     */
    record Shape(Kind kind, double cu, double cv, double r, double a, double b, double angle, List<double[]> corners) {

        String describe() {
            return switch (kind) {
                case LINE -> "line " + len(Math.hypot(corners.get(1)[0] - corners.get(0)[0],
                    corners.get(1)[1] - corners.get(0)[1]));
                case CIRCLE -> "circle R " + len(r);
                case ELLIPSE -> "ellipse " + len(2 * a) + " x " + len(2 * b);
                case RECTANGLE -> (angle == 0 ? "rectangle " : "tilted rectangle ") + len(a) + " x " + len(b);
                case TRIANGLE -> "triangle";
                case REGULAR -> name(corners.size());
                case POLYGON -> "outline of " + corners.size() + " corners";
            };
        }

        private static String len(double v) {
            return Units.best(v, Units.Category.LENGTH);
        }

        private static String name(int n) {
            return switch (n) {
                case 5 -> "pentagon";
                case 6 -> "hexagon";
                case 7 -> "heptagon";
                case 8 -> "octagon";
                default -> n + "-sided polygon";
            };
        }
    }

    /** Reads a stroke (plane points, in drawing order); null when it is too short or reads as nothing. */
    static Shape recognize(List<double[]> stroke, double px) {
        if (stroke.size() < 2 || !(px > 0)) {
            return null;
        }
        final double len = length(stroke, false);
        if (len < 12 * px) {
            return null; // a click, not a stroke
        }
        final int n = (int) Math.max(24, Math.min(240, len / (2.5 * px)));
        final List<double[]> p = resample(stroke, n, false);

        // Closed when it comes back to its start, past its first third; what overshoots the start is dropped
        int cut = n - 1;
        double gap = Double.MAX_VALUE;
        for (int i = n / 3; i < n; i++) {
            final double d = dist(p.get(i), p.get(0));
            if (d < gap) {
                gap = d;
                cut = i;
            }
        }
        boolean closed = gap < Math.max(18 * px, 0.12 * len);
        if (!closed) {
            final double chord = dist(p.get(0), p.get(n - 1));
            final double dev = deviation(p, 0, n - 1);
            if (chord > 10 * px && dev < Math.max(6 * px, 0.06 * chord)) {
                return new Shape(Kind.LINE, (p.get(0)[0] + p.get(n - 1)[0]) / 2, (p.get(0)[1] + p.get(n - 1)[1]) / 2, 0,
                    chord, 0, Math.atan2(p.get(n - 1)[1] - p.get(0)[1], p.get(n - 1)[0] - p.get(0)[0]),
                    List.of(p.get(0), p.get(n - 1)));
            }
            // a loop left a little open is a loop
            if (chord > 0.3 * len) {
                return null;
            }
            cut = n - 1;
        }
        final List<double[]> loop = resample(new ArrayList<>(p.subList(0, cut + 1)), 96, true);
        final double perimeter = length(loop, true);

        // Straight sides between sharp turns: a polygon
        final List<double[]> poly = corners(loop, px);
        final double[] circle = fitCircle(loop);
        final double roundness = circle == null ? 1 : circleResidual(loop, circle);
        if (poly != null && !(roundness < 0.025 && poly.size() > 4)) {
            return polygon(poly);
        }
        // Round: a circle, or an ellipse when it is clearly longer one way
        if (circle != null && roundness < 0.12) {
            final Shape e = ellipse(loop);
            if (e != null && e.a() / e.b() > 1.3) {
                return e;
            }
            return new Shape(Kind.CIRCLE, circle[0], circle[1], circle[2], 0, 0, 0, List.of());
        }
        final Shape e = ellipse(loop);
        if (e != null) {
            return e;
        }
        // Any other outline: its main corners
        final List<double[]> free = simplifyClosed(loop, Math.max(3 * px, 0.015 * perimeter));
        if (free.size() < 3) {
            return null;
        }
        final double[] xy = flat(free);
        if (!Polygon2D.isSimple(xy)) {
            return null;
        }
        return new Shape(Kind.POLYGON, centroid(free)[0], centroid(free)[1], 0, 0, 0, 0, free);
    }

    // ------------------------------------------------------------------
    // Polygons
    // ------------------------------------------------------------------

    /**
     * The corners of a closed loop when it is a polygon: sharp turns (40 degrees and more over a few
     * percent of the outline) with straight sides between them; null otherwise.
     */
    private static List<double[]> corners(List<double[]> loop, double px) {
        final int m = loop.size(), k = 3;
        final double[] turn = new double[m];
        for (int i = 0; i < m; i++) {
            turn[i] = turnAngle(loop.get(Math.floorMod(i - k, m)), loop.get(i), loop.get((i + k) % m));
        }
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (turn[i] < Math.toRadians(40)) {
                continue;
            }
            boolean peak = true;
            for (int j = -k; j <= k && peak; j++) {
                final int o = Math.floorMod(i + j, m);
                if (j != 0 && (turn[o] > turn[i] || (turn[o] == turn[i] && o < i))) {
                    peak = false;
                }
            }
            if (peak) {
                idx.add(i);
            }
        }
        if (idx.size() < 3) {
            return null;
        }
        // Straight sides between them
        for (int c = 0; c < idx.size(); c++) {
            final int a = idx.get(c), b = idx.get((c + 1) % idx.size());
            final double chord = dist(loop.get(a), loop.get(b));
            double dev = 0;
            for (int i = (a + 1) % m; i != b; i = (i + 1) % m) {
                dev = Math.max(dev, segDist(loop.get(i), loop.get(a), loop.get(b)));
            }
            if (dev > Math.max(4 * px, 0.09 * chord)) {
                return null;
            }
        }
        final List<double[]> pts = new ArrayList<>();
        for (int i : idx) {
            pts.add(loop.get(i));
        }
        // A corner that hardly turns (a wobble on a side) is no corner
        boolean changed = true;
        while (changed && pts.size() > 3) {
            changed = false;
            for (int i = 0; i < pts.size(); i++) {
                final double[] a = pts.get(Math.floorMod(i - 1, pts.size())), b = pts.get(i), c = pts.get((i + 1) % pts.size());
                if (turnAngle(a, b, c) < Math.toRadians(25)) {
                    pts.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return pts.size() >= 3 ? pts : null;
    }

    private static Shape polygon(List<double[]> pts) {
        final int n = pts.size();
        if (n == 4) {
            final Shape r = rectangle(pts);
            if (r != null) {
                return r;
            }
        }
        final double[] c = centroid(pts);
        if (n == 3) {
            return new Shape(Kind.TRIANGLE, c[0], c[1], 0, 0, 0, 0, pts);
        }
        if (n >= 5) {
            // Nearly regular: made regular, a side or a corner on top when it nearly is
            double mr = 0, ms = 0;
            final double[] r = new double[n], s = new double[n];
            for (int i = 0; i < n; i++) {
                r[i] = dist(pts.get(i), c);
                s[i] = dist(pts.get(i), pts.get((i + 1) % n));
                mr += r[i] / n;
                ms += s[i] / n;
            }
            double vr = 0, vs = 0;
            for (int i = 0; i < n; i++) {
                vr += (r[i] - mr) * (r[i] - mr) / n;
                vs += (s[i] - ms) * (s[i] - ms) / n;
            }
            if (Math.sqrt(vr) / mr < 0.12 && Math.sqrt(vs) / ms < 0.2) {
                final double step = 2 * Math.PI / n;
                double phase = Math.atan2(pts.get(0)[1] - c[1], pts.get(0)[0] - c[0]);
                for (double canon : new double[] {Math.PI / 2, Math.PI / 2 + step / 2}) {
                    final double d = Math.IEEEremainder(phase - canon, step);
                    if (Math.abs(d) < Math.toRadians(10)) {
                        phase -= d;
                    }
                }
                final boolean ccw = signedArea(pts) > 0;
                final List<double[]> reg = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    final double t = phase + (ccw ? 1 : -1) * step * i;
                    reg.add(new double[] {c[0] + mr * Math.cos(t), c[1] + mr * Math.sin(t)});
                }
                return new Shape(Kind.REGULAR, c[0], c[1], mr, 0, 0, phase, reg);
            }
        }
        return new Shape(Kind.POLYGON, c[0], c[1], 0, 0, 0, 0, pts);
    }

    /** Four corners at right angles, within 20 degrees: a rectangle, upright within 8 degrees of it. */
    private static Shape rectangle(List<double[]> pts) {
        double cx = 0, sy = 0;
        for (int i = 0; i < 4; i++) {
            final double[] a = pts.get((i + 3) % 4), b = pts.get(i), c = pts.get((i + 1) % 4);
            final double interior = Math.PI - turnAngle(a, b, c);
            if (Math.abs(interior - Math.PI / 2) > Math.toRadians(20)) {
                return null;
            }
            final double t = Math.atan2(c[1] - b[1], c[0] - b[0]);
            // sides are a quarter turn apart: 4t is the same for all of them
            cx += Math.cos(4 * t);
            sy += Math.sin(4 * t);
        }
        double angle = Math.atan2(sy, cx) / 4;
        if (Math.abs(angle) < Math.toRadians(8)) {
            angle = 0;
        }
        final double ca = Math.cos(-angle), sa = Math.sin(-angle);
        double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
        for (double[] p : pts) {
            final double x = p[0] * ca - p[1] * sa, y = p[0] * sa + p[1] * ca;
            minA = Math.min(minA, x);
            maxA = Math.max(maxA, x);
            minB = Math.min(minB, y);
            maxB = Math.max(maxB, y);
        }
        // the four sides, averaged: the middle of the extents
        final double a = maxA - minA, b = maxB - minB;
        final double mx = (minA + maxA) / 2, my = (minB + maxB) / 2;
        final double cu = mx * Math.cos(angle) - my * Math.sin(angle), cv = mx * Math.sin(angle) + my * Math.cos(angle);
        return new Shape(Kind.RECTANGLE, cu, cv, 0, a, b, angle, pts);
    }

    // ------------------------------------------------------------------
    // Round shapes
    // ------------------------------------------------------------------

    /** Least-squares circle (Kasa): center u, v and radius; null when degenerate. */
    static double[] fitCircle(List<double[]> pts) {
        final double[] c = centroid(pts);
        double suu = 0, suv = 0, svv = 0, suuu = 0, svvv = 0, suvv = 0, svuu = 0;
        for (double[] p : pts) {
            final double u = p[0] - c[0], v = p[1] - c[1];
            suu += u * u;
            suv += u * v;
            svv += v * v;
            suuu += u * u * u;
            svvv += v * v * v;
            suvv += u * v * v;
            svuu += v * u * u;
        }
        final double det = suu * svv - suv * suv;
        if (Math.abs(det) < 1e-30) {
            return null;
        }
        final double bu = (suuu + suvv) / 2, bv = (svvv + svuu) / 2;
        final double uc = (bu * svv - bv * suv) / det, vc = (bv * suu - bu * suv) / det;
        final double r = Math.sqrt(uc * uc + vc * vc + (suu + svv) / pts.size());
        return new double[] {uc + c[0], vc + c[1], r};
    }

    /** The spread of the points about a circle, over its radius: 0 for a perfect circle. */
    static double circleResidual(List<double[]> pts, double[] circle) {
        double s = 0;
        for (double[] p : pts) {
            final double d = Math.hypot(p[0] - circle[0], p[1] - circle[1]) - circle[2];
            s += d * d;
        }
        return Math.sqrt(s / pts.size()) / circle[2];
    }

    /** An ellipse along the loop's principal axes, when the loop follows one; upright within 10 degrees. */
    private static Shape ellipse(List<double[]> pts) {
        final double[] c = centroid(pts);
        double xx = 0, xy = 0, yy = 0;
        for (double[] p : pts) {
            final double u = p[0] - c[0], v = p[1] - c[1];
            xx += u * u;
            xy += u * v;
            yy += v * v;
        }
        double angle = 0.5 * Math.atan2(2 * xy, xx - yy);
        if (Math.abs(Math.IEEEremainder(angle, Math.PI / 2)) < Math.toRadians(10)) {
            angle -= Math.IEEEremainder(angle, Math.PI / 2);
        }
        final double ca = Math.cos(-angle), sa = Math.sin(-angle);
        double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
        final List<double[]> local = new ArrayList<>();
        for (double[] p : pts) {
            final double u = p[0] - c[0], v = p[1] - c[1];
            final double x = u * ca - v * sa, y = u * sa + v * ca;
            local.add(new double[] {x, y});
            minA = Math.min(minA, x);
            maxA = Math.max(maxA, x);
            minB = Math.min(minB, y);
            maxB = Math.max(maxB, y);
        }
        double a = (maxA - minA) / 2, b = (maxB - minB) / 2;
        final double ox = (maxA + minA) / 2, oy = (maxB + minB) / 2;
        if (a <= 0 || b <= 0) {
            return null;
        }
        double s = 0;
        for (double[] p : local) {
            final double d = Math.hypot((p[0] - ox) / a, (p[1] - oy) / b) - 1;
            s += d * d;
        }
        if (Math.sqrt(s / local.size()) > 0.1) {
            return null;
        }
        // the middle of the extents, back in the plane (before a and b may swap)
        final double cu = c[0] + ox * Math.cos(angle) - oy * Math.sin(angle);
        final double cv = c[1] + ox * Math.sin(angle) + oy * Math.cos(angle);
        if (b > a) {
            final double t = a;
            a = b;
            b = t;
            angle += Math.PI / 2;
        }
        angle = Math.IEEEremainder(angle, Math.PI);
        if (Math.abs(angle) < 1e-9) {
            angle = 0;
        } else if (Math.abs(Math.abs(angle) - Math.PI / 2) < 1e-9) {
            angle = Math.PI / 2;
        }
        return new Shape(Kind.ELLIPSE, cu, cv, 0, a, b, angle, List.of());
    }

    // ------------------------------------------------------------------
    // Polylines
    // ------------------------------------------------------------------

    /** Points spaced evenly along a path (closed: around the loop), n of them. */
    static List<double[]> resample(List<double[]> pts, int n, boolean closed) {
        final List<double[]> src = new ArrayList<>(pts);
        if (closed) {
            src.add(pts.get(0));
        }
        final double total = length(src, false);
        final List<double[]> out = new ArrayList<>();
        if (total <= 0) {
            out.add(pts.get(0).clone());
            return out;
        }
        final double step = total / (closed ? n : n - 1);
        out.add(src.get(0).clone());
        double acc = 0;
        int i = 1;
        double[] prev = src.get(0);
        while (out.size() < n && i < src.size()) {
            final double[] cur = src.get(i);
            final double d = dist(prev, cur);
            if (acc + d >= step && d > 0) {
                final double t = (step - acc) / d;
                final double[] q = {prev[0] + (cur[0] - prev[0]) * t, prev[1] + (cur[1] - prev[1]) * t};
                out.add(q);
                prev = q;
                acc = 0;
            } else {
                acc += d;
                prev = cur;
                i++;
            }
        }
        while (out.size() < n && !closed) {
            out.add(src.get(src.size() - 1).clone());
        }
        return out;
    }

    /** Ramer-Douglas-Peucker on a closed loop, split at its two farthest points. */
    static List<double[]> simplifyClosed(List<double[]> loop, double eps) {
        final int n = loop.size();
        int far = 0;
        double fd = -1;
        for (int i = 1; i < n; i++) {
            final double d = dist(loop.get(0), loop.get(i));
            if (d > fd) {
                fd = d;
                far = i;
            }
        }
        final List<double[]> a = new ArrayList<>(loop.subList(0, far + 1));
        final List<double[]> b = new ArrayList<>(loop.subList(far, n));
        b.add(loop.get(0));
        final List<double[]> out = new ArrayList<>(rdp(a, eps));
        final List<double[]> rest = rdp(b, eps);
        out.addAll(rest.subList(1, rest.size() - 1));
        return out;
    }

    private static List<double[]> rdp(List<double[]> pts, double eps) {
        if (pts.size() < 3) {
            return new ArrayList<>(pts);
        }
        int idx = -1;
        double dmax = 0;
        for (int i = 1; i < pts.size() - 1; i++) {
            final double d = segDist(pts.get(i), pts.get(0), pts.get(pts.size() - 1));
            if (d > dmax) {
                dmax = d;
                idx = i;
            }
        }
        if (dmax <= eps) {
            return new ArrayList<>(List.of(pts.get(0), pts.get(pts.size() - 1)));
        }
        final List<double[]> left = rdp(pts.subList(0, idx + 1), eps), right = rdp(pts.subList(idx, pts.size()), eps);
        final List<double[]> out = new ArrayList<>(left.subList(0, left.size() - 1));
        out.addAll(right);
        return out;
    }

    static double length(List<double[]> pts, boolean closed) {
        double l = 0;
        for (int i = 1; i < pts.size(); i++) {
            l += dist(pts.get(i - 1), pts.get(i));
        }
        if (closed && pts.size() > 1) {
            l += dist(pts.get(pts.size() - 1), pts.get(0));
        }
        return l;
    }

    /** The largest distance of the points between two indices to the chord joining them. */
    private static double deviation(List<double[]> p, int a, int b) {
        double d = 0;
        for (int i = a + 1; i < b; i++) {
            d = Math.max(d, segDist(p.get(i), p.get(a), p.get(b)));
        }
        return d;
    }

    static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static double segDist(double[] p, double[] a, double[] b) {
        final double dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
        double t = l2 > 0 ? ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p[0] - a[0] - dx * t, p[1] - a[1] - dy * t);
    }

    /** The angle a path turns by at b, from a to c: 0 straight on, pi back. */
    static double turnAngle(double[] a, double[] b, double[] c) {
        final double ux = b[0] - a[0], uy = b[1] - a[1], vx = c[0] - b[0], vy = c[1] - b[1];
        final double lu = Math.hypot(ux, uy), lv = Math.hypot(vx, vy);
        if (lu < 1e-300 || lv < 1e-300) {
            return 0;
        }
        return Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / (lu * lv))));
    }

    static double[] centroid(List<double[]> pts) {
        double u = 0, v = 0;
        for (double[] p : pts) {
            u += p[0];
            v += p[1];
        }
        return new double[] {u / pts.size(), v / pts.size()};
    }

    static double signedArea(List<double[]> pts) {
        double a = 0;
        for (int i = 0; i < pts.size(); i++) {
            final double[] p = pts.get(i), q = pts.get((i + 1) % pts.size());
            a += p[0] * q[1] - q[0] * p[1];
        }
        return a / 2;
    }

    static double[] flat(List<double[]> pts) {
        final double[] xy = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            xy[i * 2] = pts.get(i)[0];
            xy[i * 2 + 1] = pts.get(i)[1];
        }
        return xy;
    }
}
