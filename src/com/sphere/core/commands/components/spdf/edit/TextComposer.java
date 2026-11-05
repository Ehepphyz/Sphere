package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfString;
import com.sphere.components.spdf.render.Op;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays rich text out in a box: words wrapped to the width, each paragraph
 * aligned (left, centered, right, justified with the slack shared between its
 * spaces), first-line indents, raised and lowered characters, line spacing
 * from the largest size of each line. The same layout feeds the editor's
 * on-page preview and the content written to the PDF, so the page shows
 * exactly what was typed.
 *
 * Layout coordinates: x along the text, y up, the first baseline at y = 0.
 */
public final class TextComposer {

    private TextComposer() {
    }

    /** A character placed on a line. */
    public static final class Placed {
        public int index;
        public int cp;
        public RichText.Style style;
        public OutputFont font;
        public byte[] code;
        public float x;
        public float width;
        public boolean space;
    }

    public static final class Line {
        public final List<Placed> glyphs = new ArrayList<>();
        /** The baseline's y (0 for the first line, negative below). */
        public float baseline;
        public float ascent;
        public float descent;
        public float width;
        public int start;
        public int end;
        public int para;
        public float maxSize;
    }

    public static final class Composed {
        public final List<Line> lines = new ArrayList<>();
        public float boxWidth;
        public float top;
        public float bottom;
        /** Characters no font could show. */
        public int missing;

        /** The caret position (x, baseline, ascent, descent) before a character index. */
        public float[] caret(int index) {
            Line best = null;
            for (Line l : lines) {
                if (index >= l.start && index <= l.end) {
                    best = l;
                    if (index < l.end || l == lines.get(lines.size() - 1)) {
                        break;
                    }
                }
            }
            if (best == null) {
                best = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            }
            if (best == null) {
                return new float[] { 0, 0, 10, 3 };
            }
            float x = best.glyphs.isEmpty() ? lineStartX(best) : best.glyphs.get(0).x;
            for (Placed p : best.glyphs) {
                if (p.index < index) {
                    x = p.x + p.width;
                } else {
                    x = p.x;
                    break;
                }
            }
            return new float[] { x, best.baseline, Math.max(best.ascent, 1), Math.max(best.descent, 1) };
        }

        private float lineStartX(Line l) {
            return 0;
        }

        /** The character index nearest a point (layout coordinates). */
        public int indexAt(float x, float y) {
            Line best = null;
            float bestD = Float.MAX_VALUE;
            for (Line l : lines) {
                float top = l.baseline + l.ascent;
                float bot = l.baseline - l.descent;
                float d = y > top ? y - top : y < bot ? bot - y : 0;
                if (d < bestD) {
                    bestD = d;
                    best = l;
                }
            }
            if (best == null) {
                return 0;
            }
            for (Placed p : best.glyphs) {
                if (x < p.x + p.width / 2) {
                    return p.index;
                }
            }
            return best.end;
        }

        public float height() {
            return top - bottom;
        }
    }

