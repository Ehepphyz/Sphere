package com.sphere.components.spdf.render;

import com.sphere.components.spdf.font.PdfFont;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * One glyph as shown on a page, in display space (y down, points): where it
 * starts and ends on its baseline, its size and direction, its color and
 * font, and where it came from in the content (stream, operation, index in
 * the operation), which is what lets the editor remove exactly it.
 */
public final class TextGlyph {

    /** The Unicode text (several chars for a ligature), or "�" when unknown. */
    public String text;
    public int code;
    public int codeLength;
    public PdfFont font;
    /** Baseline start and end (the advance), display space. */
    public float x;
    public float y;
    public float ex;
    public float ey;
    /** The em height in display units, and the glyph box above and below the baseline. */
    public float size;
    public float ascent;
    public float descent;
    /** Unit vector of the "up" direction in display space. */
    public float ux;
    public float uy;
    public int color;
    public int renderMode;
    /** -1 for the page's own content, else the object number of the form XObject holding it. */
    public int streamId = -1;
    public int opIndex;
    public int glyphIndex;
    /** The text state when it was shown, for rewriting the operation without it. */
    public float tfs;
    public float tc;
    public float tw;
    public float th;
    public boolean wordSpace;
    /** Inside a form drawn more than once on the page, or in an annotation: shown but not editable here. */
    public boolean readOnly;
    /** The order in which the glyph was shown on the page. */
    public int seq;

    public boolean invisible() {
        return renderMode == 3 || renderMode == 7;
    }

    /** The advance length in display units. */
    public float advance() {
        return (float) Math.hypot(ex - x, ey - y);
    }

    /** The direction of the baseline, in radians (display space). */
    public double angle() {
        if (ex == x && ey == y) {
            return Math.atan2(-ux, -uy) * -1;
        }
        return Math.atan2(ey - y, ex - x);
    }

    /** The glyph's box as four corners: baseline start, baseline end, top end, top start (plus descent). */
    public float[] quad() {
        float tx = ux * ascent;
        float ty = uy * ascent;
        float bx = -ux * descent;
        float by = -uy * descent;
        return new float[] { x + bx, y + by, ex + bx, ey + by, ex + tx, ey + ty, x + tx, y + ty };
    }

    public Path2D.Float outline() {
        float[] q = quad();
        Path2D.Float p = new Path2D.Float();
        p.moveTo(q[0], q[1]);
        p.lineTo(q[2], q[3]);
        p.lineTo(q[4], q[5]);
        p.lineTo(q[6], q[7]);
        p.closePath();
        return p;
    }

    public Rectangle2D bounds() {
        float[] q = quad();
        float minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
        float maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        float minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
        float maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        return new Rectangle2D.Float(minX, minY, Math.max(0.1f, maxX - minX), Math.max(0.1f, maxY - minY));
    }

    public boolean isSpace() {
        return text != null && !text.isEmpty() && text.isBlank();
    }

    /** True when both glyphs come from the same place in the content. */
    public boolean sameSource(TextGlyph o) {
        return o.streamId == streamId && o.opIndex == opIndex && o.glyphIndex == glyphIndex;
    }

    @Override
    public String toString() {
        return "'" + text + "'@" + Math.round(x) + "," + Math.round(y) + " s" + Math.round(size);
    }
}
