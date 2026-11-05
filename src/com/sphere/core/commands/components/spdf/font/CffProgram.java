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
 * A CFF font program (FontFile3 /Type1C or /CIDFontType0C, or the CFF table
 * of an OpenType font): its INDEXes and DICTs, charset, encoding, FDSelect,
 * and a Type 2 charstring interpreter that turns glyphs into outlines (all
 * the path operators, flex, hint masks, subroutines, the arithmetic, and the
 * seac accent composition of endchar).
 */
public final class CffProgram {

    private final byte[] d;
    public String fontName = "";
    public boolean isCID;
    public int numGlyphs;
    public double[] fontMatrix = { 0.001, 0, 0, 0.001, 0, 0 };
    private byte[][] charStrings = new byte[0][];
    private byte[][] globalSubrs = new byte[0][];
    private String[] strings = new String[0];
    private int[] charset = new int[0];
    private final Map<String, Integer> nameToGid = new HashMap<>();
    private final Map<Integer, Integer> cidToGid = new HashMap<>();
    private final int[] encoding = new int[256];
    private boolean hasEncoding;

    private static final class Priv {
        byte[][] subrs = new byte[0][];
        double defaultWidth;
        double nominalWidth;
        double[] matrix;
    }

    private Priv topPriv = new Priv();
    private Priv[] fds = new Priv[0];
    private int[] fdSelect;
    private final Map<Integer, GeneralPath> cache = new HashMap<>();
    private final Map<Integer, Double> widths = new HashMap<>();

    private CffProgram(byte[] data) {
        this.d = data;
    }

    public static CffProgram parse(byte[] data) {
        CffProgram c = new CffProgram(data);
        c.read();
        return c;
    }

    /** An OpenType font with CFF outlines: its CFF table, or null. */
    public static byte[] cffTableOfOpenType(byte[] otf) {
        if (otf.length < 12 || !(otf[0] == 'O' && otf[1] == 'T' && otf[2] == 'T' && otf[3] == 'O')) {
            return null;
        }
        int n = ((otf[4] & 0xff) << 8) | (otf[5] & 0xff);
        for (int i = 0; i < n; i++) {
            int r = 12 + 16 * i;
            if (r + 16 > otf.length) {
                break;
            }
            if (otf[r] == 'C' && otf[r + 1] == 'F' && otf[r + 2] == 'F' && otf[r + 3] == ' ') {
                int off = ((otf[r + 8] & 0xff) << 24) | ((otf[r + 9] & 0xff) << 16) | ((otf[r + 10] & 0xff) << 8)
                    | (otf[r + 11] & 0xff);
                int len = ((otf[r + 12] & 0xff) << 24) | ((otf[r + 13] & 0xff) << 16) | ((otf[r + 14] & 0xff) << 8)
                    | (otf[r + 15] & 0xff);
                if (off >= 0 && off + len <= otf.length) {
                    return java.util.Arrays.copyOfRange(otf, off, off + len);
                }
            }
        }
        return null;
    }

    private int card8(int p) {
        return p < d.length ? d[p] & 0xff : 0;
    }

    private int card16(int p) {
        return (card8(p) << 8) | card8(p + 1);
    }

    private int offset(int p, int size) {
        int v = 0;
        for (int i = 0; i < size; i++) {
            v = (v << 8) | card8(p + i);
        }
        return v;
    }

    /** An INDEX at a position: its items, and the position after it in [0]. */
    private byte[][] index(int p, int[] next) {
        int count = card16(p);
        if (count == 0) {
            next[0] = p + 2;
            return new byte[0][];
        }
        int offSize = card8(p + 2);
        int offs = p + 3;
        int dataStart = offs + (count + 1) * offSize - 1;
        byte[][] items = new byte[count][];
        int prev = offset(offs, offSize);
        for (int i = 0; i < count; i++) {
            int cur = offset(offs + (i + 1) * offSize, offSize);
            int a = dataStart + prev;
            int b = dataStart + cur;
            if (a < 0 || b > d.length || b < a) {
                items[i] = new byte[0];
            } else {
                items[i] = java.util.Arrays.copyOfRange(d, a, b);
            }
            prev = cur;
        }
        next[0] = dataStart + prev;
        return items;
    }

