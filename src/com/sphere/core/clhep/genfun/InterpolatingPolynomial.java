package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::InterpolatingPolynomial (InterpolatingPolynomial.hh, .cc): the polynomial through the
 * points added, evaluated by Neville's algorithm (Numerical Recipes' polint).
 */
public class InterpolatingPolynomial extends AbsFunction {

    private final List<double[]> xPoints = new ArrayList<>();

    public InterpolatingPolynomial() {
    }

    public InterpolatingPolynomial(InterpolatingPolynomial right) {
        xPoints.addAll(right.xPoints);
    }

    @Override
    public InterpolatingPolynomial copy() {
        return new InterpolatingPolynomial(this);
    }

    @Override
    public double call(double x) {
        double y;
        double deltay;
        double dif = CMath.fabs(x - xPoints.get(0)[0]);
        double dift;
        final int K = xPoints.size();
        final int KP = K + 1;
        final double[] c = new double[KP];
        final double[] d = new double[KP];
        int ns = 0;
        for (int i = 0; i < K; i++) {
            dift = CMath.fabs(x - xPoints.get(i)[0]);
            if (dift < dif) {
                ns = i;
                dif = dift;
            }
            c[i] = d[i] = xPoints.get(i)[1];
        }
        y = xPoints.get(ns--)[1];
        for (int m = 0; m < K - 1; m++) {
            for (int i = 0; i < K - m - 1; i++) {
                final double ho = xPoints.get(i)[0] - x;
                final double hp = xPoints.get(i + m + 1)[0] - x;
                final double w = c[i + 1] - d[i];
                double den = ho - hp;
                if (den == 0) StdStreams.cerr().print("Error in polynomial extrapolation\n");
                den = w / den;
                d[i] = hp * den;
                c[i] = ho * den;
            }
            deltay = 2 * (ns + 1) < (K - m - 1) ? c[ns + 1] : d[ns--];
            y += deltay;
        }
        return y;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public void addPoint(double x, double y) {
        xPoints.add(new double[] {x, y});
    }

    /** {min, max} of the abscissas, {DBL_MAX, -DBL_MAX} without points. */
    public double[] getRange() {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] p : xPoints) {
            min = CMath.min(min, p[0]);
            max = CMath.max(max, p[0]);
        }
        return new double[] {min, max};
    }
}
