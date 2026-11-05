package com.sphere.components.spdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Type 1 font program (FontFile, PFA or PFB): the clear part's matrix and
 * encoding, the eexec-encrypted private part's Subrs and CharStrings, and a
 * Type 1 charstring interpreter (hsbw/sbw, the path operators, flex and hint
 * replacement through OtherSubrs, seac accents). This is the format TeX
 * embeds, so every arXiv paper made with pdfTeX goes through here.
 */
public final class Type1Program {

    public String fontName = "";
    public double[] fontMatrix = { 0.001, 0, 0, 0.001, 0, 0 };
    /** The built-in encoding (code to glyph name), or null for StandardEncoding. */
    public String[] encoding;
    private final Map<String, byte[]> charStrings = new HashMap<>();
    private byte[][] subrs = new byte[0][];
    private int lenIV = 4;
    private final Map<String, GeneralPath> cache = new HashMap<>();
    private final Map<String, Double> widths = new HashMap<>();

    private Type1Program() {
    }

    public static Type1Program parse(byte[] data, int length1) {
        Type1Program t = new Type1Program();
        t.read(stripPfb(data), length1);
        return t;
    }

    private static byte[] stripPfb(byte[] d) {
        if (d.length < 6 || (d[0] & 0xff) != 0x80) {
            return d;
        }
        com.sphere.components.spdf.cos.ByteBuf out = new com.sphere.components.spdf.cos.ByteBuf(d.length);
        int p = 0;
        while (p + 6 <= d.length && (d[p] & 0xff) == 0x80) {
            int type = d[p + 1] & 0xff;
            if (type == 3) {
                break;
            }
            int len = (d[p + 2] & 0xff) | ((d[p + 3] & 0xff) << 8) | ((d[p + 4] & 0xff) << 16) | ((d[p + 5] & 0xff) << 24);
            p += 6;
            len = Math.min(len, d.length - p);
            out.put(d, p, len);
            p += len;
        }
        return out.toBytes();
    }

    public boolean has(String name) {
        return charStrings.containsKey(name);
    }

    public java.util.Set<String> glyphNames() {
        return charStrings.keySet();
    }

    private void read(byte[] d, int length1) {
        int eexec = indexOf(d, "eexec".getBytes(StandardCharsets.ISO_8859_1), 0);
        int clearEnd = eexec >= 0 ? eexec : Math.min(d.length, length1 > 0 ? length1 : d.length);
        readClear(new String(d, 0, clearEnd, StandardCharsets.ISO_8859_1));
        int start;
        if (eexec >= 0) {
            start = eexec + 5;
            while (start < d.length && (d[start] == '\r' || d[start] == '\n' || d[start] == ' ' || d[start] == '\t')) {
                start++;
            }
        } else if (length1 > 0 && length1 < d.length) {
            start = length1;
        } else {
            return;
        }
        byte[] enc = java.util.Arrays.copyOfRange(d, start, d.length);
        if (isHex(enc)) {
            enc = fromHex(enc);
        }
        byte[] plain = decrypt(enc, 55665, 4);
        readPrivate(plain);
    }

    private static boolean isHex(byte[] b) {
        int n = Math.min(4, b.length);
        for (int i = 0; i < n; i++) {
            if (Character.digit(b[i], 16) < 0) {
                return false;
            }
        }
        return n == 4;
    }

    private static byte[] fromHex(byte[] b) {
        com.sphere.components.spdf.cos.ByteBuf out = new com.sphere.components.spdf.cos.ByteBuf(b.length / 2);
        int hi = -1;
        for (byte x : b) {
            int v = Character.digit(x, 16);
            if (v < 0) {
                continue;
            }
            if (hi < 0) {
                hi = v;
            } else {
                out.put((hi << 4) | v);
                hi = -1;
            }
        }
        return out.toBytes();
    }

