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

    /** Content of the cell (x, y, z), each counted from 1 as ROOT does. */
    public double content3D(int x, int y, int z) {
        final int index = x + (xAxis.bins + 2) * (y + (yAxis.bins + 2) * z);
        return (index >= 0 && index < contents.length) ? contents[index] : 0;
    }

    /** The cell in range at (i, j, k), whatever the dimension (unused axes are 1). */
    private double cell(int i, int j, int k) {
        if (dimensions == 3) {
            return content3D(i, j, k);
        }
        return dimensions == 2 ? content2D(i, j) : content(i);
    }

    private int ySpan() {
        return dimensions > 1 ? Math.max(1, yAxis.bins) : 1;
    }

    private int zSpan() {
        return dimensions > 2 ? Math.max(1, zAxis.bins) : 1;
    }

    /**
     * Sum over the bins that are in range, leaving the tails out. Every axis
     * counts: a TH3 used to be summed over its z underflow layer only.
     */
    public double integral() {
        double total = 0;
        for (int k = 1; k <= zSpan(); k++) {
            for (int j = 1; j <= ySpan(); j++) {
                for (int i = 1; i <= xAxis.bins; i++) {
                    total += cell(i, j, k);
                }
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
        for (int k = 1; k <= zSpan(); k++) {
            for (int j = 1; j <= ySpan(); j++) {
                for (int i = 1; i <= xAxis.bins; i++) {
                    best = Math.max(best, cell(i, j, k));
                }
            }
        }
        return best == Double.NEGATIVE_INFINITY ? 0 : best;
    }

    public double minContent() {
        double best = Double.POSITIVE_INFINITY;
        for (int k = 1; k <= zSpan(); k++) {
            for (int j = 1; j <= ySpan(); j++) {
                for (int i = 1; i <= xAxis.bins; i++) {
                    best = Math.min(best, cell(i, j, k));
                }
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
        if (!outer.hasCount() && outer.version <= 2 && h.dimensions == 1
                && !className.startsWith("TProfile")) {
            // Written by ROOT 2 (the 1990s): hand-coded streamers, no byte counts.
            return decodeLegacy(payload, className, h);
        }

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
            readProfile(b, outer, h);
        }
        b.endObject(outer);
        return h;
    }

    /**
     * A one-dimensional histogram as ROOT 2 wrote it, before class descriptions
     * and byte counts: TH1 version 1, TAxis version 3 (TAttAxis 2), the axis
     * limits and fMaximum, fMinimum, fNormFactor as floats, fContour and fSumw2
     * as TArrayF, fFunctions as a version-1 TList (a count). The layout was read
     * off the bytes of the GSI beam test files shipped in ROOT's
     * test/histviewer (December 1998), and accounts for every byte of them.
     */
    static RootHistogram decodeLegacy(byte[] payload, String className, RootHistogram h) {
        RootBuffer b = new RootBuffer(payload);
        try {
            b.u16();                                 // TH1F, TH1D, ... version
            b.u16();                                 // TH1 version
            b.u16();                                 // TNamed version
            b.skipTObject();
            h.name = b.string();
            h.title = b.string();
            b.u16(); b.u16(); b.u16(); b.u16();      // TAttLine: version, color, style, width
            b.u16(); b.u16(); b.u16();               // TAttFill: version, color, style
            b.u16(); b.u16(); b.u16(); b.f32();      // TAttMarker: version, color, style, size
            final int ncells = b.i32();
            h.xAxis = legacyAxis(b);
            h.yAxis = legacyAxis(b);
            h.zAxis = legacyAxis(b);
            b.u16();                                 // fBarOffset
            b.u16();                                 // fBarWidth
            h.entries = b.f64();
            h.tsumw = b.f64();
            h.tsumw2 = b.f64();
            h.tsumwx = b.f64();
            h.tsumwx2 = b.f64();
            h.maximum = b.f32();
            h.minimum = b.f32();
            b.f32();                                 // fNormFactor
            b.readArrayF();                          // fContour
            h.sumw2 = b.readArrayF();
            b.string();                              // fOption
            b.u16();                                 // TList version
            final int functions = b.i32();
            if (functions != 0) {
                // Functions attached to a 1998 histogram would need their own
                // legacy streamers; the contents that follow cannot be found.
                h.consistent = false;
                return h;
            }
            h.contents = b.readArrayFor(className);
            if (h.xAxis.bins <= 0 && ncells > 2) {
                h.xAxis.bins = ncells - 2;
            }
            h.consistent = !b.hasRemaining() && h.contents.length == ncells;
        } catch (RuntimeException truncated) {
            h.consistent = false;
        }
        return h;
    }

    private static RootAxis legacyAxis(RootBuffer b) {
        RootAxis axis = new RootAxis();
        b.u16();                                     // TAxis version
        b.u16();                                     // TNamed version
        b.skipTObject();
        axis.name = b.string();
        axis.title = b.string();
        b.u16();                                     // TAttAxis version
        b.i32();                                     // fNdivisions
        b.u16(); b.u16(); b.u16();                   // fAxisColor, fLabelColor, fLabelFont
        b.f32(); b.f32(); b.f32(); b.f32(); b.f32(); // offsets, sizes, tick length
        axis.bins = b.i32();
        axis.min = b.f32();
        axis.max = b.f32();
        axis.edges = b.readArrayF();
        b.i32();                                     // fFirst
        b.i32();                                     // fLast
        return axis;
    }

    /** Sum of weights per cell of a profile, null for a plain histogram. */
    public double[] binEntries;
    /** TProfile's fErrorMode: 0 error of the mean, 1 spread ("s"), 2 "i", 3 "g". */
    public int errorMode;

    /**
     * What a profile adds after its histogram part: fBinEntries, fErrorMode,
     * the range of y (z, t), the sums of w*y and w*y*y, and since 5.26
     * fBinSumw2.
     *
     * A profile's array holds the sum of w*y per bin, not the bin's value, and
     * its fSumw2 the sum of w*y*y: shown as they were, a profile came out as a
     * histogram of sums. They are turned here into the means and the errors
     * TProfileHelper::GetBinError gives, so the plot shows what ROOT draws.
     */
    private static void readProfile(RootBuffer b, RootBuffer.Header outer, RootHistogram h) {
        if (!b.landedInside(outer) || b.remaining() < 4) {
            return;
        }
        final double[] entries = b.readArrayD();
        if (b.remaining() < 4 + 8 * 4) {
            return;
        }
        h.errorMode = b.i32();
        b.f64();                                     // fYmin
        b.f64();                                     // fYmax
        b.f64();                                     // fTsumwy
        b.f64();                                     // fTsumwy2
        double[] binSumw2 = new double[0];
        if (outer.hasCount() && b.position() + 4 <= outer.end) {
            binSumw2 = b.readArrayD();
        }
        if (entries.length != h.contents.length) {
            return;
        }
        h.binEntries = entries;
        final double[] sumwy = h.contents;
        final double[] sumwy2 = h.sumw2;
        final double[] means = new double[sumwy.length];
        final double[] errors2 = new double[sumwy.length];
        for (int bin = 0; bin < sumwy.length; bin++) {
            final double sum = entries[bin];
            if (sum == 0) {
                continue;
            }
            means[bin] = sumwy[bin] / sum;
            final double err2 = bin < sumwy2.length ? sumwy2[bin] : 0;
            final double neff = binSumw2.length == sumwy.length
                ? (binSumw2[bin] > 0 ? sum * sum / binSumw2[bin] : 0) : sum;
            final double error;
            if (h.errorMode == 3) {
                error = 1.0 / Math.sqrt(sum);
            } else {
                final double contsum = means[bin];
                final double eprim2 = Math.abs(err2 / sum - contsum * contsum);
                final double eprim = Math.sqrt(eprim2);
                if (h.errorMode == 2) {
                    error = eprim != 0 ? eprim / Math.sqrt(neff) : 1 / Math.sqrt(12 * neff);
                } else if (h.errorMode == 1) {
                    error = eprim;
                } else {
                    error = neff > 0 ? eprim / Math.sqrt(neff) : 0;
                }
            }
            errors2[bin] = error * error;
        }
        h.contents = means;
        h.sumw2 = errors2;
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
