package com.sphere.core.geant4;

import java.util.Arrays;

/**
 * The distribution of the energy a shower leaves, carried from the network's to Geant4's: quantile transport, made
 * smooth so that it does not copy the noise of the showers it learns from.
 *
 * <p>A flow can have the right profile and the wrong total: at the low end of its range, a little too low in the bulk
 * with too heavy an upper tail, which no shift or scale corrects. Here a shower's ln(visible / E) is mapped to the value
 * of the same rank among Geant4's showers, so that its distribution is Geant4's -- and every cell is rescaled alike, so
 * the profile is untouched.
 *
 * <p>The map is held at fixed levels of rank. At each energy measured, the network's quantiles come from as many draws
 * as wanted, Geant4's from a finite sample; so the correction at each level, Geant4's quantile less the network's, is
 * fitted across the energies by weighted least squares as a parabola in t: an energy borrows the strength of its
 * neighbours. Between the energies the network's quantiles are interpolated in t; beyond the outer levels the map goes
 * on with the slope of its last segment, and it is kept increasing.
 */
final class TotalMap {

    /** The levels of rank, denser in the tails, where a heavy tail is what has to be corrected. */
    static final double[] LEVELS = {0.002, 0.005, 0.01, 0.02, 0.035, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65,
        0.7, 0.75, 0.8, 0.85, 0.9, 0.925, 0.95, 0.965, 0.98, 0.99, 0.995, 0.998};

    final double[] t;          // the energies measured, as t, increasing
    final double[][] network;  // [energy][level]: the network's quantiles of ln(visible / E)
    final double[][] shift;    // [level][3]: Geant4's quantile less the network's, a parabola in t

    private TotalMap(double[] t, double[][] network, double[][] shift) {
        this.t = t;
        this.network = network;
        this.shift = shift;
    }

    /** The identity: no energy measured yet. */
    static TotalMap identity() {
        return new TotalMap(new double[0], new double[0][], new double[LEVELS.length][3]);
    }

    boolean isIdentity() {
        return t.length == 0;
    }

    /** The quantile of a sorted sample at a level, interpolated between order statistics. */
    static double quantile(double[] sorted, double q) {
        final double x = q * sorted.length - 0.5;
        final int i = (int) Math.floor(x);
        if (i < 0) {
            return sorted[0];
        }
        if (i >= sorted.length - 1) {
            return sorted[sorted.length - 1];
        }
        final double f = x - i;
        return sorted[i] * (1 - f) + sorted[i + 1] * f;
    }

    /**
     * The map from, at each energy measured (as t), the network's values of ln(visible / E) (many draws) and Geant4's
     * (its showers), both sorted.
     */
    static TotalMap fit(double[] t, double[][] networkSorted, double[][] geant4Sorted) {
        final int k = t.length, levels = LEVELS.length;
        final Integer[] order = new Integer[k];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (a, b) -> Double.compare(t[a], t[b]));
        final double[] ts = new double[k], w = new double[k];
        final double[][] net = new double[k][levels];
        final double[][] gap = new double[levels][k];
        for (int e = 0; e < k; e++) {
            final int o = order[e];
            ts[e] = t[o];
            w[e] = geant4Sorted[o].length;
            for (int l = 0; l < levels; l++) {
                net[e][l] = quantile(networkSorted[o], LEVELS[l]);
                gap[l][e] = quantile(geant4Sorted[o], LEVELS[l]) - net[e][l];
            }
        }
        final double[][] shift = new double[levels][];
        for (int l = 0; l < levels; l++) {
            shift[l] = FastSimStats.polyfit(ts, gap[l], w, Math.min(2, k - 1));
        }
        return new TotalMap(ts, net, shift);
    }

    /** ln(visible / E) moved to Geant4's distribution at this t. */
    double apply(double f0, double at) {
        if (isIdentity()) {
            return f0;
        }
        final int levels = LEVELS.length;
        final double[] x = new double[levels], y = new double[levels];
        int e = 0;
        while (e + 1 < t.length && t[e + 1] < at) {
            e++;
        }
        final double frac = t.length < 2 ? 0 : Math.max(0, Math.min(1, (at - t[e]) / Math.max(1e-12, t[Math.min(e + 1, t.length - 1)] - t[e])));
        final int next = Math.min(e + 1, t.length - 1);
        for (int l = 0; l < levels; l++) {
            x[l] = network[e][l] * (1 - frac) + network[next][l] * frac;
            y[l] = x[l] + FastSimStats.poly(shift[l], at);
            if (l > 0) {   // kept increasing
                x[l] = Math.max(x[l], x[l - 1] + 1e-12);
                y[l] = Math.max(y[l], y[l - 1]);
            }
        }
        if (f0 <= x[0]) {
            return y[0] + (f0 - x[0]) * (y[1] - y[0]) / (x[1] - x[0]);
        }
        if (f0 >= x[levels - 1]) {
            return y[levels - 1] + (f0 - x[levels - 1]) * (y[levels - 1] - y[levels - 2]) / (x[levels - 1] - x[levels - 2]);
        }
        int lo = 0, hi = levels - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (x[mid] <= f0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return y[lo] + (f0 - x[lo]) * (y[hi] - y[lo]) / (x[hi] - x[lo]);
    }

    /** The table's constants, and the C++ that applies them as apply() does. */
    String cpp() {
        final StringBuilder b = new StringBuilder();
        b.append("// the total energy carried to Geant4's distribution: quantile transport at fixed levels of rank\n");
        b.append("constexpr std::size_t kMapLevels = ").append(LEVELS.length).append(", kMapEnergies = ").append(t.length).append(";\n");
        b.append("static const double kMapT[] = {");
        for (int i = 0; i < Math.max(1, t.length); i++) {
            b.append(i > 0 ? ", " : "").append(ShowerBasis.hex(i < t.length ? t[i] : 0));
        }
        b.append("};\n");
        ShowerBasis.matrix(b, "kMapNetwork", network, LEVELS.length);
        ShowerBasis.matrix(b, "kMapShift", shift, 3);
        return b.toString();
    }
}
