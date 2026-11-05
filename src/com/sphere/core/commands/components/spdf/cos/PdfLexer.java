package com.sphere.components.spdf.cos;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tokens of PDF syntax (7.2): numbers, strings, names come back as their
 * objects, keywords and operators as Java strings, delimiters as {@link Tok}.
 * Lenient where writers are sloppy: stray delimiters are skipped, "--1" reads
 * as -1, a number glued to letters reads as a keyword.
 */
public final class PdfLexer {

    public enum Tok { ARRAY_START, ARRAY_END, DICT_START, DICT_END, PROC_START, PROC_END, EOF }

    public final byte[] buf;
    public int pos;
    public final int end;

    private byte[] scratch = new byte[64];

    public PdfLexer(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public PdfLexer(byte[] buf, int pos, int end) {
        this.buf = buf;
        this.pos = pos;
        this.end = Math.min(end, buf.length);
    }

    public static boolean isWhite(int c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0;
    }

    public static boolean isDelimiter(int c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}'
            || c == '/' || c == '%';
    }

    public static boolean isRegular(int c) {
        return !isWhite(c) && !isDelimiter(c);
    }

    public void skipWhite() {
        while (pos < end) {
            int c = buf[pos] & 0xff;
            if (isWhite(c)) {
                pos++;
            } else if (c == '%') {
                while (pos < end && buf[pos] != '\n' && buf[pos] != '\r') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    /** The next token: a PdfObject (number, string, name), a keyword String, or a Tok. */
    public Object next() {
        skipWhite();
        if (pos >= end) {
            return Tok.EOF;
        }
        int c = buf[pos] & 0xff;
        switch (c) {
            case '[':
                pos++;
                return Tok.ARRAY_START;
            case ']':
                pos++;
                return Tok.ARRAY_END;
            case '{':
                pos++;
                return Tok.PROC_START;
            case '}':
                pos++;
                return Tok.PROC_END;
            case '<':
                if (pos + 1 < end && buf[pos + 1] == '<') {
                    pos += 2;
                    return Tok.DICT_START;
                }
                return readHex();
            case '>':
                if (pos + 1 < end && buf[pos + 1] == '>') {
                    pos += 2;
                    return Tok.DICT_END;
                }
                pos++;
                return next();
            case '(':
                return readLiteral();
            case ')':
                pos++;
                return next();
            case '/':
                return readName();
            default:
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.') {
                    return readNumber();
                }
                return readKeyword();
        }
    }

    private Object readNumber() {
        final int start = pos;
        boolean neg = false;
        while (pos < end && (buf[pos] == '-' || buf[pos] == '+')) {
            if (buf[pos] == '-') {
                neg = true;
            }
            pos++;
        }
        long ip = 0;
        boolean digits = false;
        boolean real = false;
        double value;
        while (pos < end && buf[pos] >= '0' && buf[pos] <= '9') {
            if (ip < 100000000000000000L) {
                ip = ip * 10 + (buf[pos] - '0');
            }
            digits = true;
            pos++;
        }
        value = ip;
        if (pos < end && buf[pos] == '.') {
            real = true;
            pos++;
            double scale = 0.1;
            while (pos < end && buf[pos] >= '0' && buf[pos] <= '9') {
                value += (buf[pos] - '0') * scale;
                scale *= 0.1;
                digits = true;
                pos++;
            }
            // "1.2.3": the rest is junk the number swallows
            while (pos < end && (buf[pos] == '.' || (buf[pos] >= '0' && buf[pos] <= '9'))) {
                pos++;
            }
        }
        if (digits && pos + 1 < end && (buf[pos] == 'e' || buf[pos] == 'E')) {
            int p = pos + 1;
            boolean eneg = false;
            if (buf[p] == '-' || buf[p] == '+') {
                eneg = buf[p] == '-';
                p++;
            }
            if (p < end && buf[p] >= '0' && buf[p] <= '9') {
                int e = 0;
                while (p < end && buf[p] >= '0' && buf[p] <= '9') {
                    e = Math.min(400, e * 10 + (buf[p] - '0'));
                    p++;
                }
                value *= Math.pow(10, eneg ? -e : e);
                real = true;
                pos = p;
            }
        }
        if (pos < end && isRegular(buf[pos] & 0xff)) {
            // glued to letters: not a number after all
            pos = start;
            return readKeyword();
        }
        if (!digits) {
            return new PdfNumber(0L);
        }
        if (neg) {
            value = -value;
            ip = -ip;
        }
        return real ? new PdfNumber(value) : new PdfNumber(ip);
    }

    private void put(int len, int b) {
        if (len >= scratch.length) {
            scratch = Arrays.copyOf(scratch, scratch.length * 2);
        }
        scratch[len] = (byte) b;
    }

    private PdfString readLiteral() {
        pos++;
        int depth = 1;
        int len = 0;
        while (pos < end) {
            int c = buf[pos++] & 0xff;
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    break;
                }
            } else if (c == '\\') {
                if (pos >= end) {
                    break;
                }
                c = buf[pos++] & 0xff;
                switch (c) {
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case '\r':
                        if (pos < end && buf[pos] == '\n') {
                            pos++;
                        }
                        continue;
                    case '\n':
                        continue;
                    default:
                        if (c >= '0' && c <= '7') {
                            int v = c - '0';
                            for (int k = 0; k < 2 && pos < end && buf[pos] >= '0' && buf[pos] <= '7'; k++) {
                                v = v * 8 + (buf[pos++] - '0');
                            }
                            c = v & 0xff;
                        }
                        break;
                }
            } else if (c == '\r') {
                if (pos < end && buf[pos] == '\n') {
                    pos++;
                }
                c = '\n';
            }
            put(len++, c);
        }
        return new PdfString(Arrays.copyOf(scratch, len), false);
    }

