package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.ByteBuf;
import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.cos.PdfString;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Standard annotations, each with its appearance stream so every viewer shows
 * it the same: highlight, underline and strike-out over text, sticky notes,
 * free-hand ink, rectangles, ellipses, lines and arrows. Coordinates come in
 * display space and are written in user space.
 */
public final class Annotator {

    private Annotator() {
    }

    public static String author = System.getProperty("user.name", "Sphere");

    private static String now() {
        return "D:" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "Z";
    }

    private static PdfArray color(int rgb) {
        return PdfArray.ofNumbers(((rgb >> 16) & 0xff) / 255.0, ((rgb >> 8) & 0xff) / 255.0, (rgb & 0xff) / 255.0);
    }

    private static String rg(int rgb, boolean stroke) {
        return PdfNumber.format(((rgb >> 16) & 0xff) / 255.0) + " " + PdfNumber.format(((rgb >> 8) & 0xff) / 255.0) + " "
            + PdfNumber.format((rgb & 0xff) / 255.0) + (stroke ? " RG\n" : " rg\n");
    }

    private static PdfDict base(String subtype, Rectangle2D userRect, int rgb, String contents) {
        PdfDict a = new PdfDict();
        a.put("Type", "Annot");
        a.put("Subtype", subtype);
        a.put("Rect", PdfArray.ofNumbers(userRect.getMinX(), userRect.getMinY(), userRect.getMaxX(), userRect.getMaxY()));
        a.put("C", color(rgb));
        a.put("F", 4);
        a.put("T", PdfString.text(author));
        a.put("M", new PdfString(now().getBytes()));
        a.put("CreationDate", new PdfString(now().getBytes()));
        a.put("NM", PdfString.text("spdf-" + Long.toHexString(System.nanoTime())));
        if (contents != null && !contents.isEmpty()) {
            a.put("Contents", PdfString.text(contents));
        }
        return a;
    }

    /** An appearance stream drawing in user space over a rectangle. */
    private static PdfStream appearance(Rectangle2D r, byte[] content, PdfDict resources) {
        PdfStream s = PdfStream.of(content, true);
        s.put("Type", "XObject");
        s.put("Subtype", "Form");
        s.put("BBox", PdfArray.ofNumbers(r.getMinX(), r.getMinY(), r.getMaxX(), r.getMaxY()));
        if (resources != null) {
            s.put("Resources", resources);
        }
        return s;
    }

    private static PdfDict alphaResources(float alpha, String blend) {
        PdfDict gs = new PdfDict();
        gs.put("Type", "ExtGState");
        gs.put("ca", alpha);
        gs.put("CA", alpha);
        if (blend != null) {
            gs.put("BM", blend);
        }
        PdfDict ext = new PdfDict();
        ext.put("A0", gs);
        PdfDict res = new PdfDict();
        res.put("ExtGState", ext);
        return res;
    }

    private static PdfRef addToPage(EditHistory h, PdfPage page, int pageIndex, PdfDict annot) {
        annot.put("P", page.ref());
        PdfRef ref = h.add(annot);
        int holder = page.annotsHolder();
        if (holder == page.objNum()) {
            h.touchPage(pageIndex, holder);
        } else {
            h.touch(holder);
        }
        page.annotsForWriting().add(ref);
        return ref;
    }

