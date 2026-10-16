package com.sphere.core.rootlab;

import com.sphere.core.rootio.RTree;
import com.sphere.core.rootlab.hist.LabHist;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * What a branch of a tree holds, found in one pass over its entries: count,
 * mean and spread (Welford's algorithm, stable over billions of values),
 * extremes, non-finite values, and a uniform sample of the values (reservoir
 * sampling) from which quantiles come. The histogram it proposes is binned by
 * the Freedman-Diaconis rule over the 0.1%-99.9% range, so one outlier does
 * not squeeze every other value into a single bin; and it says what is
 * unusual about the column -- constant, integer-valued, a handful of values,
 * NaNs, values far in the tails -- the things one checks first in a new file.
 */
public final class ColumnStats {

    private static final int RESERVOIR = 50_000;

    public final String name;
    public long entries;
    public long values;
    public long nan;
    public long infinite;
    public long zeros;
    public double min = Double.POSITIVE_INFINITY;
    public double max = Double.NEGATIVE_INFINITY;
    public double mean;
    private double m2;
    private double m3;
    private double m4;
    public boolean integerValued = true;
    public long perEntryMin = Long.MAX_VALUE;
    public long perEntryMax;
    private final Set<Double> distinct = new HashSet<>();
    private boolean manyDistinct;
    private final double[] sample = new double[RESERVOIR];
    private int sampled;
    private final SplittableRandom random = new SplittableRandom(4357);
    public long nanos;

    public ColumnStats(String name) {
        this.name = name;
    }

    /** Adds one value (Welford's update of the moments, and the reservoir). */
    public void add(double v) {
        if (Double.isNaN(v)) {
            nan++;
            return;
        }
        if (Double.isInfinite(v)) {
            infinite++;
            return;
        }
        values++;
        if (v == 0) {
            zeros++;
        }
        if (v < min) {
            min = v;
        }
        if (v > max) {
            max = v;
        }
        if (integerValued && v != Math.rint(v)) {
            integerValued = false;
        }
        if (!manyDistinct) {
            distinct.add(v);
            if (distinct.size() > 64) {
                manyDistinct = true;
                distinct.clear();
            }
        }
        final double n = values;
        final double delta = v - mean;
        final double deltaN = delta / n;
        final double deltaN2 = deltaN * deltaN;
        final double term1 = delta * deltaN * (n - 1);
        mean += deltaN;
        m4 += term1 * deltaN2 * (n * n - 3 * n + 3) + 6 * deltaN2 * m2 - 4 * deltaN * m3;
        m3 += term1 * deltaN * (n - 2) - 3 * deltaN * m2;
        m2 += term1;
        if (sampled < RESERVOIR) {
            sample[sampled++] = v;
        } else {
            final long j = random.nextLong(values);
            if (j < RESERVOIR) {
                sample[(int) j] = v;
            }
        }
    }

    public double stdDev() {
        return values > 1 ? Math.sqrt(m2 / (values - 1)) : 0;
    }

    public double skewness() {
        return values > 2 && m2 > 0 ? Math.sqrt(values) * m3 / Math.pow(m2, 1.5) : 0;
    }

    /** Excess kurtosis: 0 for a Gaussian, large for heavy tails. */
    public double kurtosis() {
        return values > 3 && m2 > 0 ? values * m4 / (m2 * m2) - 3 : 0;
    }

    public int distinctValues() {
        return manyDistinct ? -1 : distinct.size();
    }

    /** The q-quantile (0 to 1) of the sample. */
    public double quantile(double q) {
        if (sampled == 0) {
            return Double.NaN;
        }
        final double[] s = Arrays.copyOf(sample, sampled);
        Arrays.sort(s);
        final double pos = q * (s.length - 1);
        final int lo = (int) Math.floor(pos);
        final int hi = Math.min(s.length - 1, lo + 1);
        return s[lo] + (pos - lo) * (s[hi] - s[lo]);
    }

    /** A histogram of the column, binned by the Freedman-Diaconis rule over its central 99.8%. */
    public LabHist histogram() {
        double lo = values > 0 ? quantile(0.001) : 0;
        double hi = values > 0 ? quantile(0.999) : 1;
        int bins;
        if (integerValued && values > 0 && max - min <= 200) {
            // integer columns get one bin per value
            lo = min - 0.5;
            hi = max + 0.5;
            bins = (int) Math.max(1, Math.round(hi - lo));
        } else {
            final double iqr = quantile(0.75) - quantile(0.25);
            final double width = iqr > 0 ? 2 * iqr / Math.cbrt(Math.max(1, values)) : 0;
            if (!(hi > lo)) {
                lo -= 0.5;
                hi += 0.5;
            }
            bins = width > 0 ? (int) Math.ceil((hi - lo) / width) : 50;
            bins = Math.max(10, Math.min(200, bins));
            final double pad = (hi - lo) * 0.02;
            lo -= pad;
            hi += pad;
        }
        final LabHist h = LabHist.th1d(name, name, bins, lo, hi);
        for (int i = 0; i < sampled; i++) {
            h.fill(sample[i], 1.0);
        }
        return h;
    }

    /** What stands out about the column, in a few words each. */
    public List<String> remarks() {
        final List<String> out = new ArrayList<>();
        if (values == 0) {
            out.add("no finite value");
            return out;
        }
        if (nan > 0) {
            out.add(String.format(Locale.ROOT, "%,d NaN value(s)", nan));
        }
        if (infinite > 0) {
            out.add(String.format(Locale.ROOT, "%,d infinite value(s)", infinite));
        }
        if (min == max) {
            out.add("constant (" + fmt(min) + ") -- carries no information");
        } else if (distinctValues() >= 0 && distinctValues() <= 8) {
            out.add(distinctValues() + " distinct values: a flag or a category");
        } else if (integerValued) {
            out.add("integer valued: a count, an index or an id");
        }
        if (zeros > 0.5 * values && min != max) {
            out.add(String.format(Locale.ROOT, "%.0f%% zeros", 100.0 * zeros / values));
        }
        final double sd = stdDev();
        if (sd > 0) {
            final double far = Math.max(Math.abs(max - mean), Math.abs(min - mean)) / sd;
            if (far > 8) {
                out.add(String.format(Locale.ROOT, "an extreme value %.0f standard deviations from the mean", far));
            }
            if (kurtosis() > 10) {
                out.add(String.format(Locale.ROOT, "heavy tails (excess kurtosis %.1f)", kurtosis()));
            }
            if (Math.abs(skewness()) > 2) {
                out.add(String.format(Locale.ROOT, "strongly skewed (%.1f)", skewness()));
            }
        }
        if (perEntryMax > 1) {
            out.add(String.format(Locale.ROOT, "%d to %d values per entry (a collection)", perEntryMin, perEntryMax));
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    public String summary() {
        return String.format(Locale.ROOT,
            "%s: %,d values in %,d entries; mean %.6g, std dev %.6g, min %.6g, max %.6g; median %.6g, "
            + "IQR [%.6g, %.6g]", name, values, entries, mean, stdDev(), min, max, quantile(0.5),
            quantile(0.25), quantile(0.75));
    }

    /**
     * One pass over a branch. maxEntries bounds the pass on a large tree (the
     * statistics then describe its first entries, and say so).
     */
    public static ColumnStats of(RTree.RBranch branch, long maxEntries, DoubleConsumer progress,
                                 BooleanSupplier cancelled) throws IOException {
        final long t0 = System.nanoTime();
        final ColumnStats s = new ColumnStats(branch.name());
        final long n = Math.min(branch.entries(), maxEntries);
        for (long i = 0; i < n; i++) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                break;
            }
            final double[] v = branch.numbers(i);
            s.entries++;
            s.perEntryMin = Math.min(s.perEntryMin, v.length);
            s.perEntryMax = Math.max(s.perEntryMax, v.length);
            for (double x : v) {
                s.add(x);
            }
            if (progress != null && (i & 1023) == 0) {
                progress.accept((double) i / Math.max(1, n));
            }
        }
        if (s.perEntryMin == Long.MAX_VALUE) {
            s.perEntryMin = 0;
        }
        s.nanos = System.nanoTime() - t0;
        return s;
    }
}
