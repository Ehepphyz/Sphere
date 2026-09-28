package com.sphere.core.fastjet;

import java.util.Locale;

/**
 * Numbers written the way a C++ ostream writes them by default, so that the
 * descriptions read exactly as FastJet's: six significant digits, trailing
 * zeros dropped, scientific notation outside 1e-4 .. 1e6.
 */
public final class Fmt {

    private Fmt() {
    }

    /** std::ostream << x with the default precision of 6. */
    public static String g(double x) {
        return g(x, 6);
    }

    /**
     * C's %.{precision}g, which is also what an ostream prints: the exact
     * binary value rounded half-even to that many significant digits (Java's
     * Formatter pads with zeros past the ~17 digits of the shortest decimal).
     */
    public static String g(double x, int precision) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        if (x == 0.0) return (1.0 / x < 0) ? "-0" : "0";
        final int p = precision == 0 ? 1 : precision;
        final java.math.BigDecimal bd = new java.math.BigDecimal(x)
            .round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN));
        final int exponent = bd.precision() - bd.scale() - 1;
        if (exponent < -4 || exponent >= p) {
            final String digits = bd.unscaledValue().abs().toString();
            String mantissa = digits.substring(0, 1) + (digits.length() > 1 ? "." + digits.substring(1) : "");
            mantissa = stripZeros(mantissa);
            final String sign = exponent < 0 ? "-" : "+";
            final int abs = Math.abs(exponent);
            return (x < 0 ? "-" : "") + mantissa + "e" + sign + (abs < 10 ? "0" + abs : String.valueOf(abs));
        }
        final String fixed = bd.setScale(Math.max(0, p - 1 - exponent), java.math.RoundingMode.HALF_EVEN).toPlainString();
        return stripZeros(fixed);
    }

    /**
     * C's %{width}.{precision}f, rounding the exact binary value half-even
     * as glibc does (Java's Formatter can round a decimal already rounded).
     */
    public static String f(double x, int width, int precision) {
        String s;
        if (Double.isNaN(x)) {
            s = "nan";
        } else if (Double.isInfinite(x)) {
            s = x > 0 ? "inf" : "-inf";
        } else {
            s = new java.math.BigDecimal(x).setScale(precision, java.math.RoundingMode.HALF_EVEN).toPlainString();
            if (x < 0 || (x == 0.0 && 1.0 / x < 0)) {
                if (!s.startsWith("-")) {
                    s = "-" + s;
                }
            }
        }
        if (s.length() >= width) {
            return s;
        }
        return " ".repeat(width - s.length()) + s;
    }

    /** C's %{width}.{precision}e, from the exact binary value. */
    public static String e(double x, int width, int precision) {
        String s;
        if (Double.isNaN(x)) {
            s = "nan";
        } else if (Double.isInfinite(x)) {
            s = x > 0 ? "inf" : "-inf";
        } else if (x == 0.0) {
            s = (1.0 / x < 0 ? "-" : "") + "0" + (precision > 0 ? "." + "0".repeat(precision) : "") + "e+00";
        } else {
            final java.math.BigDecimal r = new java.math.BigDecimal(Math.abs(x))
                .round(new java.math.MathContext(precision + 1, java.math.RoundingMode.HALF_EVEN));
            final String digits = r.unscaledValue().toString();
            final String padded = digits.length() < precision + 1
                ? digits + "0".repeat(precision + 1 - digits.length()) : digits.substring(0, precision + 1);
            final int exponent = digits.length() - 1 - r.scale();
            final StringBuilder b = new StringBuilder();
            if (x < 0) b.append('-');
            b.append(padded.charAt(0));
            if (precision > 0) b.append('.').append(padded, 1, precision + 1);
            b.append('e').append(exponent < 0 ? '-' : '+');
            final int ae = Math.abs(exponent);
            b.append(ae < 10 ? "0" + ae : String.valueOf(ae));
            s = b.toString();
        }
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }

    /** A pair written with the given number of decimals, all 106 bits being available. */
    public static String f(DD x, int width, int precision) {
        final String s = x.toFixed(precision);
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }

    private static String stripZeros(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
