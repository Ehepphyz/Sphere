package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::FourierFit (FourierFit.hh, .icc): |Σ c_n P_n(x)|² over a sine basis of order N, the
 * coefficients built from N fractions and phases so that the density stays normalized.
 */
public class FourierFit extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final int N;
    private final List<Parameter> fraction = new ArrayList<>();
    private final List<Parameter> phase = new ArrayList<>();

    public FourierFit(int N) {
        this.N = N;
        for (int i = 0; i < N; i++) fraction.add(new Parameter("Fraction " + i, 0.5, 0.0, 1.0));
        for (int i = 0; i < N; i++) phase.add(new Parameter("Phase " + i, M_PI, 0.0, 2.0 * M_PI));
    }

    public FourierFit(FourierFit right) {
        N = right.N;
        for (int i = 0; i < N; i++) {
            fraction.add(new Parameter(right.fraction.get(i)));
            phase.add(new Parameter(right.phase.get(i)));
        }
    }

    @Override
    public FourierFit copy() {
        return new FourierFit(this);
    }

    @Override
    public double call(double x) {
        int n = N;
        Complex P = new Complex(0.0);
        final Complex I = new Complex(0, 1.0);
        double f = 1.0;
        while (true) {
            if (n == 0) {
                final double fn = 1.0;
                final double Pn = Math.sqrt(1 / 2.0 / M_PI);
                P = P.plus((Math.sqrt(f * fn) * Pn));
                break;
            } else {
                final double fn = getFraction(n - 1).getValue();
                final double px = getPhase(n - 1).getValue();
                final double Pn = Math.sqrt(1 / M_PI) * CMath.sin(n * x / 2.0);
                P = P.plus(I.times(px).exp().times(Math.sqrt(f * fn)).times(Pn));
                f *= (1 - fn);
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
}
