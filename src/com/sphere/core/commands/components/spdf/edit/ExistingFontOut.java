package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.font.PdfFont;
import java.awt.Shape;

/** A font the document already has, reused for new text when it holds the glyphs. */
public final class ExistingFontOut implements OutputFont {

    public final PdfFont font;
    private final PdfObject ref;

    public ExistingFontOut(PdfFont font, PdfObject ref) {
        this.font = font;
        this.ref = ref;
    }

    @Override
    public byte[] encode(int cp) {
        return font.encode(cp);
    }

    @Override
    public float width(byte[] code) {
        return font.width(PdfFont.code(code, 0, code.length));
    }

    @Override
    public Shape glyph(byte[] code) {
        return font.glyph(PdfFont.code(code, 0, code.length));
    }

    @Override
    public float ascent() {
        return font.ascent;
    }

    @Override
    public float descent() {
        return font.descent;
    }

    @Override
    public PdfObject resource(EditHistory h) {
        return ref;
    }

    @Override
    public void used(byte[] code) {
    }

    @Override
    public String key() {
        return "doc:" + System.identityHashCode(font.dict);
    }

    @Override
    public String displayName() {
        return font.displayName();
    }

    @Override
    public boolean singleByte() {
        return !(font instanceof com.sphere.components.spdf.font.Type0Font);
    }
}
