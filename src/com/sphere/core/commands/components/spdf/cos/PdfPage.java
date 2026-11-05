package com.sphere.components.spdf.cos;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A page: its dictionary, what it inherits from the page tree, its boxes and
 * its geometry. "Display space" is the page as shown: rotated, its origin at
 * the top-left corner of the crop box, y going down, in points. Every part of
 * the viewer and the editor works in display space and converts to the PDF's
 * user space (y up, unrotated) through {@link #userToDisplay()}.
 */
public final class PdfPage {

    static final String[] INHERITABLE = { "Resources", "MediaBox", "CropBox", "Rotate" };

    private final PdfDocument doc;
    private final int objNum;
    private final PdfDict dict;

    PdfPage(PdfDocument doc, int objNum, PdfDict dict) {
        this.doc = doc;
        this.objNum = objNum;
        this.dict = dict;
    }

    public PdfDocument document() {
        return doc;
    }

    public int objNum() {
        return objNum;
    }

    public PdfRef ref() {
        return doc.ref(objNum);
    }

    public PdfDict dict() {
        return dict;
    }

    /** An attribute of the page or, when it has none, of its nearest ancestor (raw, references kept). */
    public PdfObject inheritedRaw(String key) {
        PdfDict d = dict;
        for (int depth = 0; d != null && depth < 64; depth++) {
            PdfObject v = d.getRaw(key);
            if (v != PdfNull.NULL) {
                return v;
            }
            d = d.getDict("Parent");
        }
        return null;
    }

    private PdfObject inherited(String key) {
        PdfObject raw = inheritedRaw(key);
        return raw == null ? PdfNull.NULL : PdfRef.deref(raw);
    }

    public PdfDict resources() {
        return inherited("Resources") instanceof PdfDict r ? r : null;
    }

    /** The page's resources, made the page's own (not shared through the tree) so they can change. */
    public PdfDict ownResources() {
        PdfObject raw = dict.getRaw("Resources");
        if (raw instanceof PdfRef r && r.resolve() instanceof PdfDict rd) {
            return rd;
        }
        if (raw instanceof PdfDict rd) {
            return rd;
        }
        PdfDict inheritedRes = resources();
        PdfDict mine = inheritedRes == null ? new PdfDict() : inheritedRes.copy();
        dict.put("Resources", mine);
        return mine;
    }

    public PdfArray mediaBoxArray() {
        return inherited("MediaBox") instanceof PdfArray a ? a : null;
    }

    public Rectangle2D mediaBox() {
        Rectangle2D r = box(mediaBoxArray());
        return r == null ? new Rectangle2D.Double(0, 0, 612, 792) : r;
    }

    /** The crop box, clipped to the media box (what is shown). */
    public Rectangle2D cropBox() {
        Rectangle2D media = mediaBox();
        Rectangle2D crop = box(inherited("CropBox") instanceof PdfArray a ? a : null);
        if (crop == null) {
            return media;
        }
        Rectangle2D r = crop.createIntersection(media);
        return r.getWidth() < 1 || r.getHeight() < 1 ? media : r;
    }

    private static Rectangle2D box(PdfArray a) {
        if (a == null || a.size() < 4) {
            return null;
        }
        double x0 = a.getDouble(0, 0);
        double y0 = a.getDouble(1, 0);
        double x1 = a.getDouble(2, 0);
        double y1 = a.getDouble(3, 0);
        double w = Math.abs(x1 - x0);
        double h = Math.abs(y1 - y0);
        if (w < 1 || h < 1) {
            return null;
        }
        return new Rectangle2D.Double(Math.min(x0, x1), Math.min(y0, y1), w, h);
    }

    /** 0, 90, 180 or 270: the clockwise rotation the page is shown with. */
    public int rotation() {
        int r = inherited("Rotate") instanceof PdfNumber n ? n.intValue() : 0;
        r %= 360;
        if (r < 0) {
            r += 360;
        }
        return (r / 90) * 90;
    }

    public void setRotation(int degrees) {
        int r = ((degrees % 360) + 360) % 360;
        dict.put("Rotate", r);
    }

    public double displayWidth() {
        Rectangle2D c = cropBox();
        return rotation() % 180 == 0 ? c.getWidth() : c.getHeight();
    }

    public double displayHeight() {
        Rectangle2D c = cropBox();
        return rotation() % 180 == 0 ? c.getHeight() : c.getWidth();
    }

    /** User space (y up, unrotated) to display space (y down, rotated, crop box at the origin). */
    public AffineTransform userToDisplay() {
        Rectangle2D c = cropBox();
        double x0 = c.getX();
        double y0 = c.getY();
        double w = c.getWidth();
        double h = c.getHeight();
        return switch (rotation()) {
            case 90 -> new AffineTransform(0, 1, 1, 0, -y0, -x0);
            case 180 -> new AffineTransform(-1, 0, 0, 1, x0 + w, -y0);
            case 270 -> new AffineTransform(0, -1, -1, 0, y0 + h, x0 + w);
            default -> new AffineTransform(1, 0, 0, -1, -x0, y0 + h);
        };
    }

    public AffineTransform displayToUser() {
        try {
            return userToDisplay().createInverse();
        } catch (Exception e) {
            return new AffineTransform();
        }
    }

    /** The page's content: its streams decoded and joined (a space between them, as the spec wants). */
    public byte[] contents() {
        PdfObject c = dict.get("Contents");
        if (c instanceof PdfStream s) {
            return s.getDecoded();
        }
        if (c instanceof PdfArray a) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i) instanceof PdfStream s) {
                    byte[] d = s.getDecoded();
                    out.write(d, 0, d.length);
                    out.write('\n');
                }
            }
            return out.toByteArray();
        }
        return new byte[0];
    }

    /** Replaces the page's content with one new stream. */
    public void setContents(byte[] content) {
        PdfStream s = PdfStream.of(content, true);
        dict.put("Contents", doc.addObject(s));
        doc.changed();
    }

    public List<PdfDict> annotations() {
        List<PdfDict> out = new ArrayList<>();
        PdfArray a = dict.getArray("Annots");
        if (a != null) {
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i) instanceof PdfDict d) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /** The page's /Annots array, created (as a direct array of the page) if missing. */
    public PdfArray annotsForWriting() {
        PdfObject raw = dict.getRaw("Annots");
        if (raw instanceof PdfRef r && r.resolve() instanceof PdfArray a) {
            return a;
        }
        if (raw instanceof PdfArray a) {
            return a;
        }
        PdfArray a = new PdfArray();
        dict.put("Annots", a);
        return a;
    }

    /** The object holding the /Annots array (the page or the array itself), for snapshots. */
    public int annotsHolder() {
        PdfObject raw = dict.getRaw("Annots");
        return raw instanceof PdfRef r ? r.num : objNum;
    }

    @Override
    public String toString() {
        return "Page(obj " + objNum + ")";
    }
}
