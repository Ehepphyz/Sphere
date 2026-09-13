package com.sphere.components.rootview;

import java.util.Locale;

/**
 * A TGraph2D: points that carry a third value rather than sitting on a grid.
 *
 * The counts come first -- how many points, then the drawing resolution and the
 * iteration cap -- and the three arrays follow, each sized by the point count.
 * TGraph2DErrors keeps its own three arrays after the base.
 */
public final class RootGraph2D {

    public String name = "";
    public String title = "";
    public String className = "";

    /** False when the decode did not land where the object said it ends. */
    public boolean consistent = true;

    public double[] x = new double[0];
    public double[] y = new double[0];
    public double[] z = new double[0];
    public double[] ex = new double[0];
    public double[] ey = new double[0];
    public double[] ez = new double[0];

    public int size() {
        return Math.min(x.length, Math.min(y.length, z.length));
    }

    public boolean hasErrors() {
        return ez.length > 0;
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

    public double minZ() {
        return min(z);
    }

    public double maxZ() {
        return max(z);
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

    public static RootGraph2D decode(byte[] payload, String className) {
        RootGraph2D g = new RootGraph2D();
        g.className = className;

        RootBuffer b = new RootBuffer(payload);
        RootBuffer.Header outer = b.beginObject();

        final boolean wrapped = !className.equals("TGraph2D");
        RootBuffer.Header base = wrapped ? b.beginObject() : outer;

        String[] named = b.readTNamed();
        g.name = named[0];
        g.title = named[1];

        for (int i = 0; i < 3; i++) {          // line, fill and marker attributes
            RootBuffer.Header attributes = b.beginObject();
            b.endObject(attributes);
        }

        final int points = b.i32();
        b.i32();                               // fNpx, the drawing resolution
        b.i32();                               // fNpy
        b.i32();                               // fMaxIter

        g.x = b.readSizedArray(points, false);
        g.y = b.readSizedArray(points, false);
        g.z = b.readSizedArray(points, false);

        if (wrapped) {
            b.endObject(base);
            if (className.equals("TGraph2DErrors")
                || className.equals("TGraph2DAsymmErrors")) {
                g.ex = b.readSizedArray(points, false);
                g.ey = b.readSizedArray(points, false);
                g.ez = b.readSizedArray(points, false);
            }
        }

        g.consistent = b.landedInside(outer) && g.x.length == Math.max(0, points);

        b.endObject(outer);
        return g;
    }

    public String describe() {
        return String.format(Locale.ROOT,
            "%s   %d points%nx from %.6g to %.6g%ny from %.6g to %.6g%n"
            + "z from %.6g to %.6g%s",
            className, size(), minX(), maxX(), minY(), maxY(), minZ(), maxZ(),
            hasErrors() ? "\nwith errors on z" : "");
    }
}
