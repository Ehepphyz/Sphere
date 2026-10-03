package com.sphere.components.spherebrowser;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Colour scales for values: ROOT's kBird, which a canvas uses unless told
 * otherwise, the perceptually even ones of matplotlib, and a few of Sphere's
 * own. Each is a list of stops, interpolated into 256 colours.
 */
public final class Palettes {

    private static final Map<String, double[][]> STOPS = new LinkedHashMap<>();

    static {
        STOPS.put("ROOT kBird", new double[][]{
            {0.2082, 0.1664, 0.5293}, {0.0592, 0.3599, 0.8684}, {0.0780, 0.5041, 0.8385}, {0.0232, 0.6419, 0.7914},
            {0.1802, 0.7178, 0.6425}, {0.5301, 0.7492, 0.4662}, {0.8186, 0.7328, 0.3499}, {0.9956, 0.7862, 0.1968},
            {0.9764, 0.9832, 0.0539}});
        STOPS.put("Viridis", new double[][]{
            {0.267, 0.005, 0.329}, {0.283, 0.141, 0.458}, {0.254, 0.265, 0.530}, {0.207, 0.372, 0.553},
            {0.164, 0.471, 0.558}, {0.128, 0.567, 0.551}, {0.135, 0.659, 0.518}, {0.267, 0.749, 0.441},
            {0.478, 0.821, 0.318}, {0.741, 0.873, 0.150}, {0.993, 0.906, 0.144}});
        STOPS.put("Inferno", new double[][]{
            {0.001, 0.000, 0.014}, {0.087, 0.045, 0.225}, {0.258, 0.039, 0.406}, {0.416, 0.090, 0.433},
            {0.578, 0.148, 0.404}, {0.735, 0.216, 0.330}, {0.865, 0.317, 0.226}, {0.954, 0.469, 0.098},
            {0.987, 0.645, 0.040}, {0.964, 0.843, 0.273}, {0.988, 0.998, 0.645}});
        STOPS.put("Plasma", new double[][]{
            {0.050, 0.030, 0.528}, {0.294, 0.012, 0.631}, {0.492, 0.012, 0.658}, {0.665, 0.139, 0.586},
            {0.798, 0.280, 0.470}, {0.899, 0.425, 0.360}, {0.973, 0.585, 0.252}, {0.994, 0.775, 0.161},
            {0.940, 0.975, 0.131}});
        STOPS.put("Turbo", new double[][]{
            {0.190, 0.072, 0.232}, {0.275, 0.408, 0.859}, {0.157, 0.733, 0.924}, {0.196, 0.949, 0.595},
            {0.643, 0.990, 0.235}, {0.946, 0.798, 0.217}, {0.988, 0.493, 0.123}, {0.846, 0.188, 0.022},
            {0.480, 0.016, 0.011}});
        STOPS.put("Cividis", new double[][]{
            {0.000, 0.135, 0.304}, {0.168, 0.247, 0.432}, {0.333, 0.355, 0.436}, {0.480, 0.471, 0.459},
            {0.640, 0.597, 0.452}, {0.812, 0.733, 0.389}, {0.995, 0.906, 0.144}});
        STOPS.put("ROOT kRainBow", new double[][]{
            {0.0, 0.0, 0.5}, {0.0, 0.0, 1.0}, {0.0, 1.0, 1.0}, {0.0, 1.0, 0.0}, {1.0, 1.0, 0.0}, {1.0, 0.0, 0.0},
            {0.5, 0.0, 0.0}});
        STOPS.put("Cherenkov", new double[][]{
            {0.00, 0.00, 0.02}, {0.02, 0.05, 0.25}, {0.05, 0.25, 0.70}, {0.10, 0.65, 0.95}, {0.60, 0.92, 1.00},
            {1.00, 1.00, 1.00}});
        STOPS.put("Calorimeter", new double[][]{
            {0.05, 0.05, 0.10}, {0.30, 0.05, 0.35}, {0.75, 0.10, 0.25}, {0.98, 0.45, 0.05}, {1.00, 0.85, 0.20},
            {1.00, 1.00, 0.85}});
        STOPS.put("Greys", new double[][]{{0.08, 0.08, 0.08}, {0.95, 0.95, 0.95}});
        STOPS.put("Diverging (pull)", new double[][]{
            {0.019, 0.188, 0.380}, {0.263, 0.576, 0.765}, {0.820, 0.898, 0.941}, {0.969, 0.969, 0.969},
            {0.992, 0.859, 0.780}, {0.839, 0.376, 0.302}, {0.404, 0.000, 0.122}});
    }

    private Palettes() {
    }

    public static String[] names() {
        return STOPS.keySet().toArray(new String[0]);
    }

    public static Color[] get(String name) {
        final double[][] s = STOPS.getOrDefault(name, STOPS.get("ROOT kBird"));
        final Color[] out = new Color[256];
        for (int i = 0; i < out.length; i++) {
            final double t = i / 255.0 * (s.length - 1);
            final int k = Math.min(s.length - 2, (int) Math.floor(t));
            final double f = t - k;
            out[i] = new Color(
                (float) Math.max(0, Math.min(1, s[k][0] + f * (s[k + 1][0] - s[k][0]))),
                (float) Math.max(0, Math.min(1, s[k][1] + f * (s[k + 1][1] - s[k][1]))),
                (float) Math.max(0, Math.min(1, s[k][2] + f * (s[k + 1][2] - s[k][2]))));
        }
        return out;
    }

    /** The colour of a fraction 0..1 of the scale, as an opaque ARGB int. */
    public static int at(Color[] palette, double fraction) {
        if (!Double.isFinite(fraction)) fraction = 0;
        final int i = (int) Math.round(Math.max(0, Math.min(1, fraction)) * (palette.length - 1));
        return 0xFF000000 | palette[i].getRGB();
    }
}
