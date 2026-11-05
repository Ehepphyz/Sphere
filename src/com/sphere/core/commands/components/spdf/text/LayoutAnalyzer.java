package com.sphere.components.spdf.text;

import com.sphere.components.spdf.render.TextGlyph;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the structure a PDF does not store: glyphs become lines (one
 * baseline, superscripts kept, columns kept apart), lines become paragraphs
 * (steady spacing, overlapping extent, paragraph ends and first-line indents
 * noticed), paragraphs get their alignment, and the page its reading order by
 * recursive XY-cut, which follows columns, full-width titles and figures.
 */
public final class LayoutAnalyzer {

    private LayoutAnalyzer() {
    }

    public static PageText analyze(List<TextGlyph> input, double pageWidth) {
        List<TextGlyph> glyphs = new ArrayList<>();
        for (TextGlyph g : input) {
            if (g.size > 0.5 && g.text != null && !g.text.isEmpty()) {
                glyphs.add(g);
            }
        }
        double body = bodySize(glyphs);
        // one group per direction: rotated text is laid out in its own frame
        Map<Long, List<TextGlyph>> byDir = new LinkedHashMap<>();
        for (TextGlyph g : glyphs) {
            double a = Math.atan2(g.ey - g.y, g.ex - g.x);
            if (g.ex == g.x && g.ey == g.y) {
                // no advance: the baseline runs at right angles to "up"
                a = Math.atan2(g.ux, -g.uy);
            }
            long key = Math.round(Math.toDegrees(a));
            key = ((key % 360) + 360) % 360;
            byDir.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }
        List<TextLine> allLines = new ArrayList<>();
        List<TextBlock> allBlocks = new ArrayList<>();
        for (Map.Entry<Long, List<TextGlyph>> e : byDir.entrySet()) {
            double a = Math.toRadians(e.getKey());
            double dx = Math.cos(a);
            double dy = Math.sin(a);
            List<TextLine> lines = buildLines(e.getValue(), dx, dy);
            allLines.addAll(lines);
            allBlocks.addAll(buildBlocks(lines, dx, dy, pageWidth));
        }
        List<TextBlock> ordered = readingOrder(allBlocks);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).order = i;
        }
        headings(ordered, body);
        return new PageText(glyphs, allLines, ordered, body);
    }

    static double bodySize(List<TextGlyph> glyphs) {
        Map<Long, Integer> hist = new HashMap<>();
        for (TextGlyph g : glyphs) {
            if (!g.isSpace()) {
                hist.merge(Math.round(g.size * 2.0), 1, Integer::sum);
            }
        }
        long best = 20;
        int n = -1;
        for (Map.Entry<Long, Integer> e : hist.entrySet()) {
            if (e.getValue() > n) {
                n = e.getValue();
                best = e.getKey();
            }
        }
        return best / 2.0;
    }

    /* ------------------------------------------------------------- lines */

    private static List<TextLine> buildLines(List<TextGlyph> glyphs, double dx, double dy) {
        List<TextLine> lines = new ArrayList<>();
        TextLine cur = null;
        for (TextGlyph g : glyphs) {
            if (cur != null && fits(cur, g)) {
                cur.add(g);
                continue;
            }
            if (g.isSpace() && cur != null) {
                // a lone space glyph does not start a line
                continue;
            }
            cur = newLine(dx, dy);
            cur.add(g);
            lines.add(cur);
        }
        // merge pieces of the same line drawn at different times
        lines.sort(Comparator.comparingDouble((TextLine l) -> l.v).thenComparingDouble(l -> l.u0));
        List<TextLine> merged = new ArrayList<>();
        for (TextLine l : lines) {
            TextLine host = null;
            for (int i = merged.size() - 1; i >= 0 && i >= merged.size() - 12; i--) {
                TextLine m = merged.get(i);
                double s = Math.max(m.size, l.size);
                if (Math.abs(m.v - l.v) < 0.3 * s) {
                    double gapRight = l.u0 - m.u1;
                    double gapLeft = m.u0 - l.u1;
                    boolean overlaps = l.u0 < m.u1 && l.u1 > m.u0;
                    if ((gapRight > -0.3 * s && gapRight < 1.3 * s) || (gapLeft > -0.3 * s && gapLeft < 1.3 * s)
                        || overlaps && overlapRatio(m, l) > 0.5) {
                        host = m;
                        break;
                    }
                }
            }
            if (host == null) {
                merged.add(l);
            } else {
                for (TextGlyph g : l.glyphs) {
                    host.add(g);
                }
            }
        }
        for (TextLine l : merged) {
            sortAndDedupe(l);
        }
        List<double[]> gutters = gutters(merged);
        List<TextLine> out = new ArrayList<>();
        for (TextLine l : merged) {
            out.addAll(splitAndSpace(l, dx, dy, gutters));
        }
        return out;
    }

    /**
     * Column gutters: wide gaps that several lines share at the same place.
     * A gap of 1.4 em is a gutter when three lines have it there, and only a
     * wide word space when one line has it alone.
     */
    private static List<double[]> gutters(List<TextLine> lines) {
        List<double[]> gaps = new ArrayList<>();
        for (TextLine l : lines) {
            TextGlyph p = null;
            for (TextGlyph g : l.glyphs) {
                if (g.isSpace()) {
                    continue;
                }
                if (p != null) {
                    double a = l.uOf(p.ex, p.ey);
                    double b = l.uOf(g.x, g.y);
                    double s = Math.max(p.size, g.size);
                    if (b - a > 0.8 * s) {
                        gaps.add(new double[] { a, b, l.v, s });
                    }
                }
                p = g;
            }
        }
        List<double[]> out = new ArrayList<>();
        for (double[] g : gaps) {
            double c = (g[0] + g[1]) / 2;
            int n = 0;
            for (double[] h : gaps) {
                if (h[0] <= c && h[1] >= c) {
                    n++;
                }
            }
            if (n >= 3) {
                out.add(g);
            }
        }
        return out;
    }

    private static double overlapRatio(TextLine a, TextLine b) {
        double ov = Math.min(a.u1, b.u1) - Math.max(a.u0, b.u0);
        double w = Math.min(a.u1 - a.u0, b.u1 - b.u0);
        return w <= 0 ? 0 : ov / w;
    }

    private static TextLine newLine(double dx, double dy) {
        TextLine l = new TextLine();
        l.dx = dx;
        l.dy = dy;
        return l;
    }

    private static boolean fits(TextLine cur, TextGlyph g) {
        double s = Math.max(cur.size, g.size);
        double gu = cur.uOf(g.x, g.y);
        double gv = cur.vOf(g.x, g.y);
        double dv = Math.abs(gv - cur.v);
        boolean script = g.size < cur.size * 0.9 || cur.size < g.size * 0.9;
        double vtol = script ? 0.6 * s : 0.3 * s;
        if (dv > vtol) {
            return false;
        }
        double du = gu - cur.u1;
        return du > -0.6 * s && du < 1.3 * s;
    }

    private static void sortAndDedupe(TextLine l) {
        List<TextGlyph> gs = new ArrayList<>(l.glyphs);
        gs.sort(Comparator.comparingDouble(g -> l.uOf(g.x, g.y)));
        List<TextGlyph> keep = new ArrayList<>();
        for (TextGlyph g : gs) {
            boolean dup = false;
            for (int k = keep.size() - 1; k >= 0 && k >= keep.size() - 3; k--) {
                TextGlyph p = keep.get(k);
                // the same glyph drawn twice, a hair apart: simulated bold, or fill then stroke
                if (p.text.equals(g.text) && Math.abs(l.uOf(p.x, p.y) - l.uOf(g.x, g.y)) < 0.12 * g.size
                    && Math.abs(l.vOf(p.x, p.y) - l.vOf(g.x, g.y)) < 0.12 * g.size && !g.isSpace()) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                keep.add(g);
            }
        }
        l.glyphs.clear();
        l.glyphs.addAll(keep);
    }

    /** Inserts the implied spaces, and splits the line where a gap is a column gutter. */
    private static List<TextLine> splitAndSpace(TextLine l, double dx, double dy, List<double[]> gutters) {
        List<TextLine> out = new ArrayList<>();
        List<TextGlyph> gs = l.glyphs;
        if (gs.isEmpty()) {
            return out;
        }
        // the typical gap between letters, so letter-spaced titles are not split into letters
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < gs.size(); i++) {
            gaps.add(l.uOf(gs.get(i).x, gs.get(i).y) - l.uOf(gs.get(i - 1).ex, gs.get(i - 1).ey));
        }
        double median = 0;
        if (!gaps.isEmpty()) {
            List<Double> sorted = new ArrayList<>(gaps);
            sorted.sort(Double::compare);
            median = Math.max(0, sorted.get(sorted.size() / 2));
        }
        TextLine cur = newLine(dx, dy);
        cur.add(gs.get(0));
        cur.spaceBefore.add(false);
        out.add(cur);
        TextGlyph solid = gs.get(0).isSpace() ? null : gs.get(0);
        for (int i = 1; i < gs.size(); i++) {
            TextGlyph p = gs.get(i - 1);
            TextGlyph g = gs.get(i);
            double s = Math.min(p.size, g.size) > 0 ? (p.size + g.size) / 2 : g.size;
            double gap = gaps.get(i - 1);
            boolean split = false;
            if (!g.isSpace() && solid != null) {
                // the gap from the last drawn letter, over any space glyphs between
                double a = l.uOf(solid.ex, solid.ey);
                double b = l.uOf(g.x, g.y);
                double wide = b - a;
                if (wide > 2.2 * s) {
                    split = true;
                } else if (wide > 0.8 * s) {
                    for (double[] gt : gutters) {
                        double c = (gt[0] + gt[1]) / 2;
                        if (a <= c && b >= c) {
                            split = true;
                            break;
                        }
                    }
                }
            }
            if (!g.isSpace()) {
                solid = g;
            }
            if (split) {
                cur = newLine(dx, dy);
                cur.add(g);
                cur.spaceBefore.add(false);
                out.add(cur);
                continue;
            }
            boolean space = !p.isSpace() && !g.isSpace() && gap > Math.max(0.15 * s, median * 1.8 + 0.05 * s);
            cur.add(g);
            cur.spaceBefore.add(space);
        }
        // a line's leading and trailing space glyphs carry nothing
        for (TextLine t : out) {
            while (t.glyphs.size() > 1 && t.last().isSpace()) {
                t.glyphs.remove(t.glyphs.size() - 1);
                t.spaceBefore.remove(t.spaceBefore.size() - 1);
            }
            recompute(t);
        }
        out.removeIf(t -> t.glyphs.isEmpty() || t.glyphs.stream().allMatch(TextGlyph::isSpace));
        return out;
    }

    private static void recompute(TextLine t) {
        List<TextGlyph> gs = new ArrayList<>(t.glyphs);
        List<Boolean> sp = new ArrayList<>(t.spaceBefore);
        t.glyphs.clear();
        t.spaceBefore.clear();
        for (int i = 0; i < gs.size(); i++) {
            t.add(gs.get(i));
            t.spaceBefore.add(i == 0 ? false : sp.get(i));
        }
        t.invalidate();
    }

    /* ------------------------------------------------------------- paragraphs */

    /** The usual baseline-to-baseline distance of the page's lines, by rounded size. */
    private static Map<Long, Double> typicalSpacing(List<TextLine> sorted) {
        Map<Long, List<Double>> by = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            TextLine a = sorted.get(i);
            for (int j = i + 1; j < sorted.size() && j < i + 40; j++) {
                TextLine b = sorted.get(j);
                double d = b.v - a.v;
                if (d < 0.5 * a.size) {
                    continue;
                }
                if (d > 2.5 * a.size) {
                    break;
                }
                if (Math.abs(a.size - b.size) < 0.15 * a.size && overlapRatio(a, b) > 0.6) {
                    by.computeIfAbsent(Math.round(a.size), k -> new ArrayList<>()).add(d);
                    break;
                }
            }
        }
        Map<Long, Double> out = new HashMap<>();
        for (Map.Entry<Long, List<Double>> e : by.entrySet()) {
            List<Double> v = e.getValue();
            if (v.size() >= 3) {
                v.sort(Double::compare);
                out.put(e.getKey(), v.get(v.size() / 2));
            }
        }
        return out;
    }

    private static List<TextBlock> buildBlocks(List<TextLine> lines, double dx, double dy, double pageWidth) {
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble((TextLine l) -> l.v).thenComparingDouble(l -> l.u0));
        Map<Long, Double> typical = typicalSpacing(sorted);
        List<TextBlock> blocks = new ArrayList<>();
        for (TextLine l : sorted) {
            TextBlock best = null;
            double bestScore = Double.MAX_VALUE;
            for (TextBlock b : blocks) {
                TextLine p = b.lines.get(b.lines.size() - 1);
                double s = Math.max(p.size, l.size);
                double d = l.v - p.v;
                if (d < 0.5 * s || d > 2.0 * s) {
                    continue;
                }
                double ratio = Math.max(p.size, l.size) / Math.max(0.1, Math.min(p.size, l.size));
                if (ratio > 1.3) {
                    continue;
                }
                Double typ = typical.get(Math.round(l.size));
                if (typ != null && d > typ * 1.3) {
                    // more room than lines of this size usually get: a new paragraph or a heading
                    continue;
                }
                double bu0 = b.u0();
                double bu1 = b.u1();
                double overlap = Math.min(bu1, l.u1) - Math.max(bu0, l.u0);
                if (overlap < -0.3 * s) {
                    continue;
                }
                if (Math.abs(l.u0 - bu0) > 4 * s && Math.abs(l.u1 - bu1) > 4 * s
                    && Math.abs((l.u0 + l.u1) / 2 - (bu0 + bu1) / 2) > 2 * s) {
                    continue;
                }
                if (b.lines.size() >= 2 && b.lineSpacing > 0 && Math.abs(d - b.lineSpacing) > 0.3 * s) {
                    continue;
                }
                // the previous line of a justified paragraph ending short closed the paragraph
                if (b.lines.size() >= 2 && rightAligned(b, 0.3 * s) && p.u1 < bu1 - 2.5 * s) {
                    continue;
                }
                // a first-line indent after a full line opens a new paragraph
                if (b.lines.size() >= 2 && l.u0 > bu0 + 0.8 * s && l.u0 < bu0 + 5 * s
                    && Math.abs(p.u0 - bu0) < 0.3 * s && p.u1 > bu1 - 0.5 * s && leftAligned(b, 0.3 * s)) {
                    continue;
                }
                double score = d + Math.abs(l.u0 - bu0) * 0.05 - Math.max(0, overlap) * 0.001;
                if (score < bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best == null) {
                best = new TextBlock();
                best.dx = dx;
                best.dy = dy;
                blocks.add(best);
            } else if (best.lines.size() == 1) {
                best.lineSpacing = l.v - best.lines.get(0).v;
            }
            best.lines.add(l);
        }
        for (TextBlock b : blocks) {
            alignment(b, pageWidth);
        }
        return blocks;
    }

    private static boolean rightAligned(TextBlock b, double tol) {
        double r = b.u1();
        for (int i = 0; i < b.lines.size() - 1; i++) {
            if (Math.abs(b.lines.get(i).u1 - r) > tol) {
                return false;
            }
        }
        return true;
    }

    private static boolean leftAligned(TextBlock b, double tol) {
        double l0 = b.lines.size() > 1 ? b.lines.get(1).u0 : b.lines.get(0).u0;
        for (int i = 1; i < b.lines.size(); i++) {
            if (Math.abs(b.lines.get(i).u0 - l0) > tol) {
                return false;
            }
        }
        return true;
    }

    private static void alignment(TextBlock b, double pageWidth) {
        int n = b.lines.size();
        double s = b.size();
        double tol = Math.max(0.8, 0.2 * s);
        if (n == 1) {
            TextLine l = b.lines.get(0);
            double c = (l.u0 + l.u1) / 2;
            b.align = pageWidth > 0 && b.isUpright() && Math.abs(c - pageWidth / 2) < 3 && l.u0 > pageWidth * 0.15
                ? TextBlock.Align.CENTER : TextBlock.Align.LEFT;
            return;
        }
        double left = b.lines.get(n > 1 ? 1 : 0).u0;
        boolean leftOk = true;
        for (int i = 1; i < n; i++) {
            leftOk &= Math.abs(b.lines.get(i).u0 - left) < tol;
        }
        if (n == 2) {
            // two lines: an indent cannot be told from centering by the second line alone
            leftOk = Math.abs(b.lines.get(0).u0 - left) < tol
                || (b.lines.get(0).u0 > left && b.lines.get(0).u0 - left < 4 * s
                    && Math.abs(b.lines.get(0).u1 - b.lines.get(1).u1) < tol);
        }
        double right = b.u1();
        boolean rightOk = true;
        for (int i = 0; i < n - 1; i++) {
            rightOk &= Math.abs(b.lines.get(i).u1 - right) < tol;
        }
        boolean lastRight = Math.abs(b.lines.get(n - 1).u1 - right) < tol;
        double center = (b.lines.get(0).u0 + b.lines.get(0).u1) / 2;
        boolean centered = true;
        for (TextLine l : b.lines) {
            centered &= Math.abs((l.u0 + l.u1) / 2 - center) < tol;
        }
        b.firstIndent = b.lines.get(0).u0 - left;
        if (Math.abs(b.firstIndent) < tol) {
            b.firstIndent = 0;
        }
        if (leftOk && rightOk && (n >= 3 || !lastRight)) {
            boolean firstLeftOk = Math.abs(b.lines.get(0).u0 - left) < tol || b.firstIndent > 0;
            b.align = firstLeftOk ? TextBlock.Align.JUSTIFY : TextBlock.Align.LEFT;
            if (n == 2 && lastRight) {
                b.align = TextBlock.Align.LEFT;
            }
        } else if (leftOk) {
            b.align = TextBlock.Align.LEFT;
        } else if (rightOk && lastRight) {
            b.align = TextBlock.Align.RIGHT;
        } else if (centered) {
            b.align = TextBlock.Align.CENTER;
        } else {
            b.align = TextBlock.Align.LEFT;
        }
        if (b.align != TextBlock.Align.LEFT && b.align != TextBlock.Align.JUSTIFY) {
            b.firstIndent = 0;
        }
    }

    /* ------------------------------------------------------------- reading order */

    private static List<TextBlock> readingOrder(List<TextBlock> blocks) {
        List<TextBlock> out = new ArrayList<>();
        Map<TextBlock, Rectangle2D> boxes = new HashMap<>();
        for (TextBlock b : blocks) {
            boxes.put(b, b.bounds());
        }
        xyCut(blocks, boxes, out, 0);
        return out;
    }

    private static void xyCut(List<TextBlock> bs, Map<TextBlock, Rectangle2D> box, List<TextBlock> out, int depth) {
        if (bs.size() <= 1 || depth > 40) {
            List<TextBlock> s = new ArrayList<>(bs);
            s.sort(Comparator.comparingDouble((TextBlock b) -> box.get(b).getMinY())
                             .thenComparingDouble(b -> box.get(b).getMinX()));
            out.addAll(s);
            return;
        }
        // widest horizontal gap (between rows)
        List<TextBlock> byY = new ArrayList<>(bs);
        byY.sort(Comparator.comparingDouble(b -> box.get(b).getMinY()));
        double hGap = -1;
        double hCut = 0;
        double maxY = box.get(byY.get(0)).getMaxY();
        for (int i = 1; i < byY.size(); i++) {
            Rectangle2D r = box.get(byY.get(i));
            double gap = r.getMinY() - maxY;
            if (gap > hGap) {
                hGap = gap;
                hCut = (maxY + r.getMinY()) / 2;
            }
            maxY = Math.max(maxY, r.getMaxY());
        }
        // widest vertical gap (between columns)
        List<TextBlock> byX = new ArrayList<>(bs);
        byX.sort(Comparator.comparingDouble(b -> box.get(b).getMinX()));
        double vGap = -1;
        double vCut = 0;
        double maxX = box.get(byX.get(0)).getMaxX();
        for (int i = 1; i < byX.size(); i++) {
            Rectangle2D r = box.get(byX.get(i));
            double gap = r.getMinX() - maxX;
            if (gap > vGap) {
                vGap = gap;
                vCut = (maxX + r.getMinX()) / 2;
            }
            maxX = Math.max(maxX, r.getMaxX());
        }
        boolean vertical;
        if (vGap > 3 && vGap * 1.2 >= hGap) {
            vertical = true;
        } else if (hGap > 0.5) {
            vertical = false;
        } else if (vGap > 0.5) {
            vertical = true;
        } else {
            List<TextBlock> s = new ArrayList<>(bs);
            s.sort(Comparator.comparingDouble((TextBlock b) -> box.get(b).getMinY())
                             .thenComparingDouble(b -> box.get(b).getMinX()));
            out.addAll(s);
            return;
        }
        List<TextBlock> a = new ArrayList<>();
        List<TextBlock> b = new ArrayList<>();
        for (TextBlock t : bs) {
            Rectangle2D r = box.get(t);
            double c = vertical ? r.getCenterX() : r.getCenterY();
            (c < (vertical ? vCut : hCut) ? a : b).add(t);
        }
        if (a.isEmpty() || b.isEmpty()) {
            List<TextBlock> s = new ArrayList<>(bs);
            s.sort(Comparator.comparingDouble((TextBlock x) -> box.get(x).getMinY())
                             .thenComparingDouble(x -> box.get(x).getMinX()));
            out.addAll(s);
            return;
        }
        xyCut(a, box, out, depth + 1);
        xyCut(b, box, out, depth + 1);
    }

    /* ------------------------------------------------------------- headings */

    private static void headings(List<TextBlock> blocks, double body) {
        List<Double> sizes = new ArrayList<>();
        for (TextBlock b : blocks) {
            double s = b.size();
            String t = b.text().trim();
            if (s > body * 1.12 && t.length() < 200 && b.lines.size() <= 3) {
                boolean known = false;
                for (double x : sizes) {
                    known |= Math.abs(x - s) < 0.5;
                }
                if (!known) {
                    sizes.add(s);
                }
            }
        }
        sizes.sort((x, y) -> Double.compare(y, x));
        for (TextBlock b : blocks) {
            double s = b.size();
            String t = b.text().trim();
            if (t.isEmpty() || t.length() > 200 || b.lines.size() > 3) {
                continue;
            }
            for (int i = 0; i < sizes.size(); i++) {
                if (Math.abs(sizes.get(i) - s) < 0.5) {
                    b.headingLevel = Math.min(4, i + 1);
                }
            }
            if (b.headingLevel == 0) {
                TextGlyph d = b.dominant();
                boolean boldish = d != null && d.font != null && d.font.bold;
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^((\\d+)(\\.\\d+)*|[IVX]+|[A-Z])[.)]?\\s+\\S.*").matcher(t);
                if (boldish && t.length() < 120 && b.lines.size() == 1 && (m.matches() || Character.isUpperCase(t.charAt(0)))) {
                    int depth = 1;
                    if (m.matches() && m.group(1) != null) {
                        depth = m.group(1).split("\\.").length;
                    }
                    b.headingLevel = Math.min(4, sizes.size() + depth);
                }
            }
        }
    }
}