    /** Text markup (Highlight, Underline, StrikeOut, Squiggly) over quads given in display space. */
    public static PdfRef markup(EditHistory h, PdfPage page, int pageIndex, String subtype, List<float[]> displayQuads,
                                int rgb, String note) {
        AffineTransform d2u = page.displayToUser();
        PdfArray qp = new PdfArray();
        Rectangle2D bounds = null;
        ByteBuf ap = new ByteBuf();
        boolean hl = subtype.equals("Highlight");
        ap.put(hl ? "/A0 gs\n" : "").put(rg(rgb, false)).put(rg(rgb, true));
        for (float[] q : displayQuads) {
            // display quad: baseline start, baseline end, top end, top start → PDF order: top-left, top-right, bottom-left, bottom-right
            double[] pts = { q[6], q[7], q[4], q[5], q[0], q[1], q[2], q[3] };
            d2u.transform(pts, 0, pts, 0, 4);
            for (double v : pts) {
                qp.add(PdfNumber.of(Math.round(v * 100) / 100.0));
            }
            for (int i = 0; i < 8; i += 2) {
                if (bounds == null) {
                    bounds = new Rectangle2D.Double(pts[i], pts[i + 1], 0, 0);
                } else {
                    bounds.add(pts[i], pts[i + 1]);
                }
            }
            double tlx = pts[0];
            double tly = pts[1];
            double trx = pts[2];
            double try_ = pts[3];
            double blx = pts[4];
            double bly = pts[5];
            double brx = pts[6];
            double bry = pts[7];
            double h2 = Math.hypot(tlx - blx, tly - bly);
            switch (subtype) {
                case "Highlight" -> ap.num(blx).put(' ').num(bly).put(" m ").num(brx).put(' ').num(bry).put(" l ")
                    .num(trx).put(' ').num(try_).put(" l ").num(tlx).put(' ').num(tly).put(" l h f\n");
                case "Underline" -> ap.num(Math.max(0.5, h2 * 0.06)).put(" w ").num(blx).put(' ')
                    .num(bly + h2 * 0.05).put(" m ").num(brx).put(' ').num(bry + h2 * 0.05).put(" l S\n");
                case "StrikeOut" -> ap.num(Math.max(0.5, h2 * 0.06)).put(" w ").num((blx + tlx) / 2).put(' ')
                    .num((bly + tly) / 2 - h2 * 0.05).put(" m ").num((brx + trx) / 2).put(' ')
                    .num((bry + try_) / 2 - h2 * 0.05).put(" l S\n");
                default -> {
                    // squiggly: a wave under the text
                    double len = Math.hypot(brx - blx, bry - bly);
                    int waves = Math.max(2, (int) (len / Math.max(2, h2 * 0.25)));
                    ap.num(Math.max(0.4, h2 * 0.05)).put(" w ").num(blx).put(' ').num(bly).put(" m\n");
                    for (int i = 1; i <= waves; i++) {
                        double t = i / (double) waves;
                        double x = blx + (brx - blx) * t;
                        double y = bly + (bry - bly) * t + (i % 2 == 0 ? 0 : h2 * 0.08);
                        ap.num(x).put(' ').num(y).put(" l\n");
                    }
                    ap.put("S\n");
                }
            }
        }
        if (bounds == null) {
            return null;
        }
        bounds = new Rectangle2D.Double(bounds.getX() - 1, bounds.getY() - 1, bounds.getWidth() + 2, bounds.getHeight() + 2);
        PdfDict a = base(subtype, bounds, rgb, note);
        a.put("QuadPoints", qp);
        if (hl) {
            a.put("CA", 1);
        }
        PdfDict apd = new PdfDict();
        apd.put("N", h.add(appearance(bounds, ap.toBytes(), hl ? alphaResources(1f, "Multiply") : null)));
        a.put("AP", apd);
        return addToPage(h, page, pageIndex, a);
    }

    /** A sticky note at a display point. */
    public static PdfRef note(EditHistory h, PdfPage page, int pageIndex, Point2D displayAt, String text, int rgb) {
        Point2D u = page.displayToUser().transform(displayAt, null);
        Rectangle2D r = new Rectangle2D.Double(u.getX(), u.getY() - 20, 20, 20);
        PdfDict a = base("Text", r, rgb, text);
        a.put("Name", "Comment");
        a.put("Open", com.sphere.components.spdf.cos.PdfBoolean.FALSE);
        ByteBuf ap = new ByteBuf();
        double x = r.getX();
        double y = r.getY();
        ap.put(rg(rgb, false)).put("0.25 0.25 0.25 RG 0.8 w\n");
        // a speech bubble with three lines of text
        ap.num(x + 2).put(' ').num(y + 6).put(" m ").num(x + 2).put(' ').num(y + 18).put(" l ").num(x + 18).put(' ')
            .num(y + 18).put(" l ").num(x + 18).put(' ').num(y + 6).put(" l ").num(x + 9).put(' ').num(y + 6)
            .put(" l ").num(x + 5).put(' ').num(y + 2).put(" l ").num(x + 6).put(' ').num(y + 6).put(" l h B\n");
        ap.put("0.25 0.25 0.25 RG 1 w\n");
        for (int i = 0; i < 3; i++) {
            double ly = y + 15 - i * 3;
            ap.num(x + 5).put(' ').num(ly).put(" m ").num(x + (i == 2 ? 11 : 15)).put(' ').num(ly).put(" l S\n");
        }
        PdfDict apd = new PdfDict();
        apd.put("N", h.add(appearance(r, ap.toBytes(), null)));
        a.put("AP", apd);
        return addToPage(h, page, pageIndex, a);
    }

