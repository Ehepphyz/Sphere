package com.sphere.core.hepmc3.cxx;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The x87 long double of gcc on x86-64: a 64-bit significand, each operation
 * rounded to nearest, ties to even. GenEvent::boost and rotate compute in it,
 * so a port in double would not give the bits HepMC3 gives; this does.
 *
 * <p>A value is held exactly as m * 2^e with m of at most 64 bits; add,
 * subtract and multiply are formed exactly then rounded, divide and square
 * root carry a sticky bit so that they round as the hardware does. The
 * exponent range is Java's int, so nothing overflows or becomes subnormal
 * where the x87 would; event kinematics never come near either limit.
 */
public final class LongDouble implements Comparable<LongDouble> {

    public static final LongDouble ZERO = new LongDouble(BigInteger.ZERO, 0, false, false, false);

    private final BigInteger m;
    private final int e;
    private final boolean negativeZero;
    private final boolean nan;
    private final boolean infinite;

    private LongDouble(BigInteger m, int e, boolean negativeZero, boolean nan, boolean infinite) {
        this.m = m;
        this.e = e;
        this.negativeZero = negativeZero;
        this.nan = nan;
        this.infinite = infinite;
    }

    private static final LongDouble NAN = new LongDouble(BigInteger.ZERO, 0, false, true, false);

    public static LongDouble of(double d) {
        if (Double.isNaN(d)) return NAN;
        if (Double.isInfinite(d)) return new LongDouble(d > 0 ? BigInteger.ONE : BigInteger.ONE.negate(), 0, false, false, true);
        if (d == 0) return new LongDouble(BigInteger.ZERO, 0, 1 / d < 0, false, false);
        final long bits = Double.doubleToRawLongBits(d);
        final int biased = (int) ((bits >>> 52) & 0x7FF);
        long mant = bits & 0xFFFFFFFFFFFFFL;
        int exp;
        if (biased == 0) {
            exp = -1074;
        } else {
            mant |= 1L << 52;
            exp = biased - 1075;
        }
        BigInteger M = BigInteger.valueOf(mant);
        if (d < 0) M = M.negate();
        return new LongDouble(M, exp, false, false, false);
    }

    /** n * 2^k rounded to 64 bits, ties to even; sticky says the exact value has more below n. */
    public static LongDouble ofScaled(BigInteger n, int k, boolean sticky) {
        return round(n, k, sticky);
    }

    public static LongDouble of(long v) {
        return round(BigInteger.valueOf(v), 0, false);
    }

    public boolean isNaN() {
        return nan;
    }

    public boolean isZero() {
        return !nan && !infinite && m.signum() == 0;
    }

    public int signum() {
        return nan ? 0 : m.signum();
    }

    /* ---- rounding ------------------------------------------------------ */

    /** M * 2^E rounded to 64 significant bits, half to even; sticky says bits below M were not zero. */
    private static LongDouble round(BigInteger M, int E, boolean sticky) {
        if (M.signum() == 0) return new LongDouble(BigInteger.ZERO, 0, false, false, false);
        final boolean neg = M.signum() < 0;
        BigInteger a = M.abs();
        final int len = a.bitLength();
        if (len > 64) {
            final int drop = len - 64;
            final BigInteger kept = a.shiftRight(drop);
            final boolean roundBit = a.testBit(drop - 1);
            final boolean below = sticky || a.getLowestSetBit() < drop - 1;
            BigInteger r = kept;
            if (roundBit && (below || kept.testBit(0))) r = r.add(BigInteger.ONE);
            int ne = E + drop;
            if (r.bitLength() > 64) {
                r = r.shiftRight(1);
                ne++;
            }
            return new LongDouble(neg ? r.negate() : r, ne, false, false, false);
        }
        // exact already: a sticky bit below a value that fits cannot round it (it came from a longer form)
        return new LongDouble(M, E, false, false, false);
    }

    /* ---- arithmetic ---------------------------------------------------- */

    public LongDouble add(LongDouble o) {
        if (nan || o.nan) return NAN;
        if (isZero()) return o.isZero() ? (negativeZero && o.negativeZero ? this : ZERO) : o;
        if (o.isZero()) return this;
        final int me = Math.min(e, o.e);
        final BigInteger a = m.shiftLeft(e - me);
        final BigInteger b = o.m.shiftLeft(o.e - me);
        return round(a.add(b), me, false);
    }

    public LongDouble add(double d) {
        return add(of(d));
    }

    public LongDouble negate() {
        if (nan) return this;
        return new LongDouble(m.negate(), e, isZero() && !negativeZero, false, infinite);
    }

    public LongDouble subtract(LongDouble o) {
        return add(o.negate());
    }

    public LongDouble subtract(double d) {
        return subtract(of(d));
    }

    public LongDouble multiply(LongDouble o) {
        if (nan || o.nan) return NAN;
        return round(m.multiply(o.m), e + o.e, false);
    }

    public LongDouble multiply(double d) {
        return multiply(of(d));
    }

