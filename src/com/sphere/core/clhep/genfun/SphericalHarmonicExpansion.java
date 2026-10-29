package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's Genfun::SphericalHarmonicExpansion (SphericalHarmonicExpansion.hh, .icc): Σ c_lm Y_lm(x, phi),
 * x = cos(theta), over a set of complex coefficients (a copy), as its real or imaginary part, modulus or
 * squared modulus; a function of two variables.
 */
public class SphericalHarmonicExpansion extends AbsFunction {

    public enum Type {
        REAL, IMAG, MAG, MAGSQ
    }

    private final Type type;
    private final SphericalHarmonicCoefficientSet coefficients;

    public SphericalHarmonicExpansion(Type type, SphericalHarmonicCoefficientSet coefficients) {
        this.type = type;
        this.coefficients = new SphericalHarmonicCoefficientSet(coefficients);
    }

    public SphericalHarmonicExpansion(SphericalHarmonicExpansion right) {
        type = right.type;
        coefficients = new SphericalHarmonicCoefficientSet(right.coefficients);
    }

    @Override
    public SphericalHarmonicExpansion copy() {
        return new SphericalHarmonicExpansion(this);
    }

    @Override
    public int dimensionality() {
        return 2;
    }

    @Override
    public double call(double x) {
        throw new RuntimeException("Dimensionality error in SphericalHarmonicExpansion");
    }

    @Override
    public double call(Argument a) {
        final int LMAX = coefficients.getLMax();
        final double x = a.get(0);
        final double phi = a.get(1);
        final double[][] Plm = new double[LMAX + 1][];
        for (int m = 0; m <= LMAX; m++) Plm[m] = Gsl.legendreSphPlmArray(LMAX, m, x);
        Complex P = new Complex(0.0);
        final Complex I = new Complex(0, 1.0);
        for (int l = 0; l <= LMAX; l++) {
            for (int m = 0; m <= l; m++) {
                final int LP = l - Math.abs(m);
                final double Pn = Plm[Math.abs(m)][LP];
                if (!Double.isFinite(Pn)) {
                    throw new RuntimeException(new COStream().put("Non-finite Pn(x=").put(x).put(")").str());
                }
                P = P.plus(coefficients.get(l, m).times(Pn).times(I.times(m * phi).exp()));
                if (m != 0) {
                    P = P.plus(coefficients.get(l, -m).times((m % 2) != 0 ? -1.0 : 1.0).times(Pn)
                        .times(I.negate().times(m * phi).exp()));
                }
            }
        }
        return switch (type) {
            case MAGSQ -> P.norm();
            case MAG -> P.abs();
            case REAL -> P.re();
            case IMAG -> P.im();
        };
    }

    public SphericalHarmonicCoefficientSet coefficientSet() {
        return coefficients;
    }
}
