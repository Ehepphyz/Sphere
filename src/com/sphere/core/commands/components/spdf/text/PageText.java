package com.sphere.components.spdf.text;

import com.sphere.components.spdf.render.TextGlyph;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The text of a page as people read it: glyphs, lines, paragraphs in reading
 * order (columns followed one after the other), and the page's text as one
 * string whose every character points back to its glyph. Selection, search,
 * copy, the outline, the insights and the editor all start from here.
 */
public final class PageText {

    public final List<TextGlyph> glyphs;
    public final List<TextLine> lines;
    public final List<TextBlock> blocks;
    /** The page's text in reading order; blocks separated by a blank line. */
    public final String text;
    /** The glyph of each char of {@link #text} (null for spaces and breaks the layout added). */
    public final TextGlyph[] charGlyph;
    /** The block of each char of {@link #text}. */
    public final int[] charBlock;
    public final double bodySize;
    /** The pictures of the page, in drawing order (the last is on top), found in the same pass as the text. */
    public List<com.sphere.components.spdf.render.PageImage> images = List.of();

    PageText(List<TextGlyph> glyphs, List<TextLine> lines, List<TextBlock> blocks, double bodySize) {
        this.glyphs = glyphs;
        this.lines = lines;
        this.blocks = blocks;
        this.bodySize = bodySize;
        StringBuilder sb = new StringBuilder();
        List<TextGlyph> map = new ArrayList<>();
        List<Integer> bmap = new ArrayList<>();
        for (int b = 0; b < blocks.size(); b++) {
            TextBlock block = blocks.get(b);
            if (b > 0) {
                sb.append("\n\n");
                map.add(null);
                map.add(null);
                bmap.add(b);
                bmap.add(b);
            }
            for (int li = 0; li < block.lines.size(); li++) {
                TextLine line = block.lines.get(li);
                if (li > 0) {
                    sb.append('\n');
                    map.add(null);
                    bmap.add(b);
                }
                for (int i = 0; i < line.glyphs.size(); i++) {
                    if (line.spaceBefore.get(i)) {
                        sb.append(' ');
                        map.add(null);
                        bmap.add(b);
                    }
                    TextGlyph g = line.glyphs.get(i);
                    for (int k = 0; k < g.text.length(); k++) {
                        sb.append(g.text.charAt(k));
                        map.add(g);
                        bmap.add(b);
                    }
                }
            }
        }
        text = sb.toString();
        charGlyph = map.toArray(new TextGlyph[0]);
        charBlock = new int[bmap.size()];
        for (int i = 0; i < charBlock.length; i++) {
            charBlock[i] = bmap.get(i);
        }
    }

    /* ------------------------------------------------------------- hit testing */

    /** The block under a point (display space), with a little slack, or null. */
    public TextBlock blockAt(double x, double y) {
        TextBlock best = null;
        double bestD = Double.MAX_VALUE;
        for (TextBlock b : blocks) {
            Rectangle2D r = b.bounds();
            double slack = Math.max(2, b.size() * 0.3);
            Rectangle2D grown = new Rectangle2D.Double(r.getX() - slack, r.getY() - slack, r.getWidth() + 2 * slack,
                                                       r.getHeight() + 2 * slack);
            if (grown.contains(x, y)) {
                double d = r.contains(x, y) ? 0 : distance(r, x, y);
                if (d < bestD || (d == bestD && best != null && r.getWidth() * r.getHeight()
                                  < best.bounds().getWidth() * best.bounds().getHeight())) {
                    best = b;
                    bestD = d;
                }
            }
        }
        return best;
    }

    private static double distance(Rectangle2D r, double x, double y) {
        double dx = Math.max(Math.max(r.getMinX() - x, 0), x - r.getMaxX());
        double dy = Math.max(Math.max(r.getMinY() - y, 0), y - r.getMaxY());
        return Math.hypot(dx, dy);
    }

