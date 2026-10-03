package com.sphere.core.fjcontrib.energycorrelator;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.List;

/**
 * The N-point energy correlation function, fastjet::contrib::EnergyCorrelator
 * (EnergyCorrelator 1.3.2; A.J. Larkoski, G.P. Salam and J. Thaler, JHEP 06
 * (2013) 108):
 * ECF(N, beta) = sum over N-tuples of the product of their energies and of
 * all their pairwise angles to the power beta. N = 0 to 5.
 *
 * The double-precision sums are the C++'s term for term. For
 * double-double constituents every sum is compensated (each term still a
 * double), which removes the rounding of adding up the O(n^N) terms.
 */
public class EnergyCorrelator implements FunctionOfPseudoJet<Double> {

    static {
        ContribCitations.use("ecf");
    }

    /** What energy and angle are. */
    public enum Measure {
        /** Transverse momenta and boost-invariant angles. */
        pt_R,
        /** Energies and angles. */
        E_theta,
        /** Energies and invariant masses. */
        E_inv
    }

    /** Whether the pairwise angles are cached. */
    public enum Strategy {
        slow,
        storage_array
    }

    protected final int n;
    protected final double beta;
    protected final Measure measure;
    protected final Strategy strategy;

    public EnergyCorrelator(int n, double beta, Measure measure, Strategy strategy) {
        this.n = n;
        this.beta = beta;
        this.measure = measure;
        this.strategy = strategy;
    }

    public EnergyCorrelator(int n, double beta, Measure measure) {
        this(n, beta, measure, Strategy.storage_array);
    }

    public EnergyCorrelator(int n, double beta) {
        this(n, beta, Measure.pt_R, Strategy.storage_array);
    }

    /** A sum, plain as the C++'s or compensated for double-double inputs. */
    static final class Acc {
        private final boolean exact;
        double hi;
        double lo;

        Acc(boolean exact) {
            this.exact = exact;
        }

        void add(double x) {
            if (!exact) {
                hi += x;
                return;
            }
            final double s = hi + x;
            final double bb = s - hi;
            lo += (hi - (s - bb)) + (x - bb);
            hi = s;
        }

        double value() {
            return exact ? hi + lo : hi;
        }
    }

    static boolean exact(List<PseudoJet> particles) {
        return !particles.isEmpty() && particles.get(0).isDD();
    }

