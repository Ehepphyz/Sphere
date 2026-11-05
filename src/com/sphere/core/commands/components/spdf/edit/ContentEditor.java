package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.ByteBuf;
import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.cos.PdfString;
import com.sphere.components.spdf.font.PdfFont;
import com.sphere.components.spdf.render.ContentParser;
import com.sphere.components.spdf.render.Op;
import com.sphere.components.spdf.render.TextGlyph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surgery on content streams. A glyph is removed by turning its text
 * operation into a TJ where the glyph's bytes become the number that moves
 * the text position exactly as the glyph did: everything after it, on the
 * line and further in the text object, stays where it was. New content is
 * appended after the original wrapped in q/Q, in a marked-content block
 * (/SPDF) of its own, so it starts from the page's default state.
 */
public final class ContentEditor {

    private ContentEditor() {
    }

    /** What to take out of one operation: its glyph indices, and the text state they were shown with. */
    public static final class Removal {
        PdfFont font;
        float tfs;
        float tc;
        float tw;
        final Set<Integer> glyphs = new HashSet<>();
    }

    /** Groups glyphs by stream (-1 the page, else a form's object number), then by operation. */
    public static Map<Integer, Map<Integer, Removal>> group(Collection<TextGlyph> glyphs) {
        Map<Integer, Map<Integer, Removal>> out = new HashMap<>();
        for (TextGlyph g : glyphs) {
            if (g.streamId < -1) {
                continue;
            }
            Removal r = out.computeIfAbsent(g.streamId, k -> new HashMap<>()).computeIfAbsent(g.opIndex, k -> {
                Removal n = new Removal();
                n.font = g.font;
                n.tfs = g.tfs;
                n.tc = g.tc;
                n.tw = g.tw;
                return n;
            });
            r.glyphs.add(g.glyphIndex);
        }
        return out;
    }

    /** The operations with the glyphs taken out. */
    public static List<Op> removeGlyphs(List<Op> ops, Map<Integer, Removal> removals) {
        List<Op> out = new ArrayList<>(ops.size() + removals.size() * 3);
        for (int i = 0; i < ops.size(); i++) {
            Removal r = removals.get(i);
            Op op = ops.get(i);
            if (r == null || r.tfs == 0) {
                out.add(op);
            } else {
                out.addAll(rewrite(op, r));
            }
        }
        return out;
    }

    private static List<Op> rewrite(Op op, Removal r) {
        List<Op> out = new ArrayList<>(4);
        PdfArray elements;
        switch (op.name) {
            case "Tj":
                elements = PdfArray.of(op.args.length > 0 ? op.args[0] : new PdfString(new byte[0]));
                break;
            case "'":
                out.add(new Op("T*", new PdfObject[0]));
                elements = PdfArray.of(op.args.length > 0 ? op.args[0] : new PdfString(new byte[0]));
                break;
            case "\"":
                out.add(new Op("Tw", new PdfObject[] { op.args[0] }));
                out.add(new Op("Tc", new PdfObject[] { op.args[1] }));
                out.add(new Op("T*", new PdfObject[0]));
                elements = PdfArray.of(op.args.length > 2 ? op.args[2] : new PdfString(new byte[0]));
                break;
            case "TJ":
                elements = op.args.length > 0 && op.args[0] instanceof PdfArray a ? a : new PdfArray();
                break;
            default:
                out.add(op);
                return out;
        }
        PdfFont font = r.font;
        PdfArray na = new PdfArray();
        int glyph = 0;
        for (PdfObject e : elements) {
            if (e instanceof PdfNumber n) {
                addNumber(na, n.doubleValue());
            } else if (e instanceof PdfString s) {
                byte[] b = s.getBytes();
                ByteBuf cur = new ByteBuf(b.length);
                int pos = 0;
                while (pos < b.length) {
                    int len = Math.max(1, font.codeLength(b, pos));
                    len = Math.min(len, b.length - pos);
                    int code = PdfFont.code(b, pos, len);
                    if (r.glyphs.contains(glyph)) {
                        flush(na, cur, s.hex);
                        float w = font.vertical ? font.verticalAdvance(code) : font.width(code);
                        boolean ws = font.isWordSpace(code, len);
                        double adv = w * 1000.0 + (r.tc + (ws ? r.tw : 0)) * 1000.0 / r.tfs;
                        addNumber(na, -adv);
                    } else {
                        cur.put(b, pos, len);
                    }
                    pos += len;
                    glyph++;
                }
                flush(na, cur, s.hex);
            }
        }
        out.add(new Op("TJ", new PdfObject[] { na }));
        return out;
    }

