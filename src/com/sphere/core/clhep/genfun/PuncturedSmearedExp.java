package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::PuncturedSmearedExp (PuncturedSmearedExp.hh, .cc): an exponential decay smeared by a
 * Gaussian, normalized over the intervals left after the punctures (overlapping punctures merged, with
 * CLHEP's test of their overlap).
 */
public class PuncturedSmearedExp extends AbsFunction {

    private final Parameter _lifetime;
    private final Parameter _sigma;
    private final List<Parameter> _punctures = new ArrayList<>();

    public PuncturedSmearedExp() {
        _lifetime = new Parameter("Lifetime", 1.0, 0.0);
        _sigma = new Parameter("Sigma", 1.0, 0.0);
    }

    public PuncturedSmearedExp(PuncturedSmearedExp right) {
        _lifetime = new Parameter(right._lifetime);
        _sigma = new Parameter(right._sigma);
        for (Parameter p : right._punctures) _punctures.add(new Parameter(p));
    }

    @Override
    public PuncturedSmearedExp copy() {
        return new PuncturedSmearedExp(this);
    }

    public Parameter lifetime() {
        return _lifetime;
    }

    public Parameter sigma() {
        return _sigma;
    }

    /** An interval taken out: parameters Min_i and Max_i. */
    public void puncture(double xmin, double xmax) {
        _punctures.add(new Parameter("Min_" + _punctures.size() / 2, xmin, 0.0, 10.0));
        _punctures.add(new Parameter("Max_" + _punctures.size() / 2, xmax, 0.0, 10.0));
    }

    public Parameter min(int i) {
        return _punctures.get(2 * i);
    }

    public Parameter max(int i) {
        return _punctures.get(2 * i + 1);
    }

    @Override
    public double call(double argument) {
        final double sqrtTwo = Math.sqrt(2.0);
        final double xsigma = _sigma.getValue();
        final double tau = _lifetime.getValue();
        final double x = argument;

        final List<Double> punctures = new ArrayList<>(_punctures.size());
        for (Parameter p : _punctures) punctures.add(p.getValue());

        // merge the overlapping punctures
        boolean overlap = true;
        while (overlap) {
            overlap = false;
            for (int i = 0; i < punctures.size() / 2; i++) {
                sort2(punctures, 2 * i);
                final double min1 = punctures.get(2 * i);
                final double max1 = punctures.get(2 * i + 1);
                for (int j = i + 1; j < punctures.size() / 2; j++) {
                    sort2(punctures, 2 * j);
                    final double min2 = punctures.get(2 * j);
                    final double max2 = punctures.get(2 * j + 1);
                    if ((min2 > min1 && max1 > min2) || (min1 > min2 && max2 < min1)) {
                        punctures.set(2 * i, (min2 < min1) ? min2 : min1);
                        punctures.set(2 * i + 1, (max1 < max2) ? max2 : max1);
                        punctures.remove(2 * j);
                        punctures.remove(2 * j);
                        overlap = true;
                        break;
                    }
                }
                if (overlap) break;
            }
        }

        double expG = 0;
        double norm = 0;
        for (int i = 0; i < punctures.size() / 2; i++) {
            final double a = punctures.get(2 * i);
            final double b = punctures.get(2 * i + 1);
            final double alpha = (a / xsigma + xsigma / tau) / sqrtTwo;
            final double beta = (b / xsigma + xsigma / tau) / sqrtTwo;
            final double delta = 1 / sqrtTwo / xsigma;
            norm += 2 * tau * CMath.exp(1 / (4 * delta * delta * tau * tau))
                * (CMath.exp(-alpha / (delta * tau)) - CMath.exp(-beta / (delta * tau)));
            expG += ((ErrorFunctions.erfc(alpha - delta * x) - ErrorFunctions.erfc(beta - delta * x))
                * CMath.exp(-x / tau));
        }
        if (norm == 0) return norm;
        return expG / norm;
    }

    /** std::sort of two elements. */
    private static void sort2(List<Double> l, int at) {
        final double a = l.get(at);
        final double b = l.get(at + 1);
        if (b < a) {
            l.set(at, b);
            l.set(at + 1, a);
        }
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
