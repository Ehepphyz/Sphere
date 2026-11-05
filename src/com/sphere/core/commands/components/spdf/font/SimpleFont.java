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
import java.awt.geom.GeneralPath;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple font (Type1, MMType1, TrueType): one byte per code, an encoding
 * from names (base encoding, Differences, or the program's own), widths
 * from /Widths. The outline comes from the embedded program (Type 1, CFF or
 * TrueType, the TrueType code-to-glyph rules of 9.6.6.4 included) or, when
 * nothing is embedded, from the closest installed font.
 */
public final class SimpleFont extends PdfFont {

    private final float[] widths = new float[256];
    private final boolean[] hasWidth = new boolean[256];
    private float missingWidth;
    private final String[] names = new String[256];
    private boolean hasDifferences;
    private String encodingName;
    private TrueTypeProgram tt;
    private CffProgram cff;
    private Type1Program t1;
    private Font substitute;
    private boolean symbolFont;
    private boolean dingbats;
    private final Shape[] glyphs = new Shape[256];
    private final boolean[] glyphDone = new boolean[256];
    private final int[] ttGids = new int[256];
    private Map<Integer, Integer> reverse;

    SimpleFont(PdfDict dict) {
        this(dict, false);
    }

    SimpleFont(PdfDict dict, boolean fallbackOnly) {
        super(dict);
        Arrays.fill(ttGids, -1);
        PdfDict fd = dict.getDict("FontDescriptor");
        readDescriptor(fd);
        String bare = subset ? baseFont.substring(7) : baseFont;
        String low = bare.toLowerCase(java.util.Locale.ROOT);
        symbolFont = low.startsWith("symbol");
        dingbats = low.contains("dingbats");
        if (!fallbackOnly && fd != null) {
            loadProgram(fd);
        }
        embedded = tt != null || cff != null || t1 != null;
        if (tt != null) {
            if (tt.ascender != 0) {
                ascent = Math.max(ascent, 0.5f);
            }
            if (fd == null || !fd.has("Flags")) {
                bold |= tt.bold;
                italic |= tt.italic;
            }
        }
        buildEncoding();
        int first = dict.getInt("FirstChar", 0);
        PdfArray w = dict.getArray("Widths");
        if (w != null) {
            for (int i = 0; i < w.size(); i++) {
                int code = first + i;
                if (code >= 0 && code < 256 && w.get(i) instanceof PdfNumber n) {
                    widths[code] = n.floatValue() / 1000f;
                    hasWidth[code] = true;
                }
            }
        }
        if (fd != null) {
            missingWidth = fd.getFloat("MissingWidth", 0) / 1000f;
        }
        if (!embedded) {
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
                if ("OpenType".equals(ff.getName("Subtype"))) {
                    byte[] c = CffProgram.cffTableOfOpenType(b);
                    if (c != null) {
                        cff = CffProgram.parse(c);
                    } else {
                        TrueTypeProgram p = TrueTypeProgram.parse(b, 0);
                        if (p.hasOutlines()) {
                            tt = p;
                        }
                    }
                } else {
                    cff = CffProgram.parse(b);
                }
                return;
            }
            ff = fd.getStream("FontFile");
            if (ff != null) {
                Type1Program p = Type1Program.parse(ff.getDecoded(), ff.getInt("Length1", 0));
                if (!p.glyphNames().isEmpty()) {
                    t1 = p;
                }
            }
        } catch (RuntimeException e) {
            tt = null;
            cff = null;
            t1 = null;
        }
    }

    private void buildEncoding() {
        String[] base = null;
        PdfArray diffs = null;
        PdfObject enc = dict.get("Encoding");
        if (enc instanceof PdfName n) {
            encodingName = n.name;
            base = GlyphNames.encoding(n.name);
        } else if (enc instanceof PdfDict ed) {
            encodingName = ed.getName("BaseEncoding");
            base = GlyphNames.encoding(encodingName);
            diffs = ed.getArray("Differences");
        }
        if (base == null) {
            if (t1 != null && t1.encoding != null) {
                base = t1.encoding;
            } else if (cff != null && cff.hasBuiltInEncoding() && !cff.isCID) {
                base = new String[256];
                for (int c = 0; c < 256; c++) {
                    int g = cff.gidForCode(c);
                    if (g > 0) {
                        base[c] = cff.nameForGid(g);
                    }
                }
            } else if (symbolFont || dingbats || (tt != null && symbolic)) {
                base = null;
            } else {
                base = GlyphNames.STANDARD;
            }
        }
        if (base != null) {
            System.arraycopy(base, 0, names, 0, 256);
        }
        if (diffs != null) {
            int code = 0;
            for (int i = 0; i < diffs.size(); i++) {
                PdfObject o = diffs.get(i);
                if (o instanceof PdfNumber n) {
                    code = n.intValue();
                } else if (o instanceof PdfName n && code >= 0 && code < 256) {
                    names[code++] = n.name;
                    hasDifferences = true;
                }
            }
        }
    }

    @Override
    public int codeLength(byte[] s, int pos) {
        return 1;
    }

    @Override
    public float width(int code) {
        if (code < 0 || code > 255) {
            return 0;
        }
        if (hasWidth[code]) {
            return widths[code];
        }
        float pw = programWidth(code);
        return pw > 0 ? pw : missingWidth;
    }

    private float programWidth(int code) {
        if (t1 != null) {
            String n = names[code];
            return n != null && t1.has(n) ? (float) (t1.width(n) * t1.fontMatrix[0]) : 0;
        }
        if (cff != null) {
            int g = cffGid(code);
            return g > 0 ? (float) (cff.width(g) * cff.fontMatrix[0]) : 0;
        }
        if (tt != null) {
            int g = ttGid(code);
            return g > 0 ? tt.advance(g) / (float) tt.unitsPerEm : 0;
        }
        if (substitute != null) {
            String u = unicode(code);
            if (u != null) {
                float[] adv = new float[1];
                SystemFonts.outline(substitute, u, adv);
                return adv[0];
            }
        }
        return 0;
    }

    /** The glyph name of a code in this font's encoding, or null. */
    public String glyphName(int code) {
        return code >= 0 && code < 256 ? names[code] : null;
    }

    @Override
    protected String fontUnicode(int code) {
        if (code < 0 || code > 255) {
            return null;
        }
        if (symbolFont && !embedded && names[code] == null) {
            char c = GlyphNames.SYMBOL_UNICODE[code];
            return c == 0 ? null : String.valueOf(c);
        }
        String n = names[code];
        if (n != null) {
            String u = GlyphNames.toUnicode(n);
            if (u != null) {
                return u;
            }
        }
        if (tt != null) {
            // the glyph's own name in the program, or the character the Unicode cmap gives it
            int g = ttGid(code);
            if (g > 0) {
                String gn = tt.hasGlyphNames() ? tt.glyphName(g) : null;
                String u = gn == null ? null : GlyphNames.toUnicode(gn);
                if (u != null) {
                    return u;
                }
                Integer back = unicodeOfGid(g);
                if (back != null) {
                    return new String(Character.toChars(back));
                }
            }
            if (n == null) {
                Map<Integer, Integer> m31 = tt.cmap(3, 1);
                if (m31 != null && m31.containsKey(code) && code >= 32) {
                    return String.valueOf((char) code);
                }
            }
        }
        if (cff != null) {
            int g = cffGid(code);
            String gn = g > 0 ? cff.nameForGid(g) : null;
            String u = gn == null ? null : GlyphNames.toUnicode(gn);
            if (u != null) {
                return u;
            }
        }
        if (symbolFont) {
            char c = GlyphNames.SYMBOL_UNICODE[code];
            return c == 0 ? null : String.valueOf(c);
        }
        if (n == null && code >= 32 && code < 127) {
            return String.valueOf((char) code);
        }
        return null;
    }

    @Override
    public synchronized Shape glyph(int code) {
        if (code < 0 || code > 255) {
            return null;
        }
        if (!glyphDone[code]) {
            glyphs[code] = buildGlyph(code);
            glyphDone[code] = true;
        }
        return glyphs[code];
    }

    private Shape buildGlyph(int code) {
        try {
            if (t1 != null) {
                String n = names[code];
                if (n == null || !t1.has(n)) {
                    return null;
                }
                return transform(t1.glyph(n), t1.fontMatrix);
            }
            if (cff != null) {
                int g = cffGid(code);
                return g > 0 ? transform(cff.glyph(g), cff.fontMatrix) : null;
            }
            if (tt != null) {
                int g = ttGid(code);
                if (g <= 0) {
                    return null;
                }
                double k = 1.0 / tt.unitsPerEm;
                GeneralPath p = tt.glyph(g);
                return p.createTransformedShape(AffineTransform.getScaleInstance(k, k));
            }
            String u = unicode(code);
            if (u == null || u.isBlank()) {
                return null;
            }
            float[] adv = new float[1];
            Shape s = SystemFonts.outline(substitute, u, adv);
            float w = width(code);
            if (w > 0 && adv[0] > w * 1.08f) {
                // a wider stand-in is narrowed to the declared width
                s = AffineTransform.getScaleInstance(w / adv[0], 1).createTransformedShape(s);
            }
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<Integer, Integer> gidToUnicode;

    /** The character the program's Unicode cmap maps to a glyph, if any. */
    private synchronized Integer unicodeOfGid(int gid) {
        if (gidToUnicode == null) {
            gidToUnicode = new HashMap<>();
            Map<Integer, Integer> m = tt.cmap(3, 1);
            if (m == null) {
                m = tt.cmap(0, 3);
            }
            if (m != null) {
                for (Map.Entry<Integer, Integer> e : m.entrySet()) {
                    gidToUnicode.putIfAbsent(e.getValue(), e.getKey());
                }
            }
        }
        return gidToUnicode.get(gid);
    }

    private int cffGid(int code) {
        String n = names[code];
        if (n != null && cff.hasName(n)) {
            return cff.gidForName(n);
        }
        if (cff.isCID) {
            return cff.gidForCID(code);
        }
        int g = cff.gidForCode(code);
        if (g > 0) {
            return g;
        }
        if (n != null) {
            return cff.gidForName(n);
        }
        return 0;
    }

    private int ttGid(int code) {
        int g = ttGids[code];
        if (g < 0) {
            g = computeTtGid(code);
            ttGids[code] = g;
        }
        return g;
    }

    private int computeTtGid(int code) {
        String name = names[code];
        Map<Integer, Integer> m31 = tt.cmap(3, 1);
        Map<Integer, Integer> m30 = tt.cmap(3, 0);
        Map<Integer, Integer> m10 = tt.cmap(1, 0);
        Integer g;
        if (m31 != null && name != null && !(symbolic && !hasDifferences && m30 != null)) {
            String u = GlyphNames.toUnicode(name);
            if (u != null && (g = m31.get(u.codePointAt(0))) != null && g > 0) {
                return g;
            }
        }
        if (m30 != null) {
            for (int b : new int[] { 0xF000, 0xF100, 0xF200, 0 }) {
                if ((g = m30.get(b + code)) != null && g > 0) {
                    return g;
                }
            }
        }
        if (m10 != null) {
            if (name != null && !"MacRomanEncoding".equals(encodingName)) {
                for (int c = 0; c < 256; c++) {
                    if (name.equals(GlyphNames.MAC_ROMAN[c]) && (g = m10.get(c)) != null && g > 0) {
                        return g;
                    }
                }
            }
            if ((g = m10.get(code)) != null && g > 0) {
                return g;
            }
        }
        if (m31 != null && (g = m31.get(code)) != null && g > 0) {
            return g;
        }
        if (name != null && tt.hasGlyphNames()) {
            int gn = tt.gidForName(name);
            if (gn > 0) {
                return gn;
            }
        }
        if (m31 != null && name == null) {
            String std = GlyphNames.STANDARD[code];
            String u = std == null ? null : GlyphNames.toUnicode(std);
            if (u != null && (g = m31.get((int) u.charAt(0))) != null && g > 0) {
                return g;
            }
        }
        if (m31 == null && m30 == null && m10 == null) {
            return code < tt.numGlyphs ? code : 0;
        }
        return 0;
    }

    /* ------------------------------------------------------------- writing text */

    @Override
    public synchronized byte[] encode(int cp) {
        if (reverse == null) {
            reverse = new HashMap<>();
            for (int c = 0; c < 256; c++) {
                String u = unicode(c);
                if (u != null && u.codePointCount(0, u.length()) == 1 && hasWidth[c]) {
                    int k = u.codePointAt(0);
                    Integer prev = reverse.get(k);
                    if (prev == null || codeScore(c, k) > codeScore(prev, k)) {
                        reverse.put(k, c);
                    }
                }
            }
            // a no-break space and a plain one share the space glyph
            if (!reverse.containsKey(0xA0) && reverse.containsKey(32)) {
                reverse.put(0xA0, reverse.get(32));
            }
        }
        Integer code = reverse.get(cp);
        if (code == null) {
            return null;
        }
        if (!Character.isWhitespace(cp) && !glyphPresent(code)) {
            return null;
        }
        if (widths[code] <= 0 && cp != 0x200B) {
            // a code drawn with no advance would glue the words together
            return null;
        }
        return new byte[] { (byte) (int) code };
    }

    /** Which of two codes of one character to write: one that advances, has its glyph, is the plain code. */
    private int codeScore(int c, int cp) {
        int s = 0;
        if (widths[c] > 0) {
            s += 4;
        }
        if (glyphPresent(c)) {
            s += 2;
        }
        if (c == cp) {
            s += 1;
        }
        return s;
    }

    private boolean glyphPresent(int code) {
        if (!embedded) {
            return true;
        }
        if (t1 != null) {
            return names[code] != null && t1.has(names[code]);
        }
        if (cff != null) {
            return cffGid(code) > 0;
        }
        if (tt != null) {
            int g = ttGid(code);
            return g > 0 && tt.hasGlyph(g);
        }
        return false;
    }

    public boolean isStandard14() {
        return !embedded && SystemFonts.standardFamily(subset ? baseFont.substring(7) : baseFont) != null;
    }
}
