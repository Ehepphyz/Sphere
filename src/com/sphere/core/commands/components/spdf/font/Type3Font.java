package com.sphere.components.spdf.font;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfStream;
import java.awt.Shape;
import java.util.HashMap;
import java.util.Map;

/**
 * A Type 3 font: each glyph is a little content stream (a CharProc) the
 * renderer runs with the font matrix. Matplotlib and Ghostscript write their
 * text this way, so plots go through here.
 */
public final class Type3Font extends PdfFont {

    public final double[] fontMatrix;
    private final PdfDict charProcs;
    private final String[] names = new String[256];
    private final float[] widths = new float[256];
    public final PdfDict resources;
    private Map<Integer, Integer> reverse;

    Type3Font(PdfDict dict) {
        super(dict);
        PdfArray fm = dict.getArray("FontMatrix");
        double[] m = { 0.001, 0, 0, 0.001, 0, 0 };
        if (fm != null && fm.size() == 6) {
            m = fm.toDoubles();
        }
        fontMatrix = m;
        charProcs = dict.getDict("CharProcs");
        resources = dict.getDict("Resources");
        PdfObject enc = dict.get("Encoding");
        if (enc instanceof PdfDict ed) {
            String[] base = GlyphNames.encoding(ed.getName("BaseEncoding"));
            if (base != null) {
                System.arraycopy(base, 0, names, 0, 256);
            }
            PdfArray diffs = ed.getArray("Differences");
            if (diffs != null) {
                int code = 0;
                for (int i = 0; i < diffs.size(); i++) {
                    PdfObject o = diffs.get(i);
                    if (o instanceof PdfNumber n) {
                        code = n.intValue();
                    } else if (o instanceof PdfName n && code >= 0 && code < 256) {
                        names[code++] = n.name;
                    }
                }
            }
        } else if (enc instanceof PdfName n) {
            String[] base = GlyphNames.encoding(n.name);
            if (base != null) {
                System.arraycopy(base, 0, names, 0, 256);
            }
        }
        int first = dict.getInt("FirstChar", 0);
        PdfArray w = dict.getArray("Widths");
        if (w != null) {
            for (int i = 0; i < w.size(); i++) {
                int code = first + i;
                if (code >= 0 && code < 256) {
                    widths[code] = (float) (w.getFloat(i, 0) * m[0]);
                }
            }
        }
        readDescriptor(dict.getDict("FontDescriptor"));
        String name = dict.getName("Name");
        if (baseFont.isEmpty() && name != null) {
            baseFont = name;
        }
        // Type 3 glyphs are usually drawn from a real font: guess its family from the glyph names' font
        if (family.isEmpty() || family.equals(baseFont)) {
            family = SystemFonts.familyOf(baseFont.isEmpty() ? "Type3" : baseFont);
        }
        embedded = true;
        double h = Math.hypot(m[2], m[3]) * 1000;
        if (h > 0) {
            ascent = 0.8f;
            descent = -0.2f;
        }
    }

    @Override
    public int codeLength(byte[] s, int pos) {
        return 1;
    }

    @Override
    public float width(int code) {
        return code >= 0 && code < 256 ? widths[code] : 0;
    }

    @Override
    protected String fontUnicode(int code) {
        if (code < 0 || code > 255) {
            return null;
        }
        String n = names[code];
        String u = n == null ? null : GlyphNames.toUnicode(n);
        if (u == null && n == null && code >= 32 && code < 127) {
            return String.valueOf((char) code);
        }
        return u;
    }

    @Override
    public Shape glyph(int code) {
        return null;
    }

    @Override
    public boolean isType3() {
        return true;
    }

    /** The procedure drawing a code, or null. */
    public PdfStream charProc(int code) {
        if (charProcs == null || code < 0 || code > 255 || names[code] == null) {
            return null;
        }
        return charProcs.getStream(names[code]);
    }

    @Override
    public synchronized byte[] encode(int cp) {
        if (reverse == null) {
            reverse = new HashMap<>();
            for (int c = 0; c < 256; c++) {
                String u = unicode(c);
                if (u != null && u.codePointCount(0, u.length()) == 1 && charProc(c) != null) {
                    reverse.putIfAbsent(u.codePointAt(0), c);
                }
            }
        }
        Integer code = reverse.get(cp);
        return code == null ? null : new byte[] { (byte) (int) code };
    }
}
