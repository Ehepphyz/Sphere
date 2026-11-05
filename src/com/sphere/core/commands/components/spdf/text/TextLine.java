package com.sphere.components.spdf.text;

import com.sphere.components.spdf.render.TextGlyph;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Glyphs sharing a baseline, in visual order, with the spaces the layout
 * implies between them (PDFs rarely draw spaces). Coordinates are in display
 * space; u runs along the baseline, v across it (down positive).
 */
public final class TextLine {

    public final List<TextGlyph> glyphs = new ArrayList<>();
    /** For each glyph, whether a space precedes it in the text (not drawn as a glyph). */
    public final List<Boolean> spaceBefore = new ArrayList<>();
    /** The unit direction of the baseline in display space. */
    public double dx = 1;
    public double dy = 0;
    /** Extent along the baseline, and the baseline's position across it. */
    public double u0;
    public double u1;
    public double v;
    public double size;
    private String text;

    double uOf(double x, double y) {
        return x * dx + y * dy;
    }

    double vOf(double x, double y) {
        return -x * dy + y * dx;
    }

    void add(TextGlyph g) {
        glyphs.add(g);
        double gu0 = uOf(g.x, g.y);
        double gu1 = uOf(g.ex, g.ey);
        if (glyphs.size() == 1) {
            u0 = Math.min(gu0, gu1);
            u1 = Math.max(gu0, gu1);
            v = vOf(g.x, g.y);
            size = g.size;
        } else {
            u0 = Math.min(u0, Math.min(gu0, gu1));
            u1 = Math.max(u1, Math.max(gu0, gu1));
            // the baseline is the one of the larger glyphs (superscripts do not move it)
            if (g.size > size * 1.05) {
                v = vOf(g.x, g.y);
                size = g.size;
            }
        }
        text = null;
    }

    /** The line's text, spaces included. */
    public String text() {
        if (text == null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < glyphs.size(); i++) {
                if (spaceBefore.size() > i && spaceBefore.get(i)) {
                    b.append(' ');
                }
                b.append(glyphs.get(i).text);
            }
            text = b.toString();
        }
        return text;
    }

    void invalidate() {
        text = null;
    }

    public Rectangle2D bounds() {
        Rectangle2D r = null;
        for (TextGlyph g : glyphs) {
            if (r == null) {
                r = g.bounds();
            } else {
                r.add(g.bounds());
            }
        }
        return r == null ? new Rectangle2D.Double() : r;
    }

    public TextGlyph first() {
        return glyphs.get(0);
    }

    public TextGlyph last() {
        return glyphs.get(glyphs.size() - 1);
    }

    /** The angle of the baseline in degrees (0 = left to right, upright). */
    public double angleDegrees() {
        return Math.toDegrees(Math.atan2(dy, dx));
    }

    @Override
    public String toString() {
        return text();
    }
}
