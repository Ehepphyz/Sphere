package com.sphere.core.minuit2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * A value with its errors written as the Particle Data Group rounds them
 * (Review of Particle Physics, "Rounding"): the three highest digits of the
 * error decide, 100 to 354 two significant digits, 355 to 949 one, 950 to 999
 * rounded up to 1000 and two digits; the value is given to the same decimal
 * place. With several errors the smallest one decides (the LHCb rule, which
 * iminuit follows too). Very large or small numbers share a power of ten,
 * a multiple of 3: (2.30 +- 0.35)e-9. Written in ASCII, so that every engine and console reads it.
 */
public final class PdgFormat {

    private PdgFormat() {
    }

    /** value +- error (+- error2 ...). */
    public static String format(double value, double... errors) {
        return render(value, errors, null);
    }

    /** value -lower +upper, lower given as a negative or positive number (Minos). */
    public static String asymmetric(double value, double lower, double upper) {
        return render(value, new double[] {Math.abs(lower), Math.abs(upper)}, new boolean[] {true});
    }

    /** The decimal place of the last digit kept: 10^place. */
    static int place(double leadError) {
        int n = (int) Math.floor(Math.log10(leadError));
        long three = Math.round(leadError / Math.pow(10, n - 2));
        if (three >= 1000) {
            n++;
            three = 100;
        } else if (three < 100) {
            n--;
            three = Math.round(leadError / Math.pow(10, n - 2));
        }
        return three <= 354 ? n - 1 : n;
    }

    private static String render(double value, double[] errors, boolean[] asym) {
        double lead = Double.POSITIVE_INFINITY;
        for (double e : errors) if (e > 0 && Double.isFinite(e) && e < lead) lead = e;
        if (!Double.isFinite(value) || Double.isInfinite(lead)) {
            final StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%.6g", value));
            for (int i = 0; i < errors.length; i++) s.append(sep(asym, i)).append(String.format(Locale.ROOT, "%.3g", errors[i]));
            return s.toString();
        }
        final int d = place(lead);
        final double big = Math.max(Math.abs(value), lead);
        final int m = (int) Math.floor(Math.log10(big));
        final int exp = m > 4 || m < -3 || d > 3 ? 3 * Math.floorDiv(m, 3) : 0;
        final StringBuilder s = new StringBuilder(digits(value, d, exp));
        for (int i = 0; i < errors.length; i++) s.append(sep(asym, i)).append(digits(errors[i], d, exp));
        String out = s.toString();
        if (exp != 0) out = "(" + out + ")e" + exp;
        return out;
    }

    private static String sep(boolean[] asym, int i) {
        if (asym != null && i == 0) return " -";
        if (asym != null && i == 1) return " +";
        return " +- ";
    }

    /** x rounded at 10^d, written in units of 10^exp. */
    private static String digits(double x, int d, int exp) {
        if (!Double.isFinite(x)) return String.valueOf(x);
        BigDecimal b = new BigDecimal(x).setScale(-d, RoundingMode.HALF_EVEN).movePointLeft(exp);
        if (b.scale() < 0) b = b.setScale(0, RoundingMode.UNNECESSARY);
        final String t = b.toPlainString();
        return t.matches("-0(\\.0*)?") ? t.substring(1) : t;
    }
}
