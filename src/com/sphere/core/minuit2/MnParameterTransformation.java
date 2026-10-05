package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.LongDouble;
import com.sphere.core.hepmc3.cxx.NativeMath;

/**
 * The transformations between a limited external parameter and the
 * unlimited internal one: arcsin for two limits, sqrt(1 + x^2) for one.
 *
 * <p>Minuit2 computes them in long double, and that is what is computed
 * here: the x87 80-bit format of GCC and Clang on x86-64 (Linux, WSL,
 * macOS Intel, MinGW), each operation rounded to 64 significant bits by
 * {@link LongDouble}, the functions sinl, cosl, asinl and powl being the C
 * library's through {@link NativeMath}; or double where long double is double
 * (MSVC, Apple Silicon). The values handed back are rounded to double, as the
 * C++ returns them to MnUserTransformation.
 */
public final class MnParameterTransformation {

    /** The C++ long double being reproduced. */
    public enum LongDoubleKind {
        /** x87 extended precision, 64-bit significand: GCC and Clang on x86-64. */
        X87,
        /** long double is double: MSVC, Apple Silicon. */
        DOUBLE
    }

    private static volatile LongDoubleKind kind = NativeMath.longDoubleIsDouble() ? LongDoubleKind.DOUBLE : LongDoubleKind.X87;

    private MnParameterTransformation() {
    }

    public static LongDoubleKind longDouble() {
        return kind;
    }

    /** Chooses the long double of the C++ build to reproduce (for a comparison with an MSVC build of ROOT, DOUBLE). */
    public static void setLongDouble(LongDoubleKind k) {
        kind = k;
    }

    private static boolean dbl() {
        return kind == LongDoubleKind.DOUBLE;
    }

    private static LongDouble ld(double d) {
        return LongDouble.of(d);
    }

    /** 2 * atan(1.), the double GCC folds it to. */
    private static final double PIBY2 = 2. * 0.7853981633974483;

    /* ---- two limits: sin -------------------------------------------------------- */

    /** Lower + 0.5 (Upper - Lower)(sin(Value) + 1). */
    public static double sinInt2ext(double value, double upper, double lower) {
        if (dbl()) return lower + 0.5 * (upper - lower) * (NativeMath.sin(value) + 1.);
        final LongDouble u = ld(upper), l = ld(lower);
        return l.add(u.subtract(l).multiply(0.5).multiply(NativeMath.sinl(ld(value)).add(1.))).toDouble();
    }

    /** asin(2 (Value - Lower)/(Upper - Lower) - 1), kept a little inside +-pi/2 at the limits. */
    public static double sinExt2int(double value, double upper, double lower, MnMachinePrecision prec) {
        final double distnn = 8. * Math.sqrt(prec.eps2());
        if (dbl()) {
            final double vlimhi = PIBY2 - distnn;
            final double vlimlo = -PIBY2 + distnn;
            final double yy = 2. * (value - lower) / (upper - lower) - 1.;
            final double yy2 = yy * yy;
            if (yy2 > (1. - prec.eps2())) return yy < 0. ? vlimlo : vlimhi;
            return NativeMath.asin(yy);
        }
        final LongDouble piby2 = ld(PIBY2);
        final LongDouble vlimhi = piby2.subtract(ld(distnn));
        final LongDouble vlimlo = piby2.negate().add(ld(distnn));
        final LongDouble yy = ld(value).subtract(ld(lower)).multiply(2.).divide(ld(upper).subtract(ld(lower))).subtract(1.);
        final LongDouble yy2 = yy.multiply(yy);
        if (yy2.compareTo(ld(1. - prec.eps2())) > 0) {
            return (yy.signum() < 0 ? vlimlo : vlimhi).toDouble();
        }
        return NativeMath.asinl(yy).toDouble();
    }

    /** d ext / d int = 0.5 (Upper - Lower) cos(Value). */
    public static double sinDInt2Ext(double value, double upper, double lower) {
        if (dbl()) return 0.5 * ((upper - lower) * NativeMath.cos(value));
        return ld(upper).subtract(ld(lower)).multiply(NativeMath.cosl(ld(value))).multiply(0.5).toDouble();
    }