    /** Free-hand ink: strokes as display-space polylines. */
    public static PdfRef ink(EditHistory h, PdfPage page, int pageIndex, List<List<Point2D>> strokes, int rgb, float width,
                             float alpha) {
        AffineTransform d2u = page.displayToUser();
        PdfArray inkList = new PdfArray();
        Rectangle2D bounds = null;
        ByteBuf ap = new ByteBuf();
        ap.put(alpha < 1 ? "/A0 gs\n" : "").put(rg(rgb, true)).num(width).put(" w 1 J 1 j\n");
        for (List<Point2D> s : strokes) {
            PdfArray pts = new PdfArray();
            boolean first = true;
            List<Point2D> smooth = smooth(s);
            for (Point2D p : smooth) {
                Point2D u = d2u.transform(p, null);
                pts.add(PdfNumber.of(Math.round(u.getX() * 100) / 100.0));
                pts.add(PdfNumber.of(Math.round(u.getY() * 100) / 100.0));
                ap.num(u.getX()).put(' ').num(u.getY()).put(first ? " m\n" : " l\n");
                first = false;
                if (bounds == null) {
                    bounds = new Rectangle2D.Double(u.getX(), u.getY(), 0, 0);
                } else {
                    bounds.add(u);
                }
            }
            if (smooth.size() == 1) {
                Point2D u = d2u.transform(smooth.get(0), null);
                ap.num(u.getX() + 0.01).put(' ').num(u.getY()).put(" l\n");
            }
            ap.put("S\n");
            inkList.add(pts);
        }
        if (bounds == null) {
            return null;
        }
        double m = width / 2 + 1;
        bounds = new Rectangle2D.Double(bounds.getX() - m, bounds.getY() - m, bounds.getWidth() + 2 * m, bounds.getHeight() + 2 * m);
        PdfDict a = base("Ink", bounds, rgb, null);
        a.put("InkList", inkList);
        PdfDict bs = new PdfDict();
        bs.put("W", width);
        a.put("BS", bs);
        if (alpha < 1) {
            a.put("CA", alpha);
        }
        PdfDict apd = new PdfDict();
        apd.put("N", h.add(appearance(bounds, ap.toBytes(), alpha < 1 ? alphaResources(alpha, "Multiply") : null)));
        a.put("AP", apd);
        return addToPage(h, page, pageIndex, a);
    }

    /** Drops points closer than a third of a point, a lighter path for the same line. */
    private static List<Point2D> smooth(List<Point2D> s) {
        List<Point2D> out = new java.util.ArrayList<>();
        Point2D last = null;
        for (Point2D p : s) {
            if (last == null || last.distance(p) > 0.35) {
                out.add(p);
                last = p;
            }
        }
        if (out.isEmpty() && !s.isEmpty()) {
            out.add(s.get(0));
        }
        return out;
    }

