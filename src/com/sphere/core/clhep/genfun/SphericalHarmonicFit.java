package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::SphericalHarmonicFit (SphericalHarmonicFit.hh, .icc): |Σ a_lm Y_lm(x, phi)|², the
 * coefficients a_lm built from fractions (of L or higher, of |M| or higher, of positive M) and phases so
 * that the density stays normalized on the sphere; a function of two variables. CLHEP's abs(l1-l2) of
 * unsigned values is read as the int |l1 - l2| it means.
 */
public class SphericalHarmonicFit extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private static final class MStruct {
        int M;
        Parameter fractionAbsMOrHigher;
        Parameter fractionMPositive;
        Parameter phaseMPlus;
        Parameter phaseMMinus;
    }

    private static final class LStruct {
        int L;
        Parameter fractionLOrHigher;
        Parameter phaseLM0;
        final List<MStruct> mstruct = new ArrayList<>();
    }

    private final List<LStruct> lstruct = new ArrayList<>();
    private final int LMAX;
    private final SphericalHarmonicCoefficientSet coefficientsA;
    private final SphericalHarmonicCoefficientSet coefficientsASq;
    private final ClebschGordanCoefficientSet ClebschGordan = new ClebschGordanCoefficientSet();

    public SphericalHarmonicFit(int LMAX) {
        this.LMAX = LMAX;
        coefficientsA = new SphericalHarmonicCoefficientSet(LMAX);
        coefficientsASq = new SphericalHarmonicCoefficientSet(2 * LMAX);
        for (int l = 1; l <= LMAX; l++) {
            final LStruct ls = new LStruct();
            ls.L = l;
            ls.fractionLOrHigher = new Parameter("Fraction L>=" + l, 0.5, 0, 1);
            ls.phaseLM0 = new Parameter("Phase L=" + l + "; M=0", M_PI, -2 * M_PI, 2 * M_PI);
            for (int m = 1; m <= l; m++) {
                final MStruct ms = new MStruct();
                ms.M = m;
                ms.fractionAbsMOrHigher = new Parameter("Fraction L= " + l + "; |M| >=" + m, 0.5, 0, 1);
                ms.fractionMPositive = new Parameter("Fraction L=" + l + "; M=+" + m, 0.5, 0, 1);
                ms.phaseMPlus = new Parameter("Phase L=" + l + "; M=+" + m, M_PI, -2 * M_PI, 2 * M_PI);
                ms.phaseMMinus = new Parameter("Phase L=" + l + "; M=-" + m, M_PI, -2 * M_PI, 2 * M_PI);
                ls.mstruct.add(ms);
            }
            lstruct.add(ls);
        }
    }

    public SphericalHarmonicFit(SphericalHarmonicFit right) {
        LMAX = right.LMAX;
        coefficientsA = new SphericalHarmonicCoefficientSet(LMAX);
        coefficientsASq = new SphericalHarmonicCoefficientSet(2 * LMAX);
        for (LStruct r : right.lstruct) {
            final LStruct ls = new LStruct();
            ls.L = r.L;
            ls.fractionLOrHigher = new Parameter(r.fractionLOrHigher);
            ls.phaseLM0 = new Parameter(r.phaseLM0);
            for (MStruct rm : r.mstruct) {
                final MStruct ms = new MStruct();
                ms.M = rm.M;
                ms.fractionAbsMOrHigher = new Parameter(rm.fractionAbsMOrHigher);
                ms.fractionMPositive = new Parameter(rm.fractionMPositive);
                ms.phaseMPlus = new Parameter(rm.phaseMPlus);
                ms.phaseMMinus = new Parameter(rm.phaseMMinus);
                ls.mstruct.add(ms);
            }
            lstruct.add(ls);
        }
    }

    @Override
    public SphericalHarmonicFit copy() {
        return new SphericalHarmonicFit(this);
    }

    @Override
    public int dimensionality() {
        return 2;
    }

    @Override
    public double call(double x) {
        throw new RuntimeException("Dimensionality error in SphericalHarmonicFit");
    }

    @Override
    public double call(Argument a) {
        final double x = a.get(0);
        final double phi = a.get(1);
        final double[][] Plm = new double[LMAX + 1][];
        for (int m = 0; m <= LMAX; m++) Plm[m] = Gsl.legendreSphPlmArray(LMAX, m, x);
        recomputeCoefficients();
        Complex P = new Complex(0.0);
        final Complex I = new Complex(0, 1.0);
        for (int l = 0; l <= LMAX; l++) {
            for (int m = 0; m <= l; m++) {
                final int LP = l - Math.abs(m);
                final double Pn = Plm[Math.abs(m)][LP];
                if (!Double.isFinite(Pn)) return 0.0;
                P = P.plus(coefficientsA.get(l, m).times(Pn).times(I.times(m * phi).exp()));
                if (m != 0) {
                    P = P.plus(coefficientsA.get(l, -m).times((m % 2) != 0 ? -1.0 : 1.0).times(Pn)
                        .times(I.negate().times(m * phi).exp()));
                }
            }
        }
        final double retVal = P.norm();
        if (!Double.isFinite(retVal)) return 0.0;
        return retVal;
    }

    public int lMax() {
        return LMAX;
    }

    public int numComponents() {
        return (LMAX + 1) * (LMAX + 1) - 1;
    }

    public Parameter getFractionLOrHigher(int L) {
        return lstruct.get(L - 1).fractionLOrHigher;
    }

    public Parameter getPhaseLM0(int L) {
        return lstruct.get(L - 1).phaseLM0;
    }

    public Parameter getFractionAbsMOrHigher(int L, int M) {
        return lstruct.get(L - 1).mstruct.get(M - 1).fractionAbsMOrHigher;
    }

    public Parameter getFractionMPositive(int L, int M) {
        return lstruct.get(L - 1).mstruct.get(M - 1).fractionMPositive;
    }

    public Parameter getPhaseMPlus(int L, int M) {
        return lstruct.get(L - 1).mstruct.get(M - 1).phaseMPlus;
    }

    public Parameter getPhaseMMinus(int L, int M) {
        return lstruct.get(L - 1).mstruct.get(M - 1).phaseMMinus;
    }

    public SphericalHarmonicCoefficientSet coefficientsA() {
        recomputeCoefficients();
        return coefficientsA;
    }

    /** The coefficients of the expansion of the density itself, up to 2 LMAX. */
    public SphericalHarmonicCoefficientSet coefficientsASq() {
        recomputeCoefficients();
        final int LMAX = coefficientsA.getLMax();
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
                                            .times(ClebschGordan.get(l1, l2, 0, 0, L, 0))
                                            .times(ClebschGordan.get(l1, l2, m1, -m2, L, M));
                                        coefficientsASq.set(L, M, coefficientsASq.get(L, M).plus(term));
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

    /** The coefficients a_lm from the parameters; a negative fraction is corrected to 0 (on cout, as CLHEP). */
    public void recomputeCoefficients() {
        final Complex I = new Complex(0, 1.0);
        double f = 1.0;
        for (int l = 0; l <= LMAX; l++) {
            final LStruct lStructThis = (l == 0 ? null : lstruct.get(l - 1));
            final LStruct lStructNext = (l == LMAX ? null : lstruct.get(l));
            final double fHigher = lStructNext != null ? lStructNext.fractionLOrHigher.getValue() : 0;
            double fThis = f * (1 - fHigher);
            double g = 1.0;
            for (int m = 0; m <= l; m++) {
                final MStruct mStructThis = ((m == 0 || lStructThis == null) ? null : lStructThis.mstruct.get(m - 1));
                final MStruct mStructNext = (m == l ? null : lStructThis.mstruct.get(m));
                final double gHigher = mStructNext != null ? mStructNext.fractionAbsMOrHigher.getValue() : 0;
                double gThis = g * (1 - gHigher);
                if (fThis < 0) {
                    StdStreams.cout().print(new COStream().put("L-fraction correction").put(fThis).put("-->0").str() + "\n");
                    fThis = 0.0;
                }
                if (gThis < 0) {
                    StdStreams.cout().print(new COStream().put("M-fraction correction").put(gThis).put("-->0").str() + "\n");
                    gThis = 0.0;
                }
                double px = 0.0;
                if (m == 0) {
                    if (lStructThis != null) {
                        final double amplitude = Math.sqrt(fThis * gThis);
                        px = lStructThis.phaseLM0.getValue();
                        coefficientsA.set(l, m, I.times(px).exp().times(amplitude));
                    } else {
                        final double amplitude = Math.sqrt(fThis * gThis);
                        coefficientsA.set(l, m, I.times(px).exp().times(amplitude));
                    }
                } else {
                    {
                        final double amplitude = Math.sqrt(fThis * gThis * mStructThis.fractionMPositive.getValue());
                        px = mStructThis.phaseMPlus.getValue();
                        coefficientsA.set(l, m, I.times(px).exp().times(amplitude));
                    }
                    {
                        final double amplitude = Math.sqrt(fThis * gThis * (1 - mStructThis.fractionMPositive.getValue()));
                        px = mStructThis.phaseMMinus.getValue();
                        coefficientsA.set(l, -m, I.times(px).exp().times(amplitude));
                    }
                }
                g *= gHigher;
            }
            f *= fHigher;
        }
    }
}
