package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.Filters;
import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfNull;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Images (8.9) to ARGB pictures: raw samples of 1 to 16 bits in any color
 * space with their Decode arrays, JPEG (CMYK and YCCK included), CCITT fax,
 * stencil masks painted in the fill color, color-key masks, explicit masks
 * and soft masks. Very large images are subsampled to what a screen can show.
 */
public final class ImageDecoder {

    private ImageDecoder() {
    }

    /** At most this many pixels are decoded; bigger images are subsampled. */
    public static int maxPixels = 24_000_000;

    public static BufferedImage decode(PdfDict d, byte[] inlineData, PdfDict resources, Map<Object, PdfColorSpace> csCache,
                                       int stencilRgb) {
        int w = d.getInt("Width", d.getInt("W", 0));
        int h = d.getInt("Height", d.getInt("H", 0));
        if (w <= 0 || h <= 0 || (long) w * h > 400_000_000L) {
            return null;
        }
        boolean mask = d.getBool("ImageMask", d.getBool("IM", false));
        int bpc = mask ? 1 : d.getInt("BitsPerComponent", d.getInt("BPC", 8));
        String codec;
        byte[] data;
        if (inlineData != null) {
            codec = Filters.imageFilter(d);
            data = Filters.decode(inlineData, d, false);
        } else if (d instanceof PdfStream s) {
            codec = s.imageFilter();
            data = s.getDecoded();
        } else {
            return null;
        }
        PdfColorSpace cs = null;
        if (!mask) {
            PdfObject cso = d.get("ColorSpace", "CS");
            if (cso != PdfNull.NULL) {
                cs = PdfColorSpace.resolve(cso, resources, csCache);
            }
        }
        float[] decode = d.getArray("Decode") != null ? d.getArray("Decode").toFloats()
            : d.getArray("D") != null ? d.getArray("D").toFloats() : null;

        int sub = 1;
        while ((long) (w / sub) * (h / sub) > maxPixels) {
            sub++;
        }
        BufferedImage img;
        if ("DCTDecode".equals(codec) || "JPXDecode".equals(codec)) {
            img = jpeg(data, decode, sub, codec);
            if (img == null) {
                return placeholder(w, h, sub);
            }
            if (mask) {
                return stencilFromImage(img, stencilRgb, decode);
            }
        } else if ("CCITTFaxDecode".equals(codec)) {
            PdfDict p = Filters.imageParams(d);
            byte[] bits = CcittDecoder.decode(data, p, w, h);
            if (bits == null) {
                return placeholder(w, h, sub);
            }
            boolean blackIs1 = p != null && p.getBool("BlackIs1", false);
            if (!blackIs1) {
                // CCITT gives 1 for black; with BlackIs1 false the image says 0 is black
                for (int i = 0; i < bits.length; i++) {
                    bits[i] = (byte) ~bits[i];
                }
            }
            data = bits;
            img = mask ? stencil(data, w, h, decode, stencilRgb, sub) : raw(data, w, h, 1, cs == null ? PdfColorSpace.GRAY : cs, decode, sub);
        } else if ("JBIG2Decode".equals(codec)) {
            return placeholder(w, h, sub);
        } else if (mask) {
            img = stencil(data, w, h, decode, stencilRgb, sub);
        } else {
            if (cs == null) {
                cs = PdfColorSpace.GRAY;
            }
            img = raw(data, w, h, bpc, cs, decode, sub);
        }
        if (img == null || mask) {
            return img;
        }
        // masks
        PdfStream smask = d.getStream("SMask");
        if (smask != null) {
            applySoftMask(img, smask);
        } else {
            PdfObject m = d.get("Mask");
            if (m instanceof PdfStream ms) {
                applyExplicitMask(img, ms);
            } else if (m instanceof PdfArray ka && cs != null && !"DCTDecode".equals(codec)) {
                applyColorKey(img, data, w, h, bpc, cs.components(), ka.toFloats(), sub);
            }
        }
        return img;
    }

