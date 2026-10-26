package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;

/** Planar polygon helpers: signed area, signed distance, containment, triangulation. */
public final class Polygon2D {

    private Polygon2D() {
    }

    /** Signed area of a polygon xy xy ...: positive when counter-clockwise. */
    public static double signedArea(double[] xy) {
        final int n = xy.length / 2;
        double a = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            a += xy[j * 2] * xy[i * 2 + 1] - xy[i * 2] * xy[j * 2 + 1];
        }
        return a / 2;
    }

    public static boolean contains(double[] xy, double x, double y) {
        final int n = xy.length / 2;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = xy[i * 2], yi = xy[i * 2 + 1], xj = xy[j * 2], yj = xy[j * 2 + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /** Exact signed distance to the polygon's boundary: negative inside. */
    public static double signedDistance(double[] xy, double x, double y) {
        final int n = xy.length / 2;
        double d2 = Double.MAX_VALUE;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = xy[i * 2], yi = xy[i * 2 + 1], xj = xy[j * 2], yj = xy[j * 2 + 1];
            final double ex = xi - xj, ey = yi - yj;
            final double wx = x - xj, wy = y - yj;
            final double l2 = ex * ex + ey * ey;
            double t = l2 > 0 ? (wx * ex + wy * ey) / l2 : 0;
            t = Math.max(0, Math.min(1, t));
            final double dx = wx - ex * t, dy = wy - ey * t;
            d2 = Math.min(d2, dx * dx + dy * dy);
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        final double d = Math.sqrt(d2);
        return in ? -d : d;
    }

    /**
     * Ear-clipping triangulation of a simple polygon. Returns index triples
     * into the input vertices, wound counter-clockwise; a clockwise input is
     * triangulated as given and the triples keep its orientation.
     */
    public static int[] triangulate(double[] xy) {
        final int n = xy.length / 2;
        if (n < 3) {
            return new int[0];
        }
        final boolean ccw = signedArea(xy) >= 0;
        final List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            idx.add(ccw ? i : n - 1 - i);
        }
        final List<Integer> out = new ArrayList<>((n - 2) * 3);
        int guard = 0;
        while (idx.size() > 3 && guard < n * n) {
            guard++;
            boolean clipped = false;
            final int m = idx.size();
            for (int k = 0; k < m; k++) {
                final int a = idx.get((k + m - 1) % m), b = idx.get(k), c = idx.get((k + 1) % m);
                if (isEar(xy, idx, a, b, c)) {
                    if (ccw) {
                        out.add(a);
                        out.add(b);
                        out.add(c);
                    } else {
                        out.add(c);
                        out.add(b);
                        out.add(a);
                    }
                    idx.remove(k);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                // Degenerate (collinear or self-touching) remainder: fan it.
                for (int k = 1; k + 1 < idx.size(); k++) {
                    if (ccw) {
                        out.add(idx.get(0));
                        out.add(idx.get(k));
                        out.add(idx.get(k + 1));
                    } else {
                        out.add(idx.get(k + 1));
                        out.add(idx.get(k));
                        out.add(idx.get(0));
                    }
                }
                idx.clear();
                break;
            }
        }
        if (idx.size() == 3) {
            if (ccw) {
                out.add(idx.get(0));
                out.add(idx.get(1));
                out.add(idx.get(2));
            } else {
                out.add(idx.get(2));
                out.add(idx.get(1));
                out.add(idx.get(0));
            }
        }
        final int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    private static boolean isEar(double[] xy, List<Integer> idx, int a, int b, int c) {
        final double ax = xy[a * 2], ay = xy[a * 2 + 1], bx = xy[b * 2], by = xy[b * 2 + 1],
            cx = xy[c * 2], cy = xy[c * 2 + 1];
        final double cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (cross <= 1e-18) {
            return false; // reflex or flat corner
        }
        for (int k : idx) {
            if (k == a || k == b || k == c) {
                continue;
            }
            final double px = xy[k * 2], py = xy[k * 2 + 1];
            if ((px == ax && py == ay) || (px == bx && py == by) || (px == cx && py == cy)) {
                continue;
            }
            if (inTriangle(px, py, ax, ay, bx, by, cx, cy)) {
                return false;
            }
        }
        return true;
    }

    private static boolean inTriangle(double px, double py, double ax, double ay, double bx, double by,
                                      double cx, double cy) {
        final double d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        final double d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        final double d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        final boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        final boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /** True when no two non-adjacent edges cross: what G4ExtrudedSolid requires. */
    public static boolean isSimple(double[] xy) {
        final int n = xy.length / 2;
        for (int i = 0; i < n; i++) {
            final int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++) {
                final int j2 = (j + 1) % n;
                if (j == i2 || j2 == i) {
                    continue;
                }
                if (segmentsCross(xy[i * 2], xy[i * 2 + 1], xy[i2 * 2], xy[i2 * 2 + 1],
                                  xy[j * 2], xy[j * 2 + 1], xy[j2 * 2], xy[j2 * 2 + 1])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean segmentsCross(double ax, double ay, double bx, double by,
                                         double cx, double cy, double dx, double dy) {
        final double d1 = orient(cx, cy, dx, dy, ax, ay), d2 = orient(cx, cy, dx, dy, bx, by);
        final double d3 = orient(ax, ay, bx, by, cx, cy), d4 = orient(ax, ay, bx, by, dx, dy);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0)) && d1 != 0 && d2 != 0 && d3 != 0 && d4 != 0;
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
