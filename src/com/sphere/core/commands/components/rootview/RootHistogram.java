package com.sphere.components.rootview;

import java.util.Locale;

/**
 * A histogram decoded straight from the file.
 *
 * TH1 writes its bases in order -- TNamed, then the drawing attributes, then its
 * own members -- and each of them carries a byte count, so the ones a viewer
 * does not need are stepped over rather than parsed.
 */
public final class RootHistogram {

    public String name = "";
    public String title = "";
    public String className = "";

    public int dimensions = 1;
    /** False when the decode did not land where the object said it ends. */
    public boolean consistent = true;
    public RootAxis xAxis = new RootAxis();
    public RootAxis yAxis = new RootAxis();
    public RootAxis zAxis = new RootAxis();

    /** Bin contents including the underflow at 0 and the overflow at the end. */
    public double[] contents = new double[0];
    /** Sum of squared weights per bin, empty when the file did not store them. */
    public double[] sumw2 = new double[0];

    public double entries;
    public double tsumw;
    public double tsumw2;
    public double tsumwx;
    public double tsumwx2;
    public double maximum = Double.NaN;
    public double minimum = Double.NaN;

    // ---- access ------------------------------------------------------------

    public int binCount() {
        return xAxis.bins;
    }

    /** Content of bin `i`, counted from 1 as ROOT does. */
    public double content(int bin) {
        return (bin >= 0 && bin < contents.length) ? contents[bin] : 0;
    }

    public double error(int bin) {
        if (bin >= 0 && bin < sumw2.length) {
            return Math.sqrt(Math.max(0, sumw2[bin]));
        }
        // Without stored weights, a count's error is its square root.
        return Math.sqrt(Math.max(0, content(bin)));
    }

    public double content2D(int x, int y) {
        final int index = (yAxis.bins + 2) * 0 + x + (xAxis.bins + 2) * y;
        return (index >= 0 && index < contents.length) ? contents[index] : 0;
    }

    public double underflow() {
        return content(0);
    }

    public double overflow() {
        return content(xAxis.bins + 1);
    }

    /** How many cells the histogram has in range, over all of its axes. */
    private int rows() {
        return dimensions > 1 ? Math.max(1, yAxis.bins) : 1;
    }

    /** Sum over the bins that are in range, leaving the tails out. */
    public double integral() {
        double total = 0;
        for (int j = 1; j <= rows(); j++) {
            for (int i = 1; i <= xAxis.bins; i++) {
                total += dimensions > 1 ? content2D(i, j) : content(i);
            }
        }
        return total;
    }

    public double mean() {
        if (tsumw != 0) {
            return tsumwx / tsumw;
        }
        double sum = 0;
        double weight = 0;
        for (int i = 1; i <= xAxis.bins && i < contents.length; i++) {
            sum += contents[i] * xAxis.center(i - 1);
            weight += contents[i];
        }
        return weight == 0 ? 0 : sum / weight;
    }

    public double stdDev() {
        if (tsumw != 0) {
            final double m = tsumwx / tsumw;
            final double variance = tsumwx2 / tsumw - m * m;
            return variance > 0 ? Math.sqrt(variance) : 0;
        }
        final double m = mean();
        double sum = 0;
        double weight = 0;
        for (int i = 1; i <= xAxis.bins && i < contents.length; i++) {
            final double d = xAxis.center(i - 1) - m;
            sum += contents[i] * d * d;
            weight += contents[i];
        }
        return weight == 0 ? 0 : Math.sqrt(sum / weight);
    }

    public double maxContent() {
        double best = Double.NEGATIVE_INFINITY;
        for (int j = 1; j <= rows(); j++) {
            for (int i = 1; i <= xAxis.bins; i++) {
                best = Math.max(best, dimensions > 1 ? content2D(i, j) : content(i));
            }
        }
        return best == Double.NEGATIVE_INFINITY ? 0 : best;
    }