    public LongDouble divide(LongDouble o) {
        if (nan || o.nan || o.isZero()) return NAN;
        if (isZero()) return ZERO;
        // quotient with at least 66 significant bits, the rest as sticky
        final int shift = Math.max(0, 66 + o.m.abs().bitLength() - m.abs().bitLength());
        final BigInteger[] qr = m.shiftLeft(shift).divideAndRemainder(o.m);
        return round(qr[0], e - o.e - shift, qr[1].signum() != 0);
    }

    public LongDouble divide(double d) {
        return divide(of(d));
    }

    public LongDouble sqrt() {
        if (nan || m.signum() < 0) return NAN;
        if (isZero()) return this;
        BigInteger M = m;
        int E = e;
        // make the exponent even and the integer large enough for 66 bits of root
        int shift = Math.max(0, 140 - M.bitLength());
        if (((E - shift) & 1) != 0) shift++;
        M = M.shiftLeft(shift);
        E -= shift;
        final BigInteger r = M.sqrt();
        final boolean sticky = r.multiply(r).compareTo(M) != 0;
        return round(r, E / 2, sticky);
    }

    /* ---- conversions --------------------------------------------------- */

    /** The double nearest the value, ties to even, as a store to a double variable does. */
    public double toDouble() {
        if (nan) return Double.NaN;
        if (infinite) return m.signum() > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        if (m.signum() == 0) return negativeZero ? -0.0 : 0.0;
        final boolean neg = m.signum() < 0;
        BigInteger a = m.abs();
        int E = e;
        final int len = a.bitLength();
        if (len > 53) {
            final int drop = len - 53;
            BigInteger kept = a.shiftRight(drop);
            final boolean roundBit = a.testBit(drop - 1);
            final boolean below = a.getLowestSetBit() < drop - 1;
            if (roundBit && (below || kept.testBit(0))) kept = kept.add(BigInteger.ONE);
            a = kept;
            E += drop;
        }
        final double v = Math.scalb(a.doubleValue(), E);
        return neg ? -v : v;
    }

    public boolean isInfinite() {
        return infinite;
    }

    /**
     * The value as the x87 unit stores it in memory (fstpt): a 64-bit
     * significand with its integer bit, a 15-bit exponent biased by 16383,
     * the sign; ten bytes, little-endian.
     */
    public byte[] toX87() {
        final byte[] out = new byte[10];
        int top;
        long sig;
        if (nan) {
            top = 0x7FFF;
            sig = 0xC000000000000000L;
        } else if (infinite) {
            top = (m.signum() < 0 ? 0x8000 : 0) | 0x7FFF;
            sig = 0x8000000000000000L;
        } else if (m.signum() == 0) {
            top = negativeZero ? 0x8000 : 0;
            sig = 0;
        } else {
            final BigInteger a = m.abs();
            final int shift = 64 - a.bitLength();
            int biased = e - shift + 63 + 16383;
            BigInteger s = a.shiftLeft(shift);
            if (biased <= 0) {
                // subnormal for the x87: the significand loses its integer bit
                s = s.shiftRight(1 - biased);
                biased = 0;
            }
            sig = s.longValue();
            top = (m.signum() < 0 ? 0x8000 : 0) | biased;
        }
        for (int i = 0; i < 8; i++) out[i] = (byte) (sig >>> (8 * i));
        out[8] = (byte) top;
        out[9] = (byte) (top >>> 8);
        return out;
    }

    /** A value as the x87 unit stores it in memory (fldt), from ten bytes, little-endian. */
    public static LongDouble fromX87(byte[] b) {
        long sig = 0;
        for (int i = 7; i >= 0; i--) sig = (sig << 8) | (b[i] & 0xFF);
        final int top = (b[8] & 0xFF) | ((b[9] & 0xFF) << 8);
        final boolean negative = (top & 0x8000) != 0;
        final int biased = top & 0x7FFF;
        if (biased == 0x7FFF) {
            if ((sig << 1) == 0) {
                return new LongDouble(negative ? BigInteger.ONE.negate() : BigInteger.ONE, 0, false, false, true);
            }
            return NAN;
        }
        if (sig == 0) return new LongDouble(BigInteger.ZERO, 0, negative, false, false);
        BigInteger s = new BigInteger(Long.toUnsignedString(sig));
        if (negative) s = s.negate();
        final int exp = (biased == 0 ? 1 : biased) - 16383 - 63;
        return new LongDouble(s, exp, false, false, false);
    }

    /** The exact value. */
    public BigDecimal toBigDecimal() {
        if (nan || infinite) throw new ArithmeticException("not finite");
        final BigDecimal M = new BigDecimal(m);
        return e >= 0 ? M.multiply(new BigDecimal(BigInteger.TWO.pow(e)))
            : M.divide(new BigDecimal(BigInteger.TWO.pow(-e)));
    }