    /** d2 ext / d int2 = -0.5 (Upper - Lower) sin(Value). */
    public static double sinD2Int2Ext(double value, double upper, double lower) {
        if (dbl()) return -0.5 * ((upper - lower) * NativeMath.sin(value));
        return ld(upper).subtract(ld(lower)).multiply(NativeMath.sinl(ld(value))).multiply(-0.5).toDouble();
    }

    /** d int / d ext = 1 / sqrt((Value - Lower)(Upper - Value)). */
    public static double sinDExt2Int(double value, double upper, double lower) {
        if (dbl()) return 1. / Math.sqrt((value - lower) * (upper - value));
        final LongDouble v = ld(value);
        return LongDouble.of(1.).divide(v.subtract(ld(lower)).multiply(ld(upper).subtract(v)).sqrt()).toDouble();
    }

    /* ---- lower limit: sqrt(1 + x^2) ---------------------------------------------- */

    /** lower - 1 + sqrt(value^2 + 1). */
    public static double lowInt2ext(double value, double lower) {
        if (dbl()) return lower - 1. + Math.sqrt(value * value + 1.);
        final LongDouble v = ld(value);
        return ld(lower).subtract(1.).add(v.multiply(v).add(1.).sqrt()).toDouble();
    }

    public static double lowExt2int(double value, double lower) {
        if (dbl()) {
            final double yy = value - lower + 1.;
            final double yy2 = yy * yy;
            return yy2 < 1. ? 0 : Math.sqrt(yy2 - 1);
        }
        final LongDouble yy = ld(value).subtract(ld(lower)).add(1.);
        final LongDouble yy2 = yy.multiply(yy);
        if (yy2.compareTo(ld(1.)) < 0) return 0;
        return yy2.subtract(1.).sqrt().toDouble();
    }

    public static double lowDInt2Ext(double value) {
        if (dbl()) return value / (Math.sqrt(value * value + 1.));
        final LongDouble v = ld(value);
        return v.divide(v.multiply(v).add(1.).sqrt()).toDouble();
    }

    public static double lowD2Int2Ext(double value) {
        if (dbl()) return NativeMath.pow(value * value + 1., -1.5);
        final LongDouble v = ld(value);
        return NativeMath.powl(v.multiply(v).add(1.), ld(-1.5)).toDouble();
    }

    public static double lowDExt2Int(double value, double lower) {
        if (dbl()) return (value - lower + 1) / (Math.sqrt((value - lower + 1) * (value - lower + 1) - 1.));
        final LongDouble a = ld(value).subtract(ld(lower)).add(1.);
        return a.divide(a.multiply(a).subtract(1.).sqrt()).toDouble();
    }

    /* ---- upper limit ----------------------------------------------------------- */

    /** upper + 1 - sqrt(value^2 + 1). */
    public static double upInt2ext(double value, double upper) {
        if (dbl()) return upper + 1. - Math.sqrt(value * value + 1.);
        final LongDouble v = ld(value);
        return ld(upper).add(1.).subtract(v.multiply(v).add(1.).sqrt()).toDouble();
    }

    public static double upExt2int(double value, double upper) {
        if (dbl()) {
            final double yy = upper - value + 1.;
            final double yy2 = yy * yy;
            return yy2 < 1. ? 0 : Math.sqrt(yy2 - 1);
        }
        final LongDouble yy = ld(upper).subtract(ld(value)).add(1.);
        final LongDouble yy2 = yy.multiply(yy);
        if (yy2.compareTo(ld(1.)) < 0) return 0;
        return yy2.subtract(1.).sqrt().toDouble();
    }

    public static double upDInt2Ext(double value) {
        if (dbl()) return -value / (Math.sqrt(value * value + 1.));
        final LongDouble v = ld(value);
        return v.negate().divide(v.multiply(v).add(1.).sqrt()).toDouble();
    }

    public static double upD2Int2Ext(double value) {
        if (dbl()) return -NativeMath.pow(value * value + 1., -1.5);
        final LongDouble v = ld(value);
        return NativeMath.powl(v.multiply(v).add(1.), ld(-1.5)).negate().toDouble();
    }

    public static double upDExt2Int(double value, double upper) {
        if (dbl()) return -(upper - value + 1) / (Math.sqrt((upper - value + 1) * (upper - value + 1) - 1.));
        final LongDouble a = ld(upper).subtract(ld(value)).add(1.);
        return a.negate().divide(a.multiply(a).subtract(1.).sqrt()).toDouble();
    }
}
