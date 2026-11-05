package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.render.Op;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.TextBlock;
import com.sphere.components.spdf.text.TextLine;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Writes an edit of text into the page: the glyphs that were there come out
 * of the content (where they were drawn: the page or a form), the new text is
 * laid out and appended with the fonts it needs registered in the page's
 * resources, the embedded subsets are brought up to date, all in one undoable
 * step.
 */
public final class TextEdit {

    private TextEdit() {
    }

    /**
     * The frame of a paragraph: layout coordinates (x along the text, y up,
     * origin at the left end of the first baseline) to user space.
     */
    public static AffineTransform frameOf(TextBlock b, PdfPage page) {
        TextLine l0 = b.lines.get(0);
        double dx = l0.dx;
        double dy = l0.dy;
        double u0 = b.u0();
        double v0 = l0.v;
        double bx = u0 * dx - v0 * dy;
        double by = u0 * dy + v0 * dx;
        // up is the baseline direction turned a quarter toward the top of the page
        AffineTransform localToDisplay = new AffineTransform(dx, dy, dy, -dx, bx, by);
        AffineTransform t = page.displayToUser();
        t.concatenate(localToDisplay);
        return t;
    }

    /** The frame of a new text box whose first baseline starts at a display point. */
    public static AffineTransform frameAt(PdfPage page, double x, double y) {
        AffineTransform t = page.displayToUser();
        t.concatenate(new AffineTransform(1, 0, 0, -1, x, y));
        return t;
    }

    /** The width a paragraph's text reflows in: its widest line, and a hair more against rounding. */
    public static float widthOf(TextBlock b) {
        return (float) (b.u1() - b.u0()) + 0.75f;
    }

    /**
     * Replaces glyphs of a page by laid-out text (or only removes them, with
     * null text). Throws, leaving the document as it was, when it cannot.
     */
    public static TextComposer.Composed commit(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex,
                                               PageRenderer renderer, FontResolver fonts,
                                               Collection<TextGlyph> remove, RichText text,
                                               AffineTransform frame, float width, String label) {
        List<TextComposer.Composed> out = commitMany(h, doc, page, pageIndex, renderer, fonts,
                                                     List.of(new Piece(remove, text, frame, width)), label);
        return out.isEmpty() ? null : out.get(0);
    }

    /** One paragraph's change: the glyphs it replaces and the text taking their place. */
    public record Piece(Collection<TextGlyph> remove, RichText text, AffineTransform frame, float width) {
    }

    /**
     * Several paragraphs of one page changed in a single rewrite of its
     * content: glyph positions in the content are only valid before it changes.
     */
    public static List<TextComposer.Composed> commitMany(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex,
                                                         PageRenderer renderer, FontResolver fonts, List<Piece> pieces,
                                                         String label) {
        h.begin(label);
        List<TextComposer.Composed> out = new ArrayList<>();
        try {
            List<Op> ops = new ArrayList<>(renderer.pageOps(page));
            List<TextGlyph> remove = new ArrayList<>();
            for (Piece p : pieces) {
                if (p.remove() != null) {
                    remove.addAll(p.remove());
                }
            }
            if (!remove.isEmpty()) {
                ops = ContentEditor.removeEverywhere(h, doc, ops, remove);
            }
            for (Piece p : pieces) {
                RichText text = p.text();
                if (text != null && text.length() > 0 && !text.text().isBlank()) {
                    TextComposer.Composed c = TextComposer.compose(text, p.width(), fonts);
                    Map<OutputFont, String> names = TextComposer.fontNames(c);
                    registerFonts(h, page, pageIndex, names);
                    List<Op> block = TextComposer.toOps(c, p.frame(), names);
                    ops = ContentEditor.append(ContentEditor.wrap(ops), block);
                    out.add(c);
                }
            }
            ContentEditor.setPageOps(h, page, pageIndex, ops);
            fonts.embedder().sync(h);
            h.commit();
            return out;
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** Appends raw operations (a drawing, a stamp) to a page as a block of its own. */
    public static void appendOps(EditHistory h, PdfPage page, int pageIndex, PageRenderer renderer, List<Op> block,
                                 String label) {
        h.begin(label);
        try {
            List<Op> ops = ContentEditor.append(ContentEditor.wrap(new ArrayList<>(renderer.pageOps(page))), block);
            ContentEditor.setPageOps(h, page, pageIndex, ops);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** The page's resources, made writable (snapshotted) for the step. */
    public static PdfDict resources(EditHistory h, PdfPage page, int pageIndex) {
        PdfObject raw = page.dict().getRaw("Resources");
        if (raw instanceof PdfRef r && r.resolve() instanceof PdfDict rd) {
            h.touch(r.num);
            return rd;
        }
        h.touchPage(pageIndex, page.objNum());
        return page.ownResources();
    }

    /** A sub-dictionary of the resources (Font, XObject, ExtGState...), made writable. */
    public static PdfDict category(EditHistory h, PdfDict res, String key) {
        PdfObject raw = res.getRaw(key);
        if (raw instanceof PdfRef r && r.resolve() instanceof PdfDict d) {
            h.touch(r.num);
            return d;
        }
        if (raw instanceof PdfDict d) {
            return d;
        }
        PdfDict d = new PdfDict();
        res.put(key, d);
        return d;
    }

    /** Gives every font a name in the page's /Font resources, reusing names already there. */
    static void registerFonts(EditHistory h, PdfPage page, int pageIndex, Map<OutputFont, String> names) {
        PdfDict res = resources(h, page, pageIndex);
        PdfDict fonts = category(h, res, "Font");
        for (Map.Entry<OutputFont, String> e : names.entrySet()) {
            PdfObject value = e.getKey().resource(h);
            String found = null;
            for (PdfName k : fonts.keys()) {
                PdfObject v = fonts.getRaw(k);
                if (v == value || (v instanceof PdfRef a && value instanceof PdfRef b && a.num == b.num)) {
                    found = k.name;
                    break;
                }
            }
            if (found == null) {
                int i = 1;
                while (fonts.has("SPDF" + i)) {
                    i++;
                }
                found = "SPDF" + i;
                fonts.put(found, value);
            }
            e.setValue(found);
        }
    }

    /** The name of an ExtGState with these alphas (and blend), added when missing. */
    public static String extGState(EditHistory h, PdfPage page, int pageIndex, float fillAlpha, float strokeAlpha,
                                   String blend) {
        PdfDict res = resources(h, page, pageIndex);
        PdfDict gs = category(h, res, "ExtGState");
        for (PdfName k : gs.keys()) {
            PdfDict d = gs.getDict(k.name);
            if (d != null && d.getFloat("ca", 1) == fillAlpha && d.getFloat("CA", 1) == strokeAlpha
                && (blend == null ? !d.has("BM") : blend.equals(d.getName("BM")))) {
                return k.name;
            }
        }
        int i = 1;
        while (gs.has("SPDFgs" + i)) {
            i++;
        }
        PdfDict d = new PdfDict();
        d.put("Type", "ExtGState");
        d.put("ca", fillAlpha);
        d.put("CA", strokeAlpha);
        if (blend != null) {
            d.put("BM", blend);
        }
        gs.put("SPDFgs" + i, d);
        return "SPDFgs" + i;
    }
}
