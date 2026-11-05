package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfObject;
import java.awt.Shape;

/**
 * A font new text is written with: one of the document's own (reused when it
 * holds every glyph needed) or an installed TrueType font embedded as a
 * subset. Widths and outlines are in em units, so the editor draws exactly
 * what the PDF will show.
 */
public interface OutputFont {

    /** The bytes showing a character, or null when this font cannot. */
    byte[] encode(int codePoint);

    /** The advance of encoded bytes, in em. */
    float width(byte[] code);

    /** The outline of encoded bytes, in em, y up; null when the font draws through procedures. */
    Shape glyph(byte[] code);

    float ascent();

    float descent();

    /** The font dictionary (a reference) to put in the page's /Font resources. */
    PdfObject resource(EditHistory h);

    /** Called for every code a commit writes (subsets are built from them). */
    void used(byte[] code);

    /** A key identifying the font among the others of a document. */
    String key();

    String displayName();

    /** Whether a code byte 32 is a single-byte space (Tw applies). */
    boolean singleByte();
}
