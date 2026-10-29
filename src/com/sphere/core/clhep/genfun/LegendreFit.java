package com.sphere.core.clhep.genfun;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::LegendreFit (LegendreFit.hh, .icc): |Σ a_l sqrt((2l+1)/2) P_l(x)|², the coefficients a_l
 * built from N fractions and phases so that the density stays normalized on [-1, 1]. CLHEP's abs(l1-l2)
 * of unsigned values (ambiguous for GCC 16) is read as the int |l1 - l2| it means.
 */
public class LegendreFit extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final int N;
    private final List<Parameter> fraction = new ArrayList<>();
    private final List<Parameter> phase = new ArrayList<>();
    private final LegendreCoefficientSet coefA;
    private final LegendreCoefficientSet coefASq;
    private final ClebschGordanCoefficientSet ClebschGordan = new ClebschGordanCoefficientSet();

    public LegendreFit(int N) {
        this.N = N;
        coefA = new LegendreCoefficientSet(N);
        coefASq = new LegendreCoefficientSet(2 * N);
        for (int i = 0; i < N; i++) fraction.add(new Parameter("Fraction " + i, 0.5, 0.0, 1.0));
        for (int i = 0; i < N; i++) phase.add(new Parameter("Phase " + i, M_PI, 0.0, 2.0 * M_PI));
    }

    public LegendreFit(LegendreFit right) {
        N = right.N;
        coefA = new LegendreCoefficientSet(right.N);
        coefASq = new LegendreCoefficientSet(2 * right.N);
        for (int i = 0; i < N; i++) {
            fraction.add(new Parameter(right.fraction.get(i)));
            phase.add(new Parameter(right.phase.get(i)));
        }
    }

    @Override
    public LegendreFit copy() {
        return new LegendreFit(this);
    }

    @Override
    public double call(double x) {
        recomputeCoefficients();
        final double[] Pk = Gsl.legendrePlArray(N, x);
        int n = N;
        Complex P = new Complex(0.0);
        while (true) {
            if (n == 0) {
                final double Pn = Math.sqrt((2 * n + 1.0) / 2.0) * Pk[n];
                P = P.plus(coefA.get(n).times(Pn));
                break;
            } else {
                final double Pn = Math.sqrt((2 * n + 1.0) / 2.0) * Pk[n];
                P = P.plus(coefA.get(n).times(Pn));
                n--;
            }
        }
        return P.norm();
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public int order() {
        return N;
    }

    public Parameter getFraction(int i) {
        return fraction.get(i);
    }

    public Parameter getPhase(int i) {
        return phase.get(i);
    }

    public LegendreCoefficientSet coefficientsA() {
        recomputeCoefficients();
        return coefA;
    }

    /** The coefficients of the expansion of the density itself, up to 2N. */
    public LegendreCoefficientSet coefficientsASq() {
        recomputeCoefficients();
        final int LMAX = coefA.getLMax();
        for (int L = 0; L <= 2 * LMAX; L++) {
            coefASq.set(L, new Complex(0.0));
            for (int l1 = 0; l1 <= LMAX; l1++) {
                for (int l2 = 0; l2 <= LMAX; l2++) {
                    if (((l1 + l2) >= L) && Math.abs(l1 - l2) <= L) {
                        final double cg = ClebschGordan.get(l1, l2, 0, 0, L, 0);
                        coefASq.set(L, coefASq.get(L).plus(coefA.get(l1).times(coefA.get(l2).conj())
                            .times(Math.sqrt((2 * l1 + 1) * (2 * l2 + 1) / 4.0)).times(cg * cg)));
                    }
                }
            }
        }
        return coefASq;
    }

    public void recomputeCoefficients() {
        int n = N;
        final Complex I = new Complex(0, 1.0);
        double f = 1.0;
        while (true) {
            if (n == 0) {
                final double fn = 1.0;
                coefA.set(n, new Complex(Math.sqrt(f * fn)));
                break;
            } else {
                final double fn = getFraction(n - 1).getValue();
                final double px = getPhase(n - 1).getValue();
                coefA.set(n, I.times(px).exp().times(Math.sqrt(f * fn)));
                f *= (1 - fn);
                n--;
            }
        }
    }
}