    static byte[] decrypt(byte[] in, int r, int skip) {
        if (in.length <= skip) {
            return new byte[0];
        }
        byte[] out = new byte[in.length - skip];
        for (int i = 0; i < in.length; i++) {
            int c = in[i] & 0xff;
            int p = c ^ (r >> 8);
            r = ((c + r) * 52845 + 22719) & 0xffff;
            if (i >= skip) {
                out[i - skip] = (byte) p;
            }
        }
        return out;
    }

    private void readClear(String s) {
        int fm = s.indexOf("/FontMatrix");
        if (fm >= 0) {
            int a = s.indexOf('[', fm);
            int b = s.indexOf(']', a + 1);
            if (a > 0 && b > a && b - a < 200) {
                String[] parts = s.substring(a + 1, b).trim().split("\\s+");
                if (parts.length == 6) {
                    try {
                        double[] m = new double[6];
                        for (int i = 0; i < 6; i++) {
                            m[i] = Double.parseDouble(parts[i]);
                        }
                        fontMatrix = m;
                    } catch (NumberFormatException e) {
                        // keep the default
                    }
                }
            }
        }
        int fn = s.indexOf("/FontName");
        if (fn >= 0) {
            int slash = s.indexOf('/', fn + 9);
            if (slash > 0) {
                int e = slash + 1;
                while (e < s.length() && !Character.isWhitespace(s.charAt(e)) && s.charAt(e) != '/') {
                    e++;
                }
                fontName = s.substring(slash + 1, e);
            }
        }
        int en = s.indexOf("/Encoding");
        if (en >= 0) {
            String rest = s.substring(en + 9, Math.min(s.length(), en + 60)).trim();
            if (rest.startsWith("StandardEncoding")) {
                encoding = null;
            } else {
                encoding = new String[256];
                int p = en;
                int stop = s.indexOf("readonly def", en);
                if (stop < 0) {
                    stop = s.indexOf(" def", en + 20);
                }
                if (stop < 0) {
                    stop = s.length();
                }
                String body = s.substring(p, stop);
                String[] tok = body.split("\\s+");
                for (int i = 0; i + 3 < tok.length; i++) {
                    if (tok[i].equals("dup") && tok[i + 2].startsWith("/") && tok[i + 3].equals("put")) {
                        try {
                            int code = Integer.parseInt(tok[i + 1]);
                            if (code >= 0 && code < 256) {
                                encoding[code] = tok[i + 2].substring(1);
                            }
                        } catch (NumberFormatException e) {
                            // not an entry
                        }
                    }
                }
            }
        }
    }

