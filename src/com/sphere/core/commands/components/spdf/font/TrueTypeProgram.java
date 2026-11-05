package com.sphere.components.spdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A TrueType font program (or a TrueType collection member): outlines from
 * glyf/loca, composite glyphs included, advances from hmtx, the cmap
 * subtables (formats 0, 4, 6, 12), glyph names from post, names and metrics
 * from name, OS/2, head and hhea. It also writes subsets for embedding: the
 * glyph ids stay the same, the glyphs not used are emptied.
 */
public final class TrueTypeProgram {

    private final byte[] d;
    private final Map<String, int[]> tables = new HashMap<>();
    public int unitsPerEm = 1000;
    public int numGlyphs;
    private int locFormat;
    private int[] loca;
    private int glyfOff = -1;
    private int glyfLen;
    private int[] advances = new int[0];
    private final Map<Long, Map<Integer, Integer>> cmaps = new HashMap<>();
    private String[] postNames;
    private Map<String, Integer> nameToGid;
    public String family = "";
    public String subfamily = "";
    public String fullName = "";
    public String postscriptName = "";
    public int weightClass = 400;
    public boolean italic;
    public boolean bold;
    public int ascender;
    public int descender;
    public int capHeight;
    public int xHeight;
    public float italicAngle;
    public int[] bbox = new int[4];
    private final Map<Integer, GeneralPath> cache = new HashMap<>();

    private TrueTypeProgram(byte[] data) {
        this.d = data;
    }

    /** Reads a font; for a collection, the member at an index. Throws on a file that is not TrueType. */
    public static TrueTypeProgram parse(byte[] data, int index) {
        TrueTypeProgram t = new TrueTypeProgram(data);
        t.read(index);
        return t;
    }

    /** The number of fonts of a collection (1 for a plain font). */
    public static int collectionSize(byte[] data) {
        if (data.length > 12 && data[0] == 't' && data[1] == 't' && data[2] == 'c' && data[3] == 'f') {
            return (int) u32(data, 8);
        }
        return 1;
    }

    private void read(int index) {
        int base = 0;
        if (collectionSize(d) > 1 || (d.length > 4 && d[0] == 't' && d[1] == 't' && d[2] == 'c' && d[3] == 'f')) {
            int n = (int) u32(d, 8);
            base = (int) u32(d, 12 + 4 * Math.max(0, Math.min(index, n - 1)));
        }
        int numTables = u16(base + 4);
        for (int i = 0; i < numTables; i++) {
            int r = base + 12 + 16 * i;
            String tag = new String(d, r, 4, StandardCharsets.ISO_8859_1);
            int off = (int) u32(d, r + 8);
            int len = (int) u32(d, r + 12);
            if (off >= 0 && off <= d.length) {
                tables.put(tag, new int[] { off, (int) Math.min(len, (long) d.length - off) });
            }
        }
        int[] head = tables.get("head");
        if (head != null) {
            unitsPerEm = u16(head[0] + 18);
            if (unitsPerEm < 16 || unitsPerEm > 16384) {
                unitsPerEm = 1000;
            }
            bbox = new int[] { s16(head[0] + 36), s16(head[0] + 38), s16(head[0] + 40), s16(head[0] + 42) };
            int macStyle = u16(head[0] + 44);
            bold = (macStyle & 1) != 0;
            italic = (macStyle & 2) != 0;
            locFormat = s16(head[0] + 50);
        }
        int[] maxp = tables.get("maxp");
        if (maxp != null) {
            numGlyphs = u16(maxp[0] + 4);
        }
        int[] loc = tables.get("loca");
        int[] glyf = tables.get("glyf");
        if (loc != null && glyf != null) {
            glyfOff = glyf[0];
            glyfLen = glyf[1];
            int n = locFormat == 0 ? loc[1] / 2 : loc[1] / 4;
            if (numGlyphs == 0) {
                numGlyphs = Math.max(0, n - 1);
            }
            n = Math.min(n, numGlyphs + 1);
            loca = new int[n];
            for (int i = 0; i < n; i++) {
                loca[i] = locFormat == 0 ? u16(loc[0] + 2 * i) * 2 : (int) u32(d, loc[0] + 4 * i);
            }
        }
        int[] hhea = tables.get("hhea");
        int[] hmtx = tables.get("hmtx");
        if (hhea != null) {
            ascender = s16(hhea[0] + 4);
            descender = s16(hhea[0] + 6);
            if (hmtx != null) {
                int nh = Math.min(u16(hhea[0] + 34), hmtx[1] / 4);
                advances = new int[Math.max(nh, numGlyphs)];
                int last = 0;
                for (int i = 0; i < advances.length; i++) {
                    if (i < nh) {
                        last = u16(hmtx[0] + 4 * i);
                    }
                    advances[i] = last;
                }
            }
        }
        int[] os2 = tables.get("OS/2");
        if (os2 != null && os2[1] >= 78) {
            weightClass = u16(os2[0] + 4);
            int fs = u16(os2[0] + 62);
            italic |= (fs & 1) != 0;
            bold |= (fs & 32) != 0;
            int typoAsc = s16(os2[0] + 68);
            int typoDesc = s16(os2[0] + 70);
            if (ascender == 0) {
                ascender = typoAsc;
                descender = typoDesc;
            }
            if (os2[1] >= 96 && u16(os2[0]) >= 2) {
                xHeight = s16(os2[0] + 86);
                capHeight = s16(os2[0] + 88);
            }
        }
        if (weightClass >= 600) {
            bold = true;
        }
        int[] post = tables.get("post");
        if (post != null && post[1] >= 8) {
            italicAngle = s16(post[0] + 4) + u16(post[0] + 6) / 65536f;
        }
        readNames();
        if (capHeight == 0) {
            capHeight = (int) (unitsPerEm * 0.7);
        }
    }