    /**
     * strtold(text, nullptr): the number the text starts with (blanks
     * skipped, the rest ignored), 0 when it starts with none.
     */
    public static LongDouble parsePrefix(String text) {
        final double asDouble = CStr.strtod(text, 0);
        final int end = CStr.end();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) return of(asDouble);
        String number = text.substring(0, end).trim();
        if (number.isEmpty()) return ZERO;
        if (number.startsWith("0x") || number.startsWith("0X") || number.startsWith("-0x") || number.startsWith("+0x")) {
            return of(asDouble);
        }
        if (number.startsWith("+")) number = number.substring(1);
        if (number.endsWith(".")) number = number + "0";
        if (number.startsWith(".")) number = "0" + number;
        if (number.startsWith("-.")) number = "-0" + number.substring(1);
        return parse(number);
    }

    /** strtold: the decimal text rounded to the nearest long double. */
    public static LongDouble parse(String text) {
        final String t = text.trim();
        if (t.isEmpty()) return ZERO;
        final BigDecimal v;
        try {
            v = new BigDecimal(t);
        } catch (NumberFormatException e) {
            return of(CStr.strtod(t, 0));
        }
        if (v.signum() == 0) return t.startsWith("-") ? of(-0.0) : ZERO;
        // v = N / D exactly; choose E so that v / 2^E has 66+ bits, then round with a sticky bit
        final BigInteger unscaled = v.unscaledValue();
        final int scale = v.scale();
        BigInteger num = unscaled.abs();
        BigInteger den = BigInteger.ONE;
        if (scale > 0) den = BigInteger.TEN.pow(scale);
        else num = num.multiply(BigInteger.TEN.pow(-scale));
        final int estimate = num.bitLength() - den.bitLength();
        final int E = estimate - 70;
        if (E >= 0) den = den.shiftLeft(E);
        else num = num.shiftLeft(-E);
        final BigInteger[] qr = num.divideAndRemainder(den);
        final BigInteger q = v.signum() < 0 ? qr[0].negate() : qr[0];
        return round(q, E, qr[1].signum() != 0);
    }

    /**
     * printf's %.{p}Le, %Lf or %Lg of this value ('e', 'f' or 'g'), from its
     * exact value, half to even.
     */
    public String format(char conv, int precision, boolean alt, boolean upper) {
        if (nan) return upper ? "NAN" : "nan";
        if (infinite) return (m.signum() < 0 ? "-" : "") + (upper ? "INF" : "inf");
        final boolean neg = m.signum() < 0 || (m.signum() == 0 && negativeZero);
        final BigDecimal x = toBigDecimal().abs();
        final int p = precision < 0 ? 6 : precision;
        String body;
        switch (conv) {
            case 'f' -> {
                body = x.setScale(p, RoundingMode.HALF_EVEN).toPlainString();
                if (alt && p == 0) body += ".";
            }
            case 'e' -> body = exp(x, p, alt);
            default -> {
                final int P = p == 0 ? 1 : p;
                if (x.signum() == 0) {
                    body = alt ? (P > 1 ? "0." + "0".repeat(P - 1) : "0.") : "0";
                } else {
                    final BigDecimal r = x.round(new MathContext(P, RoundingMode.HALF_EVEN));
                    final int X = r.precision() - r.scale() - 1;
                    if (X < -4 || X >= P) {
                        body = exp(x, P - 1, alt);
                        if (!alt) {
                            final int ep = body.indexOf('e');
                            body = strip(body.substring(0, ep)) + body.substring(ep);
                        }
                    } else {
                        body = x.setScale(P - 1 - X, RoundingMode.HALF_EVEN).toPlainString();
                        if (!alt) body = strip(body);
                    }
                }
            }
        }
        if (upper) body = body.toUpperCase(java.util.Locale.ROOT);
        return (neg ? "-" : "") + body;
    }

    private static String exp(BigDecimal x, int p, boolean alt) {
        if (x.signum() == 0) return "0" + (p > 0 || alt ? "." : "") + "0".repeat(p) + "e+00";
        final BigDecimal r = x.round(new MathContext(p + 1, RoundingMode.HALF_EVEN));
        String digits = r.unscaledValue().toString();
        final int exponent = digits.length() - 1 - r.scale();
        if (digits.length() < p + 1) digits = digits + "0".repeat(p + 1 - digits.length());
        final StringBuilder b = new StringBuilder();
        b.append(digits.charAt(0));
        if (p > 0 || alt) b.append('.');
        b.append(digits, 1, p + 1);
        b.append('e').append(exponent < 0 ? '-' : '+');
        final int ae = Math.abs(exponent);
        if (ae < 10) b.append('0');
        b.append(ae);
        return b.toString();
    }

    private static String strip(String s) {
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    @Override
    public int compareTo(LongDouble o) {
        return toBigDecimal().compareTo(o.toBigDecimal());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LongDouble l && !nan && !l.nan && compareTo(l) == 0;
    }

    @Override
    public int hashCode() {
        return nan ? 0 : toBigDecimal().stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return format('g', 18, false, false);
    }
}