    /** Lays text out in a box of a width. */
    public static Composed compose(RichText t, float boxWidth, FontResolver fonts) {
        Composed c = new Composed();
        c.boxWidth = boxWidth;
        String s = t.text();
        int n = s.length();
        int paraStart = 0;
        int para = 0;
        float baseline = 0;
        boolean firstLine = true;
        float prevDescentGap = 0;
        while (paraStart <= n) {
            int paraEnd = s.indexOf('\n', paraStart);
            if (paraEnd < 0) {
                paraEnd = n;
            }
            RichText.Para ps = t.para(para);
            // measure every character of the paragraph
            List<Placed> items = new ArrayList<>();
            for (int i = paraStart; i < paraEnd; ) {
                int cp = s.codePointAt(i);
                int len = Character.charCount(cp);
                Placed p = new Placed();
                p.index = i;
                p.cp = cp;
                p.style = t.styleAt(i);
                p.space = cp == ' ' || cp == '\t' || cp == 0xA0;
                int shown = cp == '\t' ? ' ' : cp;
                FontResolver.Choice ch = p.style == null ? null : fonts.choose(p.style, shown);
                if (ch == null && p.style != null) {
                    ch = fonts.choose(p.style, '?');
                    c.missing++;
                }
                if (ch != null) {
                    p.font = ch.font();
                    p.code = ch.code();
                    p.width = ch.font().width(ch.code()) * p.style.size * (cp == '\t' ? 4 : 1);
                }
                items.add(p);
                i += len;
            }
            // break into lines
            List<List<Placed>> rows = new ArrayList<>();
            List<Placed> row = new ArrayList<>();
            float rowW = 0;
            int i = 0;
            boolean first = true;
            while (i < items.size()) {
                // the next word: spaces before it, then its letters
                int j = i;
                float wSpaces = 0;
                while (j < items.size() && items.get(j).space) {
                    wSpaces += items.get(j).width;
                    j++;
                }
                int k = j;
                float wWord = 0;
                while (k < items.size() && !items.get(k).space) {
                    wWord += items.get(k).width;
                    k++;
                }
                float avail = boxWidth - (first ? ps.firstIndent : 0);
                if (!row.isEmpty() && rowW + wSpaces + wWord > avail + 0.01f && wWord > 0) {
                    // the spaces stay at the end of the full row (they hang)
                    for (int q = i; q < j; q++) {
                        row.add(items.get(q));
                    }
                    rows.add(row);
                    row = new ArrayList<>();
                    rowW = 0;
                    first = false;
                    i = j;
                    continue;
                }
                if (row.isEmpty() && wWord > avail && k - j > 1) {
                    // a word longer than the box is cut where it overflows
                    float w = 0;
                    int q = j;
                    while (q < k && (w + items.get(q).width <= avail || q == j)) {
                        w += items.get(q).width;
                        q++;
                    }
                    for (int z = i; z < q; z++) {
                        row.add(items.get(z));
                    }
                    rows.add(row);
                    row = new ArrayList<>();
                    rowW = 0;
                    first = false;
                    i = q;
                    continue;
                }
                for (int q = i; q < k; q++) {
                    row.add(items.get(q));
                }
                rowW += wSpaces + wWord;
                i = k;
            }
            rows.add(row);
            // place the rows
            for (int r = 0; r < rows.size(); r++) {
                List<Placed> rr = rows.get(r);
                Line line = new Line();
                line.para = para;
                RichText.Style fallback = t.styleAt(Math.max(0, Math.min(n - 1, paraStart > 0 ? paraStart - 1 : paraStart)));
                float maxSize = 0;
                float asc = 0;
                float desc = 0;
                for (Placed p : rr) {
                    if (p.style == null) {
                        continue;
                    }
                    maxSize = Math.max(maxSize, p.style.size);
                    float a = (p.font == null ? 0.8f : Math.max(0.6f, p.font.ascent())) * p.style.size + p.style.rise;
                    float d = (p.font == null ? 0.2f : Math.max(0.15f, -p.font.descent())) * p.style.size - p.style.rise;
                    asc = Math.max(asc, a);
                    desc = Math.max(desc, d);
                }
                if (maxSize == 0) {
                    maxSize = fallback == null ? 12 : fallback.size;
                    asc = maxSize * 0.8f;
                    desc = maxSize * 0.2f;
                }
                line.maxSize = maxSize;
                line.ascent = asc;
                line.descent = desc;
                if (!firstLine) {
                    baseline -= Math.max(maxSize * ps.lineHeight, prevDescentGap > 0 ? 0 : 0);
                }
                line.baseline = baseline;
                firstLine = false;
                // natural width without the hanging spaces
                int last = rr.size() - 1;
                while (last >= 0 && rr.get(last).space) {
                    last--;
                }
                float natural = 0;
                int spaces = 0;
                for (int q = 0; q <= last; q++) {
                    natural += rr.get(q).width;
                    if (rr.get(q).space) {
                        spaces++;
                    }
                }
                float indent = r == 0 ? ps.firstIndent : 0;
                float avail = boxWidth - indent;
                float x = indent;
                float extra = 0;
                boolean lastOfPara = r == rows.size() - 1;
                switch (ps.align) {
                    case CENTER -> x = indent + (avail - natural) / 2;
                    case RIGHT -> x = indent + (avail - natural);
                    case JUSTIFY -> {
                        if (!lastOfPara && spaces > 0 && natural < avail) {
                            extra = (avail - natural) / spaces;
                        }
                    }
                    default -> { }
                }
                for (int q = 0; q < rr.size(); q++) {
                    Placed p = rr.get(q);
                    p.x = x;
                    x += p.width;
                    if (p.space && q <= last) {
                        x += extra;
                        p.width += extra;
                    }
                    line.glyphs.add(p);
                }
                line.width = natural + extra * spaces;
                line.start = rr.isEmpty() ? paraStart : rr.get(0).index;
                line.end = rr.isEmpty() ? paraStart : rr.get(rr.size() - 1).index
                    + Character.charCount(rr.get(rr.size() - 1).cp);
                if (lastOfPara) {
                    line.end = paraEnd;
                }
                if (r == 0) {
                    line.start = paraStart;
                }
                c.lines.add(line);
            }
            baseline -= ps.spaceAfter;
            para++;
            paraStart = paraEnd + 1;
            if (paraEnd >= n) {
                break;
            }
        }
        if (!c.lines.isEmpty()) {
            c.top = c.lines.get(0).ascent;
            Line l = c.lines.get(c.lines.size() - 1);
            c.bottom = l.baseline - l.descent;
        }
        return c;
    }

    /* ------------------------------------------------------------- content */

