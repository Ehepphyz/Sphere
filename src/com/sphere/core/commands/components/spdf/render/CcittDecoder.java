package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.PdfDict;
import java.util.HashMap;
import java.util.Map;

/**
 * CCITT fax decoding (T.4 one- and two-dimensional, T.6 / Group 4), the
 * compression of scanned black-and-white pages. Rows come out packed, one
 * bit per pixel, 1 for black.
 */
public final class CcittDecoder {

    private CcittDecoder() {
    }

    private static final Map<Integer, Integer> WHITE = new HashMap<>();
    private static final Map<Integer, Integer> BLACK = new HashMap<>();

    private static final String WHITE_TERM = "00110101 000111 0111 1000 1011 1100 1110 1111 10011 10100 00111 01000 "
        + "001000 000011 110100 110101 101010 101011 0100111 0001100 0001000 0010111 0000011 0000100 0101000 0101011 "
        + "0010011 0100100 0011000 00000010 00000011 00011010 00011011 00010010 00010011 00010100 00010101 00010110 "
        + "00010111 00101000 00101001 00101010 00101011 00101100 00101101 00000100 00000101 00001010 00001011 "
        + "01010010 01010011 01010100 01010101 00100100 00100101 01011000 01011001 01011010 01011011 01001010 "
        + "01001011 00110010 00110011 00110100";
    private static final String WHITE_MAKEUP = "11011 10010 010111 0110111 00110110 00110111 01100100 01100101 01101000 "
        + "01100111 011001100 011001101 011010010 011010011 011010100 011010101 011010110 011010111 011011000 011011001 "
        + "011011010 011011011 010011000 010011001 010011010 011000 010011011";
    private static final String BLACK_TERM = "0000110111 010 11 10 011 0011 0010 00011 000101 000100 0000100 0000101 "
        + "0000111 00000100 00000111 000011000 0000010111 0000011000 0000001000 00001100111 00001101000 00001101100 "
        + "00000110111 00000101000 00000010111 00000011000 000011001010 000011001011 000011001100 000011001101 "
        + "000001101000 000001101001 000001101010 000001101011 000011010010 000011010011 000011010100 000011010101 "
        + "000011010110 000011010111 000001101100 000001101101 000011011010 000011011011 000001010100 000001010101 "
        + "000001010110 000001010111 000001100100 000001100101 000001010010 000001010011 000000100100 000000110111 "
        + "000000111000 000000100111 000000101000 000001011000 000001011001 000000101011 000000101100 000001011010 "
        + "000001100110 000001100111";
    private static final String BLACK_MAKEUP = "0000001111 000011001000 000011001001 000001011011 000000110011 "
        + "000000110100 000000110101 0000001101100 0000001101101 0000001001010 0000001001011 0000001001100 "
        + "0000001001101 0000001110010 0000001110011 0000001110100 0000001110101 0000001110110 0000001110111 "
        + "0000001010010 0000001010011 0000001010100 0000001010101 0000001011010 0000001011011 0000001100100 "
        + "0000001100101";
    private static final String EXT_MAKEUP = "00000001000 00000001100 00000001101 000000010010 000000010011 "
        + "000000010100 000000010101 000000010110 000000010111 000000011100 000000011101 000000011110 000000011111";

    static {
        fill(WHITE, WHITE_TERM, 0, 1);
        fill(WHITE, WHITE_MAKEUP, 64, 64);
        fill(BLACK, BLACK_TERM, 0, 1);
        fill(BLACK, BLACK_MAKEUP, 64, 64);
        fill(WHITE, EXT_MAKEUP, 1792, 64);
        fill(BLACK, EXT_MAKEUP, 1792, 64);
    }

    private static void fill(Map<Integer, Integer> m, String codes, int first, int step) {
        String[] c = codes.split(" ");
        for (int i = 0; i < c.length; i++) {
            m.put((c[i].length() << 16) | Integer.parseInt(c[i], 2), first + i * step);
        }
    }

    private static final class Bits {
        final byte[] d;
        long pos;

        Bits(byte[] d) {
            this.d = d;
        }

        int bit() {
            int i = (int) (pos >> 3);
            if (i >= d.length) {
                pos++;
                return 0;
            }
            int b = (d[i] >> (7 - (pos & 7))) & 1;
            pos++;
            return b;
        }

        int peek(int n) {
            long save = pos;
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            pos = save;
            return v;
        }

        boolean done() {
            return (pos >> 3) >= d.length;
        }

        void align() {
            pos = (pos + 7) & ~7L;
        }
    }

