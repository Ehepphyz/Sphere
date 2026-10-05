package com.sphere.core.hepmc3.cxx;

/**
 * C's number parsing on a string read as C reads a char buffer: strtol,
 * strtoul, strtod and their short forms atoi, atol, atof, each stopping at
 * the first character that cannot continue the number and saying where.
 *
 * <p>The readers HepMC3 is made of walk a line with a cursor, find the next
 * blank with strchr and hand what follows to atoi or strtod; a port that used
 * Integer.parseInt would refuse what they accept ("12abc" is 12 to atoi) and
 * accept what they refuse. These follow C: leading white space skipped, a
 * sign, the digits, and the end index returned in {@link #end}.
 */
public final class CStr {

    private CStr() {
    }

    /** Where the last parse on this thread stopped: the index after the number, or the start when none was read. */
    private static final ThreadLocal<int[]> END = ThreadLocal.withInitial(() -> new int[1]);

    public static int end() {
        return END.get()[0];
    }

    public static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static int skipSpace(CharSequence s, int i) {
        while (i < s.length() && isSpace(s.charAt(i))) i++;
        return i;
    }

    /** strtol(s + from, &end, base) with base 10 or 0 (0x hexadecimal, 0 octal); saturates as C does on 64 bits. */
    public static long strtol(CharSequence s, int from, int base) {
        int i = skipSpace(s, from);
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int b = base;
        if ((b == 0 || b == 16) && i + 1 < s.length() && s.charAt(i) == '0'
                && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X') && i + 2 < s.length()
                && Character.digit(s.charAt(i + 2), 16) >= 0) {
            i += 2;
            b = 16;
        } else if (b == 0) {
            b = i < s.length() && s.charAt(i) == '0' ? 8 : 10;
        }
        final int start = i;
        long v = 0;
        boolean overflow = false;
        while (i < s.length()) {
            final int d = Character.digit(s.charAt(i), b);
            if (d < 0 || s.charAt(i) > 'z') break;
            if (!overflow) {
                if (v > (Long.MAX_VALUE - d) / b) overflow = true;
                else v = v * b + d;
            }
            i++;
        }
        if (i == start) {
            END.get()[0] = from;
            return 0;
        }
        END.get()[0] = i;
        if (overflow) return neg ? Long.MIN_VALUE : Long.MAX_VALUE;
        return neg ? -v : v;
    }

    /** strtoul / strtoull: the same grammar, a minus sign negating modulo 2^64. */
    public static long strtoul(CharSequence s, int from, int base) {
        int i = skipSpace(s, from);
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int b = base;
        if ((b == 0 || b == 16) && i + 1 < s.length() && s.charAt(i) == '0'
                && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X') && i + 2 < s.length()
                && Character.digit(s.charAt(i + 2), 16) >= 0) {
            i += 2;
            b = 16;
        } else if (b == 0) {
            b = i < s.length() && s.charAt(i) == '0' ? 8 : 10;
        }
        final int start = i;
        long v = 0;
        boolean overflow = false;
        while (i < s.length()) {
            final int d = Character.digit(s.charAt(i), b);
            if (d < 0 || s.charAt(i) > 'z') break;
            if (!overflow) {
                final long hiPart = Math.multiplyHigh(v, b) + ((v >> 63) & b);
                final long lo = v * b;
                final long sum = lo + d;
                if (hiPart != 0 || Long.compareUnsigned(sum, lo) < 0) overflow = true;
                else v = sum;
            }
            i++;
        }
        if (i == start) {
            END.get()[0] = from;
            return 0;
        }
        END.get()[0] = i;
        if (overflow) return -1L;
        return neg ? -v : v;
    }

    /** atoi: strtol truncated to int as the C library does on two's complement. */
    public static int atoi(CharSequence s, int from) {
        return (int) strtol(s, from, 10);
    }

    public static int atoi(CharSequence s) {
        return atoi(s, 0);
    }

    public static long atol(CharSequence s, int from) {
        return strtol(s, from, 10);
    }

