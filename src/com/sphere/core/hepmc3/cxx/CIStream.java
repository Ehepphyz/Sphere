package com.sphere.core.hepmc3.cxx;

/**
 * std::istringstream and its extraction operators, as libstdc++ reads: white
 * space skipped before every value; a number made of what num_get accepts
 * (sign, digits, point, exponent) and then converted; on failure the value
 * becomes 0 (C++11) and the stream stays failed, so that every later
 * extraction fails too. "while (is >> w)" therefore stops where C++ stops.
 */
public final class CIStream {

    private final String s;
    private int pos;
    private boolean fail;
    private boolean eof;

    public CIStream(String text) {
        this.s = text == null ? "" : text;
    }

    public boolean fail() {
        return fail;
    }

    public boolean eof() {
        return eof;
    }

    /** The stream converted to bool: neither failbit nor badbit. */
    public boolean ok() {
        return !fail;
    }

    public int position() {
        return pos;
    }

    /** The text read since a position given by {@link #position()}. */
    public String consumedSince(int before) {
        return s.substring(Math.max(0, before), Math.min(pos, s.length()));
    }

    /** What is left to read. */
    public String rest() {
        return pos >= s.length() ? "" : s.substring(pos);
    }

    private boolean skipWs() {
        if (fail) return false;
        while (pos < s.length() && CStr.isSpace(s.charAt(pos))) pos++;
        if (pos >= s.length()) {
            eof = true;
            fail = true;
            return false;
        }
        return true;
    }

    /** >> int: 0 and failed when no number; the extremes and failed when it overflows. */
    public int nextInt() {
        final long v = integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
        return (int) v;
    }

    /** >> long (64 bits, as on Linux). */
    public long nextLong() {
        return integer(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * >> unsigned of the given width (32 or 64 bits): a minus sign wraps
     * modulo 2^bits, as num_get does; too large fails with the maximum.
     * The value is returned as the low bits of a long.
     */
    public long nextUnsigned(int bits) {
        if (!skipWs()) return 0;
        final int start = pos;
        boolean neg = false;
        if (s.charAt(pos) == '+' || s.charAt(pos) == '-') {
            neg = s.charAt(pos) == '-';
            pos++;
        }
        final int ds = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
        if (pos == ds) {
            fail = true;
            pos = start;
            return 0;
        }
        if (pos >= s.length()) eof = true;
        final java.math.BigInteger v = new java.math.BigInteger(s.substring(ds, pos));
        final java.math.BigInteger modulus = java.math.BigInteger.ONE.shiftLeft(bits);
        final long max = bits == 64 ? -1L : (1L << bits) - 1;
        if (v.compareTo(modulus) >= 0) {
            fail = true;
            return max;
        }
        final java.math.BigInteger r = neg ? modulus.subtract(v).mod(modulus) : v;
        return r.longValue();
    }

    private long integer(long min, long max) {
        if (!skipWs()) return 0;
        final int start = pos;
        boolean neg = false;
        if (s.charAt(pos) == '+' || s.charAt(pos) == '-') {
            neg = s.charAt(pos) == '-';
            pos++;
        }
        final int ds = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
        if (pos == ds) {
            fail = true;
            pos = start;
            return 0;
        }
        if (pos >= s.length()) eof = true;
        long v = 0;
        boolean overflow = false;
        for (int k = ds; k < pos; k++) {
            final int d = s.charAt(k) - '0';
            if (v > (Long.MAX_VALUE - d) / 10) {
                overflow = true;
                break;
            }
            v = v * 10 + d;
        }
        if (neg) v = -v;
        if (overflow || v > max || v < min) {
            fail = true;
            return neg ? min : max;
        }
        return v;
    }

    /** >> double: what num_get accumulates, then strtod; no inf or nan, as in libstdc++. */
    public double nextDouble() {
        if (!skipWs()) return 0;
        final int start = pos;
        final int n = s.length();
        if (s.charAt(pos) == '+' || s.charAt(pos) == '-') pos++;
        int digits = 0;
        while (pos < n && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            pos++;
            digits++;
        }
        if (pos < n && s.charAt(pos) == '.') {
            pos++;
            while (pos < n && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
                digits++;
            }
        }
        if (digits > 0 && pos < n && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            int e = pos + 1;
            if (e < n && (s.charAt(e) == '+' || s.charAt(e) == '-')) e++;
            // num_get takes the 'e' and the sign even without digits after them
            int es = e;
            while (e < n && s.charAt(e) >= '0' && s.charAt(e) <= '9') e++;
            pos = e;
            if (e == es) {
                fail = true;
                return 0;
            }
        }
        if (pos >= n) eof = true;
        if (digits == 0) {
            fail = true;
            return 0;
        }
        final double v = Double.parseDouble(s.substring(start, pos));
        if (Double.isInfinite(v)) {
            fail = true;
            return v > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE;
        }
        return v;
    }

    /** >> float. */
    public float nextFloat() {
        final double v = nextDouble();
        return (float) v;
    }

    /** >> std::string: the next blank-separated word. */
    public String nextWord() {
        if (!skipWs()) return "";
        final int start = pos;
        while (pos < s.length() && !CStr.isSpace(s.charAt(pos))) pos++;
        if (pos >= s.length()) eof = true;
        return s.substring(start, pos);
    }

    /** >> char: the next non-blank character, -1 and failed at the end. */
    public int nextChar() {
        if (!skipWs()) return -1;
        return s.charAt(pos++);
    }

    /** std::getline(is, line): the rest of the current line; null when nothing is left. */
    public String getline() {
        if (fail) return null;
        if (pos >= s.length()) {
            eof = true;
            fail = true;
            return null;
        }
        final int nl = s.indexOf('\n', pos);
        final String line;
        if (nl < 0) {
            line = s.substring(pos);
            pos = s.length();
            eof = true;
        } else {
            line = s.substring(pos, nl);
            pos = nl + 1;
        }
        return line;
    }
}
