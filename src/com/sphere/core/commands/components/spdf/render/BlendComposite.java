package com.sphere.components.spdf.render;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * The separable blend modes of 11.3.5 that Java2D lacks: Multiply (what
 * highlighter annotations use), Screen, Darken, Lighten, Difference,
 * Exclusion, Overlay. Works on the ARGB pictures pages are drawn into.
 */
public final class BlendComposite implements Composite {

    private final int mode;
    private final float alpha;

    private BlendComposite(int mode, float alpha) {
        this.mode = mode;
        this.alpha = alpha;
    }

    /** A composite for a /BM name, or null for Normal and the modes not handled. */
    public static Composite of(String bm, float alpha) {
        int m = switch (bm == null ? "Normal" : bm) {
            case "Multiply" -> 1;
            case "Screen" -> 2;
            case "Darken" -> 3;
            case "Lighten" -> 4;
            case "Difference" -> 5;
            case "Exclusion" -> 6;
            case "Overlay", "HardLight", "SoftLight" -> 7;
            default -> 0;
        };
        return m == 0 ? null : new BlendComposite(m, alpha);
    }

    @Override
    public CompositeContext createContext(ColorModel srcCM, ColorModel dstCM, RenderingHints hints) {
        final ColorModel scm = srcCM;
        final ColorModel dcm = dstCM;
        return new CompositeContext() {
            @Override
            public void dispose() {
            }

            @Override
            public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
                int w = Math.min(src.getWidth(), dstIn.getWidth());
                int h = Math.min(src.getHeight(), dstIn.getHeight());
                Object sPix = null;
                Object dPix = null;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        sPix = src.getDataElements(x + src.getMinX(), y + src.getMinY(), sPix);
                        dPix = dstIn.getDataElements(x + dstIn.getMinX(), y + dstIn.getMinY(), dPix);
                        int s = scm.getRGB(sPix);
                        int d = dcm.getRGB(dPix);
                        int sa = (int) ((s >>> 24) * alpha);
                        if (sa == 0) {
                            continue;
                        }
                        int r = blend((s >> 16) & 0xff, (d >> 16) & 0xff);
                        int g = blend((s >> 8) & 0xff, (d >> 8) & 0xff);
                        int b = blend(s & 0xff, d & 0xff);
                        int da = d >>> 24;
                        // mix the blended color over the backdrop by the source alpha
                        int or = ((d >> 16) & 0xff) + ((r - ((d >> 16) & 0xff)) * sa) / 255;
                        int og = ((d >> 8) & 0xff) + ((g - ((d >> 8) & 0xff)) * sa) / 255;
                        int ob = (d & 0xff) + ((b - (d & 0xff)) * sa) / 255;
                        int oa = Math.min(255, da + sa * (255 - da) / 255);
                        int out = (oa << 24) | (or << 16) | (og << 8) | ob;
                        dstOut.setDataElements(x + dstOut.getMinX(), y + dstOut.getMinY(),
                                               dcm.getDataElements(out, null));
                    }
                }
            }
        };
    }

    private int blend(int s, int d) {
        switch (mode) {
            case 1: return s * d / 255;
            case 2: return s + d - s * d / 255;
            case 3: return Math.min(s, d);
            case 4: return Math.max(s, d);
            case 5: return Math.abs(s - d);
            case 6: return s + d - 2 * s * d / 255;
            case 7: return d < 128 ? 2 * s * d / 255 : 255 - 2 * (255 - s) * (255 - d) / 255;
            default: return s;
        }
    }

    static boolean unused(DataBuffer b) {
        return b == null;
    }
}
