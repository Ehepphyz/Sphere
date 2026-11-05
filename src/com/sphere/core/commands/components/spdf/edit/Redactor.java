package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.render.Op;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.PageText;
import com.sphere.components.spdf.text.SmartSearch;
import com.sphere.components.spdf.text.TextBlock;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * True redaction and document-wide replacement. Redacting takes the glyphs
 * out of the content (they are gone from the file, not hidden under a box)
 * and paints black boxes where they were; patterns (e-mails, phone numbers,
 * IBANs, numbers) can be redacted across the whole document at once.
 * Replacing rewrites each paragraph holding a match, reflowed, keeping its
 * fonts and styles.
 */
public final class Redactor {

    private Redactor() {
    }

    public static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("emails", Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"));
        PATTERNS.put("phone numbers", Pattern.compile("(\\+\\d{1,3}[\\s.-]?)?(\\(?\\d{1,4}\\)?[\\s.-]?){2,5}\\d{2,4}"
            + "(?=\\D|$)"));
        PATTERNS.put("IBANs", Pattern.compile("\\b[A-Z]{2}\\d{2}(\\s?[A-Z0-9]{4}){2,7}(\\s?[A-Z0-9]{1,4})?\\b"));
        PATTERNS.put("numbers", Pattern.compile("\\b\\d[\\d\\s.,-]{5,}\\d\\b"));
        PATTERNS.put("links", Pattern.compile("(https?://|www\\.)\\S+"));
    }

    /** Redacts glyphs: removes them and draws filled boxes over their places (display-space rects). */
    public static int redactGlyphs(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                                   List<TextGlyph> glyphs, List<Rectangle2D> extraBoxes, int rgb) {
        List<Rectangle2D> boxes = new ArrayList<>(extraBoxes == null ? List.of() : extraBoxes);
        for (TextGlyph g : glyphs) {
            boxes.add(g.bounds());
        }
        h.begin("Redact");
        try {
            List<Op> ops = new ArrayList<>(renderer.pageOps(page));
            ops = ContentEditor.removeEverywhere(h, doc, ops, glyphs);
            ops = ContentEditor.append(ContentEditor.wrap(ops), boxesOps(page, merge(boxes), rgb));
            ContentEditor.setPageOps(h, page, pageIndex, ops);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
        return glyphs.size();
    }

    /** Joins boxes on one line into one, so the blacking reads as bars. */
    static List<Rectangle2D> merge(List<Rectangle2D> boxes) {
        List<Rectangle2D> out = new ArrayList<>();
        for (Rectangle2D r : boxes) {
            boolean merged = false;
            for (Rectangle2D o : out) {
                double overlapY = Math.min(o.getMaxY(), r.getMaxY()) - Math.max(o.getMinY(), r.getMinY());
                double h = Math.min(o.getHeight(), r.getHeight());
                double gapX = Math.max(o.getMinX(), r.getMinX()) - Math.min(o.getMaxX(), r.getMaxX());
                if (overlapY > h * 0.6 && gapX < h * 0.6) {
                    o.add(r);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                out.add(new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight()));
            }
        }
        return out;
    }

    private static List<Op> boxesOps(PdfPage page, List<Rectangle2D> boxes, int rgb) {
        AffineTransform d2u = page.displayToUser();
        List<Op> ops = new ArrayList<>();
        ops.add(TextComposer.op("q"));
        ops.add(TextComposer.op("rg", TextComposer.num(((rgb >> 16) & 0xff) / 255.0),
                                TextComposer.num(((rgb >> 8) & 0xff) / 255.0), TextComposer.num((rgb & 0xff) / 255.0)));
        for (Rectangle2D r : boxes) {
            Rectangle2D u = d2u.createTransformedShape(r).getBounds2D();
            ops.add(TextComposer.op("re", TextComposer.num(u.getX() - 0.5), TextComposer.num(u.getY() - 0.5),
                                    TextComposer.num(u.getWidth() + 1), TextComposer.num(u.getHeight() + 1)));
            ops.add(TextComposer.op("f"));
        }
        ops.add(TextComposer.op("Q"));
        return ops;
    }

    /** Redacts every match of a pattern on a page; returns the number of matches. */
    public static int redactPattern(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                                    PageText text, Pattern p) {
        List<TextGlyph> glyphs = new ArrayList<>();
        Matcher m = p.matcher(text.text);
        int n = 0;
        while (m.find()) {
            if (m.end() - m.start() < 3) {
                continue;
            }
            glyphs.addAll(text.glyphs(m.start(), m.end()));
            n++;
        }
        if (!glyphs.isEmpty()) {
            redactGlyphs(h, doc, page, pageIndex, renderer, glyphs, null, 0x000000);
        }
        return n;
    }

    /**
     * Replaces every (smart) match of a phrase on a page by another text,
     * paragraph by paragraph, each paragraph reflowed with its styles kept.
     * Returns the number of replacements.
     */
    public static int replace(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                              FontResolver fonts, PageText text, String find, String with, boolean regex) {
        int count = 0;
        List<TextEdit.Piece> pieces = new ArrayList<>();
        {
            for (TextBlock b : text.blocks) {
                RichText rt = RichText.of(b);
                String s = rt.text();
                List<int[]> spans = new ArrayList<>();
                if (regex) {
                    Matcher m = Pattern.compile(find, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s);
                    while (m.find()) {
                        if (m.end() > m.start()) {
                            spans.add(new int[] { m.start(), m.end() });
                        }
                    }
                } else {
                    SmartSearch.Folded f = SmartSearch.fold(s);
                    String q = SmartSearch.fold(find).text();
                    int i = 0;
                    while (!q.isEmpty() && (i = f.text().indexOf(q, i)) >= 0) {
                        spans.add(new int[] { f.origin()[i], f.origin()[i + q.length() - 1] + 1 });
                        i += q.length();
                    }
                }
                if (spans.isEmpty()) {
                    continue;
                }
                for (int k = spans.size() - 1; k >= 0; k--) {
                    int[] sp = spans.get(k);
                    RichText.Style st = rt.styleAt(sp[0]);
                    String rep = with;
                    if (!regex && !with.isEmpty() && Character.isUpperCase(s.charAt(sp[0]))
                        && !Character.isUpperCase(with.charAt(0))) {
                        // keep a capital at the start of a sentence
                        rep = Character.toUpperCase(with.charAt(0)) + with.substring(1);
                    }
                    rt.delete(sp[0], sp[1]);
                    rt.insert(sp[0], rep, st);
                    count++;
                }
                pieces.add(new TextEdit.Piece(b.glyphs(), rt, TextEdit.frameOf(b, page), TextEdit.widthOf(b)));
            }
        }
        if (!pieces.isEmpty()) {
            TextEdit.commitMany(h, doc, page, pageIndex, renderer, fonts, pieces, "Replace \"" + find + "\"");
        }
        return count;
    }

    static boolean isSpdf(PdfObject o) {
        return o instanceof PdfName n && n.name.equals("SPDF");
    }
}