    /** Reads Subrs and CharStrings from the decrypted private part. */
    private void readPrivate(byte[] p) {
        String head = new String(p, 0, Math.min(p.length, 4096), StandardCharsets.ISO_8859_1);
        int li = head.indexOf("/lenIV");
        if (li >= 0) {
            try {
                String[] t = head.substring(li + 6).trim().split("\\s+");
                lenIV = Integer.parseInt(t[0]);
            } catch (RuntimeException e) {
                lenIV = 4;
            }
        }
        int pos = 0;
        List<byte[]> subrList = new ArrayList<>();
        int subrsAt = indexOf(p, "/Subrs".getBytes(StandardCharsets.ISO_8859_1), 0);
        int csAt = indexOf(p, "/CharStrings".getBytes(StandardCharsets.ISO_8859_1), 0);
        if (subrsAt >= 0 && (csAt < 0 || subrsAt < csAt)) {
            pos = subrsAt + 6;
            int[] num = new int[1];
            pos = readInt(p, pos, num);
            int count = num[0];
            byte[][] arr = new byte[Math.max(0, Math.min(count, 65536))][];
            for (int i = 0; i < arr.length; i++) {
                int dup = indexOfToken(p, "dup", pos);
                if (dup < 0 || (csAt >= 0 && dup > csAt)) {
                    break;
                }
                int[] idx = new int[1];
                int q = readInt(p, dup + 3, idx);
                int[] len = new int[1];
                q = readInt(p, q, len);
                q = skipToken(p, q);
                q++;
                if (q + len[0] > p.length || len[0] < 0) {
                    break;
                }
                byte[] bin = java.util.Arrays.copyOfRange(p, q, q + len[0]);
                if (idx[0] >= 0 && idx[0] < arr.length) {
                    arr[idx[0]] = decrypt(bin, 4330, lenIV < 0 ? 0 : lenIV);
                }
                pos = q + len[0];
            }
            subrs = arr;
            subrList.clear();
        }
        csAt = indexOf(p, "/CharStrings".getBytes(StandardCharsets.ISO_8859_1), pos);
        if (csAt < 0) {
            return;
        }
        pos = indexOfToken(p, "begin", csAt);
        if (pos < 0) {
            return;
        }
        pos += 5;
        while (pos < p.length) {
            while (pos < p.length && isSpace(p[pos])) {
                pos++;
            }
            if (pos >= p.length || p[pos] != '/') {
                if (startsWith(p, pos, "end")) {
                    break;
                }
                pos++;
                continue;
            }
            int ns = pos + 1;
            int ne = ns;
            while (ne < p.length && !isSpace(p[ne]) && p[ne] != '/' && p[ne] != '{' && p[ne] != '[') {
                ne++;
            }
            String name = new String(p, ns, ne - ns, StandardCharsets.ISO_8859_1);
            int[] len = new int[1];
            int q = readInt(p, ne, len);
            q = skipToken(p, q);
            q++;
            if (len[0] < 0 || q + len[0] > p.length) {
                break;
            }
            byte[] bin = java.util.Arrays.copyOfRange(p, q, q + len[0]);
            charStrings.put(name, decrypt(bin, 4330, lenIV < 0 ? 0 : lenIV));
            pos = q + len[0];
            pos = skipToken(p, pos);
        }
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0;
    }