    /**
     * The operations drawing composed text: layout coordinates mapped to the
     * page by localToUser (a cm), runs of one font, size, color and raise
     * shown with TJ, underline and strike-through drawn as thin rectangles.
     */
    public static List<Op> toOps(Composed c, AffineTransform localToUser, Map<OutputFont, String> names) {
        List<Op> ops = new ArrayList<>();
        ops.add(op("q"));
        double[] m = new double[6];
        localToUser.getMatrix(m);
        ops.add(op("cm", num(m[0]), num(m[1]), num(m[2]), num(m[3]), num(m[4]), num(m[5])));
        ops.add(op("BT"));
        OutputFont curFont = null;
        float curSize = -1;
        int curColor = -1;
        float curRise = 0;
        List<float[]> lines = new ArrayList<>();
        for (Line line : c.lines) {
            int i = 0;
            List<Placed> gs = line.glyphs;
            while (i < gs.size()) {
                Placed first = gs.get(i);
                if (first.font == null || first.code == null) {
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < gs.size() && same(gs.get(j), first)) {
                    j++;
                }
                // trailing spaces of a line draw nothing
                int end = j;
                if (j == gs.size()) {
                    while (end > i && gs.get(end - 1).space) {
                        end--;
                    }
                }
                if (end > i) {
                    RichText.Style st = first.style;
                    if (first.font != curFont || st.size != curSize) {
                        ops.add(op("Tf", PdfName.of(names.get(first.font)), num(st.size)));
                        curFont = first.font;
                        curSize = st.size;
                    }
                    if (st.color != curColor) {
                        ops.add(op("rg", num(((st.color >> 16) & 0xff) / 255.0), num(((st.color >> 8) & 0xff) / 255.0),
                                   num((st.color & 0xff) / 255.0)));
                        curColor = st.color;
                    }
                    if (st.rise != curRise) {
                        ops.add(op("Ts", num(st.rise)));
                        curRise = st.rise;
                    }
                    ops.add(op("Tm", num(1), num(0), num(0), num(1), num(first.x), num(line.baseline)));
                    PdfArray arr = new PdfArray();
                    com.sphere.components.spdf.cos.ByteBuf buf = new com.sphere.components.spdf.cos.ByteBuf();
                    float x = first.x;
                    for (int k = i; k < end; k++) {
                        Placed p = gs.get(k);
                        float natural = p.font.width(p.code) * st.size * (p.cp == '\t' ? 4 : 1);
                        if (Math.abs(p.x - x) > 0.001f) {
                            // a justified gap: move by the difference
                            if (buf.size() > 0) {
                                arr.add(new PdfString(buf.toBytes(), true));
                                buf.clear();
                            }
                            arr.add(num(-(p.x - x) * 1000 / st.size));
                            x = p.x;
                        }
                        if (p.cp == '\t') {
                            // a tab: four spaces' worth, as one adjustment
                            if (buf.size() > 0) {
                                arr.add(new PdfString(buf.toBytes(), true));
                                buf.clear();
                            }
                            arr.add(num(-natural * 1000 / st.size));
                        } else {
                            buf.put(p.code);
                            p.font.used(p.code);
                        }
                        x += natural;
                    }
                    if (buf.size() > 0) {
                        arr.add(new PdfString(buf.toBytes(), true));
                    }
                    ops.add(op("TJ", arr));
                    if (st.underline || st.strike) {
                        float x0 = first.x;
                        Placed lastG = gs.get(end - 1);
                        float x1 = lastG.x + lastG.width;
                        float th = Math.max(0.4f, st.size * 0.055f);
                        if (st.underline) {
                            lines.add(new float[] { x0, line.baseline + st.rise - st.size * 0.12f - th / 2, x1 - x0, th,
                                                    st.color });
                        }
                        if (st.strike) {
                            lines.add(new float[] { x0, line.baseline + st.rise + st.size * 0.28f - th / 2, x1 - x0, th,
                                                    st.color });
                        }
                    }
                }
                i = j;
            }
        }
        ops.add(op("ET"));
        for (float[] r : lines) {
            int col = (int) r[4];
            ops.add(op("rg", num(((col >> 16) & 0xff) / 255.0), num(((col >> 8) & 0xff) / 255.0), num((col & 0xff) / 255.0)));
            ops.add(op("re", num(r[0]), num(r[1]), num(r[2]), num(r[3])));
            ops.add(op("f"));
        }
        ops.add(op("Q"));
        return ops;
    }

    private static boolean same(Placed a, Placed b) {
        return a.font == b.font && a.style != null && b.style != null && a.style.size == b.style.size
            && a.style.color == b.style.color && a.style.rise == b.style.rise && a.style.underline == b.style.underline
            && a.style.strike == b.style.strike;
    }

    static Op op(String name, PdfObject... args) {
        return new Op(name, args);
    }

    static PdfNumber num(double v) {
        return PdfNumber.of(Math.round(v * 10000.0) / 10000.0);
    }

    /** Resource names for the fonts a layout uses, registered in a /Font dictionary. */
    public static Map<OutputFont, String> fontNames(Composed c) {
        Map<OutputFont, String> out = new LinkedHashMap<>();
        for (Line l : c.lines) {
            for (Placed p : l.glyphs) {
                if (p.font != null) {
                    out.putIfAbsent(p.font, null);
                }
            }
        }
        return out;
    }
}