    /** Decodes to packed rows of (columns + 7) / 8 bytes, 1 for black; null on hopeless data. */
    public static byte[] decode(byte[] data, PdfDict p, int width, int height) {
        int k = p == null ? 0 : p.getInt("K", 0);
        int columns = p == null ? width : p.getInt("Columns", 1728);
        if (columns <= 0) {
            columns = width;
        }
        int rows = p == null ? height : p.getInt("Rows", height);
        if (rows <= 0) {
            rows = height;
        }
        boolean align = p != null && p.getBool("EncodedByteAlign", false);
        int rowBytes = (columns + 7) / 8;
        byte[] out = new byte[rowBytes * rows];
        Bits in = new Bits(data);
        int[] ref = new int[columns + 4];
        int refLen = 2;
        ref[0] = columns;
        ref[1] = columns;
        int[] cur = new int[columns + 4];
        try {
            for (int row = 0; row < rows && !in.done(); row++) {
                if (align) {
                    in.align();
                }
                // skip EOLs (eleven zeros and a one)
                while (in.peek(12) == 1) {
                    in.pos += 12;
                }
                boolean twoD = k < 0;
                if (k > 0) {
                    twoD = in.bit() == 0;
                }
                int curLen;
                if (twoD) {
                    curLen = decode2D(in, ref, refLen, cur, columns);
                } else {
                    curLen = decode1D(in, cur, columns);
                }
                if (curLen < 0) {
                    break;
                }
                // paint the row
                int o = row * rowBytes;
                boolean black = false;
                int x = 0;
                for (int i = 0; i < curLen; i++) {
                    int to = Math.min(cur[i], columns);
                    if (black) {
                        for (int c = x; c < to; c++) {
                            out[o + (c >> 3)] |= (byte) (0x80 >> (c & 7));
                        }
                    }
                    x = to;
                    black = !black;
                }
                if (black) {
                    for (int c = x; c < columns; c++) {
                        out[o + (c >> 3)] |= (byte) (0x80 >> (c & 7));
                    }
                }
                System.arraycopy(cur, 0, ref, 0, curLen);
                refLen = curLen;
                ref[refLen] = columns;
                ref[refLen + 1] = columns;
                refLen += 2;
            }
        } catch (RuntimeException e) {
            // keep the rows decoded so far
        }
        if (columns != width) {
            // the image is narrower or wider than the fax: copy row by row
            int wb = (width + 7) / 8;
            byte[] fit = new byte[wb * height];
            for (int r = 0; r < Math.min(rows, height); r++) {
                System.arraycopy(out, r * rowBytes, fit, r * wb, Math.min(wb, rowBytes));
            }
            return fit;
        }
        return out;
    }

    private static int run(Bits in, boolean white) {
        Map<Integer, Integer> m = white ? WHITE : BLACK;
        int total = 0;
        while (true) {
            int code = 0;
            int len = 0;
            Integer v = null;
            while (len < 14) {
                code = (code << 1) | in.bit();
                len++;
                v = m.get((len << 16) | code);
                if (v != null) {
                    break;
                }
            }
            if (v == null) {
                return -1;
            }
            total += v;
            if (v < 64) {
                return total;
            }
        }
    }

    private static int decode1D(Bits in, int[] cur, int columns) {
        int x = 0;
        int n = 0;
        boolean white = true;
        while (x < columns) {
            int r = run(in, white);
            if (r < 0) {
                return n == 0 ? -1 : n;
            }
            x = Math.min(columns, x + r);
            cur[n++] = x;
            white = !white;
        }
        return n;
    }

    private static int decode2D(Bits in, int[] ref, int refLen, int[] cur, int columns) {
        int a0 = -1;
        boolean white = true;
        int n = 0;
        while (a0 < columns) {
            // b1: first change on the reference line right of a0 to the color opposite a0's
            int i = 0;
            while (i < refLen && (ref[i] <= a0 || ((i & 1) == 0) != white)) {
                i++;
            }
            int b1 = i < refLen ? ref[i] : columns;
            int b2 = i + 1 < refLen ? ref[i + 1] : columns;
            int mode = readMode(in);
            switch (mode) {
                case -2: { // pass
                    a0 = b2;
                    break;
                }
                case -3: { // horizontal
                    int r1 = run(in, white);
                    int r2 = run(in, !white);
                    if (r1 < 0 || r2 < 0) {
                        return -1;
                    }
                    int start = Math.max(a0, 0);
                    int a1 = Math.min(columns, start + r1);
                    int a2 = Math.min(columns, a1 + r2);
                    cur[n++] = a1;
                    cur[n++] = a2;
                    a0 = a2;
                    break;
                }
                case -4: // EOL inside a coded line: the end of the data
                    return n == 0 ? -1 : n;
                default: { // vertical, mode = offset + 3 (0..6)
                    if (mode < 0 || mode > 6) {
                        return -1;
                    }
                    int a1 = Math.max(0, Math.min(columns, b1 + mode - 3));
                    cur[n++] = a1;
                    a0 = a1;
                    white = !white;
                    break;
                }
            }
            if (n >= cur.length - 2) {
                break;
            }
        }
        return n;
    }

    /** The 2D mode: -2 pass, -3 horizontal, -4 EOL, 0..6 vertical (VL3..VR3), -1 invalid. */
    private static int readMode(Bits in) {
        if (in.bit() == 1) {
            return 3;
        }
        if (in.bit() == 1) {
            return in.bit() == 1 ? 4 : 2;
        }
        if (in.bit() == 1) {
            return -3;
        }
        if (in.bit() == 1) {
            return -2;
        }
        if (in.bit() == 1) {
            return in.bit() == 1 ? 5 : 1;
        }
        if (in.bit() == 1) {
            return in.bit() == 1 ? 6 : 0;
        }
        // 000000: an extension or an EOL
        int zeros = 6;
        while (zeros < 12 && in.bit() == 0) {
            zeros++;
        }
        return zeros >= 11 ? -4 : -1;
    }
}
