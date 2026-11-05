package com.sphere.components.spdf.text;

import com.sphere.components.spdf.render.TextGlyph;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A paragraph as the layout shows it: lines that follow one another at a
 * steady spacing, with their alignment (left, right, centered, justified),
 * line spacing and first-line indent worked out from the glyphs. This is
 * what the editor opens when a paragraph is clicked.
 */
public final class TextBlock {

    public enum Align { LEFT, RIGHT, CENTER, JUSTIFY }

    public final List<TextLine> lines = new ArrayList<>();
    public Align align = Align.LEFT;
    /** Baseline to baseline, in display units (0 for a single line). */
    public double lineSpacing;
    public double firstIndent;
    public double dx = 1;
    public double dy = 0;
    /** A heading level guessed from size and weight (0 = body text). */
    public int headingLevel;
    public int order;

    public double u0() {
        double m = Double.MAX_VALUE;
        for (TextLine l : lines) {
            m = Math.min(m, l.u0);
        }
        return m;
    }

    public double u1() {
        double m = -Double.MAX_VALUE;
        for (TextLine l : lines) {
            m = Math.max(m, l.u1);
        }
        return m;
    }

    public double size() {
        double s = 0;
        int n = 0;
        for (TextLine l : lines) {
            s += l.size * l.glyphs.size();
            n += l.glyphs.size();
        }
        return n == 0 ? 10 : s / n;
    }

    public Rectangle2D bounds() {
        Rectangle2D r = null;
        for (TextLine l : lines) {
            if (r == null) {
                r = l.bounds();
            } else {
                r.add(l.bounds());
            }
        }
        return r == null ? new Rectangle2D.Double() : r;
    }

    /** The paragraph's text: lines joined by spaces, words broken by a hyphen at a line end rejoined. */
    public String text() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text();
            if (b.length() > 0) {
                char last = b.charAt(b.length() - 1);
                if (last == '-' && b.length() > 1 && Character.isLetter(b.charAt(b.length() - 2)) && !t.isEmpty()
                    && Character.isLowerCase(t.charAt(0))) {
                    b.setLength(b.length() - 1);
                } else if (last != ' ') {
                    b.append(' ');
                }
            }
            b.append(t);
        }
        return b.toString();
    }

    /** The text as shown, one line per line. */
    public String linesText() {
        StringBuilder b = new StringBuilder();
        for (TextLine l : lines) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(l.text());
        }
        return b.toString();
    }

    public List<TextGlyph> glyphs() {
        List<TextGlyph> out = new ArrayList<>();
        for (TextLine l : lines) {
            out.addAll(l.glyphs);
        }
        return out;
    }

    /** The most used font, size and color, for text typed into the block. */
    public TextGlyph dominant() {
        Map<String, Integer> count = new HashMap<>();
        TextGlyph best = null;
        int bestN = -1;
        for (TextLine l : lines) {
            for (TextGlyph g : l.glyphs) {
                if (g.isSpace()) {
                    continue;
                }
                String k = System.identityHashCode(g.font) + "|" + Math.round(g.size * 10) + "|" + g.color;
                int n = count.merge(k, 1, Integer::sum);
                if (n > bestN) {
                    bestN = n;
                    best = g;
                }
            }
        }
        return best != null ? best : lines.isEmpty() || lines.get(0).glyphs.isEmpty() ? null : lines.get(0).first();
    }

    public boolean isUpright() {
        return Math.abs(dx - 1) < 1e-3 && Math.abs(dy) < 1e-3;
    }

    @Override
    public String toString() {
        return align + " " + text();
    }
}