    private static BufferedImage placeholder(int w, int h, int sub) {
        int pw = Math.max(1, Math.min(w / sub, 64));
        int ph = Math.max(1, Math.min(h / sub, 64));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[pw * ph];
        java.util.Arrays.fill(px, 0x30808080);
        img.setRGB(0, 0, pw, ph, px, 0, pw);
        return img;
    }

    /** Samples to pixels: the general path, with fast ones for 8-bit gray and RGB. */
    static BufferedImage raw(byte[] data, int w, int h, int bpc, PdfColorSpace cs, float[] decode, int sub) {
        int n = cs.components();
        if (n <= 0) {
            return null;
        }
        int ow = Math.max(1, w / sub);
        int oh = Math.max(1, h / sub);
        BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[ow * oh];
        int rowBytes = (w * n * bpc + 7) / 8;
        float[] dec = decode != null && decode.length >= 2 * n ? decode : cs.defaultDecode(bpc);
        boolean plainDecode = decode == null || isDefault(decode, n);
        boolean indexed = cs instanceof PdfColorSpace.Indexed;
        float max = (1 << Math.min(bpc, 16)) - 1;
        // a table per component for bpc <= 8: sample value to decoded value
        float[][] tables = null;
        if (bpc <= 8) {
            tables = new float[n][(int) max + 1];
            for (int k = 0; k < n; k++) {
                float dmin = dec[2 * k];
                float dmax = dec[2 * k + 1];
                for (int v = 0; v <= max; v++) {
                    tables[k][v] = indexed ? dmin + v * (dmax - dmin) / max * (max == 0 ? 0 : 1) : dmin + v * (dmax - dmin) / max;
                }
                if (indexed && plainDecode) {
                    for (int v = 0; v <= max; v++) {
                        tables[k][v] = v;
                    }
                }
            }
        }
        float[] c = new float[n];
        boolean fastRgb = bpc == 8 && n == 3 && cs == PdfColorSpace.RGB && plainDecode;
        boolean fastGray = bpc == 8 && n == 1 && cs == PdfColorSpace.GRAY && plainDecode;
        java.util.HashMap<Long, Integer> memo = (cs instanceof PdfColorSpace.Separation || cs == PdfColorSpace.CMYK)
            ? new java.util.HashMap<>() : null;
        for (int oy = 0; oy < oh; oy++) {
            int y = oy * sub;
            int row = y * rowBytes;
            int o = oy * ow;
            if (row >= data.length) {
                break;
            }
            for (int ox = 0; ox < ow; ox++) {
                int x = ox * sub;
                int argb;
                if (fastRgb) {
                    int p = row + 3 * x;
                    if (p + 2 >= data.length) {
                        break;
                    }
                    argb = 0xff000000 | ((data[p] & 0xff) << 16) | ((data[p + 1] & 0xff) << 8) | (data[p + 2] & 0xff);
                } else if (fastGray) {
                    int p = row + x;
                    if (p >= data.length) {
                        break;
                    }
                    int v = data[p] & 0xff;
                    argb = 0xff000000 | (v << 16) | (v << 8) | v;
                } else {
                    long key = 0;
                    for (int k = 0; k < n; k++) {
                        int v = sample(data, row, x * n + k, bpc);
                        key = (key << Math.min(bpc, 16)) | v;
                        if (bpc <= 8) {
                            c[k] = tables[k][Math.min(v, (int) max)];
                        } else {
                            c[k] = dec[2 * k] + (v / max) * (dec[2 * k + 1] - dec[2 * k]);
                        }
                    }
                    if (memo != null && n <= 4 && bpc <= 8) {
                        Integer hit = memo.get(key);
                        if (hit == null) {
                            hit = cs.rgb(c);
                            if (memo.size() < 100000) {
                                memo.put(key, hit);
                            }
                        }
                        argb = 0xff000000 | hit;
                    } else {
                        argb = 0xff000000 | cs.rgb(c);
                    }
                }
                px[o + ox] = argb;
            }
        }
        img.setRGB(0, 0, ow, oh, px, 0, ow);
        return img;
    }

    private static boolean isDefault(float[] d, int n) {
        for (int k = 0; k < n && 2 * k + 1 < d.length; k++) {
            if (d[2 * k] != 0 || d[2 * k + 1] != 1) {
                return false;
            }
        }
        return true;
    }

