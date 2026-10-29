package com.sphere.core.clhep;

import com.sphere.core.hepmc3.cxx.NativeMath;

/**
 * The C library's mathematical functions as CLHEP calls them ({@code std::exp}, {@code std::atan2}...),
 * for a port that gives the C++'s bits: the functions of this machine's C library when Sphere may call
 * it (the very ones a CLHEP built here runs), Java's StrictMath otherwise ({@link NativeMath} chooses).
 * Square root, absolute value, floor and ldexp are exact in IEEE arithmetic: Java's own serve.
 */
public final class CMath {

    private CMath() {
    }

    public static double exp(double x) {
        return NativeMath.exp(x);
    }

    public static double log(double x) {
        return NativeMath.log(x);
    }

    public static double log10(double x) {
        return NativeMath.log10(x);
    }

    /** std::log of a float: the C library's logf. */
    public static float logf(float x) {
        return NativeMath.logf(x);
    }

    /** std::exp of a float: the C library's expf. */
    public static float expf(float x) {
        return NativeMath.expf(x);
    }

    public static float sinf(float x) {
        return NativeMath.sinf(x);
    }

    public static float cosf(float x) {
        return NativeMath.cosf(x);
    }

    public static float atan2f(float y, float x) {
        return NativeMath.atan2f(y, x);
    }

    /** std::sqrt of a float (sqrtf): correctly rounded, as the square root of its double is once rounded. */
    public static float sqrtf(float x) {
        return (float) Math.sqrt(x);
    }

    public static double sin(double x) {
        return NativeMath.sin(x);
    }

    public static double cos(double x) {
        return NativeMath.cos(x);
    }

    public static double tan(double x) {
        return NativeMath.tan(x);
    }

    public static double asin(double x) {
        return NativeMath.asin(x);
    }

    public static double acos(double x) {
        return NativeMath.acos(x);
    }

    public static double atan(double x) {
        return NativeMath.atan(x);
    }

    public static double atan2(double y, double x) {
        return NativeMath.atan2(y, x);
    }

    public static double pow(double x, double y) {
        return NativeMath.pow(x, y);
    }

    /** hypot: std::abs of a complex number, which cabs computes with it. */
    public static double hypot(double x, double y) {
        return NativeMath.hypot(x, y);
    }

    public static double sinh(double x) {
        return NativeMath.sinh(x);
    }

    public static double cosh(double x) {
        return NativeMath.cosh(x);
    }

    public static double tanh(double x) {
        return NativeMath.tanh(x);
    }

    public static double sqrt(double x) {
        return Math.sqrt(x);
    }

    public static double fabs(double x) {
        return Math.abs(x);
    }

    public static double abs(double x) {
        return Math.abs(x);
    }

    public static int abs(int x) {
        return Math.abs(x);
    }

    public static double floor(double x) {
        return Math.floor(x);
    }

    public static double ceil(double x) {
        return Math.ceil(x);
    }

    /** x times 2 to the n, exactly (rounded once where the result is subnormal), as ldexp. */
    public static double ldexp(double x, int n) {
        return Math.scalb(x, n);
    }

    /** fmod: the exact remainder of x / y with the sign of x, Java's {@code %} on doubles. */
    public static double fmod(double x, double y) {
        return x % y;
    }

    public static boolean isnan(double x) {
        return Double.isNaN(x);
    }

    /** std::min of doubles: the first when they compare equal (or one is NaN), as C++'s. */
    public static double min(double a, double b) {
        return b < a ? b : a;
    }

    /** std::max of doubles: the first when they compare equal (or one is NaN), as C++'s. */
    public static double max(double a, double b) {
        return a < b ? b : a;
    }

    /**
     * int(x) as x86-64 computes it (cvttsd2si): truncation toward zero, and INT_MIN, the "integer
     * indefinite", for NaN and for values out of the int range, where Java would saturate.
     */
    public static int toInt(double x) {
        return (x > -2147483649.0 && x < 2147483648.0) ? (int) x : Integer.MIN_VALUE;
    }
}