    /**
     * The text position nearest a point: the index in {@link #text} before
     * which a caret there would stand; -1 when the page has no text.
     */
    public int indexAt(double x, double y) {
        if (charGlyph.length == 0) {
            return -1;
        }
        TextLine bestLine = null;
        double bestD = Double.MAX_VALUE;
        for (TextLine l : lines) {
            Rectangle2D r = l.bounds();
            double d = distance(r, x, y);
            // prefer the line whose band holds y
            if (y >= r.getMinY() && y <= r.getMaxY()) {
                d *= 0.25;
            }
            if (d < bestD) {
                bestD = d;
                bestLine = l;
            }
        }
        if (bestLine == null) {
            return -1;
        }
        double u = bestLine.uOf(x, y);
        TextGlyph target = null;
        boolean after = false;
        for (TextGlyph g : bestLine.glyphs) {
            double gu0 = bestLine.uOf(g.x, g.y);
            double gu1 = bestLine.uOf(g.ex, g.ey);
            if (u < (gu0 + gu1) / 2) {
                target = g;
                after = false;
                break;
            }
            target = g;
            after = true;
        }
        if (target == null) {
            return -1;
        }
        for (int i = 0; i < charGlyph.length; i++) {
            if (charGlyph[i] == target) {
                if (!after) {
                    return i;
                }
                int j = i;
                while (j < charGlyph.length && charGlyph[j] == target) {
                    j++;
                }
                return j;
            }
        }
        return -1;
    }

    /** Rectangles (one per line piece) covering the text between two indices, for selection and hits. */
    public List<Rectangle2D> rects(int from, int to) {
        List<Rectangle2D> out = new ArrayList<>();
        if (from > to) {
            int t = from;
            from = to;
            to = t;
        }
        from = Math.max(0, from);
        to = Math.min(charGlyph.length, to);
        Rectangle2D cur = null;
        TextGlyph prev = null;
        for (int i = from; i < to; i++) {
            TextGlyph g = charGlyph[i];
            if (g == null || g == prev) {
                continue;
            }
            Rectangle2D r = g.bounds();
            if (cur != null && prev != null && Math.abs(prev.y - g.y) < Math.max(prev.size, g.size) * 0.5
                && r.getMinX() >= cur.getMinX() - 1) {
                cur.add(r);
            } else {
                cur = r;
                out.add(cur);
            }
            prev = g;
        }
        return out;
    }

    /** The glyphs between two text indices. */
    public List<TextGlyph> glyphs(int from, int to) {
        List<TextGlyph> out = new ArrayList<>();
        TextGlyph prev = null;
        for (int i = Math.max(0, Math.min(from, to)); i < Math.min(charGlyph.length, Math.max(from, to)); i++) {
            TextGlyph g = charGlyph[i];
            if (g != null && g != prev) {
                out.add(g);
                prev = g;
            }
        }
        return out;
    }

    /** The word around an index: [start, end). */
    public int[] wordAt(int index) {
        if (index < 0 || index >= text.length()) {
            return new int[] { Math.max(0, index), Math.max(0, index) };
        }
        int s = index;
        int e = index;
        while (s > 0 && isWordChar(text.charAt(s - 1))) {
            s--;
        }
        while (e < text.length() && isWordChar(text.charAt(e))) {
            e++;
        }
        if (s == e) {
            e = Math.min(text.length(), e + 1);
        }
        return new int[] { s, e };
    }

    /** The paragraph around an index: [start, end). */
    public int[] blockRange(int index) {
        if (index < 0 || index >= charBlock.length) {
            return new int[] { 0, 0 };
        }
        int b = charBlock[index];
        int s = index;
        int e = index;
        while (s > 0 && charBlock[s - 1] == b && !(text.charAt(s - 1) == '\n' && s >= 2 && text.charAt(s - 2) == '\n')) {
            s--;
        }
        while (e < charBlock.length && charBlock[e] == b && !(text.charAt(e) == '\n' && e + 1 < text.length()
                                                              && text.charAt(e + 1) == '\n')) {
            e++;
        }
        return new int[] { s, e };
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '’' || c == '_' || c == '-';
    }

    /** The point where a caret before an index is drawn: top and bottom of the caret line. */
    public Point2D[] caretAt(int index) {
        TextGlyph g = null;
        boolean end = false;
        if (index < charGlyph.length) {
            g = charGlyph[index];
        }
        if (g == null) {
            for (int i = Math.min(index, charGlyph.length) - 1; i >= 0; i--) {
                if (charGlyph[i] != null) {
                    g = charGlyph[i];
                    end = true;
                    break;
                }
            }
        }
        if (g == null) {
            return null;
        }
        double bx = end ? g.ex : g.x;
        double by = end ? g.ey : g.y;
        return new Point2D[] { new Point2D.Double(bx + g.ux * g.ascent, by + g.uy * g.ascent),
                               new Point2D.Double(bx - g.ux * g.descent, by - g.uy * g.descent) };
    }
}
