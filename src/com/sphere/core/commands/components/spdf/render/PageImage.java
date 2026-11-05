package com.sphere.components.spdf.render;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * A picture drawn on a page, as the content drew it: which image (its
 * resource name and object, or an inline image), which operation drew it
 * (so it can be moved, resized or taken out), the matrix it was drawn with,
 * and where it landed on the page.
 */
public final class PageImage {

    /** The XObject's resource name; null for an inline image. */
    public final String name;
    /** The image object's number; -1 for an inline image (or a stream held directly in the resources). */
    public final int objNum;
    /** The stream holding the drawing operation: -1 the page's own content, else a form XObject's number. */
    public final int streamId;
    /** The operation's index in that stream (Do, or BI for an inline image). */
    public final int opIndex;
    /** Image space (the unit square, y up) to page user space: the content's matrix at the operation. */
    public final AffineTransform toUser;
    /** Image space to display space (points, y down, the page's rotation applied). */
    public final AffineTransform toDisplay;
    public final int pixelWidth;
    public final int pixelHeight;
    /** The image codec (DCTDecode, JPXDecode, CCITTFaxDecode...), or null for Flate or no compression. */
    public final String codec;
    public final boolean alpha;
    public final boolean stencil;
    /** How many forms deep the picture is drawn (0: by the page itself). */
    public final int depth;

    PageImage(String name, int objNum, int streamId, int opIndex, AffineTransform toUser, AffineTransform toDisplay,
              int pixelWidth, int pixelHeight, String codec, boolean alpha, boolean stencil, int depth) {
        this.name = name;
        this.objNum = objNum;
        this.streamId = streamId;
        this.opIndex = opIndex;
        this.toUser = toUser;
        this.toDisplay = toDisplay;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.codec = codec;
        this.alpha = alpha;
        this.stencil = stencil;
        this.depth = depth;
    }

    /** The picture's outline on the page, in display space (a parallelogram: pictures can be turned). */
    public Shape outline() {
        return toDisplay.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1));
    }

    public Rectangle2D bounds() {
        return outline().getBounds2D();
    }

    public boolean contains(Point2D display) {
        return outline().contains(display);
    }

    /** Width on the page, in points. */
    public double widthPt() {
        return Math.hypot(toUser.getScaleX(), toUser.getShearY());
    }

    /** Height on the page, in points. */
    public double heightPt() {
        return Math.hypot(toUser.getShearX(), toUser.getScaleY());
    }

    /** The effective resolution, in pixels per inch (the lower of the two directions). */
    public double ppi() {
        return Math.min(pixelWidth / Math.max(1e-9, widthPt() / 72), pixelHeight / Math.max(1e-9, heightPt() / 72));
    }

    /** True when the picture is drawn by the page's own content (a form's pictures are shared). */
    public boolean onPage() {
        return streamId == -1;
    }

    public boolean inline() {
        return objNum < 0;
    }

    /** A word for how the pixels are stored. */
    public String kind() {
        if (codec == null) {
            return "lossless";
        }
        return switch (codec) {
            case "DCTDecode" -> "JPEG";
            case "JPXDecode" -> "JPEG 2000";
            case "CCITTFaxDecode" -> "fax (CCITT)";
            case "JBIG2Decode" -> "JBIG2";
            default -> codec;
        };
    }

    /** The same picture's identity across extractions: the object and the operation that draw it. */
    public boolean sameAs(PageImage o) {
        return o != null && o.objNum == objNum && o.streamId == streamId && o.opIndex == opIndex;
    }
}
