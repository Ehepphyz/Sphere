package com.sphere.components.spherebrowser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delaunay triangulation of points in a plane (Bowyer-Watson), which is what
 * TGraph2D draws its TRI surfaces through. The points are first brought into
 * a unit square, so that axes of very different scales triangulate as if
 * they were alike, as ROOT does.
 */
final class Delaunay {

    private Delaunay() {
    }

    /** Triangles as triples of point indices. */
    static int[] triangulate(double[] xs, double[] ys) {
        final int n = Math.min(xs.length, ys.length);
        if (n < 3) return new int[0];
        double x0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            x0 = Math.min(x0, xs[i]);
            x1 = Math.max(x1, xs[i]);
            y0 = Math.min(y0, ys[i]);
            y1 = Math.max(y1, ys[i]);
        }
        final double sx = x1 > x0 ? 1 / (x1 - x0) : 1;
        final double sy = y1 > y0 ? 1 / (y1 - y0) : 1;
        final double[] px = new double[n + 3];
        final double[] py = new double[n + 3];
        for (int i = 0; i < n; i++) {
            // A whisker of jitter keeps collinear grids from degenerate circles.
            px[i] = (xs[i] - x0) * sx + 1e-9 * Math.sin(i * 12.9898);
            py[i] = (ys[i] - y0) * sy + 1e-9 * Math.cos(i * 78.233);
        }
        // The super triangle, far around the unit square.
        px[n] = -50;
        py[n] = -50;
        px[n + 1] = 50;
        py[n + 1] = -50;
        px[n + 2] = 0.5;
        py[n + 2] = 60;
        List<int[]> tris = new ArrayList<>();
        tris.add(new int[]{n, n + 1, n + 2});
        List<double[]> circles = new ArrayList<>();
        circles.add(circle(px, py, n, n + 1, n + 2));
        for (int i = 0; i < n; i++) {
            final double x = px[i];
            final double y = py[i];
            final Map<Long, Integer> edges = new HashMap<>();
            final List<int[]> keep = new ArrayList<>(tris.size());
            final List<double[]> keepC = new ArrayList<>(tris.size());
            for (int t = 0; t < tris.size(); t++) {
                final double[] c = circles.get(t);
                final double dx = x - c[0];
                final double dy = y - c[1];
                if (dx * dx + dy * dy < c[2]) {
                    final int[] tr = tris.get(t);
                    count(edges, tr[0], tr[1]);
                    count(edges, tr[1], tr[2]);
                    count(edges, tr[2], tr[0]);
                } else {
                    keep.add(tris.get(t));
                    keepC.add(c);
                }
            }
            for (Map.Entry<Long, Integer> e : edges.entrySet()) {
                if (e.getValue() != 1) continue;
                final int a = (int) (e.getKey() >> 32);
                final int b = (int) (e.getKey() & 0xFFFFFFFFL);
                keep.add(new int[]{a, b, i});
                keepC.add(circle(px, py, a, b, i));
            }
            tris = keep;
            circles = keepC;
        }
        final List<Integer> out = new ArrayList<>();
        for (int[] t : tris) {
            if (t[0] >= n || t[1] >= n || t[2] >= n) continue;
            out.add(t[0]);
            out.add(t[1]);
            out.add(t[2]);
        }
        final int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static void count(Map<Long, Integer> edges, int a, int b) {
        final long key = a < b ? (long) a << 32 | b : (long) b << 32 | a;
        edges.merge(key, 1, Integer::sum);
    }

    /** Centre and squared radius of the circle through three points. */
    private static double[] circle(double[] x, double[] y, int a, int b, int c) {
        final double ax = x[a];
        final double ay = y[a];
        final double bx = x[b];
        final double by = y[b];
        final double cx = x[c];
        final double cy = y[c];
        final double d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by));
        if (Math.abs(d) < 1e-300) return new double[]{0, 0, Double.MAX_VALUE};
        final double a2 = ax * ax + ay * ay;
        final double b2 = bx * bx + by * by;
        final double c2 = cx * cx + cy * cy;
        final double ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d;
        final double uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d;
        return new double[]{ux, uy, (ax - ux) * (ax - ux) + (ay - uy) * (ay - uy)};
    }
}
