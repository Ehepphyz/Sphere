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

    /** Sum over the bins that are in range, leaving the tails out. */
    public double integral() {
        double total = 0;
        for (int i = 1; i <= xAxis.bins && i < contents.length; i++) {
            total += contents[i];
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
        for (int i = 1; i <= xAxis.bins && i < contents.length; i++) {
            best = Math.max(best, contents[i]);
        }
        return best == Double.NEGATIVE_INFINITY ? 0 : best;
    }

    public double minContent() {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i <= xAxis.bins && i < contents.length; i++) {
            best = Math.min(best, contents[i]);
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best;
    }

    /** Groups neighboring bins, the way TH1::Rebin does. */
    public RootHistogram rebin(int factor) {
        if (factor <= 1 || xAxis.isVariable()) {
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
        h.dimensions = className.startsWith("TH2") ? 2
                     : className.startsWith("TH3") ? 3 : 1;

        RootBuffer b = new RootBuffer(payload);
        RootBuffer.Header outer = b.beginObject();

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
        h.contents = b.readArrayFor(className);

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