    /** strtod(s + from, &end): decimal, hexadecimal (0x...p...), inf, infinity, nan, nan(...). */
    public static double strtod(CharSequence s, int from) {
        int i = skipSpace(s, from);
        final int n = s.length();
        boolean neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        // inf / infinity / nan
        if (i < n && (s.charAt(i) == 'i' || s.charAt(i) == 'I')) {
            if (matchesIgnoreCase(s, i, "inf")) {
                int e = i + 3;
                if (matchesIgnoreCase(s, i, "infinity")) e = i + 8;
                END.get()[0] = e;
                return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            END.get()[0] = from;
            return 0;
        }
        if (i < n && (s.charAt(i) == 'n' || s.charAt(i) == 'N')) {
            if (matchesIgnoreCase(s, i, "nan")) {
                int e = i + 3;
                if (e < n && s.charAt(e) == '(') {
                    int k = e + 1;
                    while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) k++;
                    if (k < n && s.charAt(k) == ')') e = k + 1;
                }
                END.get()[0] = e;
                return neg ? -Double.NaN : Double.NaN;
            }
            END.get()[0] = from;
            return 0;
        }
        // hexadecimal
        if (i + 1 < n && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            int k = i + 2;
            final int digitsStart = k;
            while (k < n && Character.digit(s.charAt(k), 16) >= 0 && s.charAt(k) <= 'f') k++;
            int intEnd = k;
            int fracDigits = 0;
            if (k < n && s.charAt(k) == '.') {
                k++;
                while (k < n && Character.digit(s.charAt(k), 16) >= 0 && s.charAt(k) <= 'f') {
                    k++;
                    fracDigits++;
                }
            }
            if (intEnd > digitsStart || fracDigits > 0) {
                final String mant = s.subSequence(i, k).toString();
                String expo = "p0";
                if (k < n && (s.charAt(k) == 'p' || s.charAt(k) == 'P')) {
                    int e = k + 1;
                    if (e < n && (s.charAt(e) == '+' || s.charAt(e) == '-')) e++;
                    final int es = e;
                    while (e < n && Character.isDigit(s.charAt(e))) e++;
                    if (e > es) {
                        expo = s.subSequence(k, e).toString();
                        k = e;
                    }
                }
                END.get()[0] = k;
                final double v = Double.parseDouble(mant + (mant.endsWith(".") ? "0" : "") + expo);
                return neg ? -v : v;
            }
            // "0x" with no hex digit: the 0 alone is the number
            END.get()[0] = i + 1;
            return neg ? -0.0 : 0.0;
        }
        final int start = i;
        int digits = 0;
        while (i < n && isDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            END.get()[0] = from;
            return 0;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int e = i + 1;
            if (e < n && (s.charAt(e) == '+' || s.charAt(e) == '-')) e++;
            final int es = e;
            while (e < n && isDigit(s.charAt(e))) e++;
            if (e > es) i = e;
        }
        END.get()[0] = i;
        final double v = parse(s, start, i);
        return neg ? -v : v;
    }

    public static double atof(CharSequence s, int from) {
        return strtod(s, from);
    }

    public static double atof(CharSequence s) {
        return strtod(s, 0);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean matchesIgnoreCase(CharSequence s, int at, String word) {
        if (at + word.length() > s.length()) return false;
        for (int k = 0; k < word.length(); k++) {
            if (Character.toLowerCase(s.charAt(at + k)) != word.charAt(k)) return false;
        }
        return true;
    }

    /**
     * Correctly rounded decimal to double, as glibc's strtod. Short plain
     * numbers (the common case in event files) are formed without a String.
     */
    private static double parse(CharSequence s, int from, int to) {
        return Double.parseDouble(s.subSequence(from, to).toString());
    }

    /** The index of c at or after from, or -1: strchr. */
    public static int strchr(CharSequence s, int from, char c) {
        for (int i = Math.max(0, from); i < s.length(); i++) {
            if (s.charAt(i) == c) return i;
        }
        return -1;
    }
}
