package com.sphere.core.hepmc3.cxx;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * C's printf, as the C++ library HepMC3 is compiled against writes numbers:
 * every conversion of a double rounds its exact binary value half to even,
 * which is what glibc and mingw-w64 do and what Java's Formatter does not
 * (it rounds a decimal already shortened, and pads %.22e with zeros where C
 * prints the true digits).
 *
 * <p>The files HepMC3 writes are made of %.16e numbers, so that one is fast:
 * the value times a power of ten is formed exactly in 128-bit integers and
 * rounded from the bits thrown away. Anything outside that range goes
 * through BigDecimal, which is exact too, only slower.
 *
 * <p>Conversions: d i u (with h l ll z j t q L modifiers, read and ignored),
 * x X o, c, s, e E f F g G, a A (via Java's hex form), %; flags - + space 0 #;
 * width and precision, either as '*'.
 */
public final class CFormat {

    private CFormat() {
    }

    /* ------------------------------------------------------------------ */
    /* printf                                                              */
    /* ------------------------------------------------------------------ */

    /** sprintf(format, args...): the text C would write. */
    public static String sprintf(String fmt, Object... args) {
        final StringBuilder out = new StringBuilder(fmt.length() + 16 * args.length);
        format(out, fmt, args);
        return out.toString();
    }

    /** Appends what printf(format, args...) writes. */
    public static void format(StringBuilder out, String fmt, Object... args) {
        int a = 0;
        int i = 0;
        final int n = fmt.length();
        while (i < n) {
            final char c = fmt.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i < n && fmt.charAt(i) == '%') {
                out.append('%');
                i++;
                continue;
            }
            boolean minus = false;
            boolean plus = false;
            boolean space = false;
            boolean zero = false;
            boolean alt = false;
            while (i < n) {
                final char f = fmt.charAt(i);
                if (f == '-') minus = true;
                else if (f == '+') plus = true;
                else if (f == ' ') space = true;
                else if (f == '0') zero = true;
                else if (f == '#') alt = true;
                else break;
                i++;
            }
            int width = 0;
            if (i < n && fmt.charAt(i) == '*') {
                width = ((Number) args[a++]).intValue();
                if (width < 0) {
                    minus = true;
                    width = -width;
                }
                i++;
            } else {
                while (i < n && Character.isDigit(fmt.charAt(i))) width = 10 * width + (fmt.charAt(i++) - '0');
            }
            int precision = -1;
            if (i < n && fmt.charAt(i) == '.') {
                i++;
                precision = 0;
                if (i < n && fmt.charAt(i) == '*') {
                    precision = ((Number) args[a++]).intValue();
                    if (precision < 0) precision = -1;
                    i++;
                } else {
                    while (i < n && Character.isDigit(fmt.charAt(i))) precision = 10 * precision + (fmt.charAt(i++) - '0');
                }
            }
            int lengthMod = 0;
            while (i < n && "hlLqjzt".indexOf(fmt.charAt(i)) >= 0) {
                if (fmt.charAt(i) == 'h') lengthMod--;
                else lengthMod++;
                i++;
            }
            if (i >= n) break;
            final char conv = fmt.charAt(i++);
            final Object arg = conv == 'n' ? null : args[a++];
            convert(out, conv, arg, minus, plus, space, zero, alt, width, precision, lengthMod);
        }
    }

    private static void convert(StringBuilder out, char conv, Object arg, boolean minus, boolean plus,
                                boolean space, boolean zero, boolean alt, int width, int precision, int lengthMod) {
        String body;
        String prefix = "";
        boolean numeric = true;
        switch (conv) {
            case 'd', 'i' -> {
                long v = integer(arg);
                if (lengthMod < 0) v = lengthMod == -1 ? (short) v : (byte) v;
                else if (lengthMod == 0 && !(arg instanceof Long)) v = (int) v;
                String digits = v == Long.MIN_VALUE ? "9223372036854775808" : Long.toString(Math.abs(v));
                if (precision == 0 && v == 0) digits = "";
                if (precision > digits.length()) digits = "0".repeat(precision - digits.length()) + digits;
                prefix = v < 0 ? "-" : plus ? "+" : space ? " " : "";
                body = digits;
                if (precision >= 0) zero = false;
            }
            case 'u', 'x', 'X', 'o' -> {
                long v = integer(arg);
                if (lengthMod < 0) v &= lengthMod == -1 ? 0xFFFFL : 0xFFL;
                else if (lengthMod == 0 && !(arg instanceof Long)) v &= 0xFFFFFFFFL;
                String digits = switch (conv) {
                    case 'u' -> Long.toUnsignedString(v);
                    case 'x' -> Long.toHexString(v);
                    case 'X' -> Long.toHexString(v).toUpperCase(java.util.Locale.ROOT);
                    default -> Long.toOctalString(v);
                };
                if (precision == 0 && v == 0) digits = "";
                if (precision > digits.length()) digits = "0".repeat(precision - digits.length()) + digits;
                if (alt && conv == 'o' && !digits.startsWith("0")) digits = "0" + digits;
                if (alt && v != 0 && (conv == 'x' || conv == 'X')) prefix = conv == 'x' ? "0x" : "0X";
                body = digits;
                if (precision >= 0) zero = false;
            }
            case 'e', 'E', 'f', 'F', 'g', 'G', 'a', 'A' -> {
                final double v = ((Number) arg).doubleValue();
                final boolean negative = v < 0 || (v == 0 && 1.0 / v < 0) || (Double.isNaN(v) && Double.doubleToRawLongBits(v) < 0);
                prefix = negative ? "-" : plus ? "+" : space ? " " : "";
                final boolean upper = Character.isUpperCase(conv);
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    body = Double.isNaN(v) ? "nan" : "inf";
                    if (upper) body = body.toUpperCase(java.util.Locale.ROOT);
                    zero = false;
                } else {
                    final double x = Math.abs(v);
                    final int p = precision < 0 ? 6 : precision;
                    body = switch (Character.toLowerCase(conv)) {
                        case 'e' -> e(x, p, alt);
                        case 'f' -> f(x, p, alt);
                        case 'g' -> g(x, p, alt);
                        default -> a(x, precision, alt);
                    };
                    if (upper) body = body.toUpperCase(java.util.Locale.ROOT);
                }
            }
            case 'c' -> {
                numeric = false;
                body = arg instanceof Character ch ? String.valueOf(ch)
                    : String.valueOf((char) (((Number) arg).intValue() & 0xFF));
            }
            case 's' -> {
                numeric = false;
                body = arg == null ? "(null)" : String.valueOf(arg);
                if (precision >= 0 && body.length() > precision) body = body.substring(0, precision);
            }
            case 'p' -> {
                numeric = false;
                body = "0x" + Long.toHexString(integer(arg));
            }
            default -> throw new IllegalArgumentException("unsupported conversion %" + conv);
        }
        final int len = prefix.length() + body.length();
        if (len >= width) {
            out.append(prefix).append(body);
        } else if (minus) {
            out.append(prefix).append(body);
            pad(out, ' ', width - len);
        } else if (zero && numeric) {
            out.append(prefix);
            pad(out, '0', width - len);
            out.append(body);
        } else {
            pad(out, ' ', width - len);
            out.append(prefix).append(body);
        }
    }

    private static long integer(Object o) {
        if (o instanceof Character ch) return ch;
        if (o instanceof Boolean b) return b ? 1 : 0;
        return ((Number) o).longValue();
    }

    private static void pad(StringBuilder out, char c, int n) {
        for (int k = 0; k < n; k++) out.append(c);
    }

    /* ------------------------------------------------------------------ */
    /* The conversions of a non-negative finite double                     */
    /* ------------------------------------------------------------------ */

    /** %.{p}e of x >= 0, without sign. */
    public static String e(double x, int p, boolean alt) {
        final StringBuilder b = new StringBuilder(p + 8);
        if (x == 0) {
            b.append('0');
            if (p > 0 || alt) b.append('.');
            pad(b, '0', p);
            return b.append("e+00").toString();
        }
        final Digits d = digits(x, p + 1);
        final String s = d.text;
        b.append(s.charAt(0));
        if (p > 0 || alt) b.append('.');
        b.append(s, 1, s.length());
        exponent(b, d.exponent);
        return b.toString();
    }

    /** %.{p}f of x >= 0, without sign. */
    public static String f(double x, int p, boolean alt) {
        String s;
        final long fast = fixedFast(x, p);
        if (fast >= 0) {
            s = Long.toString(fast);
            if (p > 0) {
                if (s.length() <= p) s = "0".repeat(p - s.length() + 1) + s;
                s = s.substring(0, s.length() - p) + "." + s.substring(s.length() - p);
            }
        } else {
            s = new BigDecimal(x).setScale(p, RoundingMode.HALF_EVEN).toPlainString();
        }
        if (alt && p == 0) s += ".";
        return s;
    }

    /** %.{p}g of x >= 0, without sign: the precision is the number of significant digits. */
    public static String g(double x, int p, boolean alt) {
        final int P = p == 0 ? 1 : p;
        if (x == 0) {
            if (!alt) return "0";
            return P > 1 ? "0." + "0".repeat(P - 1) : "0.";
        }
        final Digits d = digits(x, P);
        final int X = d.exponent;
        String s;
        if (X < -4 || X >= P) {
            final StringBuilder b = new StringBuilder();
            String m = d.text.substring(0, 1);
            if (P > 1) m += "." + d.text.substring(1);
            if (!alt) m = strip(m);
            else if (P == 1) m += ".";
            b.append(m);
            exponent(b, X);
            s = b.toString();
        } else {
            // style f with precision P-1-X: the same P significant digits with the point moved
            final String t = d.text;
            final int decimals = P - 1 - X;
            final String digitsText;
            if (X >= 0) {
                digitsText = t.substring(0, X + 1) + (decimals > 0 ? "." + t.substring(X + 1) : "");
            } else {
                digitsText = "0." + "0".repeat(-X - 1) + t;
            }
            s = alt ? (digitsText.indexOf('.') < 0 ? digitsText + "." : digitsText) : strip(digitsText);
        }
        return s;
    }

    /** %a: Java's hexadecimal form brought to C's spelling. */
    private static String a(double x, int precision, boolean alt) {
        if (x == 0) return precision > 0 ? "0x0." + "0".repeat(precision) + "p+0" : alt ? "0x0.p+0" : "0x0p+0";
        String h = Double.toHexString(x).replace("0x1.0p", "0x1p");
        final int pp = h.indexOf('p');
        final String exp = h.substring(pp + 1);
        h = h.substring(0, pp) + "p" + (exp.startsWith("-") ? exp : "+" + exp);
        return h;
    }

    private static void exponent(StringBuilder b, int e) {
        b.append('e').append(e < 0 ? '-' : '+');
        final int ae = Math.abs(e);
        if (ae < 10) b.append('0');
        b.append(ae);
    }

    private static String strip(String s) {
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    /* ------------------------------------------------------------------ */
    /* Exact significant digits                                            */
    /* ------------------------------------------------------------------ */

    /** P significant digits of x > 0, rounded half-even from its exact value, and the decimal exponent of the first. */
    public record Digits(String text, int exponent) {
    }

    private static final long[] POW10 = new long[19];
    private static final long[] POW5 = new long[28];

    static {
        POW10[0] = 1;
        for (int k = 1; k < POW10.length; k++) POW10[k] = POW10[k - 1] * 10;
        POW5[0] = 1;
        for (int k = 1; k < POW5.length; k++) POW5[k] = POW5[k - 1] * 5;
    }

    public static Digits digits(double x, int P) {
        if (P <= 18) {
            final Digits fast = digitsFast(x, P);
            if (fast != null) return fast;
        }
        final BigDecimal r = new BigDecimal(x).round(new MathContext(P, RoundingMode.HALF_EVEN));
        String t = r.unscaledValue().toString();
        final int exponent = t.length() - 1 - r.scale();
        if (t.length() < P) t = t + "0".repeat(P - t.length());
        else if (t.length() > P) t = t.substring(0, P);
        return new Digits(t, exponent);
    }

    /**
     * The fast way: x * 10^k with k = P-1-E is m * 5^k * 2^(q+k), formed in
     * 128 bits when 0 <= k <= 27, then rounded from the bits shifted out.
     * Null when the value is outside that range.
     */
    private static Digits digitsFast(double x, int P) {
        final long bits = Double.doubleToRawLongBits(x);
        final int biased = (int) ((bits >>> 52) & 0x7FF);
        if (biased == 0x7FF) return null;
        long m = bits & 0xFFFFFFFFFFFFFL;
        int q;
        if (biased == 0) {
            if (m == 0) return null;
            q = -1074;
        } else {
            m |= 1L << 52;
            q = biased - 1075;
        }
        int E = (int) Math.floor(Math.log10(x));
        for (int attempt = 0; attempt < 3; attempt++) {
            final int k = P - 1 - E;
            if (k < 0 || k > 27) return null;
            final long[] r = scaleRound(m, q, k);
            if (r == null) return null;
            final long floor = r[0];
            if (floor >= POW10[P]) {
                E++;
                continue;
            }
            if (floor < POW10[P - 1]) {
                E--;
                continue;
            }
            long D = floor + r[1];
            int exponent = E;
            if (D == POW10[P]) {
                D = POW10[P - 1];
                exponent++;
            }
            return new Digits(Long.toString(D), exponent);
        }
        return null;
    }

    /**
     * floor(m * 2^q * 10^k) and whether rounding half-even adds one, for
     * 0 <= k <= 27; null when the result would not fit a long.
     */
    private static long[] scaleRound(long m, int q, int k) {
        final long f = POW5[k];
        long hi = Math.multiplyHigh(m, f);
        long lo = m * f;
        final int s = q + k;
        if (s >= 0) {
            if (hi != 0 || s >= 63) return null;
            if (lo != 0 && Long.numberOfLeadingZeros(lo) <= s) return null;
            return new long[]{lo << s, 0};
        }
        final int r = -s;
        if (r >= 128) return new long[]{0, 0};
        // floor = (hi:lo) >> r; remainder = low r bits
        long fl;
        boolean above;
        boolean exactHalf;
        if (r >= 64) {
            final int rr = r - 64;
            fl = rr == 0 ? hi : hi >>> rr;
            // remainder: low rr bits of hi, then lo
            final long remHi = rr == 0 ? 0 : hi & ((1L << rr) - 1);
            // half = 2^(r-1): bit (rr-1) of hi when rr >= 1, else top bit of lo
            if (rr == 0) {
                above = Long.compareUnsigned(lo, 0x8000000000000000L) > 0;
                exactHalf = lo == 0x8000000000000000L;
            } else {
                final long halfHi = 1L << (rr - 1);
                final int cmp = Long.compareUnsigned(remHi, halfHi);
                above = cmp > 0 || (cmp == 0 && lo != 0);
                exactHalf = cmp == 0 && lo == 0;
            }
        } else {
            // r is at least 1 here (s < 0); the floor must fit 64 bits
            if ((hi >>> r) != 0) return null;
            fl = (lo >>> r) | (hi << (64 - r));
            final long rem = lo & ((1L << r) - 1);
            final long half = 1L << (r - 1);
            final int cmp = Long.compareUnsigned(rem, half);
            above = cmp > 0;
            exactHalf = cmp == 0;
        }
        if (fl < 0) return null;
        final long up = above || (exactHalf && (fl & 1) == 1) ? 1 : 0;
        return new long[]{fl, up};
    }

    /** round(x * 10^p) as a long, half-even from the exact value; -1 when not computed this way. */
    private static long fixedFast(double x, int p) {
        if (x == 0) return 0;
        if (p > 27 || x >= 1e15) return -1;
        final long bits = Double.doubleToRawLongBits(x);
        final int biased = (int) ((bits >>> 52) & 0x7FF);
        if (biased == 0 || biased == 0x7FF) return -1;
        final long m = (bits & 0xFFFFFFFFFFFFFL) | (1L << 52);
        final int q = biased - 1075;
        final long[] r = scaleRound(m, q, p);
        if (r == null) return -1;
        final long v = r[0] + r[1];
        return v < 0 ? -1 : v;
    }

    /** The exact value, digit for digit, for whoever needs more than a format gives. */
    public static BigInteger exactScaled(double x, int k) {
        return new BigDecimal(x).scaleByPowerOfTen(k).setScale(0, RoundingMode.FLOOR).toBigInteger();
    }
}
