package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.NativeMath;

/**
 * The few C++ library functions Minuit2 leans on, with their C++ meaning:
 * std::max and std::min as the standard writes them (the first argument when
 * neither is smaller, which is not Java's Math.max for NaN and signed zeros),
 * and the C library's exp, log, log10, pow and atan of the machine, through
 * {@link NativeMath}, so that a function written in Java gives the values the
 * same function compiled with the C++ gives.
 */
public final class Cxx {

    private Cxx() {
    }

    /** std::max(a, b): (a &lt; b) ? b : a. */
    public static double max(double a, double b) {
        return (a < b) ? b : a;
    }

    /** std::min(a, b): (b &lt; a) ? b : a. */
    public static double min(double a, double b) {
        return (b < a) ? b : a;
    }

    public static int max(int a, int b) {
        return (a < b) ? b : a;
    }

    public static int min(int a, int b) {
        return (b < a) ? b : a;
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

    public static double pow(double x, double y) {
        return NativeMath.pow(x, y);
    }

    public static double sin(double x) {
        return NativeMath.sin(x);
    }

    public static double cos(double x) {
        return NativeMath.cos(x);
    }

    public static double atan(double x) {
        return NativeMath.atan(x);
    }

    /** ROOT::Math::Util::EvalLog: the logarithm, continued linearly below twice the smallest normal double. */
    public static double evalLog(double x) {
        final double epsilon = 2.0 * Double.MIN_NORMAL;
        return x <= epsilon ? x / epsilon + log(epsilon) - 1.0 : log(x);
    }
}