    /** The i-th sample of a row. */
    static int sample(byte[] data, int row, int i, int bpc) {
        switch (bpc) {
            case 8: {
                int p = row + i;
                return p < data.length ? data[p] & 0xff : 0;
            }
            case 16: {
                int p = row + 2 * i;
                return p + 1 < data.length ? ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff) : 0;
            }
            case 1: case 2: case 4: {
                long bit = (long) i * bpc;
                int p = row + (int) (bit >> 3);
                if (p >= data.length) {
                    return 0;
                }
                int shift = 8 - bpc - (int) (bit & 7);
                return ((data[p] & 0xff) >> shift) & ((1 << bpc) - 1);
            }
            default:
                return 0;
        }
    }

    static BufferedImage stencil(byte[] data, int w, int h, float[] decode, int rgb, int sub) {
        boolean invert = decode != null && decode.length >= 1 && decode[0] == 1;
        int ow = Math.max(1, w / sub);
        int oh = Math.max(1, h / sub);
        BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[ow * oh];
        int rowBytes = (w + 7) / 8;
        int paint = 0xff000000 | rgb;
        for (int oy = 0; oy < oh; oy++) {
            int row = oy * sub * rowBytes;
            for (int ox = 0; ox < ow; ox++) {
                int v = sample(data, row, ox * sub, 1);
                boolean painted = invert ? v == 1 : v == 0;
                px[oy * ow + ox] = painted ? paint : 0;
            }
        }
        img.setRGB(0, 0, ow, oh, px, 0, ow);
        return img;
    }

    private static BufferedImage stencilFromImage(BufferedImage src, int rgb, float[] decode) {
        boolean invert = decode != null && decode.length >= 1 && decode[0] == 1;
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = src.getRGB(x, y) & 0xff;
                boolean painted = invert ? v >= 128 : v < 128;
                img.setRGB(x, y, painted ? 0xff000000 | rgb : 0);
            }
        }
        return img;
    }

    private static void applySoftMask(BufferedImage img, PdfStream smask) {
        BufferedImage m = decode(smask, null, null, null, 0);
        if (m == null) {
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int mw = m.getWidth();
        int mh = m.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] mp = m.getRGB(0, 0, mw, mh, null, 0, mw);
        for (int y = 0; y < h; y++) {
            int my = Math.min(mh - 1, (int) ((long) y * mh / h));
            for (int x = 0; x < w; x++) {
                int mx = Math.min(mw - 1, (int) ((long) x * mw / w));
                int a = mp[my * mw + mx] & 0xff;
                int p = px[y * w + x];
                int oa = (p >>> 24) * a / 255;
                px[y * w + x] = (oa << 24) | (p & 0xffffff);
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    private static void applyExplicitMask(BufferedImage img, PdfStream ms) {
        int mw0 = ms.getInt("Width", 0);
        int mh0 = ms.getInt("Height", 0);
        if (mw0 <= 0 || mh0 <= 0) {
            return;
        }
        BufferedImage m = decode(ms, null, null, null, 0xffffff);
        if (m == null) {
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int mw = m.getWidth();
        int mh = m.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] mp = m.getRGB(0, 0, mw, mh, null, 0, mw);
        for (int y = 0; y < h; y++) {
            int my = Math.min(mh - 1, (int) ((long) y * mh / h));
            for (int x = 0; x < w; x++) {
                int mx = Math.min(mw - 1, (int) ((long) x * mw / w));
                // the mask decoded as a stencil: painted (opaque) where it shows the image
                if ((mp[my * mw + mx] >>> 24) == 0) {
                    px[y * w + x] = 0;
                }
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    private static void applyColorKey(BufferedImage img, byte[] data, int w, int h, int bpc, int n, float[] ranges, int sub) {
        if (ranges.length < 2 * n) {
            return;
        }
        int ow = img.getWidth();
        int oh = img.getHeight();
        int rowBytes = (w * n * bpc + 7) / 8;
        for (int oy = 0; oy < oh; oy++) {
            int row = oy * sub * rowBytes;
            for (int ox = 0; ox < ow; ox++) {
                boolean in = true;
                for (int k = 0; k < n && in; k++) {
                    int v = sample(data, row, ox * sub * n + k, bpc);
                    in = v >= ranges[2 * k] && v <= ranges[2 * k + 1];
                }
                if (in) {
                    img.setRGB(ox, oy, 0);
                }
            }
        }
    }

    /* ------------------------------------------------------------- JPEG */

    private static BufferedImage jpeg(byte[] data, float[] decode, int sub, String codec) {
        String format = "JPXDecode".equals(codec) ? "jpeg2000" : "jpeg";
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(format);
        if (!it.hasNext()) {
            return null;
        }
        ImageReader r = it.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            r.setInput(in);
            ImageReadParam p = r.getDefaultReadParam();
            if (sub > 1) {
                p.setSourceSubsampling(sub, sub, 0, 0);
            }
            BufferedImage img;
            int adobe = adobeTransform(data);
            try {
                Raster raster = r.readRaster(0, p);
                if (raster.getNumBands() == 4) {
                    img = cmykRaster(raster, adobe, decode);
                    return img;
                }
            } catch (Exception e) {
                // fall through to the regular read
            }
            BufferedImage read = r.read(0, p);
            img = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.drawImage(read, 0, 0, null);
            g.dispose();
            if (decode != null && decode.length >= 2 && decode[0] == 1 && decode[1] == 0) {
                int w = img.getWidth();
                int h = img.getHeight();
                int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0; i < px.length; i++) {
                    px[i] = 0xff000000 | (~px[i] & 0xffffff);
                }
                img.setRGB(0, 0, w, h, px, 0, w);
            }
            return img;
        } catch (Exception e) {
            return null;
        } finally {
            r.dispose();
        }
    }

    /** The Adobe APP14 transform byte (0 CMYK/RGB, 1 YCbCr, 2 YCCK), or -1 when there is no marker. */
    private static int adobeTransform(byte[] d) {
        int p = 2;
        while (p + 4 < d.length && (d[p] & 0xff) == 0xFF) {
            int marker = d[p + 1] & 0xff;
            int len = ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            if (marker == 0xEE && len >= 12 && p + 15 < d.length && d[p + 4] == 'A' && d[p + 5] == 'd'
                && d[p + 6] == 'o' && d[p + 7] == 'b' && d[p + 8] == 'e') {
                return d[p + 15] & 0xff;
            }
            if (marker == 0xDA) {
                break;
            }
            p += 2 + len;
        }
        return -1;
    }

    private static BufferedImage cmykRaster(Raster raster, int adobe, float[] decode) {
        int w = raster.getWidth();
        int h = raster.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];
        int[] s = new int[4];
        boolean invert = adobe >= 0;
        if (decode != null && decode.length >= 2 && decode[0] == 1 && decode[1] == 0) {
            invert = !invert;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.getPixel(x + raster.getMinX(), y + raster.getMinY(), s);
                float c;
                float m;
                float yy;
                float k;
                if (adobe == 2) {
                    // YCCK: YCbCr of the inverted CMY, then K
                    double yv = s[0];
                    double cb = s[1] - 128;
                    double cr = s[2] - 128;
                    double r = yv + 1.402 * cr;
                    double g = yv - 0.344136 * cb - 0.714136 * cr;
                    double b = yv + 1.772 * cb;
                    c = (float) (1 - clamp(r) / 255);
                    m = (float) (1 - clamp(g) / 255);
                    yy = (float) (1 - clamp(b) / 255);
                    k = invert ? 1 - s[3] / 255f : s[3] / 255f;
                } else if (invert) {
                    c = 1 - s[0] / 255f;
                    m = 1 - s[1] / 255f;
                    yy = 1 - s[2] / 255f;
                    k = 1 - s[3] / 255f;
                } else {
                    c = s[0] / 255f;
                    m = s[1] / 255f;
                    yy = s[2] / 255f;
                    k = s[3] / 255f;
                }
                px[y * w + x] = 0xff000000 | PdfColorSpace.cmyk(c, m, yy, k);
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
