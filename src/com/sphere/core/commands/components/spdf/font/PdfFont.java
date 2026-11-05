package com.sphere.components.spdf.font;

import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfStream;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;

/**
 * A font as a content stream uses it: how a string splits into codes, each
 * code's width, its Unicode text, and its outline, all in text space (one
 * unit is the font size). Built from the font dictionary by {@link #load};
 * the subclasses handle simple fonts, composite (Type 0) fonts and Type 3.
 */
public abstract class PdfFont {

    public final PdfDict dict;
    /** BaseFont as written, subset tag included. */
    public String baseFont = "";
    /** The family the font belongs to, without subset tag or style ("Times New Roman"). */
    public String family = "";
    public boolean bold;
    public boolean italic;
    public boolean serif;
    public boolean fixedPitch;
    public boolean symbolic;
    public boolean embedded;
    public float ascent = 0.8f;
    public float descent = -0.2f;
    public float capHeight = 0.7f;
    public boolean vertical;
    protected CMap toUnicode;
    /** Subset fonts carry a tag "ABCDEF+" before their name. */
    public boolean subset;

    protected PdfFont(PdfDict dict) {
        this.dict = dict;
        String bf = dict.getName("BaseFont");
        if (bf != null) {
            baseFont = bf;
        }
        PdfStream tu = dict.getStream("ToUnicode");
        if (tu != null) {
            try {
                toUnicode = CMap.parse(tu.getDecoded());
            } catch (RuntimeException e) {
                toUnicode = null;
            }
        }
        subset = baseFont.length() > 7 && baseFont.charAt(6) == '+' && baseFont.substring(0, 6).chars()
            .allMatch(c -> c >= 'A' && c <= 'Z');
        String bare = subset ? baseFont.substring(7) : baseFont;
        String low = bare.toLowerCase(java.util.Locale.ROOT);
        bold = low.contains("bold") || low.contains("black") || low.contains("heavy") || low.contains("semibold")
            || low.contains("demi") || low.matches(".*\\bbd\\b.*") || low.endsWith(",bd");
        italic = low.contains("italic") || low.contains("oblique") || low.endsWith("-it") || low.contains("-it,")
            || low.endsWith(",it") || low.matches(".*-(bold)?it$") || low.matches(".*(bx|bi|ti|sl)\\d+$");
        family = SystemFonts.familyOf(bare);
    }

    /** Reads the font descriptor's flags and metrics (from the descendant for a composite font). */
    protected void readDescriptor(PdfDict fd) {
        if (fd == null) {
            return;
        }
        int flags = fd.getInt("Flags", 0);
        fixedPitch |= (flags & 1) != 0;
        serif |= (flags & 2) != 0;
        symbolic = (flags & 4) != 0 && (flags & 32) == 0;
        italic |= (flags & 64) != 0 || Math.abs(fd.getFloat("ItalicAngle", 0)) > 1;
        bold |= (flags & (1 << 18)) != 0 || fd.getFloat("FontWeight", 400) >= 600 || fd.getFloat("StemV", 0) > 120;
        float a = fd.getFloat("Ascent", 0);
        float d = fd.getFloat("Descent", 0);
        if (a > 0 && a < 3000) {
            ascent = a / 1000f;
        }
        if (d < 0 && d > -2000) {
            descent = d / 1000f;
        }
        if (ascent - descent < 0.5f) {
            ascent = 0.8f;
            descent = -0.2f;
        }
        float ch = fd.getFloat("CapHeight", 0);
        if (ch > 0 && ch < 2000) {
            capHeight = ch / 1000f;
        }
        String fam = fd.getText("FontFamily");
        if (fam != null && !fam.isBlank()) {
            family = fam.trim();
        }
    }

    /* ------------------------------------------------------------- reading text */

    /** How many bytes the code at a position takes. */
    public abstract int codeLength(byte[] s, int pos);

    public static int code(byte[] s, int pos, int len) {
        int v = 0;
        for (int i = 0; i < len && pos + i < s.length; i++) {
            v = (v << 8) | (s[pos + i] & 0xff);
        }
        return v;
    }

    /** The horizontal advance of a code, in text space units. */
    public abstract float width(int code);

    /** The vertical advance (negative going down) for vertical writing, in text space units. */
    public float verticalAdvance(int code) {
        return -1f;
    }

    /** The position vector of a glyph in vertical writing (where its origin sits), in text space units. */
    public float[] verticalOrigin(int code) {
        return new float[] { width(code) / 2, ascent };
    }

    /** The Unicode text of a code: ToUnicode first, then what the font itself knows; null when nothing. */
    public String unicode(int code) {
        if (toUnicode != null) {
            String u = toUnicode.toUnicode(code);
            if (u != null) {
                return u;
            }
        }
        return fontUnicode(code);
    }

    protected abstract String fontUnicode(int code);

    /** The outline of a code in text space (y up), or null (Type 3 glyphs draw through their procedure). */
    public abstract Shape glyph(int code);

    /** Word spacing applies to the single-byte code 32 only. */
    public boolean isWordSpace(int code, int len) {
        return len == 1 && code == 32;
    }

    public boolean isType3() {
        return false;
    }

    /* ------------------------------------------------------------- writing text */

    /**
     * The bytes that show a character in this font, or null when the font
     * cannot show it (no code for it, or the glyph is not in the embedded subset).
     */
    public abstract byte[] encode(int codePoint);

    /** The font's name for people: its family and style. */
    public String displayName() {
        String f = family.isEmpty() ? baseFont : family;
        return f + (bold ? " Bold" : "") + (italic ? " Italic" : "");
    }

    /* ------------------------------------------------------------- helpers */

    /** A glyph outline scaled by a font matrix into text space. */
    protected static Shape transform(GeneralPath p, double[] m) {
        if (p == null) {
            return null;
        }
        return p.createTransformedShape(new AffineTransform(m[0], m[1], m[2], m[3], m[4], m[5]));
    }

    /** Builds the right font for a dictionary; never fails (a broken font falls back to a system one). */
    public static PdfFont load(PdfDict dict) {
        String sub = dict.getName("Subtype", "Type1");
        try {
            return switch (sub) {
                case "Type0" -> new Type0Font(dict);
                case "Type3" -> new Type3Font(dict);
                default -> new SimpleFont(dict);
            };
        } catch (RuntimeException e) {
            return new SimpleFont(dict, true);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + baseFont + ")";
    }
}
