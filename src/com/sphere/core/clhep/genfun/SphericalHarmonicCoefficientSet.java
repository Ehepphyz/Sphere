package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.Arrays;

/**
 * CLHEP's Genfun::SphericalHarmonicCoefficientSet (SphericalHarmonicCoefficientSet.hh, .icc): the complex
 * coefficients of a spherical-harmonic expansion, l = 0..LMAX and m = -l..l, with their dot product and the
 * coefficients of the expansion of |f|² (squareExpansionCoefficients).
 */
public class SphericalHarmonicCoefficientSet {

    private static final double M_PI = 3.14159265358979323846;

    private Complex[][] data;

    public SphericalHarmonicCoefficientSet(int LMAX) {
        data = new Complex[LMAX + 1][];
        for (int l = 0; l <= LMAX; l++) {
            data[l] = new Complex[2 * l + 1];
            Arrays.fill(data[l], Complex.ZERO);
        }
    }

    public SphericalHarmonicCoefficientSet(SphericalHarmonicCoefficientSet right) {
        data = copyOf(right.data);
    }

    private static Complex[][] copyOf(Complex[][] d) {
        final Complex[][] c = new Complex[d.length][];
        for (int l = 0; l < d.length; l++) c[l] = d[l].clone();
        return c;
    }

    public SphericalHarmonicCoefficientSet assign(SphericalHarmonicCoefficientSet source) {
        if (this != source) data = copyOf(source.data);
        return this;
    }

    public int getLMax() {
        return data.length - 1;
    }

    public Complex get(int l, int m) {
        return data[l][m + l];
    }

    public void set(int l, int m, Complex c) {
        data[l][m + l] = c;
    }

    /** operator*=. */
    public SphericalHarmonicCoefficientSet timesAssign(Complex s) {
        final int LMAX = getLMax();
        for (int l = 0; l <= LMAX; l++) {
            for (int m = -l; m <= l; m++) set(l, m, get(l, m).times(s));
        }
        return this;
    }

    /** operator+=. */
    public SphericalHarmonicCoefficientSet plusAssign(SphericalHarmonicCoefficientSet source) {
        final int LMAX = getLMax();
        for (int l = 0; l <= LMAX; l++) {
            for (int m = -l; m <= l; m++) set(l, m, get(l, m).plus(source.get(l, m)));
        }
        return this;
    }

    /** operator-=. */
    public SphericalHarmonicCoefficientSet minusAssign(SphericalHarmonicCoefficientSet source) {
        final int LMAX = getLMax();
        for (int l = 0; l <= LMAX; l++) {
            for (int m = -l; m <= l; m++) set(l, m, get(l, m).minus(source.get(l, m)));
        }
        return this;
    }

    /** operator&lt;&lt;. */
    public COStream print(COStream o) {
        for (int l = 0; l <= getLMax(); l++) {
            for (int m = -l; m <= l; m++) {
                o.put("l=").put(l).put(" m=");
                if (m == 0) o.put(" ");
                if (m > 0) o.put("+");
                o.put(m).put(" mag: ");
                get(l, m).print(o).put("\n");
            }
            o.put("\n");
        }
        return o;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }

    /** The sum of a(l,m) conj(b(l,m)). */
    public static Complex dot(SphericalHarmonicCoefficientSet a, SphericalHarmonicCoefficientSet b) {
        Complex result = new Complex(0.0);
        if (a.getLMax() != b.getLMax()) {
            throw new RuntimeException("function dot:  SphericalHarmonicCoefficientSets of different dimension");
        }
        for (int l = 0; l <= a.getLMax(); l++) {
            for (int m = -l; m <= l; m++) result = result.plus(a.get(l, m).times(b.get(l, m).conj()));
        }
        return result;
    }

    private static final ClebschGordanCoefficientSet clebschGordan = new ClebschGordanCoefficientSet();

    /** The coefficients, up to 2 LMAX, of the expansion of |f|² from those of f. */
    public static SphericalHarmonicCoefficientSet squareExpansionCoefficients(SphericalHarmonicCoefficientSet coefficientsA) {
        final int LMAX = coefficientsA.getLMax();
        final SphericalHarmonicCoefficientSet coefficientsASq = new SphericalHarmonicCoefficientSet(2 * LMAX);
        synchronized (clebschGordan) {
            for (int L = 0; L <= 2 * LMAX; L++) {
                for (int M = -L; M <= L; M++) {
                    coefficientsASq.set(L, M, new Complex(0.0));
                    for (int l1 = 0; l1 <= LMAX; l1++) {
                        for (int l2 = 0; l2 <= LMAX; l2++) {
                            for (int m1 = -l1; m1 <= l1; m1++) {
                                for (int m2 = -l2; m2 <= l2; m2++) {
                                    if (m1 - m2 == M) {
                                        if (((l1 + l2) >= L) && Math.abs(l1 - l2) <= L) {
                                            final Complex term = coefficientsA.get(l1, m1)
                                                .times(coefficientsA.get(l2, m2).conj())
                                                .times((m2 % 2) != 0 ? -1.0 : 1.0)
                                                .times(Math.sqrt((2 * l1 + 1) * (2 * l2 + 1) / (4 * M_PI * (2 * L + 1))))
                                                .times(clebschGordan.get(l1, l2, 0, 0, L, 0))
                                                .times(clebschGordan.get(l1, l2, m1, -m2, L, M));
                                            coefficientsASq.set(L, M, coefficientsASq.get(L, M).plus(term));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return coefficientsASq;
    }
}