    private PdfString readHex() {
        pos++;
        int len = 0;
        int hi = -1;
        while (pos < end) {
            int c = buf[pos++] & 0xff;
            if (c == '>') {
                break;
            }
            int v = hexValue(c);
            if (v < 0) {
                continue;
            }
            if (hi < 0) {
                hi = v;
            } else {
                put(len++, (hi << 4) | v);
                hi = -1;
            }
        }
        if (hi >= 0) {
            put(len++, hi << 4);
        }
        return new PdfString(Arrays.copyOf(scratch, len), true);
    }

    public static int hexValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Names are kept one char per byte (Latin-1), so they write back exactly as read. */
    private PdfName readName() {
        pos++;
        int len = 0;
        while (pos < end) {
            int c = buf[pos] & 0xff;
            if (!isRegular(c)) {
                break;
            }
            pos++;
            if (c == '#' && pos + 1 < end) {
                int h = hexValue(buf[pos] & 0xff);
                int l = hexValue(buf[pos + 1] & 0xff);
                if (h >= 0 && l >= 0) {
                    c = (h << 4) | l;
                    pos += 2;
                }
            }
            put(len++, c);
        }
        return PdfName.of(new String(scratch, 0, len, StandardCharsets.ISO_8859_1));
    }

    private String readKeyword() {
        int start = pos;
        while (pos < end && isRegular(buf[pos] & 0xff)) {
            pos++;
        }
        if (pos == start) {
            pos++;
            return "";
        }
        return new String(buf, start, pos - start, StandardCharsets.ISO_8859_1);
    }

    /** True when the bytes at the position spell the keyword, followed by a non-regular byte. */
    public boolean at(String keyword) {
        int n = keyword.length();
        if (pos + n > end) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (buf[pos + i] != keyword.charAt(i)) {
                return false;
            }
        }
        return pos + n >= end || !isRegular(buf[pos + n] & 0xff);
    }

    /** Finds a byte sequence from a position, or -1. */
    public static int indexOf(byte[] b, byte[] what, int from, int to) {
        to = Math.min(to, b.length) - what.length;
        outer:
        for (int i = Math.max(0, from); i <= to; i++) {
            for (int k = 0; k < what.length; k++) {
                if (b[i + k] != what[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The last occurrence of a byte sequence before a position, or -1. */
    public static int lastIndexOf(byte[] b, byte[] what, int before) {
        outer:
        for (int i = Math.min(before, b.length) - what.length; i >= 0; i--) {
            for (int k = 0; k < what.length; k++) {
                if (b[i + k] != what[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