    private int u16(int p) {
        if (p < 0 || p + 1 >= d.length) {
            return 0;
        }
        return ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
    }

    private int s16(int p) {
        return (short) u16(p);
    }

    private static long u32(byte[] d, int p) {
        if (p < 0 || p + 3 >= d.length) {
            return 0;
        }
        return ((long) (d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16) | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
    }

    public boolean hasTable(String tag) {
        return tables.containsKey(tag);
    }

    public boolean hasOutlines() {
        return loca != null && glyfOff >= 0;
    }

    /* ------------------------------------------------------------- names */

    private void readNames() {
        int[] name = tables.get("name");
        if (name == null) {
            return;
        }
        int p = name[0];
        int count = u16(p + 2);
        int strings = p + u16(p + 4);
        String[] best = new String[18];
        int[] bestScore = new int[18];
        for (int i = 0; i < count; i++) {
            int r = p + 6 + 12 * i;
            int platform = u16(r);
            int encoding = u16(r + 2);
            int lang = u16(r + 4);
            int nameId = u16(r + 6);
            int len = u16(r + 8);
            int off = strings + u16(r + 10);
            if (nameId >= best.length || off + len > d.length) {
                continue;
            }
            String s;
            int score;
            if (platform == 3 || platform == 0) {
                s = new String(d, off, len, StandardCharsets.UTF_16BE);
                score = (platform == 3 && lang == 0x409) ? 3 : 2;
            } else if (platform == 1 && encoding == 0) {
                s = new String(d, off, len, StandardCharsets.ISO_8859_1);
                score = 1;
            } else {
                continue;
            }
            if (score > bestScore[nameId]) {
                best[nameId] = s;
                bestScore[nameId] = score;
            }
        }
        family = best[16] != null ? best[16] : best[1] != null ? best[1] : "";
        subfamily = best[17] != null ? best[17] : best[2] != null ? best[2] : "";
        fullName = best[4] != null ? best[4] : family;
        postscriptName = best[6] != null ? best[6] : "";
    }

    /* ------------------------------------------------------------- metrics */

    public int advance(int gid) {
        if (gid < 0 || advances.length == 0) {
            return 0;
        }
        return advances[Math.min(gid, advances.length - 1)];
    }

    /** An advance in thousandths of an em. */
    public float advance1000(int gid) {
        return advance(gid) * 1000f / unitsPerEm;
    }

    /* ------------------------------------------------------------- cmap */

    /** A cmap subtable as code to glyph id, or null. */
    public Map<Integer, Integer> cmap(int platform, int encoding) {
        long key = ((long) platform << 16) | encoding;
        if (cmaps.containsKey(key)) {
            return cmaps.get(key);
        }
        Map<Integer, Integer> map = null;
        int[] cm = tables.get("cmap");
        if (cm != null) {
            int p = cm[0];
            int n = u16(p + 2);
            for (int i = 0; i < n && map == null; i++) {
                int r = p + 4 + 8 * i;
                if (u16(r) == platform && u16(r + 2) == encoding) {
                    map = readSubtable(p + (int) u32(d, r + 4));
                }
            }
        }
        cmaps.put(key, map);
        return map;
    }

    /** The platform/encoding pairs present. */
    public List<int[]> cmapIds() {
        List<int[]> out = new ArrayList<>();
        int[] cm = tables.get("cmap");
        if (cm != null) {
            int n = u16(cm[0] + 2);
            for (int i = 0; i < n; i++) {
                int r = cm[0] + 4 + 8 * i;
                out.add(new int[] { u16(r), u16(r + 2) });
            }
        }
        return out;
    }

    private Map<Integer, Integer> readSubtable(int p) {
        Map<Integer, Integer> m = new HashMap<>();
        int format = u16(p);
        try {
            switch (format) {
                case 0:
                    for (int c = 0; c < 256; c++) {
                        int g = d[p + 6 + c] & 0xff;
                        if (g != 0) {
                            m.put(c, g);
                        }
                    }
                    break;
                case 4: {
                    int segX2 = u16(p + 6);
                    int ends = p + 14;
                    int starts = ends + segX2 + 2;
                    int deltas = starts + segX2;
                    int ranges = deltas + segX2;
                    for (int s = 0; s < segX2 / 2; s++) {
                        int end = u16(ends + 2 * s);
                        int start = u16(starts + 2 * s);
                        int delta = s16(deltas + 2 * s);
                        int ro = u16(ranges + 2 * s);
                        if (start > end || end - start > 65535) {
                            continue;
                        }
                        for (int c = start; c <= end && c != 0xFFFF; c++) {
                            int g;
                            if (ro == 0) {
                                g = (c + delta) & 0xFFFF;
                            } else {
                                int gp = ranges + 2 * s + ro + 2 * (c - start);
                                g = u16(gp);
                                if (g != 0) {
                                    g = (g + delta) & 0xFFFF;
                                }
                            }
                            if (g != 0) {
                                m.put(c, g);
                            }
                        }
                    }
                    break;
                }
                case 6: {
                    int first = u16(p + 6);
                    int count = u16(p + 8);
                    for (int i = 0; i < count; i++) {
                        int g = u16(p + 10 + 2 * i);
                        if (g != 0) {
                            m.put(first + i, g);
                        }
                    }
                    break;
                }
                case 12: {
                    long groups = u32(d, p + 12);
                    for (long i = 0; i < groups && i < 100000; i++) {
                        int r = p + 16 + (int) (12 * i);
                        long sc = u32(d, r);
                        long ec = u32(d, r + 4);
                        long sg = u32(d, r + 8);
                        for (long c = sc; c <= ec && c - sc < 70000; c++) {
                            m.put((int) c, (int) (sg + c - sc));
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (RuntimeException e) {
            // a broken subtable maps what it could
        }
        return m;
    }

    /** The glyph of a Unicode character through the Unicode cmaps, or 0. */
    public int gidForUnicode(int u) {
        for (int[] id : new int[][] { { 3, 10 }, { 3, 1 }, { 0, 4 }, { 0, 3 }, { 0, 6 }, { 0, 0 }, { 0, 1 } }) {
            Map<Integer, Integer> m = cmap(id[0], id[1]);
            if (m != null) {
                Integer g = m.get(u);
                if (g != null && g > 0) {
                    return g;
                }
            }
        }
        return 0;
    }

    /** Every Unicode character the font maps, with its glyph. */
    public Map<Integer, Integer> unicodeMap() {
        for (int[] id : new int[][] { { 3, 10 }, { 3, 1 }, { 0, 4 }, { 0, 3 } }) {
            Map<Integer, Integer> m = cmap(id[0], id[1]);
            if (m != null && !m.isEmpty()) {
                return m;
            }
        }
        return Map.of();
    }

    /* ------------------------------------------------------------- post names */

    private static final String[] MAC_NAMES = (".notdef .null nonmarkingreturn space exclam quotedbl numbersign dollar "
        + "percent ampersand quotesingle parenleft parenright asterisk plus comma hyphen period slash zero one two "
        + "three four five six seven eight nine colon semicolon less equal greater question at A B C D E F G H I J K "
        + "L M N O P Q R S T U V W X Y Z bracketleft backslash bracketright asciicircum underscore grave a b c d e f "
        + "g h i j k l m n o p q r s t u v w x y z braceleft bar braceright asciitilde Adieresis Aring Ccedilla "
        + "Eacute Ntilde Odieresis Udieresis aacute agrave acircumflex adieresis atilde aring ccedilla eacute egrave "
        + "ecircumflex edieresis iacute igrave icircumflex idieresis ntilde oacute ograve ocircumflex odieresis "
        + "otilde uacute ugrave ucircumflex udieresis dagger degree cent sterling section bullet paragraph "
        + "germandbls registered copyright trademark acute dieresis notequal AE Oslash infinity plusminus lessequal "
        + "greaterequal yen mu partialdiff summation product pi integral ordfeminine ordmasculine Omega ae oslash "
        + "questiondown exclamdown logicalnot radical florin approxequal Delta guillemotleft guillemotright ellipsis "
        + "nonbreakingspace Agrave Atilde Otilde OE oe endash emdash quotedblleft quotedblright quoteleft quoteright "
        + "divide lozenge ydieresis Ydieresis fraction currency guilsinglleft guilsinglright fi fl daggerdbl "
        + "periodcentered quotesinglbase quotedblbase perthousand Acircumflex Ecircumflex Aacute Edieresis Egrave "
        + "Iacute Icircumflex Idieresis Igrave Oacute Ocircumflex apple Ograve Uacute Ucircumflex Ugrave dotlessi "
        + "circumflex tilde macron breve dotaccent ring cedilla hungarumlaut ogonek caron Lslash lslash Scaron "
        + "scaron Zcaron zcaron brokenbar Eth eth Yacute yacute Thorn thorn minus multiply onesuperior twosuperior "
        + "threesuperior onehalf onequarter threequarters franc Gbreve gbreve Idotaccent Scedilla scedilla Cacute "
        + "cacute Ccaron ccaron dcroat").split(" ");

    private void readPost() {
        if (postNames != null) {
            return;
        }
        postNames = new String[0];
        int[] post = tables.get("post");
        if (post == null || post[1] < 32) {
            return;
        }
        int p = post[0];
        long format = u32(d, p);
        if (format == 0x00010000L) {
            postNames = Arrays.copyOf(MAC_NAMES, Math.min(MAC_NAMES.length, Math.max(numGlyphs, 1)));
        } else if (format == 0x00020000L) {
            int n = u16(p + 32);
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) {
                idx[i] = u16(p + 34 + 2 * i);
            }
            List<String> extra = new ArrayList<>();
            int q = p + 34 + 2 * n;
            int end = p + post[1];
            while (q < end && q < d.length) {
                int len = d[q] & 0xff;
                if (q + 1 + len > d.length) {
                    break;
                }
                extra.add(new String(d, q + 1, len, StandardCharsets.ISO_8859_1));
                q += 1 + len;
            }
            postNames = new String[n];
            for (int i = 0; i < n; i++) {
                int k = idx[i];
                if (k < 258) {
                    postNames[i] = MAC_NAMES[k];
                } else if (k - 258 < extra.size()) {
                    postNames[i] = extra.get(k - 258);
                }
            }
        }
    }

    public String glyphName(int gid) {
        readPost();
        return gid >= 0 && gid < postNames.length ? postNames[gid] : null;
    }

    public int gidForName(String name) {
        if (nameToGid == null) {
            readPost();
            nameToGid = new HashMap<>();
            for (int i = postNames.length - 1; i >= 0; i--) {
                if (postNames[i] != null) {
                    nameToGid.put(postNames[i], i);
                }
            }
        }
        Integer g = nameToGid.get(name);
        return g == null ? 0 : g;
    }

    public boolean hasGlyphNames() {
        readPost();
        return postNames.length > 0;
    }

    /* ------------------------------------------------------------- outlines */

    /** The outline of a glyph in font units, y up; an empty path when it has none. */
    public synchronized GeneralPath glyph(int gid) {
        GeneralPath p = cache.get(gid);
        if (p == null) {
            p = new GeneralPath(Path2D.WIND_NON_ZERO);
            try {
                appendGlyph(p, gid, new AffineTransform(), 0);
            } catch (RuntimeException e) {
                // a damaged glyph draws as nothing
            }
            cache.put(gid, p);
        }
        return p;
    }

    /** True when the glyph has data (a subset keeps only some glyphs). */
    public boolean hasGlyph(int gid) {
        if (loca == null || gid < 0 || gid + 1 >= loca.length) {
            return false;
        }
        return loca[gid + 1] > loca[gid];
    }

    private void appendGlyph(GeneralPath path, int gid, AffineTransform at, int depth) {
        if (loca == null || gid < 0 || gid + 1 >= loca.length || depth > 8) {
            return;
        }
        int off = loca[gid];
        int end = loca[gid + 1];
        if (end <= off || off >= glyfLen) {
            return;
        }
        int p = glyfOff + off;
        int nc = s16(p);
        if (nc >= 0) {
            appendSimple(path, p, nc, at);
        } else {
            appendComposite(path, p, at, depth);
        }
    }

    private void appendSimple(GeneralPath path, int p, int nc, AffineTransform at) {
        if (nc == 0) {
            return;
        }
        int[] endPts = new int[nc];
        for (int i = 0; i < nc; i++) {
            endPts[i] = u16(p + 10 + 2 * i);
        }
        int n = endPts[nc - 1] + 1;
        if (n <= 0 || n > 65535) {
            return;
        }
        int q = p + 10 + 2 * nc;
        int instr = u16(q);
        q += 2 + instr;
        byte[] flags = new byte[n];
        for (int i = 0; i < n; ) {
            byte f = d[q++];
            flags[i++] = f;
            if ((f & 8) != 0) {
                int rep = d[q++] & 0xff;
                for (int k = 0; k < rep && i < n; k++) {
                    flags[i++] = f;
                }
            }
        }
        double[] xs = new double[n];
        double[] ys = new double[n];
        int v = 0;
        for (int i = 0; i < n; i++) {
            int f = flags[i];
            if ((f & 2) != 0) {
                int dx = d[q++] & 0xff;
                v += (f & 16) != 0 ? dx : -dx;
            } else if ((f & 16) == 0) {
                v += s16(q);
                q += 2;
            }
            xs[i] = v;
        }
        v = 0;
        for (int i = 0; i < n; i++) {
            int f = flags[i];
            if ((f & 4) != 0) {
                int dy = d[q++] & 0xff;
                v += (f & 32) != 0 ? dy : -dy;
            } else if ((f & 32) == 0) {
                v += s16(q);
                q += 2;
            }
            ys[i] = v;
        }
        double[] pt = new double[2];
        int start = 0;
        for (int c = 0; c < nc; c++) {
            int last = endPts[c];
            if (last < start || last >= n) {
                break;
            }
            contour(path, xs, ys, flags, start, last, at, pt);
            start = last + 1;
        }
    }

    private static void contour(GeneralPath path, double[] xs, double[] ys, byte[] flags, int s, int e,
                                AffineTransform at, double[] tmp) {
        int count = e - s + 1;
        if (count < 2) {
            return;
        }
        int firstOn = -1;
        for (int i = s; i <= e; i++) {
            if ((flags[i] & 1) != 0) {
                firstOn = i;
                break;
            }
        }
        double sx;
        double sy;
        int begin;
        if (firstOn < 0) {
            sx = (xs[e] + xs[s]) / 2;
            sy = (ys[e] + ys[s]) / 2;
            begin = s - 1;
        } else {
            sx = xs[firstOn];
            sy = ys[firstOn];
            begin = firstOn;
        }
        move(path, at, sx, sy, tmp);
        boolean prevOff = false;
        double cx = 0;
        double cy = 0;
        for (int k = 1; k <= count; k++) {
            int i = s + Math.floorMod(begin - s + k, count);
            boolean on = (flags[i] & 1) != 0;
            double x = xs[i];
            double y = ys[i];
            if (firstOn < 0 && k == count) {
                // the implied start closes the all-off-curve contour below
                if (prevOff) {
                    quad(path, at, cx, cy, (cx + x) / 2, (cy + y) / 2, tmp);
                }
                cx = x;
                cy = y;
                prevOff = true;
                break;
            }
            if (on) {
                if (prevOff) {
                    quad(path, at, cx, cy, x, y, tmp);
                } else {
                    line(path, at, x, y, tmp);
                }
                prevOff = false;
            } else {
                if (prevOff) {
                    quad(path, at, cx, cy, (cx + x) / 2, (cy + y) / 2, tmp);
                }
                cx = x;
                cy = y;
                prevOff = true;
            }
        }
        if (prevOff) {
            quad(path, at, cx, cy, sx, sy, tmp);
        }
        path.closePath();
    }

    private static void move(GeneralPath p, AffineTransform at, double x, double y, double[] t) {
        t[0] = x;
        t[1] = y;
        at.transform(t, 0, t, 0, 1);
        p.moveTo(t[0], t[1]);
    }

    private static void line(GeneralPath p, AffineTransform at, double x, double y, double[] t) {
        t[0] = x;
        t[1] = y;
        at.transform(t, 0, t, 0, 1);
        p.lineTo(t[0], t[1]);
    }

    private static void quad(GeneralPath p, AffineTransform at, double cx, double cy, double x, double y, double[] t) {
        double[] q = { cx, cy, x, y };
        at.transform(q, 0, q, 0, 2);
        p.quadTo(q[0], q[1], q[2], q[3]);
    }

    private void appendComposite(GeneralPath path, int p, AffineTransform at, int depth) {
        int q = p + 10;
        while (true) {
            int flags = u16(q);
            int gid = u16(q + 2);
            q += 4;
            double dx;
            double dy;
            if ((flags & 1) != 0) {
                dx = (flags & 2) != 0 ? s16(q) : u16(q);
                dy = (flags & 2) != 0 ? s16(q + 2) : u16(q + 2);
                q += 4;
            } else {
                dx = (flags & 2) != 0 ? (byte) d[q] : d[q] & 0xff;
                dy = (flags & 2) != 0 ? (byte) d[q + 1] : d[q + 1] & 0xff;
                q += 2;
            }
            if ((flags & 2) == 0) {
                // point matching: rare, approximated by no offset
                dx = 0;
                dy = 0;
            }
            double a = 1;
            double b = 0;
            double c = 0;
            double e = 1;
            if ((flags & 8) != 0) {
                a = e = f2dot14(q);
                q += 2;
            } else if ((flags & 0x40) != 0) {
                a = f2dot14(q);
                e = f2dot14(q + 2);
                q += 4;
            } else if ((flags & 0x80) != 0) {
                a = f2dot14(q);
                b = f2dot14(q + 2);
                c = f2dot14(q + 4);
                e = f2dot14(q + 6);
                q += 8;
            }
            AffineTransform t = new AffineTransform(at);
            t.concatenate(new AffineTransform(a, b, c, e, dx, dy));
            appendGlyph(path, gid, t, depth + 1);
            if ((flags & 0x20) == 0) {
                break;
            }
        }
    }

    private double f2dot14(int p) {
        return s16(p) / 16384.0;
    }

    /** The glyphs a composite glyph is made of. */
    private void components(int gid, Set<Integer> out, int depth) {
        if (loca == null || gid < 0 || gid + 1 >= loca.length || depth > 8) {
            return;
        }
        int off = loca[gid];
        if (loca[gid + 1] <= off) {
            return;
        }
        int p = glyfOff + off;
        if (s16(p) >= 0) {
            return;
        }
        int q = p + 10;
        while (true) {
            int flags = u16(q);
            int g = u16(q + 2);
            if (out.add(g)) {
                components(g, out, depth + 1);
            }
            q += 4 + ((flags & 1) != 0 ? 4 : 2);
            if ((flags & 8) != 0) {
                q += 2;
            } else if ((flags & 0x40) != 0) {
                q += 4;
            } else if ((flags & 0x80) != 0) {
                q += 8;
            }
            if ((flags & 0x20) == 0) {
                break;
            }
        }
    }

    /* ------------------------------------------------------------- subsetting */

    /**
     * A copy of the font keeping only some glyphs' outlines (and the parts of
     * the composites among them). Glyph ids do not change, so a CIDFontType2
     * with an Identity CIDToGIDMap uses it as is. Tables a PDF consumer does
     * not need (layout, kerning, names, cmap) are left out.
     */
    public byte[] subset(Set<Integer> keep) {
        if (loca == null) {
            return d;
        }
        Set<Integer> all = new HashSet<>(keep);
        all.add(0);
        for (int g : new ArrayList<>(all)) {
            components(g, all, 0);
        }
        int n = loca.length - 1;
        com.sphere.components.spdf.cos.ByteBuf glyf = new com.sphere.components.spdf.cos.ByteBuf(glyfLen / 4 + 64);
        int[] newLoca = new int[n + 1];
        for (int g = 0; g < n; g++) {
            newLoca[g] = glyf.size();
            if (all.contains(g) && loca[g + 1] > loca[g] && loca[g + 1] <= glyfLen) {
                glyf.put(d, glyfOff + loca[g], loca[g + 1] - loca[g]);
                while ((glyf.size() & 3) != 0) {
                    glyf.put(0);
                }
            }
        }
        newLoca[n] = glyf.size();
        com.sphere.components.spdf.cos.ByteBuf locaBuf = new com.sphere.components.spdf.cos.ByteBuf(4 * (n + 1));
        for (int v : newLoca) {
            locaBuf.put(v >>> 24).put(v >>> 16).put(v >>> 8).put(v);
        }
        TreeMap<String, byte[]> out = new TreeMap<>();
        for (String tag : new String[] { "head", "hhea", "hmtx", "maxp", "cvt ", "fpgm", "prep", "OS/2" }) {
            int[] t = tables.get(tag);
            if (t != null) {
                out.put(tag, Arrays.copyOfRange(d, t[0], t[0] + t[1]));
            }
        }
        byte[] head = out.get("head");
        if (head != null && head.length >= 54) {
            head[8] = head[9] = head[10] = head[11] = 0;
            head[50] = 0;
            head[51] = 1;
        }
        // post format 3: no names
        byte[] post = new byte[32];
        int[] pt = tables.get("post");
        if (pt != null && pt[1] >= 32) {
            System.arraycopy(d, pt[0], post, 0, 32);
        }
        post[0] = 0;
        post[1] = 3;
        post[2] = 0;
        post[3] = 0;
        out.put("post", post);
        out.put("glyf", glyf.toBytes());
        out.put("loca", locaBuf.toBytes());
        return assemble(out);
    }

    private static byte[] assemble(TreeMap<String, byte[]> tabs) {
        int n = tabs.size();
        int pow = 1;
        int log = 0;
        while (pow * 2 <= n) {
            pow *= 2;
            log++;
        }
        com.sphere.components.spdf.cos.ByteBuf b = new com.sphere.components.spdf.cos.ByteBuf(1 << 16);
        b.put(0).put(1).put(0).put(0);
        b.put(n >> 8).put(n);
        b.put((pow * 16) >> 8).put(pow * 16);
        b.put(log >> 8).put(log);
        b.put((n * 16 - pow * 16) >> 8).put(n * 16 - pow * 16);
        int offset = 12 + 16 * n;
        for (Map.Entry<String, byte[]> e : tabs.entrySet()) {
            byte[] t = e.getValue();
            long sum = checksum(t);
            b.put(e.getKey());
            b.put((int) (sum >>> 24)).put((int) (sum >>> 16)).put((int) (sum >>> 8)).put((int) sum);
            b.put(offset >>> 24).put(offset >>> 16).put(offset >>> 8).put(offset);
            b.put(t.length >>> 24).put(t.length >>> 16).put(t.length >>> 8).put(t.length);
            offset += (t.length + 3) & ~3;
        }
        for (byte[] t : tabs.values()) {
            b.put(t);
            for (int k = t.length; (k & 3) != 0; k++) {
                b.put(0);
            }
        }
        return b.toBytes();
    }

    private static long checksum(byte[] t) {
        long sum = 0;
        for (int i = 0; i < t.length; i += 4) {
            long v = 0;
            for (int k = 0; k < 4; k++) {
                v = (v << 8) | (i + k < t.length ? t[i + k] & 0xff : 0);
            }
            sum = (sum + v) & 0xFFFFFFFFL;
        }
        return sum;
    }
}
