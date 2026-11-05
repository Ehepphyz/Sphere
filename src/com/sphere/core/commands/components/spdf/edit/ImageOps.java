package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.render.ContentParser;
import com.sphere.components.spdf.render.Op;
import com.sphere.components.spdf.render.PageImage;
import com.sphere.components.spdf.render.PageRenderer;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * The pictures of a PDF, edited: replaced (by a file, or by what Sphere Image
 * made of them), moved and resized, turned and mirrored without touching a
 * pixel (their matrix changes), taken out, added, made lighter. Every change
 * is one undoable step. New pixels are written as JPEG when they are a
 * photograph without transparency, else losslessly (Flate with the PNG
 * predictors), transparency going into a soft mask.
 */
public final class ImageOps {

    private ImageOps() {
    }

    /* ================================================================= encoding */

    /**
     * An image XObject holding a picture. jpeg: true or false forces the
     * codec, null lets the picture choose (a photograph without transparency
     * becomes a JPEG). The soft mask, when the picture has transparency, is
     * added to the document as part of the step.
     */
    public static PdfStream encode(EditHistory h, BufferedImage img, Boolean jpeg, float quality) {
        return attach(h, encodeDetached(img, jpeg, quality));
    }

    /** A picture encoded but not yet in a document: the image, and its soft mask when it has transparency. */
    public record Encoded(PdfStream image, PdfStream smask) {
        /** The bytes it takes in the file. */
        public long size() {
            return image.getRaw().length + (smask == null ? 0 : smask.getRaw().length);
        }
    }

    /** Puts an encoded picture's soft mask into the document (as part of the open step); returns the image. */
    public static PdfStream attach(EditHistory h, Encoded e) {
        if (e.smask() != null) {
            e.image().put("SMask", h.add(e.smask()));
        }
        return e.image();
    }

    /** Encodes a picture without touching any document (safe off the event thread). */
    public static Encoded encodeDetached(BufferedImage img, Boolean jpeg, float quality) {
        int w = img.getWidth();
        int ht = img.getHeight();
        int[] px = img.getRGB(0, 0, w, ht, null, 0, w);
        boolean alpha = false;
        boolean gray = true;
        for (int p : px) {
            if ((p >>> 24) != 255) {
                alpha = true;
            }
            int r = (p >> 16) & 255;
            int g = (p >> 8) & 255;
            int b = p & 255;
            if (r != g || g != b) {
                gray = false;
            }
            if (alpha && !gray) {
                break;
            }
        }
        boolean useJpeg = jpeg != null ? jpeg : !alpha && looksLikePhoto(px);
        PdfStream s;
        if (useJpeg) {
            s = new PdfStream(null, null);
            s.setRaw(jpegBytes(px, w, ht, gray, quality));
            s.put("Filter", "DCTDecode");
        } else {
            int n = gray ? 1 : 3;
            byte[] data = new byte[w * ht * n];
            for (int i = 0, k = 0; i < px.length; i++) {
                int p = px[i];
                // what shows through a transparent pixel is its color mixed with white, as viewers composite it
                if (gray) {
                    data[k++] = (byte) (p & 255);
                } else {
                    data[k++] = (byte) (p >> 16);
                    data[k++] = (byte) (p >> 8);
                    data[k++] = (byte) p;
                }
            }
            s = flate(data, w, n);
        }
        s.put("Type", "XObject");
        s.put("Subtype", "Image");
        s.put("Width", w);
        s.put("Height", ht);
        s.put("ColorSpace", gray ? "DeviceGray" : "DeviceRGB");
        s.put("BitsPerComponent", 8);
        PdfStream mask = null;
        if (alpha) {
            byte[] a = new byte[w * ht];
            for (int i = 0; i < px.length; i++) {
                a[i] = (byte) (px[i] >>> 24);
            }
            mask = flate(a, w, 1);
            mask.put("Type", "XObject");
            mask.put("Subtype", "Image");
            mask.put("Width", w);
            mask.put("Height", ht);
            mask.put("ColorSpace", "DeviceGray");
            mask.put("BitsPerComponent", 8);
        }
        return new Encoded(s, mask);
    }

