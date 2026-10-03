package com.sphere.core.fjcontrib.energycorrelator;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelator.Acc;

import java.util.Arrays;
import java.util.List;

/**
 * The generalised energy correlation functions, EnergyCorrelatorGeneralized
 * (I. Moult, L. Necib and J. Thaler, JHEP 12 (2016) 153): ECFG(v, N, beta),
 * normalised energies, and only the v smallest pairwise angles of each
 * N-tuple (v = -1: all of them).
 */
public class EnergyCorrelatorGeneralized implements FunctionOfPseudoJet<Double> {

    static {
        ContribCitations.use("ecfg");
    }

    private final int angles;
    private final int n;
    private final double beta;
    private final EnergyCorrelator.Measure measure;
    private final EnergyCorrelator.Strategy strategy;
    private final EnergyCorrelator helper;

    public EnergyCorrelatorGeneralized(int vAngles, int n, double beta, EnergyCorrelator.Measure measure,
                                       EnergyCorrelator.Strategy strategy) {
        this.angles = vAngles;
        this.n = n;
        this.beta = beta;
        this.measure = measure;
        this.strategy = strategy;
        this.helper = new EnergyCorrelator(1, beta, measure, strategy);
    }

    public EnergyCorrelatorGeneralized(int vAngles, int n, double beta, EnergyCorrelator.Measure measure) {
        this(vAngles, n, beta, measure, EnergyCorrelator.Strategy.storage_array);
    }

    public EnergyCorrelatorGeneralized(int vAngles, int n, double beta) {
        this(vAngles, n, beta, EnergyCorrelator.Measure.pt_R, EnergyCorrelator.Strategy.storage_array);
    }

    private double energy(PseudoJet j) {
        return helper.energy(j);
    }

    private double angleSquared(PseudoJet a, PseudoJet b) {
        return helper.angleSquared(a, b);
    }