    @Override
    public Double result(PseudoJet jet) {
        if (!jet.hasConstituents()) throw new FastJetException("EnergyCorrelator called on jet with no constituents.");
        if (n == 0) return 1.0;
        final List<PseudoJet> particles = jet.constituents();
        if (particles.size() < n) return 0.0;
        final Acc answer = new Acc(exact(particles));
        if (n == 1) {
            for (PseudoJet p : particles) answer.add(energy(p));
            return answer.value();
        }
        final double halfBeta = beta / 2.0;
        if (n == 2) {
            for (int i = 0; i < particles.size(); i++) {
                for (int j = i + 1; j < particles.size(); j++) {
                    answer.add(energy(particles.get(i)) * energy(particles.get(j))
                        * CRMath.pow(angleSquared(particles.get(i), particles.get(j)), halfBeta));
                }
            }
            return answer.value();
        }
        if (n > 5) throw new FastJetException("EnergyCorrelator is only hard coded for N = 0,1,2,3,4,5");
        if (strategy == Strategy.storage_array) {
            final int nC = particles.size();
            final double[] energyStore = new double[nC];
            final double[][] angleStore = new double[nC][];
            precomputeEnergiesAndAngles(particles, energyStore, angleStore);
            final int nAngles = n * (n - 1) / 2;
            return switch (n) {
                case 3 -> evaluateN3(nC, nAngles, energyStore, angleStore, exact(particles));
                case 4 -> evaluateN4(nC, nAngles, energyStore, angleStore, exact(particles));
                default -> evaluateN5(nC, nAngles, energyStore, angleStore, exact(particles));
            };
        }
        final int size = particles.size();
        if (n == 3) {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    final double ansIj = energy(particles.get(i)) * energy(particles.get(j))
                        * CRMath.pow(angleSquared(particles.get(i), particles.get(j)), halfBeta);
                    for (int k = j + 1; k < size; k++) {
                        answer.add(ansIj * energy(particles.get(k))
                            * CRMath.pow(angleSquared(particles.get(i), particles.get(k)), halfBeta)
                            * CRMath.pow(angleSquared(particles.get(j), particles.get(k)), halfBeta));
                    }
                }
            }
        } else if (n == 4) {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    final double ansIj = energy(particles.get(i)) * energy(particles.get(j))
                        * CRMath.pow(angleSquared(particles.get(i), particles.get(j)), halfBeta);
                    for (int k = j + 1; k < size; k++) {
                        final double ansIjk = ansIj * energy(particles.get(k))
                            * CRMath.pow(angleSquared(particles.get(i), particles.get(k)), halfBeta)
                            * CRMath.pow(angleSquared(particles.get(j), particles.get(k)), halfBeta);
                        for (int l = k + 1; l < size; l++) {
                            answer.add(ansIjk * energy(particles.get(l))
                                * CRMath.pow(angleSquared(particles.get(i), particles.get(l)), halfBeta)
                                * CRMath.pow(angleSquared(particles.get(j), particles.get(l)), halfBeta)
                                * CRMath.pow(angleSquared(particles.get(k), particles.get(l)), halfBeta));
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    final double ansIj = energy(particles.get(i)) * energy(particles.get(j))
                        * CRMath.pow(angleSquared(particles.get(i), particles.get(j)), halfBeta);
                    for (int k = j + 1; k < size; k++) {
                        final double ansIjk = ansIj * energy(particles.get(k))
                            * CRMath.pow(angleSquared(particles.get(i), particles.get(k)), halfBeta)
                            * CRMath.pow(angleSquared(particles.get(j), particles.get(k)), halfBeta);
                        for (int l = k + 1; l < size; l++) {
                            final double ansIjkl = ansIjk * energy(particles.get(l))
                                * CRMath.pow(angleSquared(particles.get(i), particles.get(l)), halfBeta)
                                * CRMath.pow(angleSquared(particles.get(j), particles.get(l)), halfBeta)
                                * CRMath.pow(angleSquared(particles.get(k), particles.get(l)), halfBeta);
                            for (int m = l + 1; m < size; m++) {
                                answer.add(ansIjkl * energy(particles.get(m))
                                    * CRMath.pow(angleSquared(particles.get(i), particles.get(m)), halfBeta)
                                    * CRMath.pow(angleSquared(particles.get(j), particles.get(m)), halfBeta)
                                    * CRMath.pow(angleSquared(particles.get(k), particles.get(m)), halfBeta)
                                    * CRMath.pow(angleSquared(particles.get(l), particles.get(m)), halfBeta));
                            }
                        }
                    }
                }
            }
        }
        return answer.value();
    }

    double energy(PseudoJet jet) {
        return switch (measure) {
            case pt_R -> jet.perp();
            case E_theta, E_inv -> jet.e();
        };
    }

    double angleSquared(PseudoJet jet1, PseudoJet jet2) {
        switch (measure) {
            case pt_R:
                return jet1.squaredDistance(jet2);
            case E_theta: {
                final double dot = jet1.px() * jet2.px() + jet1.py() * jet2.py() + jet1.pz() * jet2.pz();
                final double norm1 = jet1.px() * jet1.px() + jet1.py() * jet1.py() + jet1.pz() * jet1.pz();
                final double norm2 = jet2.px() * jet2.px() + jet2.py() * jet2.py() + jet2.pz() * jet2.pz();
                double costheta = dot / Math.sqrt(norm1 * norm2);
                if (costheta > 1.0) costheta = 1.0;
                final double theta = CRMath.acos(costheta);
                return theta * theta;
            }
            default: {
                if (jet1.E() < 0.0000001 || jet2.E() < 0.0000001) return 0.0;
                final double dot4 = Math.max(jet1.E() * jet2.E() - jet1.px() * jet2.px() - jet1.py() * jet2.py()
                    - jet1.pz() * jet2.pz(), 0.0);
                return 2.0 * dot4 / jet1.E() / jet2.E();
            }
        }
    }

    /** The product of the n smallest angles, taken smallest first (a used one set to INT_MAX). */
    static double multiplyAngles(double[] angleList, int nAngles, int nTotal) {
        double product = 1;
        for (int a = 0; a < nAngles; a++) {
            double curMin = angleList[0];
            int curMinPos = 0;
            for (int b = 1; b < nTotal; b++) {
                if (angleList[b] < curMin) {
                    curMin = angleList[b];
                    curMinPos = b;
                }
            }
            product *= curMin;
            angleList[curMinPos] = Integer.MAX_VALUE;
        }
        return product;
    }

    void precomputeEnergiesAndAngles(List<PseudoJet> particles, double[] energyStore, double[][] angleStore) {
        final int nC = particles.size();
        for (int i = 0; i < nC; i++) angleStore[i] = new double[i];
        final double halfBeta = beta / 2.0;
        for (int i = 0; i < nC; i++) {
            energyStore[i] = energy(particles.get(i));
            for (int j = 0; j < i; j++) {
                angleStore[i][j] = halfBeta == 1.0 ? angleSquared(particles.get(i), particles.get(j))
                    : CRMath.pow(angleSquared(particles.get(i), particles.get(j)), halfBeta);
            }
        }
    }

    static double evaluateN3(int nC, int nAngles, double[] e, double[][] a, boolean exact) {
        final int nTotal = 3;
        final Acc answer = new Acc(exact);
        final double[] list = new double[3];
        for (int i = 2; i < nC; i++) {
            for (int j = 1; j < i; j++) {
                final double eij = e[i] * e[j];
                for (int k = 0; k < j; k++) {
                    final double a1 = a[i][j], a2 = a[i][k], a3 = a[j][k];
                    final double angle;
                    if (nAngles == nTotal) {
                        angle = a1 * a2 * a3;
                    } else {
                        list[0] = a1;
                        list[1] = a2;
                        list[2] = a3;
                        angle = multiplyAngles(list, nAngles, nTotal);
                    }
                    answer.add(eij * e[k] * angle);
                }
            }
        }
        return answer.value();
    }

    static double evaluateN4(int nC, int nAngles, double[] e, double[][] a, boolean exact) {
        final int nTotal = 6;
        final Acc answer = new Acc(exact);
        final double[] list = new double[6];
        for (int i = 3; i < nC; i++) {
            for (int j = 2; j < i; j++) {
                for (int k = 1; k < j; k++) {
                    for (int l = 0; l < k; l++) {
                        final double a1 = a[i][j], a2 = a[i][k], a3 = a[i][l], a4 = a[j][k], a5 = a[j][l], a6 = a[k][l];
                        final double angle;
                        if (nAngles == nTotal) {
                            angle = a1 * a2 * a3 * a4 * a5 * a6;
                        } else {
                            list[0] = a1;
                            list[1] = a2;
                            list[2] = a3;
                            list[3] = a4;
                            list[4] = a5;
                            list[5] = a6;
                            angle = multiplyAngles(list, nAngles, nTotal);
                        }
                        answer.add(e[i] * e[j] * e[k] * e[l] * angle);
                    }
                }
            }
        }
        return answer.value();
    }

    static double evaluateN5(int nC, int nAngles, double[] e, double[][] a, boolean exact) {
        final int nTotal = 10;
        final Acc answer = new Acc(exact);
        final double[] list = new double[10];
        for (int i = 4; i < nC; i++) {
            for (int j = 3; j < i; j++) {
                for (int k = 2; k < j; k++) {
                    for (int l = 1; l < k; l++) {
                        for (int m = 0; m < l; m++) {
                            list[0] = a[i][j];
                            list[1] = a[i][k];
                            list[2] = a[i][l];
                            list[3] = a[i][m];
                            list[4] = a[j][k];
                            list[5] = a[j][l];
                            list[6] = a[j][m];
                            list[7] = a[k][l];
                            list[8] = a[k][m];
                            list[9] = a[l][m];
                            final double angle = multiplyAngles(list, nAngles, nTotal);
                            answer.add(e[i] * e[j] * e[k] * e[l] * e[m] * angle);
                        }
                    }
                }
            }
        }
        return answer.value();
    }

    public int N() { return n; }
    public double beta() { return beta; }
    public Measure measure() { return measure; }
    public Strategy strategy() { return strategy; }

    public String descriptionParameters() {
        return "N=" + n + ", beta=" + Fmt.g(beta) + measureAndStrategy();
    }

    public String descriptionNoN() {
        return "beta=" + Fmt.g(beta) + measureAndStrategy();
    }

    private String measureAndStrategy() {
        final String m = switch (measure) {
            case pt_R -> ", pt_R measure";
            case E_theta -> ", E_theta measure";
            case E_inv -> ", E_inv measure";
        };
        return m + (strategy == Strategy.slow ? " and 'slow' strategy" : " and 'storage_array' strategy");
    }

    @Override
    public String description() {
        return "Energy Correlator ECF(N,beta) for " + descriptionParameters();
    }
}
