package com.sphere.components.spdf.font;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfStream;
import java.awt.Font;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.HashMap;
import java.util.Map;

/**
 * A composite font (Type 0): a CMap splits strings into codes and maps them
 * to CIDs; the descendant CIDFont (CFF-based CIDFontType0 or TrueType-based
 * CIDFontType2) gives widths (/W, /DW, /W2 for vertical writing) and glyphs
 * (through /CIDToGIDMap for TrueType). Most modern PDFs (Word, LibreOffice,
 * browsers, LaTeX with XeTeX/LuaTeX) write their text with these.
 */
public final class Type0Font extends PdfFont {

    private CMap encoding;
    private TrueTypeProgram tt;
    private CffProgram cff;
    private int[] cidToGid;
    private float dw = 1f;
    private final Map<Integer, Float> w = new HashMap<>();
    private float dw2Vy = 0.88f;
    private float dw2W1 = -1f;
    private final Map<Integer, float[]> w2 = new HashMap<>();
    private Font substitute;
    private Map<Integer, Integer> gidToUni;
    private final Map<Integer, Shape> cache = new HashMap<>();
    private Map<Integer, Integer> reverse;
    private boolean ucs2Encoding;

    Type0Font(PdfDict dict) {
        super(dict);
        PdfObject enc = dict.get("Encoding");
        if (enc instanceof PdfName n) {
            encoding = CMap.predefined(n.name);
            ucs2Encoding = n.name.startsWith("Uni") && n.name.contains("UCS2");
        } else if (enc instanceof PdfStream s) {
            try {
                encoding = CMap.parse(s.getDecoded());
            } catch (RuntimeException e) {
                encoding = CMap.identity(false);
            }
        } else {
            encoding = CMap.identity(false);
        }
        vertical = encoding.vertical;
        PdfArray desc = dict.getArray("DescendantFonts");
        PdfDict cid = desc == null ? null : desc.getDict(0);
        if (cid == null) {
            cid = new PdfDict();
        }
        PdfDict fd = cid.getDict("FontDescriptor");
        readDescriptor(fd);
        if (fd != null) {
            loadProgram(fd);
        }
        embedded = tt != null || cff != null;
        PdfObject map = cid.get("CIDToGIDMap");
        if (map instanceof PdfStream s) {
            byte[] b = s.getDecoded();
            cidToGid = new int[b.length / 2];
            for (int i = 0; i < cidToGid.length; i++) {
                cidToGid[i] = ((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff);
            }
        }
        dw = cid.getFloat("DW", 1000) / 1000f;
        readW(cid.getArray("W"));
        PdfArray dw2 = cid.getArray("DW2");
        if (dw2 != null && dw2.size() >= 2) {
            dw2Vy = dw2.getFloat(0, 880) / 1000f;
            dw2W1 = dw2.getFloat(1, -1000) / 1000f;
        }
        readW2(cid.getArray("W2"));
        if (!embedded) {
            String bare = subset ? baseFont.substring(7) : baseFont;
            substitute = SystemFonts.javaFont(bare, bold, italic, serif, fixedPitch);
        }
    }

    private void loadProgram(PdfDict fd) {
        try {
            PdfStream ff = fd.getStream("FontFile2");
            if (ff != null) {
                TrueTypeProgram p = TrueTypeProgram.parse(ff.getDecoded(), 0);
                if (p.hasOutlines()) {
                    tt = p;
                }
                return;
            }
            ff = fd.getStream("FontFile3");
            if (ff != null) {
                byte[] b = ff.getDecoded();
                byte[] c = CffProgram.cffTableOfOpenType(b);
                if (c != null) {
                    cff = CffProgram.parse(c);
                } else if (b.length > 4 && b[0] == 0 && b[1] == 1 && b[2] == 0 && b[3] == 0) {
                    TrueTypeProgram p = TrueTypeProgram.parse(b, 0);
                    if (p.hasOutlines()) {
                        tt = p;
                    }
                } else {
                    cff = CffProgram.parse(b);
                }
            }
        } catch (RuntimeException e) {
            tt = null;
            cff = null;
        }
    }

    private void readW(PdfArray a) {
        if (a == null) {
            return;
        }
        int i = 0;
        while (i < a.size()) {
            int first = a.getInt(i, 0);
            PdfObject next = a.get(i + 1);
            if (next instanceof PdfArray list) {
                for (int k = 0; k < list.size(); k++) {
                    w.put(first + k, list.getFloat(k, 0) / 1000f);
                }
                i += 2;
            } else if (next instanceof PdfNumber last && i + 2 < a.size()) {
                float v = a.getFloat(i + 2, 0) / 1000f;
                for (int c = first; c <= last.intValue() && c - first < 65536; c++) {
                    w.put(c, v);
                }
                i += 3;
            } else {
                break;
            }
        }
    }

    private void readW2(PdfArray a) {
        if (a == null) {
            return;
        }
        int i = 0;
        while (i < a.size()) {
            int first = a.getInt(i, 0);
            PdfObject next = a.get(i + 1);
            if (next instanceof PdfArray list) {
                for (int k = 0; k + 2 < list.size(); k += 3) {
                    w2.put(first + k / 3, new float[] { list.getFloat(k, -1000) / 1000f, list.getFloat(k + 1, 0) / 1000f,
                                                        list.getFloat(k + 2, 880) / 1000f });
                }
                i += 2;
            } else if (next instanceof PdfNumber last && i + 4 < a.size()) {
                float[] v = { a.getFloat(i + 2, -1000) / 1000f, a.getFloat(i + 3, 0) / 1000f,
                              a.getFloat(i + 4, 880) / 1000f };
                for (int c = first; c <= last.intValue() && c - first < 65536; c++) {
                    w2.put(c, v);
                }
                i += 5;
            } else {
                break;
            }
        }
    }

    @Override
    public int codeLength(byte[] s, int pos) {
        return encoding.codeLength(s, pos);
    }

    public int cid(int code) {
        return encoding.toCID(code);
    }

    @Override
    public float width(int code) {
        Float v = w.get(cid(code));
        return v == null ? dw : v;
    }

    @Override
    public float verticalAdvance(int code) {
        float[] v = w2.get(cid(code));
        return v == null ? dw2W1 : v[0];
    }

    @Override
    public float[] verticalOrigin(int code) {
        float[] v = w2.get(cid(code));
        if (v != null) {
            return new float[] { v[1], v[2] };
        }
        return new float[] { width(code) / 2, dw2Vy };
    }

    @Override
    public boolean isWordSpace(int code, int len) {
        return len == 1 && code == 32;
    }

    private int gid(int cid) {
        if (tt != null) {
            if (cidToGid != null) {
                return cid >= 0 && cid < cidToGid.length ? cidToGid[cid] : 0;
            }
            return cid;
        }
        if (cff != null) {
            return cff.gidForCID(cid);
        }
        return cid;
    }

    @Override
    protected String fontUnicode(int code) {
        if (ucs2Encoding) {
            return String.valueOf((char) code);
        }
        int g = gid(cid(code));
        if (tt != null) {
            synchronized (this) {
                if (gidToUni == null) {
                    gidToUni = new HashMap<>();
                    for (Map.Entry<Integer, Integer> e : tt.unicodeMap().entrySet()) {
                        gidToUni.putIfAbsent(e.getValue(), e.getKey());
                    }
                }
            }
            Integer u = gidToUni.get(g);
            if (u != null) {
                return new String(Character.toChars(u));
            }
            String n = tt.hasGlyphNames() ? tt.glyphName(g) : null;
            return n == null ? null : GlyphNames.toUnicode(n);
        }
        if (cff != null && !cff.isCID) {
            return GlyphNames.toUnicode(cff.nameForGid(g));
        }
        return null;
    }

    @Override
    public synchronized Shape glyph(int code) {
        int cid = cid(code);
        Shape s = cache.get(cid);
        if (s == null && !cache.containsKey(cid)) {
            s = build(code, cid);
            cache.put(cid, s);
        }
        return s;
    }

    private Shape build(int code, int cid) {
        try {
            if (tt != null) {
                int g = gid(cid);
                double k = 1.0 / tt.unitsPerEm;
                return tt.glyph(g).createTransformedShape(AffineTransform.getScaleInstance(k, k));
            }
            if (cff != null) {
                return transform(cff.glyph(gid(cid)), cff.fontMatrix);
            }
            String u = unicode(code);
            if (u == null || u.isBlank() || substitute == null) {
                return null;
            }
            float[] adv = new float[1];
            Shape s = SystemFonts.outline(substitute, u, adv);
            float wd = width(code);
            if (wd > 0 && adv[0] > wd * 1.08f) {
                s = AffineTransform.getScaleInstance(wd / adv[0], 1).createTransformedShape(s);
            }
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized byte[] encode(int cp) {
        if (reverse == null) {
            reverse = new HashMap<>();
            Map<Integer, Integer> cidCode = encoding.isIdentity() ? null : encoding.cidToCode();
            if (toUnicode != null) {
                for (Map.Entry<Integer, String> e : toUnicode.unicodeMap().entrySet()) {
                    String u = e.getValue();
                    if (u != null && u.codePointCount(0, u.length()) == 1) {
                        reverse.putIfAbsent(u.codePointAt(0), e.getKey());
                    }
                }
            }
            if (tt != null && encoding.isIdentity() && (cidToGid == null)) {
                for (Map.Entry<Integer, Integer> e : tt.unicodeMap().entrySet()) {
                    int g = e.getValue();
                    if (w.containsKey(g) && tt.hasGlyph(g)) {
                        reverse.putIfAbsent(e.getKey(), g);
                    }
                }
            } else if (tt != null && cidCode != null) {
                // nothing more: codes other than the CMap's cannot be named
                cidCode.size();
            }
        }
        Integer code = reverse.get(cp);
        if (code == null) {
            return null;
        }
        int cid = cid(code);
        if (!w.containsKey(cid) && w.size() > 0) {
            return null;
        }
        if (width(code) <= 0) {
            return null;
        }
        if (!Character.isWhitespace(cp)) {
            if (tt != null && !tt.hasGlyph(gid(cid))) {
                return null;
            }
            if (cff != null && gid(cid) <= 0) {
                return null;
            }
        }
        int len = encoding.maxCodeLength() > 0 ? Math.max(1, encoding.isIdentity() ? 2 : encoding.maxCodeLength()) : 2;
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[len - 1 - i] = (byte) (code >> (8 * i));
        }
        return b;
    }
}