    @Override
    public Double result(PseudoJet jet) {
        if (!jet.hasConstituents()) throw new FastJetException("EnergyCorrelator called on jet with no constituents.");
        if (n == 0) return 1.0;
        if (n == 1) return 1.0;
        final List<PseudoJet> p = jet.constituents();
        if (p.size() < n) return 0.0;
        final boolean exact = EnergyCorrelator.exact(p);
        final Acc answer = new Acc(exact);
        final double ej = helper.result(jet);
        final double norm = CRMath.pow(ej, n);
        final int nTotal = n * (n - 1) / 2;
        if (angles > nTotal) throw new FastJetException("Requested number of angles for EnergyCorrelatorGeneralized is larger than number of angles available");
        if (angles < -1) throw new FastJetException("Negative number of angles called for EnergyCorrelatorGeneralized");
        final double halfBeta = beta / 2.0;
        final int size = p.size();
        if (n == 2) {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    answer.add(energy(p.get(i)) * energy(p.get(j)) * CRMath.pow(angleSquared(p.get(i), p.get(j)), halfBeta) / norm);
                }
            }
            return answer.value();
        }
        if (n > 5) throw new FastJetException("EnergyCorrelatorGeneralized is only hard coded for N = 0,1,2,3,4,5");
        if (strategy == EnergyCorrelator.Strategy.storage_array) {
            final double[] energyStore = new double[size];
            final double[][] angleStore = new double[size][];
            helper.precomputeEnergiesAndAngles(p, energyStore, angleStore);
            final int nAngles = angles < 0 ? nTotal : angles;
            final double e = switch (n) {
                case 3 -> EnergyCorrelator.evaluateN3(size, nAngles, energyStore, angleStore, exact);
                case 4 -> EnergyCorrelator.evaluateN4(size, nAngles, energyStore, angleStore, exact);
                default -> EnergyCorrelator.evaluateN5(size, nAngles, energyStore, angleStore, exact);
            };
            return e / norm;
        }
        if (n == 3) {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    for (int k = j + 1; k < size; k++) {
                        final double[] a = {angleSquared(p.get(i), p.get(j)), angleSquared(p.get(i), p.get(k)),
                            angleSquared(p.get(j), p.get(k))};
                        final double angle = productOfSmallest(a);
                        answer.add(energy(p.get(i)) * energy(p.get(j)) * energy(p.get(k)) * CRMath.pow(angle, halfBeta) / norm);
                    }
                }
            }
        } else if (n == 4) {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    for (int k = j + 1; k < size; k++) {
                        for (int l = k + 1; l < size; l++) {
                            final double[] a = {angleSquared(p.get(i), p.get(j)), angleSquared(p.get(i), p.get(k)),
                                angleSquared(p.get(i), p.get(l)), angleSquared(p.get(j), p.get(k)),
                                angleSquared(p.get(j), p.get(l)), angleSquared(p.get(k), p.get(l))};
                            final double angle = productOfSmallest(a);
                            answer.add(energy(p.get(i)) * energy(p.get(j)) * energy(p.get(k)) * energy(p.get(l))
                                * CRMath.pow(angle, halfBeta) / norm);
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    for (int k = j + 1; k < size; k++) {
                        for (int l = k + 1; l < size; l++) {
                            for (int m = l + 1; m < size; m++) {
                                final double[] a = {angleSquared(p.get(i), p.get(j)), angleSquared(p.get(i), p.get(k)),
                                    angleSquared(p.get(i), p.get(l)), angleSquared(p.get(j), p.get(k)),
                                    angleSquared(p.get(j), p.get(l)), angleSquared(p.get(k), p.get(l)),
                                    angleSquared(p.get(m), p.get(i)), angleSquared(p.get(m), p.get(j)),
                                    angleSquared(p.get(m), p.get(k)), angleSquared(p.get(m), p.get(l))};
                                final double angle = productOfSmallest(a);
                                answer.add(energy(p.get(i)) * energy(p.get(j)) * energy(p.get(k)) * energy(p.get(l))
                                    * energy(p.get(m)) * CRMath.pow(angle, halfBeta) / norm);
                            }
                        }
                    }
                }
            }
        }
        return answer.value();
    }

    /** All the angles multiplied in order (v = -1), or the v smallest, smallest first. */
    private double productOfSmallest(double[] a) {
        if (angles == -1) {
            double angle = a[0];
            for (int s = 1; s < a.length; s++) angle *= a[s];
            return angle;
        }
        final double[] v = a.clone();
        Arrays.sort(v);
        double angle = v[0];
        for (int s = 1; s < angles; s++) angle = angle * v[s];
        return angle;
    }

    /** ECFG(v, N, beta) for every v = 1..N(N-1)/2 at once. */
    public double[] resultAllAngles(PseudoJet jet) {
        if (!jet.hasConstituents()) throw new FastJetException("EnergyCorrelator called on jet with no constituents.");
        if (n < 1) throw new FastJetException("N cannot be negative or zero");
        if (n == 1) return new double[]{1.0};
        final List<PseudoJet> p = jet.constituents();
        if (p.size() < n) return new double[n];
        final boolean exact = EnergyCorrelator.exact(p);
        final double ej = helper.result(jet);
        final double norm = CRMath.pow(ej, n);
        final int nTotal = n * (n - 1) / 2;
        final double halfBeta = beta / 2.0;
        final int size = p.size();
        if (n == 2) {
            final Acc answer = new Acc(exact);
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    answer.add(energy(p.get(i)) * energy(p.get(j)) * CRMath.pow(angleSquared(p.get(i), p.get(j)), halfBeta) / norm);
                }
            }
            final double[] ans = new double[nTotal];
            Arrays.fill(ans, answer.value());
            return ans;
        }
        if (n > 5) throw new FastJetException("EnergyCorrelatorGeneralized is only hard coded for N = 0,1,2,3,4,5");
        final Acc[] ans = new Acc[nTotal];
        for (int s = 0; s < nTotal; s++) ans[s] = new Acc(exact);
        final double[] energyStore = new double[size];
        final double[][] angleStore = new double[size][];
        final boolean stored = strategy == EnergyCorrelator.Strategy.storage_array;
        if (stored) {
            for (int i = 0; i < size; i++) {
                angleStore[i] = new double[i];
                energyStore[i] = energy(p.get(i));
                for (int j = 0; j < i; j++) {
                    angleStore[i][j] = halfBeta == 1 ? angleSquared(p.get(i), p.get(j))
                        : CRMath.pow(angleSquared(p.get(i), p.get(j)), halfBeta);
                }
            }
        }
        final double[] list = new double[nTotal];
        final int[] idx = new int[n];
        // the N-tuples, in the order of the C++ loops: descending indices when stored, ascending when slow
        forEachTuple(size, n, stored, idx, () -> {
            double zProduct;
            if (stored) {
                int a = 0;
                for (int x = 0; x < n; x++) for (int y = x + 1; y < n; y++) list[a++] = angleStore[idx[x]][idx[y]];
                zProduct = energyStore[idx[0]];
                for (int x = 1; x < n; x++) zProduct *= energyStore[idx[x]];
            } else {
                slowAngles(p, idx, halfBeta, list);
                zProduct = energy(p.get(idx[0]));
                for (int x = 1; x < n; x++) zProduct *= energy(p.get(idx[x]));
            }
            zProduct /= norm;
            Arrays.sort(list);
            double finalAngle = list[0];
            ans[0].add(zProduct * finalAngle);
            for (int s = 1; s < nTotal; s++) {
                finalAngle = finalAngle * list[s];
                ans[s].add(zProduct * finalAngle);
            }
        });
        final double[] out = new double[nTotal];
        for (int s = 0; s < nTotal; s++) out[s] = ans[s].value();
        return out;
    }

    /** The slow strategy's angles, in its order (for N = 5 the last four pair m with the others). */
    private void slowAngles(List<PseudoJet> p, int[] idx, double halfBeta, double[] list) {
        if (n == 5) {
            final PseudoJet i = p.get(idx[0]), j = p.get(idx[1]), k = p.get(idx[2]), l = p.get(idx[3]), m = p.get(idx[4]);
            final PseudoJet[][] pairs = {{i, j}, {i, k}, {i, l}, {j, k}, {j, l}, {k, l}, {m, i}, {m, j}, {m, k}, {m, l}};
            for (int a = 0; a < 10; a++) list[a] = CRMath.pow(angleSquared(pairs[a][0], pairs[a][1]), halfBeta);
            return;
        }
        int a = 0;
        for (int x = 0; x < n; x++) {
            for (int y = x + 1; y < n; y++) {
                list[a++] = CRMath.pow(angleSquared(p.get(idx[x]), p.get(idx[y])), halfBeta);
            }
        }
    }

    /**
     * Visits the N-tuples as the C++ nested loops do: with {@code descending},
     * i > j > k > ... (i outermost, from N-1 up); otherwise i < j < k ...
     */
    private static void forEachTuple(int size, int n, boolean descending, int[] idx, Runnable body) {
        tupleLevel(size, n, descending, idx, 0, body);
    }

    private static void tupleLevel(int size, int n, boolean descending, int[] idx, int level, Runnable body) {
        if (level == n) {
            body.run();
            return;
        }
        if (descending) {
            final int lo = n - 1 - level;
            final int hi = level == 0 ? size : idx[level - 1];
            for (int v = lo; v < hi; v++) {
                idx[level] = v;
                tupleLevel(size, n, true, idx, level + 1, body);
            }
        } else {
            final int lo = level == 0 ? 0 : idx[level - 1] + 1;
            for (int v = lo; v < size; v++) {
                idx[level] = v;
                tupleLevel(size, n, false, idx, level + 1, body);
            }
        }
    }

    @Override
    public String description() {
        return "";
    }
}
