package com.sphere.components.spdf.font;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfLexer;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A CMap (9.7.5, 9.10.3): the codespace that splits a string into codes, and
 * what each code means: a CID for a composite font's encoding, Unicode text
 * for a ToUnicode map. Identity-H and Identity-V are built in; other
 * predefined CJK CMaps are read as Identity (their tables are not carried).
 */
public final class CMap {

    private record Range(byte[] lo, byte[] hi) {
        int len() {
            return lo.length;
        }

        boolean contains(byte[] s, int pos) {
            for (int i = 0; i < lo.length; i++) {
                int b = s[pos + i] & 0xff;
                if (b < (lo[i] & 0xff) || b > (hi[i] & 0xff)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Range> codespace = new ArrayList<>();
    private final Map<Integer, Integer> cids = new HashMap<>();
    private final List<int[]> cidRanges = new ArrayList<>();
    private final Map<Integer, String> unicode = new HashMap<>();
    private boolean identity;
    private int minLen = 4;
    private int maxLen = 1;
    public boolean vertical;
    public String name = "";

    private CMap() {
    }

    public static CMap identity(boolean vertical) {
        CMap m = new CMap();
        m.identity = true;
        m.vertical = vertical;
        m.name = vertical ? "Identity-V" : "Identity-H";
        m.codespace.add(new Range(new byte[] { 0, 0 }, new byte[] { (byte) 0xff, (byte) 0xff }));
        m.minLen = 2;
        m.maxLen = 2;
        return m;
    }

    /** A predefined CMap by name: Identity, or (approximated as) Identity for the CJK ones. */
    public static CMap predefined(String name) {
        CMap m = identity(name != null && name.endsWith("-V"));
        if (name != null) {
            m.name = name;
        }
        return m;
    }

    public static CMap parse(byte[] data) {
        CMap m = new CMap();
        PdfLexer lx = new PdfLexer(data);
        List<Object> stack = new ArrayList<>();
        while (true) {
            Object t = lx.next();
            if (t == PdfLexer.Tok.EOF) {
                break;
            }
            if (t == PdfLexer.Tok.ARRAY_START) {
                PdfArray a = new PdfArray();
                while (true) {
                    Object x = lx.next();
                    if (x == PdfLexer.Tok.ARRAY_END || x == PdfLexer.Tok.EOF) {
                        break;
                    }
                    if (x instanceof PdfString s) {
                        a.add(s);
                    } else if (x instanceof PdfNumber n) {
                        a.add(n);
                    } else if (x instanceof PdfName n) {
                        a.add(n);
                    }
                }
                stack.add(a);
                continue;
            }
            if (!(t instanceof String kw)) {
                stack.add(t);
                if (stack.size() > 4096) {
                    stack.subList(0, 2048).clear();
                }
                continue;
            }
            switch (kw) {
                case "begincodespacerange":
                    stack.clear();
                    while (true) {
                        Object a = lx.next();
                        if (!(a instanceof PdfString lo)) {
                            break;
                        }
                        Object b = lx.next();
                        if (b instanceof PdfString hi && lo.getBytes().length > 0
                            && lo.getBytes().length == hi.getBytes().length) {
                            m.codespace.add(new Range(lo.getBytes(), hi.getBytes()));
                            m.minLen = Math.min(m.minLen, lo.getBytes().length);
                            m.maxLen = Math.max(m.maxLen, lo.getBytes().length);
                        }
                    }
                    break;
                case "begincidrange":
                    stack.clear();
                    while (true) {
                        Object a = lx.next();
                        if (!(a instanceof PdfString lo)) {
                            break;
                        }
                        Object b = lx.next();
                        Object c = lx.next();
                        if (b instanceof PdfString hi && c instanceof PdfNumber n) {
                            m.cidRanges.add(new int[] { toInt(lo.getBytes()), toInt(hi.getBytes()), n.intValue(),
                                                        lo.getBytes().length });
                            m.noteLength(lo.getBytes().length);
                        }
                    }
                    break;
                case "begincidchar":
                    stack.clear();
                    while (true) {
                        Object a = lx.next();
                        if (!(a instanceof PdfString code)) {
                            break;
                        }
                        Object b = lx.next();
                        if (b instanceof PdfNumber n) {
                            m.cids.put(toInt(code.getBytes()), n.intValue());
                            m.noteLength(code.getBytes().length);
                        }
                    }
                    break;
                case "beginbfchar":
                    stack.clear();
                    while (true) {
                        Object a = lx.next();
                        if (!(a instanceof PdfString code)) {
                            break;
                        }
                        Object b = lx.next();
                        m.noteLength(code.getBytes().length);
                        if (b instanceof PdfString dst) {
                            m.unicode.put(toInt(code.getBytes()), utf16(dst.getBytes()));
                        } else if (b instanceof PdfName n) {
                            String u = GlyphNames.toUnicode(n.name);
                            if (u != null) {
                                m.unicode.put(toInt(code.getBytes()), u);
                            }
                        }
                    }
                    break;
                case "beginbfrange":
                    stack.clear();
                    while (true) {
                        Object a = lx.next();
                        if (!(a instanceof PdfString lo)) {
                            break;
                        }
                        Object b = lx.next();
                        Object c = lx.next();
                        if (!(b instanceof PdfString hi)) {
                            continue;
                        }
                        int l = toInt(lo.getBytes());
                        int h = toInt(hi.getBytes());
                        m.noteLength(lo.getBytes().length);
                        if (h < l || h - l > 65535) {
                            continue;
                        }
                        if (c instanceof PdfString dst) {
                            byte[] d = dst.getBytes().clone();
                            for (int code = l; code <= h; code++) {
                                m.unicode.put(code, utf16(d));
                                increment(d);
                            }
                        } else if (c == PdfLexer.Tok.ARRAY_START) {
                            int code = l;
                            while (true) {
                                Object x = lx.next();
                                if (x == PdfLexer.Tok.ARRAY_END || x == PdfLexer.Tok.EOF) {
                                    break;
                                }
                                if (x instanceof PdfString dst && code <= h) {
                                    m.unicode.put(code, utf16(dst.getBytes()));
                                }
                                code++;
                            }
                        }
                    }
                    break;
                case "usecmap":
                    if (!stack.isEmpty() && stack.get(stack.size() - 1) instanceof PdfName n) {
                        CMap base = predefined(n.name);
                        if (m.codespace.isEmpty()) {
                            m.codespace.addAll(base.codespace);
                            m.minLen = base.minLen;
                            m.maxLen = base.maxLen;
                        }
                        m.identity = m.cids.isEmpty() && m.cidRanges.isEmpty();
                    }
                    stack.clear();
                    break;
                case "def":
                    if (stack.size() >= 2) {
                        Object k = stack.get(stack.size() - 2);
                        Object v = stack.get(stack.size() - 1);
                        if (k instanceof PdfName kn && kn.name.equals("WMode") && v instanceof PdfNumber n) {
                            m.vertical = n.intValue() == 1;
                        }
                        if (k instanceof PdfName kn && kn.name.equals("CMapName") && v instanceof PdfName n) {
                            m.name = n.name;
                        }
                    }
                    stack.clear();
                    break;
                default:
                    stack.clear();
                    break;
            }
        }
        if (m.codespace.isEmpty()) {
            // no codespace: guess the code length from the mappings (1 byte, else 2)
            int len = m.maxLen <= 1 && m.minLen <= 1 ? 1 : 2;
            if (m.unicode.isEmpty() && m.cids.isEmpty() && m.cidRanges.isEmpty()) {
                len = 2;
            }
            byte[] lo = new byte[len];
            byte[] hi = new byte[len];
            java.util.Arrays.fill(hi, (byte) 0xff);
            m.codespace.add(new Range(lo, hi));
            m.minLen = len;
            m.maxLen = len;
        }
        return m;
    }

    private void noteLength(int len) {
        if (codespace.isEmpty()) {
            minLen = Math.min(minLen, len);
            maxLen = Math.max(maxLen, len);
        }
    }

    private static int toInt(byte[] b) {
        int v = 0;
        for (int i = 0; i < b.length && i < 4; i++) {
            v = (v << 8) | (b[i] & 0xff);
        }
        return v;
    }

    private static void increment(byte[] d) {
        for (int i = d.length - 1; i >= 0; i--) {
            d[i]++;
            if (d[i] != 0) {
                break;
            }
        }
    }

    private static String utf16(byte[] b) {
        if (b.length == 1) {
            return String.valueOf((char) (b[0] & 0xff));
        }
        if (b.length % 2 == 1) {
            b = java.util.Arrays.copyOf(b, b.length + 1);
        }
        return new String(b, StandardCharsets.UTF_16BE);
    }

    /** How many bytes the code starting at a position takes. */
    public int codeLength(byte[] s, int pos) {
        int avail = s.length - pos;
        for (int n = 1; n <= 4 && n <= avail; n++) {
            for (Range r : codespace) {
                if (r.len() == n && r.contains(s, pos)) {
                    return n;
                }
            }
        }
        return Math.max(1, Math.min(minLen, avail));
    }

    /** The CID of a code (a composite font's encoding). */
    public int toCID(int code) {
        if (identity) {
            return code;
        }
        Integer c = cids.get(code);
        if (c != null) {
            return c;
        }
        for (int[] r : cidRanges) {
            if (code >= r[0] && code <= r[1]) {
                return r[2] + (code - r[0]);
            }
        }
        return 0;
    }

    /** The Unicode text of a code (a ToUnicode map), or null. */
    public String toUnicode(int code) {
        return unicode.get(code);
    }

    public boolean hasUnicode() {
        return !unicode.isEmpty();
    }

    public Map<Integer, String> unicodeMap() {
        return unicode;
    }

    public boolean isIdentity() {
        return identity;
    }

    /** The codes of the CIDs this encoding maps to, for writing text back: CID to code. */
    public Map<Integer, Integer> cidToCode() {
        Map<Integer, Integer> out = new HashMap<>();
        for (int[] r : cidRanges) {
            for (int code = r[0]; code <= r[1] && code - r[0] < 65536; code++) {
                out.putIfAbsent(r[2] + (code - r[0]), code);
            }
        }
        for (Map.Entry<Integer, Integer> e : cids.entrySet()) {
            out.putIfAbsent(e.getValue(), e.getKey());
        }
        return out;
    }

    public int maxCodeLength() {
        return maxLen;
    }
}