    public double minContent() {
        double best = Double.POSITIVE_INFINITY;
        for (int j = 1; j <= rows(); j++) {
            for (int i = 1; i <= xAxis.bins; i++) {
                best = Math.min(best, dimensions > 1 ? content2D(i, j) : content(i));
            }
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best;
    }

    /** Groups neighboring bins, the way TH1::Rebin does. */
    public RootHistogram rebin(int factor) {
        if (factor <= 1 || dimensions > 1 || xAxis.isVariable()) {
            return this;
        }
        final int groups = xAxis.bins / factor;
        if (groups < 1) {
            return this;
        }
        RootHistogram out = new RootHistogram();
        out.name = name;
        out.title = title;
        out.className = className;
        out.dimensions = dimensions;
        out.entries = entries;
        out.tsumw = tsumw;
        out.tsumw2 = tsumw2;
        out.tsumwx = tsumwx;
        out.tsumwx2 = tsumwx2;

        out.xAxis = new RootAxis();
        out.xAxis.name = xAxis.name;
        out.xAxis.title = xAxis.title;
        out.xAxis.bins = groups;
        out.xAxis.min = xAxis.min;
        out.xAxis.max = xAxis.edge(groups * factor);

        out.contents = new double[groups + 2];
        out.sumw2 = sumw2.length > 0 ? new double[groups + 2] : new double[0];
        out.contents[0] = underflow();

        for (int g = 0; g < groups; g++) {
            double sum = 0;
            double weights = 0;
            for (int k = 0; k < factor; k++) {
                final int source = g * factor + k + 1;
                sum += content(source);
                if (sumw2.length > source) {
                    weights += sumw2[source];
                }
            }
            out.contents[g + 1] = sum;
            if (out.sumw2.length > 0) {
                out.sumw2[g + 1] = weights;
            }
        }

        // Whatever the grouping left over joins the overflow rather than vanishing.
        double tail = overflow();
        for (int i = groups * factor + 1; i <= xAxis.bins; i++) {
            tail += content(i);
        }
        out.contents[groups + 1] = tail;
        return out;
    }

    /**
     * Bins a column of values, the way a first look at a branch wants it.
     *
     * The range comes from the values themselves; a column that is all one value
     * still gets a frame rather than a division by zero.
     */
    public static RootHistogram fromValues(String name, String title,
                                           double[] values, int bins) {
        RootHistogram h = new RootHistogram();
        h.name = name;
        h.title = title;
        h.className = "column";
        h.dimensions = 1;

        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isFinite(v)) {
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
        }
        if (!(low <= high)) {
            low = 0;
            high = 1;
        }
        if (low == high) {
            low -= 0.5;
            high += 0.5;
        }

        final int count = Math.max(1, bins);
        h.xAxis = new RootAxis();
        h.xAxis.name = name;
        h.xAxis.title = name;
        h.xAxis.bins = count;
        h.xAxis.min = low;
        h.xAxis.max = high;
        h.contents = new double[count + 2];

        double sum = 0;
        double sumSquares = 0;
        for (double v : values) {
            if (!Double.isFinite(v)) {
                continue;
            }
            sum += v;
            sumSquares += v * v;
            final int bin = (int) Math.floor((v - low) / (high - low) * count);
            if (bin < 0) {
                h.contents[0] += 1;
            } else if (bin >= count) {
                // The largest value lands exactly on the edge, so it belongs in
                // the last bin rather than in the overflow.
                h.contents[v == high ? count : count + 1] += 1;
            } else {
                h.contents[bin + 1] += 1;
            }
        }
        h.entries = values.length;
        h.tsumw = values.length;
        h.tsumw2 = values.length;
        h.tsumwx = sum;
        h.tsumwx2 = sumSquares;
        return h;
    }

    // ---- decoding ----------------------------------------------------------

    /**
     * Reads a TH1 or TH2 out of a key's payload.
     *
     * The class name decides which array type holds the contents: a TH1F stores
     * floats, a TH1D doubles, and so on.
     */
    public static RootHistogram decode(byte[] payload, String className) {
        RootHistogram h = new RootHistogram();
        h.className = className;
        h.dimensions = className.startsWith("TH3") || className.startsWith("TProfile3D") ? 3
                     : className.startsWith("TH2") || className.startsWith("TProfile2D") ? 2
                     : 1;

        RootBuffer b = new RootBuffer(payload);
        RootBuffer.Header outer = b.beginObject();

        // A profile is written as the histogram it derives from, so it takes one
        // more level than a plain TH1 before the contents show up.
        final boolean profile = className.startsWith("TProfile");
        RootBuffer.Header wrapper = profile ? b.beginObject() : null;
        final String arrayClass = profile
            ? "TH" + h.dimensions + "D" : className;

        // TH2 and TH3 write their own header, then TH1 as their base.
        if (h.dimensions > 1) {
            RootBuffer.Header base = b.beginObject();
            readTH1(b, h);
            // What follows in TH2 is TArray plus the stats; the byte count of the
            // outer object is what gets us to the contents.
            b.endObject(base);
        } else {
            readTH1(b, h);
        }

        // TArrayX carrying the bin contents, whichever precision the class uses.
        h.contents = b.readArrayFor(arrayClass);
        h.consistent = b.landedInside(outer) && h.xAxis.bins > 0
                    && h.contents.length >= h.xAxis.bins;

        if (wrapper != null) {
            b.endObject(wrapper);
        }
        b.endObject(outer);
        return h;
    }

    private static void readTH1(RootBuffer b, RootHistogram h) {
        RootBuffer.Header th1 = b.beginObject();

        String[] named = b.readTNamed();
        h.name = named[0];
        h.title = named[1];

        // TAttLine, TAttFill, TAttMarker: three attribute blocks, each counted.
        for (int i = 0; i < 3; i++) {
            RootBuffer.Header attributes = b.beginObject();
            b.endObject(attributes);
        }

        final int ncells = b.i32();
        h.xAxis = RootAxis.read(b);
        h.yAxis = RootAxis.read(b);
        h.zAxis = RootAxis.read(b);

        b.u16();            // fBarOffset
        b.u16();            // fBarWidth
        h.entries = b.f64();
        h.tsumw = b.f64();
        h.tsumw2 = b.f64();
        h.tsumwx = b.f64();
        h.tsumwx2 = b.f64();
        h.maximum = b.f64();
        h.minimum = b.f64();
        b.f64();            // fNormFactor
        b.readArrayD();     // fContour

        h.sumw2 = b.readArrayD();
        b.string();         // fOption
        // fFunctions, fBufferSize, fBuffer and the rest are stepped over by the
        // byte count rather than parsed.
        b.endObject(th1);

        if (h.xAxis.bins <= 0 && ncells > 2) {
            h.xAxis.bins = ncells - 2;
        }
    }

    public String describe() {
        return String.format(Locale.ROOT,
            "%s   %d bins, %.6g to %.6g%n"
            + "entries %.0f   mean %.6g   std dev %.6g%n"
            + "integral %.6g   underflow %.6g   overflow %.6g",
            className, xAxis.bins, xAxis.min, xAxis.max,
            entries, mean(), stdDev(), integral(), underflow(), overflow());
    }
}
