package com.sphere.components.rootview;

/**
 * A TGraph, and the error bars of its variants.
 *
 * TGraphErrors and TGraphAsymmErrors write their errors after the base TGraph,
 * so the base is read first and the extra arrays only when the class says so.
 */
public final class RootGraph {

    public String name = "";
    public String title = "";
    public String className = "";

    public double[] x = new double[0];
    public double[] y = new double[0];
    public double[] exLow = new double[0];
    public double[] exHigh = new double[0];
    public double[] eyLow = new double[0];
    public double[] eyHigh = new double[0];

    public int size() {
        return Math.min(x.length, y.length);
    }

    public boolean hasErrors() {
        return eyLow.length > 0 || eyHigh.length > 0;
    }

    public double minX() {
        return min(x);
    }

    public double maxX() {
        return max(x);
    }

    public double minY() {
        return min(y);
    }

    public double maxY() {
        return max(y);
    }

    private static double min(double[] values) {
        double best = Double.POSITIVE_INFINITY;
        for (double v : values) {
            best = Math.min(best, v);
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best;
    }

    private static double max(double[] values) {
        double best = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            best = Math.max(best, v);
        }
        return best == Double.NEGATIVE_INFINITY ? 0 : best;
    }

    public double errorLow(int i) {
        if (i < eyLow.length) {
            return eyLow[i];
        }
        return 0;
    }

    public double errorHigh(int i) {
        if (i < eyHigh.length) {
            return eyHigh[i];
        }
        return i < eyLow.length ? eyLow[i] : 0;
    }

    public static RootGraph decode(byte[] payload, String className) {
        RootGraph g = new RootGraph();
        g.className = className;

        RootBuffer b = new RootBuffer(payload);
        RootBuffer.Header outer = b.beginObject();

        // TGraphErrors and its asymmetric cousin wrap a plain TGraph.
        final boolean wrapped = !className.equals("TGraph");
        RootBuffer.Header base = wrapped ? b.beginObject() : outer;

        String[] named = b.readTNamed();
        g.name = named[0];
        g.title = named[1];

        for (int i = 0; i < 3; i++) {          // line, fill and marker attributes
            RootBuffer.Header attributes = b.beginObject();
            b.endObject(attributes);
        }

        final int points = b.i32();
        g.x = b.readArrayD();
        g.y = b.readArrayD();
        if (points >= 0 && points < g.x.length) {
            // fNpoints can be smaller than the arrays the file reserved.
            g.x = java.util.Arrays.copyOf(g.x, points);
            g.y = java.util.Arrays.copyOf(g.y, Math.min(points, g.y.length));
        }

        if (wrapped) {
            b.endObject(base);
            if (className.equals("TGraphErrors")) {
                g.exLow = b.readArrayD();
                g.eyLow = b.readArrayD();
                g.exHigh = g.exLow;
                g.eyHigh = g.eyLow;
            } else if (className.equals("TGraphAsymmErrors")) {
                g.exLow = b.readArrayD();
                g.exHigh = b.readArrayD();
                g.eyLow = b.readArrayD();
                g.eyHigh = b.readArrayD();
            }
        }

        b.endObject(outer);
        return g;
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT,
            "%s   %d points%nx from %.6g to %.6g%ny from %.6g to %.6g%s",
            className, size(), minX(), maxX(), minY(), maxY(),
            hasErrors() ? "\nwith error bars" : "");
    }
}
