package com.sphere.core.mathcore;

import com.sphere.core.hepmc3.cxx.NativeMath;

/**
 * ROOT's TMath (core/base TMathBase.h, math/mathcore TMath.h): the functions
 * the Java translations of ROOT call, with ROOT's definitions.
 *
 * <p>Max and Min are ROOT's {@code a >= b ? a : b} and {@code a <= b ? a : b}
 * (the first argument on a tie, the second when either is NaN), which is
 * neither Java's Math.max nor std::max. The transcendental functions are the
 * C library's of the machine, through {@link NativeMath}, as TMath::Sin is
 * sin(x) compiled against it; Sqrt and Abs are exact everywhere.
 */
public final class TMath {

    private TMath() {
    }

    public static double max(double a, double b) {
        return a >= b ? a : b;
    }

    public static double min(double a, double b) {
        return a <= b ? a : b;
    }

    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    public static double sqrt(double x) {
        return Math.sqrt(x);
    }

    public static double sin(double x) {
        return NativeMath.sin(x);
    }

    public static double cos(double x) {
        return NativeMath.cos(x);
    }

    /** TMath::ASin: -Pi/2 below -1, Pi/2 above 1 (where asin gives NaN), asin(x) otherwise. */
    public static double asin(double x) {
        if (x < -1.) return -Math.PI / 2;
        if (x > 1.) return Math.PI / 2;
        return NativeMath.asin(x);
    }

    public static double atan(double x) {
        return NativeMath.atan(x);
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

    /** TMath::Power(Double_t, Double_t) and TMath::Power(Double_t, Int_t): pow(x, y). */
    public static double power(double x, double y) {
        return NativeMath.pow(x, y);
    }
}