    /** True for a picture of many colors (a photograph), false for a drawing, a chart, a screenshot of text. */
    public static boolean looksLikePhoto(int[] px) {
        Set<Integer> colors = new HashSet<>();
        int step = Math.max(1, px.length / 20000);
        for (int i = 0; i < px.length; i += step) {
            int p = px[i];
            colors.add(((p >> 19) & 31) << 10 | ((p >> 11) & 31) << 5 | ((p >> 3) & 31));
            if (colors.size() > 1500) {
                return true;
            }
        }
        return colors.size() > 1500;
    }

    private static byte[] jpegBytes(int[] px, int w, int h, boolean gray, float quality) {
        BufferedImage out = new BufferedImage(w, h, gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        if (gray) {
            java.awt.image.WritableRaster r = out.getRaster();
            for (int y = 0, i = 0; y < h; y++) {
                for (int x = 0; x < w; x++, i++) {
                    r.setSample(x, y, 0, px[i] & 255);
                }
            }
        } else {
            out.setRGB(0, 0, w, h, px, 0, w);
        }
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) {
            throw new IllegalStateException("no JPEG writer in this Java");
        }
        ImageWriter writer = it.next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), p);
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed: " + e.getMessage(), e);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** Samples compressed with Flate after the PNG predictors (the best of four per row, as PNG writers do). */
    static PdfStream flate(byte[] data, int w, int colors) {
        int bpr = w * colors;
        int rows = bpr == 0 ? 0 : data.length / bpr;
        byte[] out = new byte[(bpr + 1) * rows];
        byte[] prev = new byte[bpr];
        byte[] cur = new byte[bpr];
        byte[][] cand = new byte[5][bpr];
        for (int y = 0; y < rows; y++) {
            System.arraycopy(data, y * bpr, cur, 0, bpr);
            int best = 0;
            long bestCost = Long.MAX_VALUE;
            for (int f : new int[] { 0, 1, 2, 4 }) {
                byte[] c = cand[f];
                long cost = 0;
                for (int i = 0; i < bpr; i++) {
                    int a = i >= colors ? cur[i - colors] & 255 : 0;
                    int b = prev[i] & 255;
                    int cc = i >= colors ? prev[i - colors] & 255 : 0;
                    int x = cur[i] & 255;
                    int v = switch (f) {
                        case 1 -> x - a;
                        case 2 -> x - b;
                        case 4 -> x - paeth(a, b, cc);
                        default -> x;
                    };
                    c[i] = (byte) v;
                    cost += Math.abs((byte) v);
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = f;
                }
            }
            int o = y * (bpr + 1);
            out[o] = (byte) best;
            System.arraycopy(cand[best], 0, out, o + 1, bpr);
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        Deflater d = new Deflater(6);
        d.setInput(out);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, out.length / 3));
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        PdfStream s = new PdfStream(null, null);
        s.setRaw(bos.toByteArray());
        s.put("Filter", "FlateDecode");
        PdfDict parms = new PdfDict();
        parms.put("Predictor", 15);
        parms.put("Colors", colors);
        parms.put("BitsPerComponent", 8);
        parms.put("Columns", w);
        s.put("DecodeParms", parms);
        return s;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    /* ================================================================= reading */

    /** The original bytes of a picture stored as a file format of its own (JPEG, JPEG 2000), else null. */
    public static byte[] originalFile(PdfDocument doc, PageImage im) {
        if (im.inline() || !(doc.getObject(im.objNum) instanceof PdfStream s)) {
            return null;
        }
        PdfObject f = s.get("Filter");
        boolean single = f instanceof PdfName || f instanceof com.sphere.components.spdf.cos.PdfArray a && a.size() == 1;
        String codec = s.imageFilter();
        return single && ("DCTDecode".equals(codec) || "JPXDecode".equals(codec)) && !s.has("SMask") ? s.getRaw() : null;
    }

    /** The extension of the file {@link #originalFile} gives. */
    public static String originalExtension(PageImage im) {
        return "JPXDecode".equals(im.codec) ? "jp2" : "jpg";
    }

    /** The operation that draws a picture, from the stream holding it. */
    public static Op opOf(PdfDocument doc, PdfPage page, PageRenderer renderer, PageImage im) {
        List<Op> ops = opsOf(doc, page, renderer, im);
        return im.opIndex >= 0 && im.opIndex < ops.size() ? ops.get(im.opIndex) : null;
    }

    private static List<Op> opsOf(PdfDocument doc, PdfPage page, PageRenderer renderer, PageImage im) {
        if (im.onPage()) {
            return renderer.pageOps(page);
        }
        if (doc.getObject(im.streamId) instanceof PdfStream form) {
            return ContentParser.parse(form.getDecoded());
        }
        return List.of();
    }

    /* ================================================================= changes */

    /**
     * Replaces the pixels of a picture. An image object changes wherever it
     * is drawn (pages lists the pages showing it, as {index, object}); an
     * inline image becomes an image object of the page.
     */
    public static void replace(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                               PageImage im, BufferedImage img, List<int[]> pages, String label) {
        h.begin(label);
        try {
            if (im.inline()) {
                // the inline image's operation turns into the drawing of a new image object
                PdfStream s = encode(h, img, null, 0.9f);
                String name = addXObject(h, doc, page, pageIndex, h.add(s));
                List<Op> ops = new ArrayList<>(opsOf(doc, page, renderer, im));
                ops.set(im.opIndex, new Op("Do", new PdfObject[] { PdfName.of(name) }));
                setOps(h, doc, page, pageIndex, im, ops);
            } else {
                PdfObject old = doc.getObject(im.objNum);
                Boolean jpeg = old instanceof PdfStream os && "DCTDecode".equals(os.imageFilter()) ? Boolean.TRUE : null;
                h.touch(im.objNum);
                PdfStream s = encode(h, img, jpeg != null && hasAlpha(img) ? null : jpeg, 0.92f);
                if (old instanceof PdfStream os) {
                    for (String k : new String[] { "Interpolate", "Intent", "OC", "Name" }) {
                        if (os.has(k)) {
                            s.put(k, os.getRaw(k));
                        }
                    }
                }
                doc.putObject(im.objNum, s);
                for (int[] p : pages) {
                    h.touchPage(p[0], p[1]);
                }
            }
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    private static boolean hasAlpha(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int w = img.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int p : row) {
                if ((p >>> 24) != 255) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Moves, resizes, turns: the picture's unit square goes where newToUser
     * maps it (in page user space). The drawing operation gets the matrix
     * that makes the difference; a "q matrix cm Do Q" around it already has
     * its matrix changed rather than another level added.
     */
    public static void transform(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                                 PageImage im, AffineTransform newToUser, String label) {
        AffineTransform x;
        try {
            x = im.toUser.createInverse();
        } catch (NoninvertibleTransformException e) {
            throw new IllegalStateException("this picture is drawn flat, it cannot be moved");
        }
        x.concatenate(newToUser);
        h.begin(label);
        try {
            List<Op> ops = new ArrayList<>(opsOf(doc, page, renderer, im));
            int i = im.opIndex;
            if (i < 0 || i >= ops.size()) {
                throw new IllegalStateException("the picture's operation is no longer there");
            }
            boolean wrapped = i >= 2 && i + 1 < ops.size() && ops.get(i - 2).name.equals("q") && ops.get(i - 1).name.equals("cm")
                              && ops.get(i - 1).args.length == 6 && ops.get(i + 1).name.equals("Q");
            if (wrapped) {
                Op cm = ops.get(i - 1);
                AffineTransform c = new AffineTransform(cm.f(0), cm.f(1), cm.f(2), cm.f(3), cm.f(4), cm.f(5));
                c.concatenate(x);
                ops.set(i - 1, cm(c));
            } else {
                Op draw = ops.get(i);
                ops.set(i, new Op("Q", new PdfObject[0]));
                ops.add(i, draw);
                ops.add(i, cm(x));
                ops.add(i, new Op("q", new PdfObject[0]));
            }
            setOps(h, doc, page, pageIndex, im, ops);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** The matrix turning a picture a quarter (clockwise as seen) or half turn about its center, in user space. */
    public static AffineTransform turned(PageImage im, PdfPage page, int quarterTurnsClockwise) {
        Rectangle2D b = im.toUser.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)).getBounds2D();
        AffineTransform a = AffineTransform.getRotateInstance(-Math.PI / 2 * quarterTurnsClockwise, b.getCenterX(),
                                                              b.getCenterY());
        a.concatenate(im.toUser);
        return a;
    }

    /** The matrix mirroring a picture in itself (left-right, or top-bottom). */
    public static AffineTransform mirrored(PageImage im, boolean horizontal) {
        AffineTransform m = new AffineTransform(im.toUser);
        m.concatenate(horizontal ? new AffineTransform(-1, 0, 0, 1, 1, 0) : new AffineTransform(1, 0, 0, -1, 0, 1));
        return m;
    }

    /** Takes a picture out (its drawing operation is removed; the object stays for its other uses). */
    public static void delete(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                              PageImage im) {
        h.begin("Delete picture");
        try {
            List<Op> ops = new ArrayList<>(opsOf(doc, page, renderer, im));
            if (im.opIndex < 0 || im.opIndex >= ops.size()) {
                throw new IllegalStateException("the picture's operation is no longer there");
            }
            ops.remove(im.opIndex);
            setOps(h, doc, page, pageIndex, im, ops);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** Adds a picture to a page, filling a rectangle of display space, upright as the page is shown. */
    public static void insert(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageRenderer renderer,
                              BufferedImage img, Rectangle2D display, String label) {
        h.begin(label);
        try {
            PdfStream s = encode(h, img, null, 0.9f);
            String name = addXObject(h, doc, page, pageIndex, h.add(s));
            // the unit square (y up) onto the rectangle (y down), then display space back to user space
            AffineTransform m = page.displayToUser();
            m.concatenate(new AffineTransform(display.getWidth(), 0, 0, -display.getHeight(), display.getX(),
                                              display.getY() + display.getHeight()));
            List<Op> block = new ArrayList<>();
            block.add(new Op("q", new PdfObject[0]));
            block.add(cm(m));
            block.add(new Op("Do", new PdfObject[] { PdfName.of(name) }));
            block.add(new Op("Q", new PdfObject[0]));
            List<Op> ops = ContentEditor.append(ContentEditor.wrap(new ArrayList<>(renderer.pageOps(page))), block);
            ContentEditor.setPageOps(h, page, pageIndex, ops);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** Scales a picture to fit within a number of pixels on its longer side (never up). */
    public static BufferedImage downscale(BufferedImage img, int maxW, int maxH) {
        double f = Math.min(1.0, Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight()));
        if (f >= 0.999) {
            return img;
        }
        BufferedImage cur = img;
        int tw = Math.max(1, (int) Math.round(img.getWidth() * f));
        int th = Math.max(1, (int) Math.round(img.getHeight() * f));
        // halve while more than twice too big (bilinear alone aliases), then the last step
        while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
            cur = resample(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resample(cur, tw, th);
    }

    private static BufferedImage resample(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /* ================================================================= pixels */

    /** The picture in shades of gray. */
    public static BufferedImage grayscale(BufferedImage src) {
        BufferedImage out = argb(src);
        int w = out.getWidth();
        int h = out.getHeight();
        int[] px = out.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int y = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
            px[i] = (p & 0xff000000) | y << 16 | y << 8 | y;
        }
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /**
     * Levels stretched channel by channel between the darkest and lightest
     * half percent, so a dull scan or photograph uses the whole range.
     */
    public static BufferedImage autoEnhance(BufferedImage src) {
        BufferedImage out = argb(src);
        int w = out.getWidth();
        int h = out.getHeight();
        int[] px = out.getRGB(0, 0, w, h, null, 0, w);
        int[][] hist = new int[3][256];
        int counted = 0;
        for (int p : px) {
            if ((p >>> 24) < 16) {
                continue;
            }
            hist[0][(p >> 16) & 255]++;
            hist[1][(p >> 8) & 255]++;
            hist[2][p & 255]++;
            counted++;
        }
        if (counted == 0) {
            return out;
        }
        int[] lo = new int[3];
        int[] hi = new int[3];
        int cut = Math.max(1, counted / 200);
        for (int c = 0; c < 3; c++) {
            int acc = 0;
            int v = 0;
            while (v < 255 && (acc += hist[c][v]) < cut) {
                v++;
            }
            lo[c] = v;
            acc = 0;
            v = 255;
            while (v > 0 && (acc += hist[c][v]) < cut) {
                v--;
            }
            hi[c] = Math.max(v, lo[c] + 1);
        }
        int[][] lut = new int[3][256];
        for (int c = 0; c < 3; c++) {
            for (int v = 0; v < 256; v++) {
                lut[c][v] = Math.max(0, Math.min(255, (v - lo[c]) * 255 / (hi[c] - lo[c])));
            }
        }
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = (p & 0xff000000) | lut[0][(p >> 16) & 255] << 16 | lut[1][(p >> 8) & 255] << 8 | lut[2][p & 255];
        }
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /**
     * The paper made transparent: pixels close to white fade out (a scanned
     * signature or stamp then sits on the page like ink).
     */
    public static BufferedImage removeWhite(BufferedImage src) {
        BufferedImage out = argb(src);
        int w = out.getWidth();
        int h = out.getHeight();
        int[] px = out.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255;
            int g = (p >> 8) & 255;
            int b = p & 255;
            int lightness = Math.min(r, Math.min(g, b));
            int chroma = Math.max(r, Math.max(g, b)) - lightness;
            // white and near-white grays go; colors and dark strokes stay, with a soft edge between
            int keep = lightness >= 245 && chroma < 18 ? 0 : lightness >= 205 && chroma < 30 ? (245 - lightness) * 255 / 40 : 255;
            int a = (p >>> 24) * keep / 255;
            px[i] = a << 24 | (p & 0xffffff);
        }
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    private static BufferedImage argb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /* ================================================================= plumbing */

    private static void setOps(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PageImage im, List<Op> ops) {
        if (im.onPage()) {
            ContentEditor.setPageOps(h, page, pageIndex, ops);
        } else {
            ContentEditor.setFormOps(h, doc, im.streamId, ops);
            h.touchPage(pageIndex, page.objNum());
        }
    }

    /** Gives an image object a name in the page's /XObject resources. */
    static String addXObject(EditHistory h, PdfDocument doc, PdfPage page, int pageIndex, PdfRef ref) {
        PdfDict res = TextEdit.resources(h, page, pageIndex);
        PdfDict xo = TextEdit.category(h, res, "XObject");
        int i = 1;
        while (xo.has("SPDFim" + i)) {
            i++;
        }
        xo.put("SPDFim" + i, ref);
        return "SPDFim" + i;
    }

    private static Op cm(AffineTransform m) {
        return new Op("cm", new PdfObject[] { num(m.getScaleX()), num(m.getShearY()), num(m.getShearX()), num(m.getScaleY()),
            num(m.getTranslateX()), num(m.getTranslateY()) });
    }

    private static PdfNumber num(double v) {
        return PdfNumber.of(Math.abs(v) < 1e-9 ? 0 : Math.round(v * 1e5) / 1e5);
    }
}