    /** A DICT: operator (escaped ones as 1200+op) to its operands. */
    private static Map<Integer, double[]> dict(byte[] b) {
        Map<Integer, double[]> m = new HashMap<>();
        List<Double> ops = new ArrayList<>();
        int p = 0;
        while (p < b.length) {
            int b0 = b[p] & 0xff;
            if (b0 <= 21) {
                int key = b0;
                p++;
                if (b0 == 12 && p < b.length) {
                    key = 1200 + (b[p++] & 0xff);
                }
                double[] arr = new double[ops.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = ops.get(i);
                }
                m.put(key, arr);
                ops.clear();
            } else if (b0 == 28 && p + 2 < b.length) {
                ops.add((double) (short) (((b[p + 1] & 0xff) << 8) | (b[p + 2] & 0xff)));
                p += 3;
            } else if (b0 == 29 && p + 4 < b.length) {
                ops.add((double) (((b[p + 1] & 0xff) << 24) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 8)
                    | (b[p + 4] & 0xff)));
                p += 5;
            } else if (b0 == 30) {
                StringBuilder sb = new StringBuilder();
                p++;
                outer:
                while (p < b.length) {
                    int v = b[p++] & 0xff;
                    for (int nib : new int[] { v >> 4, v & 15 }) {
                        if (nib <= 9) {
                            sb.append((char) ('0' + nib));
                        } else if (nib == 10) {
                            sb.append('.');
                        } else if (nib == 11) {
                            sb.append('E');
                        } else if (nib == 12) {
                            sb.append("E-");
                        } else if (nib == 14) {
                            sb.append('-');
                        } else if (nib == 15) {
                            break outer;
                        }
                    }
                }
                try {
                    ops.add(Double.parseDouble(sb.toString()));
                } catch (NumberFormatException e) {
                    ops.add(0.0);
                }
            } else if (b0 >= 32 && b0 <= 246) {
                ops.add((double) (b0 - 139));
                p++;
            } else if (b0 >= 247 && b0 <= 250 && p + 1 < b.length) {
                ops.add((double) ((b0 - 247) * 256 + (b[p + 1] & 0xff) + 108));
                p += 2;
            } else if (b0 >= 251 && b0 <= 254 && p + 1 < b.length) {
                ops.add((double) (-(b0 - 251) * 256 - (b[p + 1] & 0xff) - 108));
                p += 2;
            } else {
                p++;
            }
        }
        return m;
    }

    private static double num(Map<Integer, double[]> m, int key, double def) {
        double[] v = m.get(key);
        return v != null && v.length > 0 ? v[0] : def;
    }

    private void read() {
        int hdrSize = card8(2);
        int[] next = new int[1];
        byte[][] names = index(hdrSize, next);
        if (names.length > 0) {
            fontName = new String(names[0], StandardCharsets.ISO_8859_1);
        }
        byte[][] tops = index(next[0], next);
        byte[][] strs = index(next[0], next);
        strings = new String[strs.length];
        for (int i = 0; i < strs.length; i++) {
            strings[i] = new String(strs[i], StandardCharsets.ISO_8859_1);
        }
        globalSubrs = index(next[0], next);
        if (tops.length == 0) {
            return;
        }
        Map<Integer, double[]> top = dict(tops[0]);
        isCID = top.containsKey(1230);
        double[] fm = top.get(1207);
        if (fm != null && fm.length == 6) {
            fontMatrix = fm;
        }
        int csOff = (int) num(top, 17, 0);
        if (csOff > 0) {
            charStrings = index(csOff, next);
        }
        numGlyphs = charStrings.length;
        double[] priv = top.get(18);
        if (priv != null && priv.length >= 2) {
            topPriv = readPrivate((int) priv[1], (int) priv[0]);
        }
        if (isCID) {
            int fdArrayOff = (int) num(top, 1236, 0);
            if (fdArrayOff > 0) {
                byte[][] fdDicts = index(fdArrayOff, next);
                fds = new Priv[fdDicts.length];
                for (int i = 0; i < fdDicts.length; i++) {
                    Map<Integer, double[]> fd = dict(fdDicts[i]);
                    double[] pp = fd.get(18);
                    fds[i] = pp != null && pp.length >= 2 ? readPrivate((int) pp[1], (int) pp[0]) : new Priv();
                    double[] m = fd.get(1207);
                    if (m != null && m.length == 6) {
                        fds[i].matrix = m;
                    }
                }
            }
            int fdsOff = (int) num(top, 1237, 0);
            if (fdsOff > 0) {
                readFdSelect(fdsOff);
            }
            if (fds.length > 0 && fds[0].matrix != null) {
                double[] c = multiply(fds[0].matrix, fontMatrix);
                if (Math.abs(c[0]) > 1e-5 || Math.abs(c[1]) > 1e-5) {
                    fontMatrix = c;
                }
            }
        }
        readCharset((int) num(top, 15, 0));
        if (!isCID) {
            readEncoding((int) num(top, 16, 0));
        }
    }

    private static double[] multiply(double[] a, double[] b) {
        return new double[] {
            a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
            a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5] };
    }

    private Priv readPrivate(int off, int size) {
        Priv p = new Priv();
        if (off <= 0 || off + size > d.length) {
            return p;
        }
        Map<Integer, double[]> m = dict(java.util.Arrays.copyOfRange(d, off, off + size));
        p.defaultWidth = num(m, 20, 0);
        p.nominalWidth = num(m, 21, 0);
        int subrs = (int) num(m, 19, 0);
        if (subrs > 0) {
            p.subrs = index(off + subrs, new int[1]);
        }
        return p;
    }

    private void readFdSelect(int p) {
        fdSelect = new int[numGlyphs];
        int format = card8(p);
        if (format == 0) {
            for (int i = 0; i < numGlyphs; i++) {
                fdSelect[i] = card8(p + 1 + i);
            }
        } else if (format == 3) {
            int n = card16(p + 1);
            for (int r = 0; r < n; r++) {
                int first = card16(p + 3 + 3 * r);
                int fd = card8(p + 5 + 3 * r);
                int end = card16(p + 6 + 3 * r);
                for (int g = first; g < end && g < numGlyphs; g++) {
                    fdSelect[g] = fd;
                }
            }
        }
    }

    private void readCharset(int off) {
        charset = new int[numGlyphs];
        if (off == 0 || off == 1 || off == 2) {
            // ISOAdobe (the expert charsets are rare enough to read as it)
            for (int i = 0; i < numGlyphs; i++) {
                charset[i] = i;
            }
        } else {
            int format = card8(off);
            int p = off + 1;
            int g = 1;
            if (format == 0) {
                while (g < numGlyphs) {
                    charset[g++] = card16(p);
                    p += 2;
                }
            } else {
                while (g < numGlyphs && p < d.length) {
                    int first = card16(p);
                    int left = format == 1 ? card8(p + 2) : card16(p + 2);
                    p += format == 1 ? 3 : 4;
                    for (int k = 0; k <= left && g < numGlyphs; k++) {
                        charset[g++] = first + k;
                    }
                }
            }
        }
        for (int g = 0; g < numGlyphs; g++) {
            if (isCID) {
                cidToGid.put(charset[g], g);
            } else {
                nameToGid.putIfAbsent(sid(charset[g]), g);
            }
        }
    }

    private void readEncoding(int off) {
        if (off == 0 || off == 1) {
            // Standard (or Expert): the glyph names say where each glyph goes
            String[] std = GlyphNames.STANDARD;
            for (int c = 0; c < 256; c++) {
                if (std[c] != null) {
                    Integer g = nameToGid.get(std[c]);
                    if (g != null) {
                        encoding[c] = g;
                        hasEncoding = true;
                    }
                }
            }
            return;
        }
        int format = card8(off);
        int p = off + 1;
        if ((format & 0x7f) == 0) {
            int n = card8(p++);
            for (int i = 1; i <= n; i++) {
                encoding[card8(p++)] = i;
            }
        } else if ((format & 0x7f) == 1) {
            int n = card8(p++);
            int g = 1;
            for (int r = 0; r < n; r++) {
                int first = card8(p);
                int left = card8(p + 1);
                p += 2;
                for (int k = 0; k <= left; k++) {
                    if (first + k < 256) {
                        encoding[first + k] = g;
                    }
                    g++;
                }
            }
        }
        if ((format & 0x80) != 0) {
            int n = card8(p++);
            for (int i = 0; i < n; i++) {
                int code = card8(p);
                int sid = card16(p + 1);
                p += 3;
                Integer g = nameToGid.get(sid(sid));
                if (g != null) {
                    encoding[code] = g;
                }
            }
        }
        hasEncoding = true;
    }

    /** The name a string id stands for. */
    public String sid(int sid) {
        if (sid < STD_STRINGS.length) {
            return STD_STRINGS[sid];
        }
        int i = sid - STD_STRINGS.length;
        return i < strings.length ? strings[i] : ".notdef";
    }

    public int gidForName(String name) {
        Integer g = nameToGid.get(name);
        return g == null ? 0 : g;
    }

    public boolean hasName(String name) {
        return nameToGid.containsKey(name);
    }

    public String nameForGid(int gid) {
        if (isCID || gid < 0 || gid >= charset.length) {
            return null;
        }
        return sid(charset[gid]);
    }

    public int gidForCID(int cid) {
        if (!isCID) {
            return cid < numGlyphs ? cid : 0;
        }
        Integer g = cidToGid.get(cid);
        return g == null ? 0 : g;
    }

    /** The glyph the font's own encoding gives a code, or 0. */
    public int gidForCode(int code) {
        return code >= 0 && code < 256 ? encoding[code] : 0;
    }

    public boolean hasBuiltInEncoding() {
        return hasEncoding;
    }

    /* ------------------------------------------------------------- charstrings */

    public synchronized GeneralPath glyph(int gid) {
        GeneralPath p = cache.get(gid);
        if (p == null) {
            p = new GeneralPath(Path2D.WIND_NON_ZERO);
            if (gid >= 0 && gid < charStrings.length) {
                try {
                    Interp in = new Interp(p, privFor(gid));
                    in.run(charStrings[gid], 0);
                    widths.put(gid, in.width);
                } catch (RuntimeException e) {
                    // a damaged charstring draws what it could
                }
            }
            cache.put(gid, p);
        }
        return p;
    }

    /** The advance of a glyph, in glyph space units. */
    public double width(int gid) {
        if (!widths.containsKey(gid)) {
            glyph(gid);
        }
        Double w = widths.get(gid);
        return w == null ? 0 : w;
    }

    private Priv privFor(int gid) {
        if (isCID && fdSelect != null && gid < fdSelect.length && fdSelect[gid] < fds.length) {
            return fds[fdSelect[gid]];
        }
        if (isCID && fds.length > 0) {
            return fds[0];
        }
        return topPriv;
    }

    private static int bias(int n) {
        return n < 1240 ? 107 : n < 33900 ? 1131 : 32768;
    }

    private final class Interp {
        final GeneralPath path;
        final Priv priv;
        final double[] st = new double[64];
        final double[] transient_ = new double[32];
        int sp;
        double x;
        double y;
        int stems;
        boolean haveWidth;
        double width;
        boolean open;
        int depth;
        boolean done;

        Interp(GeneralPath path, Priv priv) {
            this.path = path;
            this.priv = priv;
            this.width = priv.defaultWidth;
        }

        void push(double v) {
            if (sp < st.length) {
                st[sp++] = v;
            }
        }

        /** The first stack-clearing operator may carry the width before its arguments. */
        void takeWidth(boolean extra) {
            if (!haveWidth) {
                haveWidth = true;
                if (extra) {
                    width = priv.nominalWidth + st[0];
                    System.arraycopy(st, 1, st, 0, sp - 1);
                    sp--;
                }
            }
        }

        void moveTo(double nx, double ny) {
            if (open) {
                path.closePath();
            }
            x = nx;
            y = ny;
            path.moveTo(x, y);
            open = true;
        }

        void lineTo(double nx, double ny) {
            if (!open) {
                path.moveTo(x, y);
                open = true;
            }
            x = nx;
            y = ny;
            path.lineTo(x, y);
        }

        void curveTo(double ax, double ay, double bx, double by, double cx, double cy) {
            if (!open) {
                path.moveTo(x, y);
                open = true;
            }
            x = cx;
            y = cy;
            path.curveTo(ax, ay, bx, by, cx, cy);
        }

        void run(byte[] cs, int level) {
            if (level > 10) {
                return;
            }
            int p = 0;
            while (p < cs.length && !done) {
                int b0 = cs[p++] & 0xff;
                if (b0 >= 32) {
                    if (b0 <= 246) {
                        push(b0 - 139);
                    } else if (b0 <= 250) {
                        push((b0 - 247) * 256 + (cs[p++] & 0xff) + 108);
                    } else if (b0 <= 254) {
                        push(-(b0 - 251) * 256 - (cs[p++] & 0xff) - 108);
                    } else {
                        int v = ((cs[p] & 0xff) << 24) | ((cs[p + 1] & 0xff) << 16) | ((cs[p + 2] & 0xff) << 8)
                            | (cs[p + 3] & 0xff);
                        p += 4;
                        push(v / 65536.0);
                    }
                    continue;
                }
                if (b0 == 28) {
                    push((short) (((cs[p] & 0xff) << 8) | (cs[p + 1] & 0xff)));
                    p += 2;
                    continue;
                }
                switch (b0) {
                    case 1: case 3: case 18: case 23:
                        takeWidth(sp % 2 == 1);
                        stems += sp / 2;
                        sp = 0;
                        break;
                    case 19: case 20:
                        takeWidth(sp % 2 == 1);
                        stems += sp / 2;
                        sp = 0;
                        p += (stems + 7) / 8;
                        break;
                    case 21:
                        takeWidth(sp > 2);
                        moveTo(x + st[0], y + st[1]);
                        sp = 0;
                        break;
                    case 22:
                        takeWidth(sp > 1);
                        moveTo(x + st[0], y);
                        sp = 0;
                        break;
                    case 4:
                        takeWidth(sp > 1);
                        moveTo(x, y + st[0]);
                        sp = 0;
                        break;
                    case 5:
                        for (int i = 0; i + 1 < sp; i += 2) {
                            lineTo(x + st[i], y + st[i + 1]);
                        }
                        sp = 0;
                        break;
                    case 6: case 7: {
                        boolean horiz = b0 == 6;
                        for (int i = 0; i < sp; i++) {
                            if (horiz) {
                                lineTo(x + st[i], y);
                            } else {
                                lineTo(x, y + st[i]);
                            }
                            horiz = !horiz;
                        }
                        sp = 0;
                        break;
                    }
                    case 8:
                        for (int i = 0; i + 5 < sp; i += 6) {
                            double ax = x + st[i];
                            double ay = y + st[i + 1];
                            double bx = ax + st[i + 2];
                            double by = ay + st[i + 3];
                            curveTo(ax, ay, bx, by, bx + st[i + 4], by + st[i + 5]);
                        }
                        sp = 0;
                        break;
                    case 24: {
                        int i = 0;
                        for (; i + 5 < sp - 2; i += 6) {
                            double ax = x + st[i];
                            double ay = y + st[i + 1];
                            double bx = ax + st[i + 2];
                            double by = ay + st[i + 3];
                            curveTo(ax, ay, bx, by, bx + st[i + 4], by + st[i + 5]);
                        }
                        if (i + 1 < sp) {
                            lineTo(x + st[i], y + st[i + 1]);
                        }
                        sp = 0;
                        break;
                    }
                    case 25: {
                        int i = 0;
                        for (; i + 1 < sp - 6; i += 2) {
                            lineTo(x + st[i], y + st[i + 1]);
                        }
                        if (i + 5 < sp) {
                            double ax = x + st[i];
                            double ay = y + st[i + 1];
                            double bx = ax + st[i + 2];
                            double by = ay + st[i + 3];
                            curveTo(ax, ay, bx, by, bx + st[i + 4], by + st[i + 5]);
                        }
                        sp = 0;
                        break;
                    }
                    case 26: {
                        int i = 0;
                        double nx = x;
                        if (sp % 2 == 1) {
                            nx += st[i++];
                        }
                        for (; i + 3 < sp; i += 4) {
                            double ax = nx;
                            double ay = y + st[i];
                            double bx = ax + st[i + 1];
                            double by = ay + st[i + 2];
                            curveTo(ax, ay, bx, by, bx, by + st[i + 3]);
                            nx = x;
                        }
                        sp = 0;
                        break;
                    }
                    case 27: {
                        int i = 0;
                        double ny = y;
                        if (sp % 2 == 1) {
                            ny += st[i++];
                        }
                        for (; i + 3 < sp; i += 4) {
                            double ax = x + st[i];
                            double ay = ny;
                            double bx = ax + st[i + 1];
                            double by = ay + st[i + 2];
                            curveTo(ax, ay, bx, by, bx + st[i + 3], by);
                            ny = y;
                        }
                        sp = 0;
                        break;
                    }
                    case 30: case 31: {
                        boolean horiz = b0 == 31;
                        int i = 0;
                        while (i + 3 < sp) {
                            boolean lastOne = sp - (i + 4) == 1;
                            if (horiz) {
                                double ax = x + st[i];
                                double ay = y;
                                double bx = ax + st[i + 1];
                                double by = ay + st[i + 2];
                                double cy = by + st[i + 3];
                                double cx = bx + (lastOne ? st[i + 4] : 0);
                                curveTo(ax, ay, bx, by, cx, cy);
                            } else {
                                double ax = x;
                                double ay = y + st[i];
                                double bx = ax + st[i + 1];
                                double by = ay + st[i + 2];
                                double cx = bx + st[i + 3];
                                double cy = by + (lastOne ? st[i + 4] : 0);
                                curveTo(ax, ay, bx, by, cx, cy);
                            }
                            i += lastOne ? 5 : 4;
                            horiz = !horiz;
                        }
                        sp = 0;
                        break;
                    }
                    case 10: case 29: {
                        if (sp == 0) {
                            break;
                        }
                        byte[][] subrs = b0 == 10 ? priv.subrs : globalSubrs;
                        int idx = (int) st[--sp] + bias(subrs.length);
                        if (idx >= 0 && idx < subrs.length && depth < 10) {
                            depth++;
                            run(subrs[idx], level + 1);
                            depth--;
                        }
                        break;
                    }
                    case 11:
                        return;
                    case 14:
                        takeWidth(sp == 1 || sp == 5);
                        if (sp >= 4) {
                            seac(st[sp - 4], st[sp - 3], (int) st[sp - 2], (int) st[sp - 1]);
                        }
                        if (open) {
                            path.closePath();
                            open = false;
                        }
                        sp = 0;
                        done = true;
                        return;
                    case 12:
                        escape(cs[p++] & 0xff);
                        break;
                    default:
                        sp = 0;
                        break;
                }
            }
        }

        private void seac(double adx, double ady, int bchar, int achar) {
            String bn = bchar >= 0 && bchar < 256 ? GlyphNames.STANDARD[bchar] : null;
            String an = achar >= 0 && achar < 256 ? GlyphNames.STANDARD[achar] : null;
            if (bn == null || an == null) {
                return;
            }
            GeneralPath base = glyphNoCache(gidForName(bn));
            GeneralPath acc = glyphNoCache(gidForName(an));
            path.append(base, false);
            path.append(acc.createTransformedShape(AffineTransform.getTranslateInstance(adx, ady)), false);
        }

        private void escape(int op) {
            switch (op) {
                case 35: {
                    if (sp >= 12) {
                        curveTo(x + st[0], y + st[1], x + st[0] + st[2], y + st[1] + st[3],
                                x + st[0] + st[2] + st[4], y + st[1] + st[3] + st[5]);
                        double ax = x + st[6];
                        double ay = y + st[7];
                        double bx = ax + st[8];
                        double by = ay + st[9];
                        curveTo(ax, ay, bx, by, bx + st[10], by + st[11]);
                    }
                    sp = 0;
                    break;
                }
                case 34: {
                    if (sp >= 7) {
                        double y0 = y;
                        double ax = x + st[0];
                        double bx = ax + st[1];
                        double by = y + st[2];
                        double cx = bx + st[3];
                        curveTo(ax, y0, bx, by, cx, by);
                        double dx = x + st[4];
                        double ex = dx + st[5];
                        double fx = ex + st[6];
                        curveTo(dx, by, ex, y0, fx, y0);
                    }
                    sp = 0;
                    break;
                }
                case 36: {
                    if (sp >= 9) {
                        double y0 = y;
                        double ax = x + st[0];
                        double ay = y + st[1];
                        double bx = ax + st[2];
                        double by = ay + st[3];
                        double cx = bx + st[4];
                        curveTo(ax, ay, bx, by, cx, by);
                        double dx = x + st[5];
                        double ex = dx + st[6];
                        double ey = by + st[7];
                        double fx = ex + st[8];
                        curveTo(dx, by, ex, ey, fx, y0);
                    }
                    sp = 0;
                    break;
                }
                case 37: {
                    if (sp >= 11) {
                        double x0 = x;
                        double y0 = y;
                        double ax = x + st[0];
                        double ay = y + st[1];
                        double bx = ax + st[2];
                        double by = ay + st[3];
                        double cx = bx + st[4];
                        double cy = by + st[5];
                        curveTo(ax, ay, bx, by, cx, cy);
                        double dx = x + st[6];
                        double dy = y + st[7];
                        double ex = dx + st[8];
                        double ey = dy + st[9];
                        double sumx = ex - x0;
                        double sumy = ey - y0;
                        if (Math.abs(sumx) > Math.abs(sumy)) {
                            curveTo(dx, dy, ex, ey, ex + st[10], y0);
                        } else {
                            curveTo(dx, dy, ex, ey, x0, ey + st[10]);
                        }
                    }
                    sp = 0;
                    break;
                }
                case 3: binary((a, b) -> (a != 0 && b != 0) ? 1 : 0); break;
                case 4: binary((a, b) -> (a != 0 || b != 0) ? 1 : 0); break;
                case 5: if (sp > 0) { st[sp - 1] = st[sp - 1] == 0 ? 1 : 0; } break;
                case 9: if (sp > 0) { st[sp - 1] = Math.abs(st[sp - 1]); } break;
                case 10: binary(Double::sum); break;
                case 11: binary((a, b) -> a - b); break;
                case 12: binary((a, b) -> b == 0 ? 0 : a / b); break;
                case 14: if (sp > 0) { st[sp - 1] = -st[sp - 1]; } break;
                case 15: binary((a, b) -> a == b ? 1 : 0); break;
                case 18: if (sp > 0) { sp--; } break;
                case 20: if (sp >= 2) { int i = (int) st[sp - 1]; if (i >= 0 && i < transient_.length) { transient_[i] = st[sp - 2]; } sp -= 2; } break;
                case 21: if (sp >= 1) { int i = (int) st[sp - 1]; st[sp - 1] = i >= 0 && i < transient_.length ? transient_[i] : 0; } break;
                case 22: if (sp >= 4) { double v = st[sp - 2] <= st[sp - 1] ? st[sp - 4] : st[sp - 3]; sp -= 4; push(v); } break;
                case 23: push(Math.random()); break;
                case 24: binary((a, b) -> a * b); break;
                case 26: if (sp > 0) { st[sp - 1] = Math.sqrt(Math.abs(st[sp - 1])); } break;
                case 27: if (sp > 0) { push(st[sp - 1]); } break;
                case 28: if (sp >= 2) { double t = st[sp - 1]; st[sp - 1] = st[sp - 2]; st[sp - 2] = t; } break;
                case 29: if (sp >= 1) { int i = (int) st[sp - 1]; i = i < 0 ? 0 : i; st[sp - 1] = sp - 2 - i >= 0 ? st[sp - 2 - i] : 0; } break;
                case 30: if (sp >= 2) {
                    int j = (int) st[--sp];
                    int n = (int) st[--sp];
                    if (n > 0 && n <= sp) {
                        double[] part = java.util.Arrays.copyOfRange(st, sp - n, sp);
                        for (int k = 0; k < n; k++) {
                            st[sp - n + Math.floorMod(k + j, n)] = part[k];
                        }
                    }
                } break;
                default:
                    sp = 0;
                    break;
            }
        }

        private void binary(java.util.function.DoubleBinaryOperator f) {
            if (sp >= 2) {
                double b = st[--sp];
                double a = st[--sp];
                push(f.applyAsDouble(a, b));
            }
        }
    }

    private GeneralPath glyphNoCache(int gid) {
        GeneralPath p = new GeneralPath(Path2D.WIND_NON_ZERO);
        if (gid >= 0 && gid < charStrings.length) {
            try {
                Interp in = new Interp(p, privFor(gid));
                in.haveWidth = true;
                in.run(charStrings[gid], 1);
            } catch (RuntimeException e) {
                // nothing
            }
        }
        return p;
    }

    private static final String[] STD_STRINGS = (".notdef space exclam quotedbl numbersign dollar percent ampersand "
        + "quoteright parenleft parenright asterisk plus comma hyphen period slash zero one two three four five six "
        + "seven eight nine colon semicolon less equal greater question at A B C D E F G H I J K L M N O P Q R S T U "
        + "V W X Y Z bracketleft backslash bracketright asciicircum underscore quoteleft a b c d e f g h i j k l m n "
        + "o p q r s t u v w x y z braceleft bar braceright asciitilde exclamdown cent sterling fraction yen florin "
        + "section currency quotesingle quotedblleft guillemotleft guilsinglleft guilsinglright fi fl endash dagger "
        + "daggerdbl periodcentered paragraph bullet quotesinglbase quotedblbase quotedblright guillemotright "
        + "ellipsis perthousand questiondown grave acute circumflex tilde macron breve dotaccent dieresis ring "
        + "cedilla hungarumlaut ogonek caron emdash AE ordfeminine Lslash Oslash OE ordmasculine ae dotlessi lslash "
        + "oslash oe germandbls onesuperior logicalnot mu trademark Eth onehalf plusminus Thorn onequarter divide "
        + "brokenbar degree thorn threequarters twosuperior registered minus eth multiply threesuperior copyright "
        + "Aacute Acircumflex Adieresis Agrave Aring Atilde Ccedilla Eacute Ecircumflex Edieresis Egrave Iacute "
        + "Icircumflex Idieresis Igrave Ntilde Oacute Ocircumflex Odieresis Ograve Otilde Scaron Uacute Ucircumflex "
        + "Udieresis Ugrave Yacute Ydieresis Zcaron aacute acircumflex adieresis agrave aring atilde ccedilla eacute "
        + "ecircumflex edieresis egrave iacute icircumflex idieresis igrave ntilde oacute ocircumflex odieresis "
        + "ograve otilde scaron uacute ucircumflex udieresis ugrave yacute ydieresis zcaron exclamsmall "
        + "Hungarumlautsmall dollaroldstyle dollarsuperior ampersandsmall Acutesmall parenleftsuperior "
        + "parenrightsuperior twodotenleader onedotenleader zerooldstyle oneoldstyle twooldstyle threeoldstyle "
        + "fouroldstyle fiveoldstyle sixoldstyle sevenoldstyle eightoldstyle nineoldstyle commasuperior "
        + "threequartersemdash periodsuperior questionsmall asuperior bsuperior centsuperior dsuperior esuperior "
        + "isuperior lsuperior msuperior nsuperior osuperior rsuperior ssuperior tsuperior ff ffi ffl "
        + "parenleftinferior parenrightinferior Circumflexsmall hyphensuperior Gravesmall Asmall Bsmall Csmall "
        + "Dsmall Esmall Fsmall Gsmall Hsmall Ismall Jsmall Ksmall Lsmall Msmall Nsmall Osmall Psmall Qsmall Rsmall "
        + "Ssmall Tsmall Usmall Vsmall Wsmall Xsmall Ysmall Zsmall colonmonetary onefitted rupiah Tildesmall "
        + "exclamdownsmall centoldstyle Lslashsmall Scaronsmall Zcaronsmall Dieresissmall Brevesmall Caronsmall "
        + "Dotaccentsmall Macronsmall figuredash hypheninferior Ogoneksmall Ringsmall Cedillasmall questiondownsmall "
        + "oneeighth threeeighths fiveeighths seveneighths onethird twothirds zerosuperior foursuperior "
        + "fivesuperior sixsuperior sevensuperior eightsuperior ninesuperior zeroinferior oneinferior twoinferior "
        + "threeinferior fourinferior fiveinferior sixinferior seveninferior eightinferior nineinferior "
        + "centinferior dollarinferior periodinferior commainferior Agravesmall Aacutesmall Acircumflexsmall "
        + "Atildesmall Adieresissmall Aringsmall AEsmall Ccedillasmall Egravesmall Eacutesmall Ecircumflexsmall "
        + "Edieresissmall Igravesmall Iacutesmall Icircumflexsmall Idieresissmall Ethsmall Ntildesmall Ogravesmall "
        + "Oacutesmall Ocircumflexsmall Otildesmall Odieresissmall OEsmall Oslashsmall Ugravesmall Uacutesmall "
        + "Ucircumflexsmall Udieresissmall Yacutesmall Thornsmall Ydieresissmall 001.000 001.001 001.002 001.003 "
        + "Black Bold Book Light Medium Regular Roman Semibold").split(" ");
}
