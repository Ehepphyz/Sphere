package com.sphere.components.spdf.cos;

/** An integer or a real; PDF lets either stand where a number is expected. */
public final class PdfNumber implements PdfObject {

    private final double value;
    private final boolean integer;

    public PdfNumber(long v) {
        value = v;
        integer = true;
    }

    public PdfNumber(double v) {
        value = v;
        integer = false;
    }

    /** A number written the shortest way: an integer when the value is whole. */
    public static PdfNumber of(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return new PdfNumber((long) v);
        }
        return new PdfNumber(v);
    }

    public boolean isInteger() {
        return integer;
    }

    public int intValue() {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    public long longValue() {
        return (long) value;
    }

    public float floatValue() {
        return (float) value;
    }

    public double doubleValue() {
        return value;
    }

    @Override
    public PdfObject copy() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PdfNumber n && n.value == value;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }

    @Override
    public String toString() {
        return integer ? Long.toString((long) value) : format(value);
    }

    /** A real as PDF writes it: no exponent, at most six decimals, no trailing zeros. */
    public static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.6f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        s = s.substring(0, end);
        if (s.equals("-0") || s.isEmpty()) {
            return "0";
        }
        return s;
    }
}
