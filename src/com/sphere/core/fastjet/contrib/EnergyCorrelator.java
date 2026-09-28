package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

import java.util.List;

/**
 * Energy correlation functions (A. Larkoski, G. Salam, J. Thaler, JHEP 06
 * (2013) 108) and their generalisations (I. Moult, L. Necib, J. Thaler,
 * JHEP 12 (2016) 153):
 * <pre>
 *   e2       = sum_{i<j}   z_i z_j Delta_ij^beta
 *   e3       = sum_{i<j<k} z_i z_j z_k (Delta_ij Delta_ik Delta_jk)^beta
 *   v_e_3    = sum_{i<j<k} z_i z_j z_k  x product of the v smallest Delta^beta
 *   C2 = e3 / e2^2,   D2 = e3 / e2^3,   N2 = 2_e_3 / (1_e_2)^2,   M2 = 1_e_3 / 1_e_2
 * </pre>
 * with z the momentum fractions (pt, or energy for e+e-) and Delta the
 * (rapidity, phi) distance (or opening angle).
 *
 * Written for Sphere from the papers. Every term and every sum is carried
 * in double-double: the O(n^3) sums of e3 accumulate millions of terms of
 * all sizes, where a plain double sum loses digits.
 */
public final class EnergyCorrelator {

    static {
        Citations.use("ecf"); // listed in the console's Citations menu once used
    }

    public enum Measure { PT_R, E_THETA }

    private final double beta;
    private final Measure measure;

    public EnergyCorrelator(double beta) {
        this(beta, Measure.PT_R);
    }

    public EnergyCorrelator(double beta, Measure measure) {
        this.beta = beta;
        this.measure = measure;
    }

    /** The correlators of a jet: e2, e3, 1e2 (= e2), 1e3, 2e3. */
    public record Values(double e2, double e3, double oneE3, double twoE3) {
        public double c2() { return e2 == 0 ? 0 : e3 / (e2 * e2); }
        public double d2() { Citations.use("d2"); return e2 == 0 ? 0 : e3 / (e2 * e2 * e2); }
        public double n2() { Citations.use("n2m2"); return e2 == 0 ? 0 : twoE3 / (e2 * e2); }
        public double m2() { Citations.use("n2m2"); return e2 == 0 ? 0 : oneE3 / e2; }
    }

    public Values compute(PseudoJet jet) {
        final List<PseudoJet> c = jet.constituents();
        final int n = c.size();
        final DD[] z = new DD[n];
        DD total = DD.ZERO;
        for (int i = 0; i < n; i++) {
            z[i] = measure == Measure.E_THETA ? Kin.e(c.get(i)) : Kin.pt(c.get(i));
            total = total.add(z[i]);
        }
        if (total.isZero() || n < 2) return new Values(0, 0, 0, 0);
        for (int i = 0; i < n; i++) z[i] = z[i].div(total);
        final DD[][] d = new DD[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final DD delta = measure == Measure.E_THETA ? Kin.angle(c.get(i), c.get(j))
                    : Kin.dR2(c.get(i), c.get(j)).sqrt();
                d[i][j] = d[j][i] = Kin.pow(delta, beta);
            }
        }
        DD e2 = DD.ZERO;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) e2 = e2.add(z[i].mul(z[j]).mul(d[i][j]));
        }
        DD e3 = DD.ZERO;
        DD one = DD.ZERO;
        DD two = DD.ZERO;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final DD zij = z[i].mul(z[j]);
                for (int k = j + 1; k < n; k++) {
                    final DD w = zij.mul(z[k]);
                    DD a = d[i][j];
                    DD b = d[i][k];
                    DD cc = d[j][k];
                    // sort the three pairwise distances
                    if (b.lt(a)) { final DD t = a; a = b; b = t; }
                    if (cc.lt(b)) { final DD t = b; b = cc; cc = t; }
                    if (b.lt(a)) { final DD t = a; a = b; b = t; }
                    e3 = e3.add(w.mul(a).mul(b).mul(cc));
                    one = one.add(w.mul(a));
                    two = two.add(w.mul(a).mul(b));
                }
            }
        }
        return new Values(e2.doubleValue(), e3.doubleValue(), one.doubleValue(), two.doubleValue());
    }

    public FunctionOfPseudoJet<Double> c2() {
        return FunctionOfPseudoJet.of("C2 (beta=" + Fmt.g(beta) + ")", j -> compute(j).c2());
    }

    public FunctionOfPseudoJet<Double> d2() {
        return FunctionOfPseudoJet.of("D2 (beta=" + Fmt.g(beta) + ")", j -> compute(j).d2());
    }

    public FunctionOfPseudoJet<Double> n2() {
        return FunctionOfPseudoJet.of("N2 (beta=" + Fmt.g(beta) + ")", j -> compute(j).n2());
    }

    public FunctionOfPseudoJet<Double> m2() {
        return FunctionOfPseudoJet.of("M2 (beta=" + Fmt.g(beta) + ")", j -> compute(j).m2());
    }
}