    private static boolean startsWith(byte[] p, int pos, String s) {
        if (pos + s.length() > p.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (p[pos + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readInt(byte[] p, int pos, int[] out) {
        while (pos < p.length && isSpace(p[pos])) {
            pos++;
        }
        int v = 0;
        boolean neg = false;
        if (pos < p.length && p[pos] == '-') {
            neg = true;
            pos++;
        }
        while (pos < p.length && p[pos] >= '0' && p[pos] <= '9') {
            v = v * 10 + (p[pos] - '0');
            pos++;
        }
        out[0] = neg ? -v : v;
        return pos;
    }

    /** Skips spaces then one token (RD, -|, ND, |-, NP...), returning the position after it. */
    private static int skipToken(byte[] p, int pos) {
        while (pos < p.length && isSpace(p[pos])) {
            pos++;
        }
        while (pos < p.length && !isSpace(p[pos])) {
            pos++;
        }
        return pos;
    }

    private static int indexOfToken(byte[] p, String tok, int from) {
        byte[] t = tok.getBytes(StandardCharsets.ISO_8859_1);
        int i = from;
        while ((i = indexOf(p, t, i)) >= 0) {
            boolean before = i == 0 || isSpace(p[i - 1]);
            boolean after = i + t.length >= p.length || isSpace(p[i + t.length]);
            if (before && after) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int indexOf(byte[] b, byte[] what, int from) {
        outer:
        for (int i = Math.max(0, from); i <= b.length - what.length; i++) {
            for (int k = 0; k < what.length; k++) {
                if (b[i + k] != what[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /* ------------------------------------------------------------- charstrings */

    public synchronized GeneralPath glyph(String name) {
        GeneralPath g = cache.get(name);
        if (g == null) {
            g = new GeneralPath(Path2D.WIND_NON_ZERO);
            byte[] cs = charStrings.get(name);
            if (cs != null) {
                try {
                    Interp in = new Interp(g);
                    in.run(cs, 0);
                    widths.put(name, in.width);
                } catch (RuntimeException e) {
                    // what was drawn stays
                }
            }
            cache.put(name, g);
        }
        return g;
    }

    public double width(String name) {
        if (!widths.containsKey(name)) {
            glyph(name);
        }
        Double w = widths.get(name);
        return w == null ? 0 : w;
    }

    private final class Interp {
        final GeneralPath path;
        final double[] st = new double[64];
        int sp;
        double x;
        double y;
        double sbx;
        double width;
        boolean open;
        boolean flexing;
        final List<double[]> flex = new ArrayList<>();
        boolean done;

        Interp(GeneralPath path) {
            this.path = path;
        }

        void push(double v) {
            if (sp < st.length) {
                st[sp++] = v;
            }
        }

        void ensureOpen() {
            if (!open) {
                path.moveTo(x, y);
                open = true;
            }
        }

        void run(byte[] cs, int level) {
            if (level > 10) {
                return;
            }
            int p = 0;
            while (p < cs.length && !done) {
                int v = cs[p++] & 0xff;
                if (v >= 32) {
                    if (v <= 246) {
                        push(v - 139);
                    } else if (v <= 250) {
                        push((v - 247) * 256 + (cs[p++] & 0xff) + 108);
                    } else if (v <= 254) {
                        push(-(v - 251) * 256 - (cs[p++] & 0xff) - 108);
                    } else {
                        int n = ((cs[p] & 0xff) << 24) | ((cs[p + 1] & 0xff) << 16) | ((cs[p + 2] & 0xff) << 8)
                            | (cs[p + 3] & 0xff);
                        p += 4;
                        push(n);
                    }
                    continue;
                }
                switch (v) {
                    case 1: case 3:
                        sp = 0;
                        break;
                    case 4:
                        rmove(0, sp > 0 ? st[sp - 1] : 0);
                        break;
                    case 5:
                        ensureOpen();
                        x += st[0];
                        y += st[1];
                        path.lineTo(x, y);
                        sp = 0;
                        break;
                    case 6:
                        ensureOpen();
                        x += st[0];
                        path.lineTo(x, y);
                        sp = 0;
                        break;
                    case 7:
                        ensureOpen();
                        y += st[0];
                        path.lineTo(x, y);
                        sp = 0;
                        break;
                    case 8: {
                        ensureOpen();
                        double ax = x + st[0];
                        double ay = y + st[1];
                        double bx = ax + st[2];
                        double by = ay + st[3];
                        x = bx + st[4];
                        y = by + st[5];
                        path.curveTo(ax, ay, bx, by, x, y);
                        sp = 0;
                        break;
                    }
                    case 9:
                        if (open) {
                            path.closePath();
                            open = false;
                        }
                        sp = 0;
                        break;
                    case 10: {
                        if (sp == 0) {
                            break;
                        }
                        int idx = (int) st[--sp];
                        if (idx >= 0 && idx < subrs.length && subrs[idx] != null) {
                            run(subrs[idx], level + 1);
                        }
                        break;
                    }
                    case 11:
                        return;
                    case 13:
                        sbx = st[0];
                        width = st[1];
                        x = sbx;
                        y = 0;
                        sp = 0;
                        break;
                    case 14:
                        if (open) {
                            path.closePath();
                            open = false;
                        }
                        sp = 0;
                        done = true;
                        return;
                    case 21:
                        rmove(st[0], st[1]);
                        break;
                    case 22:
                        rmove(sp > 0 ? st[sp - 1] : 0, 0);
                        break;
                    case 30: {
                        ensureOpen();
                        double ax = x;
                        double ay = y + st[0];
                        double bx = ax + st[1];
                        double by = ay + st[2];
                        x = bx + st[3];
                        y = by;
                        path.curveTo(ax, ay, bx, by, x, y);
                        sp = 0;
                        break;
                    }
                    case 31: {
                        ensureOpen();
                        double ax = x + st[0];
                        double ay = y;
                        double bx = ax + st[1];
                        double by = ay + st[2];
                        x = bx;
                        y = by + st[3];
                        path.curveTo(ax, ay, bx, by, x, y);
                        sp = 0;
                        break;
                    }
                    case 12:
                        escape(cs[p++] & 0xff);
                        break;
                    default:
                        sp = 0;
                        break;
                }
            }
        }

        void rmove(double dx, double dy) {
            if (flexing) {
                double[] last = flex.isEmpty() ? new double[] { x, y } : flex.get(flex.size() - 1);
                flex.add(new double[] { last[0] + dx, last[1] + dy });
                sp = 0;
                return;
            }
            if (open) {
                path.closePath();
            }
            x += dx;
            y += dy;
            path.moveTo(x, y);
            open = true;
            sp = 0;
        }

        void escape(int op) {
            switch (op) {
                case 0: case 1: case 2:
                    sp = 0;
                    break;
                case 6: {
                    if (sp >= 5) {
                        double asb = st[sp - 5];
                        double adx = st[sp - 4];
                        double ady = st[sp - 3];
                        int bchar = (int) st[sp - 2];
                        int achar = (int) st[sp - 1];
                        String bn = bchar >= 0 && bchar < 256 ? GlyphNames.STANDARD[bchar] : null;
                        String an = achar >= 0 && achar < 256 ? GlyphNames.STANDARD[achar] : null;
                        if (bn != null && an != null) {
                            path.append(glyphUncached(bn), false);
                            path.append(glyphUncached(an).createTransformedShape(
                                AffineTransform.getTranslateInstance(adx + sbx - asb, ady)), false);
                        }
                    }
                    sp = 0;
                    done = true;
                    break;
                }
                case 7:
                    sbx = st[0];
                    width = st[2];
                    x = sbx;
                    y = st[1];
                    sp = 0;
                    break;
                case 12:
                    if (sp >= 2) {
                        double b = st[--sp];
                        double a = st[--sp];
                        push(b == 0 ? 0 : a / b);
                    }
                    break;
                case 16: {
                    if (sp < 2) {
                        sp = 0;
                        break;
                    }
                    int othersubr = (int) st[--sp];
                    int n = (int) st[--sp];
                    if (othersubr == 1 && n == 0) {
                        flexing = true;
                        flex.clear();
                    } else if (othersubr == 0 && n == 3) {
                        flexing = false;
                        sp = Math.max(0, sp - 3);
                        if (flex.size() >= 7) {
                            ensureOpen();
                            double[] a = flex.get(1);
                            double[] b = flex.get(2);
                            double[] c = flex.get(3);
                            path.curveTo(a[0], a[1], b[0], b[1], c[0], c[1]);
                            double[] e = flex.get(4);
                            double[] f = flex.get(5);
                            double[] g = flex.get(6);
                            path.curveTo(e[0], e[1], f[0], f[1], g[0], g[1]);
                            x = g[0];
                            y = g[1];
                        }
                        push(x);
                        push(y);
                    } else if (othersubr == 2 && n == 0) {
                        // a flex point was added by the rmoveto before
                        break;
                    }
                    // hint replacement (3) and others leave their arguments for pop/callsubr
                    break;
                }
                case 17:
                    // pop: the values othersubr left are already on the stack
                    break;
                case 33:
                    if (sp >= 2) {
                        x = st[sp - 2];
                        y = st[sp - 1];
                    }
                    sp = 0;
                    break;
                default:
                    sp = 0;
                    break;
            }
        }
    }

    private GeneralPath glyphUncached(String name) {
        GeneralPath g = new GeneralPath(Path2D.WIND_NON_ZERO);
        byte[] cs = charStrings.get(name);
        if (cs != null) {
            try {
                new Interp(g).run(cs, 1);
            } catch (RuntimeException e) {
                // nothing
            }
        }
        return g;
    }
}
