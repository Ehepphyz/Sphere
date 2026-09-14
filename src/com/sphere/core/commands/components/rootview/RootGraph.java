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

    /** False when the decode did not land where the object said it ends. */
    public boolean consistent = true;

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

    /** True when the points carry a width in x, as a binned measurement does. */
    public boolean hasWidths() {
        return exLow.length > 0 || exHigh.length > 0;
    }

    public double widthLow(int i) {
        return i < exLow.length ? exLow[i] : 0;
    }

    public double widthHigh(int i) {
        if (i < exHigh.length) {
            return exHigh[i];
        }
        return i < exLow.length ? exLow[i] : 0;
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

    /**
     * One column against another, both read from a tree.
     *
     * The points keep the order the entries had, so a scan of the tree and a
     * scan of the graph walk the same events.
     */
    public static RootGraph fromColumns(String xName, String yName,
                                        double[] xValues, double[] yValues) {
        RootGraph g = new RootGraph();
        final int count = Math.min(xValues.length, yValues.length);
        g.className = "TGraph";
        g.name = yName + " vs " + xName;
        g.title = g.name + "  (" + count + " points)";
        g.x = java.util.Arrays.copyOf(xValues, count);
        g.y = java.util.Arrays.copyOf(yValues, count);
        return g;
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
        // fX and fY are sized by fNpoints, and TGraph kept them as floats until
        // its version 3.
        final boolean narrow = base.version > 0 && base.version <= 2;
        g.x = b.readSizedArray(points, narrow);
        g.y = b.readSizedArray(points, narrow);

        if (wrapped) {
            b.endObject(base);
            final boolean narrowErrors = outer.version > 0 && outer.version <= 2;
            switch (className) {
                case "TGraphErrors" -> {
                    g.exLow = b.readSizedArray(points, narrowErrors);
                    g.eyLow = b.readSizedArray(points, narrowErrors);
                    g.exHigh = g.exLow;
                    g.eyHigh = g.eyLow;
                }
                case "TGraphAsymmErrors", "TGraphBentErrors" -> {
                    // The bent variant adds four more arrays after these, which
                    // the object's byte count steps over.
                    g.exLow = b.readSizedArray(points, narrowErrors);
                    g.exHigh = b.readSizedArray(points, narrowErrors);
                    g.eyLow = b.readSizedArray(points, narrowErrors);
                    g.eyHigh = b.readSizedArray(points, narrowErrors);
                }
                default -> {
                    // Another variant of the family: the points are in the base
                    // and are drawn, whatever it keeps after them.
                }
            }
        }

        // Every object says how long it is. Landing somewhere else means the
        // members were read in the wrong shape, which is worth saying rather
        // than drawing an empty frame.
        g.consistent = b.landedInside(outer) && g.x.length == Math.max(0, points);

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
