package com.sphere.components.imaging;

import java.awt.image.BufferedImage;

/**
 * Per-channel adjustments, applied through one lookup table so a stack of them
 * costs a single pass over the pixels.
 */
public final class ImageAdjustments {

    /** Brightness in [-100, 100]. */
    public int brightness;
    /** Contrast in [-100, 100]. */
    public int contrast;
    /** Gamma in [0.1, 5.0]; 1.0 leaves the image alone. */
    public double gamma = 1.0;
    /** Input black and white points, in [0, 255]. */
    public int blackPoint;
    public int whitePoint = 255;
    public boolean grayscale;
    public boolean invert;
    /** Saturation in [-100, 100]. */
    public int saturation;

    public boolean isIdentity() {
        return brightness == 0 && contrast == 0 && gamma == 1.0
            && blackPoint == 0 && whitePoint == 255
            && !grayscale && !invert && saturation == 0;
    }

    public void reset() {
        brightness = 0;
        contrast = 0;
        gamma = 1.0;
        blackPoint = 0;
        whitePoint = 255;
        grayscale = false;
        invert = false;
        saturation = 0;
    }

    public ImageAdjustments copy() {
        ImageAdjustments a = new ImageAdjustments();
        a.brightness = brightness;
        a.contrast = contrast;
        a.gamma = gamma;
        a.blackPoint = blackPoint;
        a.whitePoint = whitePoint;
        a.grayscale = grayscale;
        a.invert = invert;
        a.saturation = saturation;
        return a;
    }

    /** The tone curve these settings describe, one entry per input level. */
    public int[] buildCurve() {
        final int[] curve = new int[256];
        final double lower = Math.min(blackPoint, whitePoint - 1);
        final double upper = Math.max(whitePoint, blackPoint + 1);
        final double span = upper - lower;
        final double contrastFactor =
            (259.0 * (contrast + 255.0)) / (255.0 * (259.0 - contrast));
        final double safeGamma = gamma <= 0 ? 1.0 : gamma;

        for (int i = 0; i < 256; i++) {
            double v = (i - lower) / span;          // levels
            v = Math.max(0.0, Math.min(1.0, v));
            v = Math.pow(v, 1.0 / safeGamma);        // gamma
            v = v * 255.0 + brightness * 2.55;       // brightness
            v = contrastFactor * (v - 128.0) + 128.0; // contrast
            if (invert) {
                v = 255.0 - v;
            }
            curve[i] = (int) Math.max(0, Math.min(255, Math.round(v)));
        }
        return curve;
    }

    /** Returns a new image; the source is left untouched. */
    public BufferedImage apply(BufferedImage source) {
        if (source == null) {
            return null;
        }
        final int w = source.getWidth();
        final int h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (isIdentity()) {
            out.getGraphics().drawImage(source, 0, 0, null);
            return out;
        }

        final int[] curve = buildCurve();
        final double satFactor = 1.0 + saturation / 100.0;

        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            source.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                final int argb = row[x];
                final int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    row[x] = 0;
                    continue;
                }
                int r = curve[(argb >> 16) & 0xFF];
                int g = curve[(argb >> 8) & 0xFF];
                int b = curve[argb & 0xFF];

                if (grayscale) {
                    // Rec. 601 luma, which is what the eye reads as brightness.
                    final int luma = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                    r = luma;
                    g = luma;
                    b = luma;
                } else if (saturation != 0) {
                    final double luma = 0.299 * r + 0.587 * g + 0.114 * b;
                    r = clamp(luma + (r - luma) * satFactor);
                    g = clamp(luma + (g - luma) * satFactor);
                    b = clamp(luma + (b - luma) * satFactor);
                }
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Counts levels per channel, for the histogram the levels dialog shows. */
    public static int[][] histogram(BufferedImage image) {
        final int[][] bins = new int[4][256];
        if (image == null) {
            return bins;
        }
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                final int argb = row[x];
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                final int r = (argb >> 16) & 0xFF;
                final int g = (argb >> 8) & 0xFF;
                final int b = argb & 0xFF;
                bins[0][r]++;
                bins[1][g]++;
                bins[2][b]++;
                bins[3][(int) Math.round(0.299 * r + 0.587 * g + 0.114 * b)]++;
            }
        }
        return bins;
    }

    /**
     * Black and white points that put the given fraction of pixels at each end,
     * which is what an automatic levels command does.
     */
    public static int[] autoLevels(BufferedImage image, double clipFraction) {
        final int[][] bins = histogram(image);
        final int[] luma = bins[3];
        long total = 0;
        for (int count : luma) {
            total += count;
        }
        if (total == 0) {
            return new int[] {0, 255};
        }
        final long clip = (long) (total * Math.max(0.0, Math.min(0.2, clipFraction)));

        int black = 0;
        long seen = 0;
        for (int i = 0; i < 256; i++) {
            seen += luma[i];
            if (seen > clip) {
                black = i;
                break;
            }
        }
        int white = 255;
        seen = 0;
        for (int i = 255; i >= 0; i--) {
            seen += luma[i];
            if (seen > clip) {
                white = i;
                break;
            }
        }
        if (white <= black) {
            return new int[] {0, 255};
        }
        return new int[] {black, white};
    }
}