    private static void flush(PdfArray na, ByteBuf cur, boolean hex) {
        if (cur.size() > 0) {
            na.add(new PdfString(cur.toBytes(), hex));
            cur.clear();
        }
    }

    private static void addNumber(PdfArray na, double v) {
        if (na.size() > 0 && na.getRaw(na.size() - 1) instanceof PdfNumber last) {
            na.set(na.size() - 1, PdfNumber.of(round(last.doubleValue() + v)));
        } else {
            na.add(PdfNumber.of(round(v)));
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /* ------------------------------------------------------------- wrapping and appending */

    /**
     * The page's operations wrapped in q ... Q (stray Q dropped, open q
     * closed), so what comes after starts from the default state. Already
     * wrapped operations come back as they are.
     */
    public static List<Op> wrap(List<Op> ops) {
        if (isWrapped(ops)) {
            return new ArrayList<>(ops);
        }
        List<Op> out = new ArrayList<>(ops.size() + 4);
        out.add(new Op("q", new PdfObject[0]));
        int depth = 0;
        for (Op op : ops) {
            if (op.name.equals("q")) {
                depth++;
            } else if (op.name.equals("Q")) {
                if (depth == 0) {
                    continue;
                }
                depth--;
            }
            out.add(op);
        }
        while (depth-- > 0) {
            out.add(new Op("Q", new PdfObject[0]));
        }
        out.add(new Op("Q", new PdfObject[0]));
        return out;
    }

    /** True when the operations are "q original Q" followed only by spdf's own blocks. */
    static boolean isWrapped(List<Op> ops) {
        if (ops.isEmpty() || !ops.get(0).name.equals("q")) {
            return false;
        }
        int depth = 0;
        int end = -1;
        for (int i = 0; i < ops.size(); i++) {
            String n = ops.get(i).name;
            if (n.equals("q")) {
                depth++;
            } else if (n.equals("Q")) {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            return false;
        }
        int mc = 0;
        for (int i = end + 1; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.name.equals("BMC") && "SPDF".equals(op.nameArg(0)) && mc == 0) {
                mc = 1;
                continue;
            }
            if (mc == 0) {
                return false;
            }
            if (op.name.equals("BMC") || op.name.equals("BDC")) {
                mc++;
            } else if (op.name.equals("EMC")) {
                mc--;
            }
        }
        return mc == 0;
    }

    /** Appends a block of spdf's own (it carries its own q/Q) after the wrapped content. */
    public static List<Op> append(List<Op> wrapped, List<Op> block) {
        List<Op> out = new ArrayList<>(wrapped.size() + block.size() + 2);
        out.addAll(wrapped);
        out.add(new Op("BMC", new PdfObject[] { PdfName.of("SPDF") }));
        out.addAll(block);
        out.add(new Op("EMC", new PdfObject[0]));
        return out;
    }

    /** Replaces a page's content with operations, as a new compressed stream. */
    public static void setPageOps(EditHistory h, PdfPage page, int pageIndex, List<Op> ops) {
        h.touchPage(pageIndex, page.objNum());
        PdfStream s = PdfStream.of(ContentParser.write(ops), true);
        PdfRef ref = h.add(s);
        page.dict().put("Contents", ref);
    }

    /** Replaces a form XObject's content. */
    public static void setFormOps(EditHistory h, PdfDocument doc, int formNum, List<Op> ops) {
        h.touch(formNum);
        if (doc.getObject(formNum) instanceof PdfStream s) {
            s.setData(ContentParser.write(ops), true);
        }
    }

    /** Removes glyphs wherever they are (page content and forms) and returns the page's new operations. */
    public static List<Op> removeEverywhere(EditHistory h, PdfDocument doc, List<Op> pageOps,
                                            Collection<TextGlyph> glyphs) {
        Map<Integer, Map<Integer, Removal>> groups = group(glyphs);
        List<Op> page = pageOps;
        for (Map.Entry<Integer, Map<Integer, Removal>> e : groups.entrySet()) {
            int sid = e.getKey();
            if (sid == -1) {
                page = removeGlyphs(page, e.getValue());
            } else if (doc.getObject(sid) instanceof PdfStream form) {
                List<Op> fops = ContentParser.parse(form.getDecoded());
                setFormOps(h, doc, sid, removeGlyphs(fops, e.getValue()));
            }
        }
        return page;
    }
}