    /** Rectangle (Square), ellipse (Circle), Line or arrow between two display points. */
    public static PdfRef shape(EditHistory h, PdfPage page, int pageIndex, String kind, Point2D a, Point2D b, int stroke,
                               Integer fill, float width) {
        AffineTransform d2u = page.displayToUser();
        Point2D ua = d2u.transform(a, null);
        Point2D ub = d2u.transform(b, null);
        double m = width / 2 + (kind.equals("Arrow") ? width * 4 + 4 : 1);
        Rectangle2D box = new Rectangle2D.Double(Math.min(ua.getX(), ub.getX()), Math.min(ua.getY(), ub.getY()),
                                                 Math.abs(ua.getX() - ub.getX()), Math.abs(ua.getY() - ub.getY()));
        Rectangle2D rect = new Rectangle2D.Double(box.getX() - m, box.getY() - m, box.getWidth() + 2 * m,
                                                  box.getHeight() + 2 * m);
        ByteBuf ap = new ByteBuf();
        ap.put(rg(stroke, true)).num(width).put(" w 1 J 1 j\n");
        if (fill != null) {
            ap.put(rg(fill, false));
        }
        String paint = fill != null ? "B\n" : "S\n";
        String subtype;
        switch (kind) {
            case "Square" -> {
                subtype = "Square";
                ap.num(box.getX()).put(' ').num(box.getY()).put(' ').num(box.getWidth()).put(' ').num(box.getHeight())
                  .put(" re ").put(paint);
            }
            case "Circle" -> {
                subtype = "Circle";
                double cx = box.getCenterX();
                double cy = box.getCenterY();
                double rx = box.getWidth() / 2;
                double ry = box.getHeight() / 2;
                double k = 0.5523;
                ap.num(cx + rx).put(' ').num(cy).put(" m ");
                ap.num(cx + rx).put(' ').num(cy + ry * k).put(' ').num(cx + rx * k).put(' ').num(cy + ry).put(' ')
                  .num(cx).put(' ').num(cy + ry).put(" c ");
                ap.num(cx - rx * k).put(' ').num(cy + ry).put(' ').num(cx - rx).put(' ').num(cy + ry * k).put(' ')
                  .num(cx - rx).put(' ').num(cy).put(" c ");
                ap.num(cx - rx).put(' ').num(cy - ry * k).put(' ').num(cx - rx * k).put(' ').num(cy - ry).put(' ')
                  .num(cx).put(' ').num(cy - ry).put(" c ");
                ap.num(cx + rx * k).put(' ').num(cy - ry).put(' ').num(cx + rx).put(' ').num(cy - ry * k).put(' ')
                  .num(cx + rx).put(' ').num(cy).put(" c h ").put(paint);
            }
            default -> {
                subtype = "Line";
                ap.num(ua.getX()).put(' ').num(ua.getY()).put(" m ").num(ub.getX()).put(' ').num(ub.getY()).put(" l S\n");
                if (kind.equals("Arrow")) {
                    double ang = Math.atan2(ub.getY() - ua.getY(), ub.getX() - ua.getX());
                    double len = Math.max(6, width * 4 + 4);
                    double x1 = ub.getX() - len * Math.cos(ang - 0.45);
                    double y1 = ub.getY() - len * Math.sin(ang - 0.45);
                    double x2 = ub.getX() - len * Math.cos(ang + 0.45);
                    double y2 = ub.getY() - len * Math.sin(ang + 0.45);
                    ap.put(rg(stroke, false)).num(ub.getX()).put(' ').num(ub.getY()).put(" m ").num(x1).put(' ').num(y1)
                      .put(" l ").num(x2).put(' ').num(y2).put(" l h f\n");
                }
            }
        }
        PdfDict annot = base(subtype, rect, stroke, null);
        PdfDict bs = new PdfDict();
        bs.put("W", width);
        annot.put("BS", bs);
        if (fill != null) {
            annot.put("IC", color(fill));
        }
        if (subtype.equals("Line")) {
            annot.put("L", PdfArray.ofNumbers(ua.getX(), ua.getY(), ub.getX(), ub.getY()));
            if (kind.equals("Arrow")) {
                annot.put("LE", PdfArray.of(com.sphere.components.spdf.cos.PdfName.of("None"),
                                            com.sphere.components.spdf.cos.PdfName.of("ClosedArrow")));
            }
        }
        PdfDict apd = new PdfDict();
        apd.put("N", h.add(appearance(rect, ap.toBytes(), null)));
        annot.put("AP", apd);
        return addToPage(h, page, pageIndex, annot);
    }

    /** Removes an annotation (and its popup) from its page. */
    public static void delete(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PdfDict annot) {
        h.begin("Delete annotation");
        try {
            int holder = page.annotsHolder();
            if (holder == page.objNum()) {
                h.touchPage(pageIndex, holder);
            } else {
                h.touch(holder);
            }
            PdfArray arr = page.annotsForWriting();
            PdfDict popup = annot.getDict("Popup");
            for (int i = arr.size() - 1; i >= 0; i--) {
                PdfDict a = arr.getDict(i);
                if (a == annot || a == popup) {
                    arr.remove(i);
                }
            }
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** The annotation of a page under a display point, the topmost first; null when none. */
    public static PdfDict at(PdfPage page, Point2D displayPt) {
        Point2D u = page.displayToUser().transform(displayPt, null);
        List<PdfDict> list = page.annotations();
        for (int i = list.size() - 1; i >= 0; i--) {
            PdfDict a = list.get(i);
            String st = a.getName("Subtype");
            if ("Popup".equals(st) || "Link".equals(st) || "Widget".equals(st)) {
                continue;
            }
            PdfArray r = a.getArray("Rect");
            if (r != null && r.size() == 4) {
                double[] v = r.toDoubles();
                Rectangle2D rr = new Rectangle2D.Double(Math.min(v[0], v[2]), Math.min(v[1], v[3]),
                                                        Math.abs(v[2] - v[0]), Math.abs(v[3] - v[1]));
                if (rr.contains(u)) {
                    return a;
                }
            }
        }
        return null;
    }
}
