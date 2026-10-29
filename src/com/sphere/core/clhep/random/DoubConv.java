package com.sphere.core.clhep.random;

import java.util.Locale;

/**
 * CLHEP's DoubConv (Random/DoubConv.h, DoubConv.cc): a double as two 32-bit words, its most significant
 * half first, so that engine states saved as words read back to the same bits on any machine.
 */
public final class DoubConv {

    private DoubConv() {
    }

    /** dto2longs: {high 32 bits, low 32 bits}. */
    public static long[] dto2longs(double d) {
        final long bits = Double.doubleToRawLongBits(d);
        return new long[] {(bits >>> 32) & 0xffffffffL, bits & 0xffffffffL};
    }

    public static double longs2double(long[] v) {
        return longs2double(v[0], v[1]);
    }

    public static double longs2double(long hi, long lo) {
        return Double.longBitsToDouble(((hi & 0xffffffffL) << 32) | (lo & 0xffffffffL));
    }

    /** d2x: the 16 hexadecimal digits of the double's bits, most significant first. */
    public static String d2x(double d) {
        return String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(d));
    }
}
