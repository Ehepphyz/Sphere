package com.sphere.theme;

import java.awt.Color;

/**
 * Colors that read on the surface they are drawn on.
 *
 * A token color is a seed (a hue and a chroma chosen for a dark editor) moved in
 * lightness only, in OKLab, until its WCAG contrast with the background reaches
 * the target. OKLab keeps the hue as the eye sees it while the lightness moves,
 * which a mix toward black or white does not: a fixed 45% darkening turned the
 * pale yellow of function names into an olive that still read at 2.6:1 on white,
 * and left the notebook's own pastel palette at 1.3:1 there.
 *
 * Only a color below the target moves, and by the least lightness that reaches
 * it, so a palette that already reads is left exactly as its designer made it.
 */
public final class Contrast {

    /** WCAG 2 AA for normal text. */
    public static final double AA = 4.5;

    private Contrast() {
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    public static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    /** WCAG contrast ratio, 1 to 21. */
    public static double ratio(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The seed moved in OKLab lightness until it reaches {@code target} on {@code background}. */
    public static Color readable(Color seed, Color background, double target) {
        if (ratio(seed, background) >= target) {
            return seed;
        }
        double[] lab = oklab(seed);
        // Toward whichever pole leaves more room on this background.
        boolean lighten = ratio(Color.WHITE, background) >= ratio(Color.BLACK, background);
        double lo = lab[0];
        double hi = lighten ? 1.0 : 0.0;
        Color best = lighten ? Color.WHITE : Color.BLACK;
        for (int k = 0; k < 40; k++) {
            double mid = (lo + hi) / 2;
            Color c = fromOklab(mid, lab[1], lab[2]);
            if (ratio(c, background) >= target) {
                best = c;
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return new Color(best.getRed(), best.getGreen(), best.getBlue(), seed.getAlpha());
    }

    /**
     * A whole token palette: every seed made readable, then the pairs that came
     * out too alike pulled apart. Bringing each color just up to the target gives
     * them all nearly the same lightness on a light surface, and two hues that
     * differed mostly in lightness (a pale yellow and a pale green) become hard to
     * tell apart. Moving a color further from the background only raises its
     * contrast, so the closest pair is separated that way, one lightness step at a
     * time, until every pair is at least {@code minDelta} apart in OKLab or no
     * step helps any more.
     */
    public static Color[] palette(Color[] seeds, Color background, double target, double minDelta) {
        int n = seeds.length;
        Color[] out = new Color[n];
        double[] lightness = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = readable(seeds[i], background, target);
            lightness[i] = oklab(out[i])[0];
        }
        double away = ratio(Color.WHITE, background) >= ratio(Color.BLACK, background) ? 1 : -1;
        for (int round = 0; round < 400; round++) {
            int a = -1;
            int b = -1;
            double closest = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (seeds[i].equals(seeds[j])) {
                        continue;
                    }
                    double d = deltaE(out[i], out[j]);
                    if (d < closest) {
                        closest = d;
                        a = i;
                        b = j;
                    }
                }
            }
            if (a < 0 || closest >= minDelta) {
                break;
            }
            // Move whichever of the two gains the pair more distance.
            int bestIndex = -1;
            Color bestColor = null;
            double bestGain = 0;
            for (int k : new int[] { a, b }) {
                double l = lightness[k] + away * 0.01;
                if (l < 0.05 || l > 0.98) {
                    continue;
                }
                double[] seedLab = oklab(seeds[k]);
                Color moved = fromOklab(l, seedLab[1], seedLab[2]);
                double gain = deltaE(moved, out[k == a ? b : a]) - closest;
                if (gain > bestGain) {
                    bestGain = gain;
                    bestIndex = k;
                    bestColor = moved;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            out[bestIndex] = bestColor;
            lightness[bestIndex] += away * 0.01;
        }
        return out;
    }

    /** Perceptual distance in OKLab: about 0.02 is just noticeable, 0.1 clearly another color. */
    public static double deltaE(Color a, Color b) {
        double[] p = oklab(a);
        double[] q = oklab(b);
        return Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1])
                         + (p[2] - q[2]) * (p[2] - q[2]));
    }

    /** sRGB to OKLab {L, a, b} (Ottosson 2020). */
    public static double[] oklab(Color c) {
        double r = linear(c.getRed());
        double g = linear(c.getGreen());
        double b = linear(c.getBlue());
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] {
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s };
    }

    /**
     * OKLab back to sRGB. A lightness the hue cannot reach at its chroma (a
     * saturated yellow made dark) gives up chroma, never hue.
     */
    public static Color fromOklab(double L, double a, double b) {
        double scale = 1.0;
        double[] rgb = toLinearRgb(L, a, b);
        if (!inGamut(rgb)) {
            double lo = 0.0;
            double hi = 1.0;
            for (int k = 0; k < 30; k++) {
                double mid = (lo + hi) / 2;
                if (inGamut(toLinearRgb(L, a * mid, b * mid))) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            scale = lo;
            rgb = toLinearRgb(L, a * scale, b * scale);
        }
        return new Color(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]));
    }

    private static double[] toLinearRgb(double L, double a, double b) {
        double l = L + 0.3963377774 * a + 0.2158037573 * b;
        double m = L - 0.1055613458 * a - 0.0638541728 * b;
        double s = L - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return new double[] {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s };
    }

    private static boolean inGamut(double[] rgb) {
        final double eps = 1e-6;
        return rgb[0] >= -eps && rgb[0] <= 1 + eps && rgb[1] >= -eps && rgb[1] <= 1 + eps
               && rgb[2] >= -eps && rgb[2] <= 1 + eps;
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int encode(double linear) {
        double v = Math.max(0.0, Math.min(1.0, linear));
        double c = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, c)) * 255.0);
    }
}
