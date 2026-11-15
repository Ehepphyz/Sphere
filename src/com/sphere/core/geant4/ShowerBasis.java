package com.sphere.core.geant4;

import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The part of a shower that needs no network: coordinates in which the cells are independent of each other's sum, and
 * their mean and width as functions of the energy, solved in closed form over every shower. What the network learns
 * is what is left -- the fluctuations about a profile that least squares already places at every energy.
 *
 * <p>A shower of D cells becomes D numbers: ln(visible / E), then, cell after cell along the detector, the logit of the
 * share that cell takes of what the cells from it on received (stick breaking). Unlike the cells' shares, which sum to
 * one and so lie on a surface no density covers, these are free in every direction, and the shower is rebuilt from
 * them exactly. A cell that saw nothing is lifted by a floor of DELTA of the shower times a uniform number, so that the
 * logarithms stay finite, and the floor is taken off again when a shower is drawn.
 *
 * <p>Each coordinate's mean is a cubic and its log-variance a parabola in t = (ln E - mid) / half, t in [-1, 1], fitted
 * by weighted least squares: an energy has every shower's help, not only its own neighbours'. The network then reads
 * (f - mean(t)) / width(t), a variable of mean 0 and width 1 at every energy.
 */
final class ShowerBasis {

    /** The floor of a cell's share of the shower, against logarithms of zero. */
    static final double DELTA = 1e-4;
    static final int MEAN_TERMS = 4, VARIANCE_TERMS = 3;

    final int cells;
    final double uMid, uHalf;
    final double[][] mean, logVariance;

    private ShowerBasis(int cells, double uMid, double uHalf, double[][] mean, double[][] logVariance) {
        this.cells = cells;
        this.uMid = uMid;
        this.uHalf = uHalf;
        this.mean = mean;
        this.logVariance = logVariance;
    }

    /** No shower yet: means 0 and widths 1 over the energies of the plan. */
    static ShowerBasis identity(int cells, double emin, double emax) {
        return new ShowerBasis(cells, 0.5 * Math.log(emin * emax), Math.max(1e-6, 0.5 * Math.log(emax / emin)), new double[cells][MEAN_TERMS],
            new double[cells][VARIANCE_TERMS]);
    }

    double t(double energy) {
        return Math.max(-1, Math.min(1, (Math.log(energy) - uMid) / uHalf));
    }

    /**
     * ln(visible / E), then the logit of each cell's share of what it and the cells after it received; null when the
     * cells saw nothing (or less than 1e-9 of E).
     */
    static double[] raw(double energy, double[] row, SplittableRandom random) {
        final int d = row.length;
        double visible = 0;
        for (double v : row) {
            visible += v;
        }
        if (!(visible > 1e-9 * energy)) {
            return null;
        }
        final double[] e = new double[d];
        for (int i = 0; i < d; i++) {
            e[i] = row[i] + DELTA * visible * (1e-12 + random.nextDouble());
        }
        final double[] suffix = new double[d + 1];
        for (int i = d - 1; i >= 0; i--) {
            suffix[i] = suffix[i + 1] + e[i];
        }
        final double[] f = new double[d];
        f[0] = Math.log(visible / energy);
        for (int i = 1; i < d; i++) {
            f[i] = Math.log(e[i - 1]) - Math.log(suffix[i]);   // logit(e[i-1] / suffix[i-1])
        }
        return f;
    }

    /** The means and widths, by least squares over the showers (their energies, and their raw coordinates). */
    static ShowerBasis fit(double[] energies, double[][] raw) {
        final int n = energies.length, d = raw[0].length;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double e : energies) {
            lo = Math.min(lo, Math.log(e));
            hi = Math.max(hi, Math.log(e));
        }
        final ShowerBasis b = new ShowerBasis(d, 0.5 * (lo + hi), Math.max(1e-6, 0.5 * (hi - lo)), new double[d][], new double[d][]);
        final double[] t = new double[n], ones = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = b.t(energies[i]);
            ones[i] = 1;
        }
        final Integer[] order = new Integer[n];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (x, y) -> Double.compare(t[x], t[y]));
        final int bins = Math.max(1, Math.min(10, n / 300));
        final double[] y = new double[n];
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < n; i++) {
                y[i] = raw[i][j];
            }
            b.mean[j] = FastSimStats.polyfit(t, y, ones, MEAN_TERMS - 1);
            // the width in equal bins of t: the log of each bin's variance about the fitted mean, a parabola through them
            final double[] bt = new double[bins], bv = new double[bins], bw = new double[bins];
            for (int k = 0; k < bins; k++) {
                final int from = k * n / bins, to = (k + 1) * n / bins;
                double st = 0, s2 = 0;
                for (int q = from; q < to; q++) {
                    final int i = order[q];
                    final double r = y[i] - FastSimStats.poly(b.mean[j], t[i]);
                    st += t[i];
                    s2 += r * r;
                }
                final int m = Math.max(1, to - from);
                bt[k] = st / m;
                bv[k] = Math.log(Math.max(1e-12, s2 / Math.max(1, m - 1)));
                bw[k] = m;
            }
            b.logVariance[j] = FastSimStats.polyfit(bt, bv, bw, VARIANCE_TERMS - 1);
        }
        return b;
    }

    /** (f - mean(t)) / width(t). */
    double[] whiten(double[] f, double energy) {
        final double t = t(energy);
        final double[] w = new double[f.length];
        for (int j = 0; j < f.length; j++) {
            w[j] = (f[j] - FastSimStats.poly(mean[j], t)) * Math.exp(-0.5 * FastSimStats.poly(logVariance[j], t));
        }
        return w;
    }

    /** The constants SphereFastTable.hh holds for the C++ to rebuild a shower. */
    String cpp() {
        final StringBuilder b = new StringBuilder();
        b.append("// the closed-form part of the shower: t = (ln(E/MeV) - kUMid) / kUHalf in [-1, 1]; coordinate j is\n");
        b.append("// mean_j(t) + exp(logVariance_j(t) / 2) w_j, w the network's output\n");
        b.append("constexpr double kUMid = ").append(hex(uMid)).append(", kUHalf = ").append(hex(uHalf)).append(";\n");
        b.append("constexpr double kDelta = ").append(hex(DELTA)).append(";   // the floor of a cell's share, taken off again\n");
        matrix(b, "kMean", mean, MEAN_TERMS);
        matrix(b, "kLogVariance", logVariance, VARIANCE_TERMS);
        return b.toString();
    }

    static String hex(double v) {
        return Double.isFinite(v) ? Double.toHexString(v) : "0.0";
    }

    /** A C array of rows, each padded with zeros to {@code width}; one row of zeros when there are none. */
    static void matrix(StringBuilder b, String name, double[][] rows, int width) {
        b.append("static const double ").append(name).append("[").append(Math.max(1, rows.length)).append("][").append(width).append("] = {\n");
        final double[][] all = rows.length == 0 ? new double[][] {new double[width]} : rows;
        for (double[] r : all) {
            b.append("  {");
            for (int c = 0; c < width; c++) {
                b.append(c > 0 ? ", " : "").append(hex(r != null && c < r.length ? r[c] : 0));
            }
            b.append("},\n");
        }
        b.append("};\n");
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "basis of %d coordinates over ln E in [%.3f, %.3f]", cells, uMid - uHalf, uMid + uHalf);
    }
}
