package com.sphere.core.fjcontrib.lab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How well an observable tells a signal sample from a background one: the
 * area under the ROC curve, the background rejection at a working point, and
 * the ROC curve itself. Weighted events are weighted.
 *
 * <p>The side of the cut is found, not assumed: tau21 is small for a W, D2
 * small, n_SD large for gluons; the answer says which side selects the
 * signal, so that rankings of observables of both kinds compare.
 */
public final class Discrimination {

    /**
     * @param auc          area under the ROC curve on the signal-like side (0.5: no power, 1: perfect)
     * @param signalBelow  whether the signal is on the low side of the cut
     * @param rejection50  1/background efficiency at 50% signal efficiency
     * @param bestCut      the cut maximising signal efficiency minus background efficiency
     * @param effS         signal efficiencies along the ROC curve
     * @param effB         background efficiencies along it
     */
    public record Result(String observable, double auc, boolean signalBelow, double rejection50, double bestCut,
                         double bestSignalEff, double bestBackgroundEff, double[] effS, double[] effB, int nSignal,
                         int nBackground) {
    }

    private Discrimination() {
    }

    public static Result evaluate(String observable, double[] sig, double[] sigW, double[] bkg, double[] bkgW) {
        final int ns = sig.length;
        final int nb = bkg.length;
        final double[] ws = sigW == null ? ones(ns) : sigW;
        final double[] wb = bkgW == null ? ones(nb) : bkgW;
        // all values, sorted, with which sample they came from
        final double[][] all = new double[ns + nb][];
        for (int i = 0; i < ns; i++) all[i] = new double[]{sig[i], ws[i], 1};
        for (int i = 0; i < nb; i++) all[ns + i] = new double[]{bkg[i], wb[i], 0};
        Arrays.sort(all, (a, b) -> Double.compare(a[0], b[0]));
        double totS = 0;
        double totB = 0;
        for (double[] v : all) {
            if (v[2] == 1) totS += v[1];
            else totB += v[1];
        }
        if (totS <= 0 || totB <= 0) {
            return new Result(observable, Double.NaN, true, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                new double[0], new double[0], ns, nb);
        }
        // cut "value <= c" swept upwards: efficiencies of the low side, ties kept together
        final List<double[]> roc = new ArrayList<>();
        roc.add(new double[]{0, 0, Double.NEGATIVE_INFINITY});
        double cumS = 0;
        double cumB = 0;
        double aucBelow = 0;
        int k = 0;
        while (k < all.length) {
            final double value = all[k][0];
            double ds = 0;
            double db = 0;
            while (k < all.length && all[k][0] == value) {
                if (all[k][2] == 1) ds += all[k][1];
                else db += all[k][1];
                k++;
            }
            // trapezoid: P(signal below background) + half the ties
            aucBelow += ds / totS * (totB - cumB - db) / totB + 0.5 * ds / totS * db / totB;
            cumS += ds;
            cumB += db;
            roc.add(new double[]{cumS / totS, cumB / totB, value});
        }
        final boolean signalBelow = aucBelow >= 0.5;
        final double auc = signalBelow ? aucBelow : 1 - aucBelow;
        final double[] effS = new double[roc.size()];
        final double[] effB = new double[roc.size()];
        double best = Double.NEGATIVE_INFINITY;
        double bestCut = Double.NaN;
        double bestS = Double.NaN;
        double bestB = Double.NaN;
        double rej = Double.NaN;
        for (int i = 0; i < roc.size(); i++) {
            final double s = signalBelow ? roc.get(i)[0] : 1 - roc.get(roc.size() - 1 - i)[0];
            final double b = signalBelow ? roc.get(i)[1] : 1 - roc.get(roc.size() - 1 - i)[1];
            effS[i] = s;
            effB[i] = b;
            if (s - b > best) {
                best = s - b;
                bestCut = signalBelow ? roc.get(i)[2] : roc.get(roc.size() - 1 - i)[2];
                bestS = s;
                bestB = b;
            }
            if (Double.isNaN(rej) && s >= 0.5) rej = b > 0 ? 1 / b : Double.POSITIVE_INFINITY;
        }
        return new Result(observable, auc, signalBelow, rej, bestCut, bestS, bestB, effS, effB, ns, nb);
    }

    private static double[] ones(int n) {
        final double[] w = new double[n];
        Arrays.fill(w, 1.0);
        return w;
    }
}
