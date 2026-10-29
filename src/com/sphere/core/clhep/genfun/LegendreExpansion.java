package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::LegendreExpansion (LegendreExpansion.hh, .icc): Σ c_l P_l(x) over a set of complex
 * coefficients (a copy), as its real or imaginary part, modulus or squared modulus.
 */
public class LegendreExpansion extends AbsFunction {

    public enum Type {
        REAL, IMAG, MAG, MAGSQ
    }

    private final Type type;
    private final LegendreCoefficientSet coefficients;

    public LegendreExpansion(Type type, LegendreCoefficientSet coefficients) {
        this.type = type;
        this.coefficients = new LegendreCoefficientSet(coefficients);
    }

    public LegendreExpansion(LegendreExpansion right) {
        type = right.type;
        coefficients = new LegendreCoefficientSet(right.coefficients);
    }

    @Override
    public LegendreExpansion copy() {
        return new LegendreExpansion(this);
    }

    @Override
    public double call(double x) {
        final int N = coefficients.getLMax();
        final double[] Pk = Gsl.legendrePlArray(N, x);
        int n = N;
        Complex P = new Complex(0.0);
        while (true) {
            if (n == 0) {
                P = P.plus(coefficients.get(n).times(Pk[n]));
                break;
            } else {
                P = P.plus(coefficients.get(n).times(Pk[n]));
                n--;
            }
        }
        return switch (type) {
            case MAGSQ -> P.norm();
            case MAG -> P.abs();
            case REAL -> P.re();
            case IMAG -> P.im();
        };
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public LegendreCoefficientSet coefficientSet() {
        return coefficients;
    }
}
