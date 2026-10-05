package com.sphere.components.rootview;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ROOT's colour indices, as TColor creates them at start-up: the basic ones
 * (0 to 50), the "pretty palette" 51 to 99, the greys, and the colour wheel
 * (kRed-10 ... kRed+4, kOrange-9 ... kOrange+10, and so on). A function of
 * ROOT's menu that takes a Color_t takes one of these numbers.
 */
public final class RootColors {

    private static final Map<Integer, Color> TABLE = new LinkedHashMap<>();
    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    public static final int K_WHITE = 0;
    public static final int K_BLACK = 1;
    public static final int K_GRAY = 920;
    public static final int K_RED = 632;
    public static final int K_GREEN = 416;
    public static final int K_BLUE = 600;
    public static final int K_YELLOW = 400;
    public static final int K_MAGENTA = 616;
    public static final int K_CYAN = 432;
    public static final int K_ORANGE = 800;
    public static final int K_SPRING = 820;
    public static final int K_TEAL = 840;
    public static final int K_AZURE = 860;
    public static final int K_VIOLET = 880;
    public static final int K_PINK = 900;

    static {
        put(0, 1, 1, 1, "kWhite");
        put(1, 0, 0, 0, "kBlack");
        put(2, 1, 0, 0, "red");
        put(3, 0, 1, 0, "green");
        put(4, 0, 0, 1, "blue");
        put(5, 1, 1, 0, "yellow");
        put(6, 1, 0, 1, "magenta");
        put(7, 0, 1, 1, "cyan");
        put(8, 0.35, 0.83, 0.33, null);
        put(9, 0.35, 0.33, 0.85, null);
        put(10, 0.999, 0.999, 0.999, "white");
        put(11, 0.754, 0.715, 0.676, "editcol");
        put(12, .3, .3, .3, "grey12");
        put(13, .4, .4, .4, "grey13");
        put(14, .5, .5, .5, "grey14");
        put(15, .6, .6, .6, "grey15");
        put(16, .7, .7, .7, "grey16");
        put(17, .8, .8, .8, "grey17");
        put(18, .9, .9, .9, "grey18");
        put(19, .95, .95, .95, "grey19");
        put(20, 0.8, 0.78, 0.67, null);
        put(21, 0.8, 0.78, 0.67, null);
        put(22, 0.76, 0.75, 0.66, null);
        put(23, 0.73, 0.71, 0.64, null);
        put(24, 0.70, 0.65, 0.59, null);
        put(25, 0.72, 0.64, 0.61, null);
        put(26, 0.68, 0.6, 0.55, null);
        put(27, 0.61, 0.56, 0.51, null);
        put(28, 0.53, 0.4, 0.34, null);
        put(29, 0.69, 0.81, 0.78, null);
        put(30, 0.52, 0.76, 0.64, null);
        put(31, 0.54, 0.66, 0.63, null);
        put(32, 0.51, 0.62, 0.55, null);
        put(33, 0.68, 0.74, 0.78, null);
        put(34, 0.48, 0.56, 0.6, null);
        put(35, 0.46, 0.54, 0.57, null);
        put(36, 0.41, 0.51, 0.59, null);
        put(37, 0.43, 0.48, 0.52, null);
        put(38, 0.49, 0.6, 0.82, null);
        put(39, 0.5, 0.5, 0.61, null);
        put(40, 0.67, 0.65, 0.75, null);
        put(41, 0.83, 0.81, 0.53, null);
        put(42, 0.87, 0.73, 0.53, null);
        put(43, 0.74, 0.62, 0.51, null);
        put(44, 0.78, 0.6, 0.49, null);
        put(45, 0.75, 0.51, 0.47, null);
        put(46, 0.81, 0.37, 0.38, null);
        put(47, 0.67, 0.56, 0.58, null);
        put(48, 0.65, 0.47, 0.48, null);
        put(49, 0.58, 0.41, 0.44, null);
        put(50, 0.83, 0.35, 0.33, null);
        // The pretty palette: hues from 280 down to 0 at full saturation.
        for (int i = 0; i < 49; i++) {
            final double hue = 280 - (i + 1) * (280.0 / 50);
            final double[] rgb = hlsToRgb(hue, 0.5, 1);
            put(i + 51, rgb[0], rgb[1], rgb[2], null);
        }
        circle(K_MAGENTA, "kMagenta", new int[]{255, 204, 255, 255, 153, 255, 204, 153, 204, 255, 102, 255, 204, 102, 204,
            153, 102, 153, 255, 51, 255, 204, 51, 204, 153, 51, 153, 102, 51, 102, 255, 0, 255, 204, 0, 204, 153, 0, 153,
            102, 0, 102, 51, 0, 51});
        circle(K_RED, "kRed", new int[]{255, 204, 204, 255, 153, 153, 204, 153, 153, 255, 102, 102, 204, 102, 102, 153,
            102, 102, 255, 51, 51, 204, 51, 51, 153, 51, 51, 102, 51, 51, 255, 0, 0, 204, 0, 0, 153, 0, 0, 102, 0, 0, 51, 0, 0});
        circle(K_YELLOW, "kYellow", new int[]{255, 255, 204, 255, 255, 153, 204, 204, 153, 255, 255, 102, 204, 204, 102,
            153, 153, 102, 255, 255, 51, 204, 204, 51, 153, 153, 51, 102, 102, 51, 255, 255, 0, 204, 204, 0, 153, 153, 0,
            102, 102, 0, 51, 51, 0});
        circle(K_GREEN, "kGreen", new int[]{204, 255, 204, 153, 255, 153, 153, 204, 153, 102, 255, 102, 102, 204, 102,
            102, 153, 102, 51, 255, 51, 51, 204, 51, 51, 153, 51, 51, 102, 51, 0, 255, 0, 0, 204, 0, 0, 153, 0, 0, 102, 0,
            0, 51, 0});
        circle(K_CYAN, "kCyan", new int[]{204, 255, 255, 153, 255, 255, 153, 204, 204, 102, 255, 255, 102, 204, 204, 102,
            153, 153, 51, 255, 255, 51, 204, 204, 51, 153, 153, 51, 102, 102, 0, 255, 255, 0, 204, 204, 0, 153, 153, 0,
            102, 102, 0, 51, 51});
        circle(K_BLUE, "kBlue", new int[]{204, 204, 255, 153, 153, 255, 153, 153, 204, 102, 102, 255, 102, 102, 204, 102,
            102, 153, 51, 51, 255, 51, 51, 204, 51, 51, 153, 51, 51, 102, 0, 0, 255, 0, 0, 204, 0, 0, 153, 0, 0, 102, 0, 0, 51});
        rectangle(K_PINK, "kPink", new int[]{255, 51, 153, 204, 0, 102, 102, 0, 51, 153, 0, 51, 204, 51, 102, 255, 102,
            153, 255, 0, 102, 255, 51, 102, 204, 0, 51, 255, 0, 51, 255, 153, 204, 204, 102, 153, 153, 51, 102, 153, 0,
            102, 204, 51, 153, 255, 102, 204, 255, 0, 153, 204, 0, 153, 255, 51, 204, 255, 0, 153});
        rectangle(K_ORANGE, "kOrange", new int[]{255, 204, 153, 204, 153, 102, 153, 102, 51, 153, 102, 0, 204, 153, 51,
            255, 204, 102, 255, 153, 0, 255, 204, 51, 204, 153, 0, 255, 204, 0, 255, 153, 51, 204, 102, 0, 102, 51, 0,
            153, 51, 0, 204, 102, 51, 255, 153, 102, 255, 102, 0, 255, 102, 51, 204, 51, 0, 255, 51, 0});
        rectangle(K_SPRING, "kSpring", new int[]{153, 255, 51, 102, 204, 0, 51, 102, 0, 51, 153, 0, 102, 204, 51, 153,
            255, 102, 102, 255, 0, 102, 255, 51, 51, 204, 0, 51, 255, 0, 204, 255, 153, 153, 204, 102, 102, 153, 51, 102,
            153, 0, 153, 204, 51, 204, 255, 102, 153, 255, 0, 204, 255, 51, 153, 204, 0, 204, 255, 0});
        rectangle(K_TEAL, "kTeal", new int[]{153, 255, 204, 102, 204, 153, 51, 153, 102, 0, 153, 102, 51, 204, 153, 102,
            255, 204, 0, 255, 102, 51, 255, 204, 0, 204, 153, 0, 255, 204, 51, 255, 153, 0, 204, 102, 0, 102, 51, 0, 153,
            51, 51, 204, 102, 102, 255, 153, 0, 255, 153, 51, 255, 102, 0, 204, 51, 0, 255, 51});
        rectangle(K_AZURE, "kAzure", new int[]{153, 204, 255, 102, 153, 204, 51, 102, 153, 0, 51, 153, 51, 102, 204, 102,
            153, 255, 0, 102, 255, 51, 102, 255, 0, 51, 204, 0, 51, 255, 51, 153, 255, 0, 102, 204, 0, 51, 102, 0, 102,
            153, 51, 153, 204, 102, 204, 255, 0, 153, 255, 51, 204, 255, 0, 153, 204, 0, 204, 255});
        rectangle(K_VIOLET, "kViolet", new int[]{204, 153, 255, 153, 102, 204, 102, 51, 153, 102, 0, 153, 153, 51, 204,
            204, 102, 255, 153, 0, 255, 204, 51, 255, 153, 0, 204, 204, 0, 255, 153, 51, 255, 102, 0, 204, 51, 0, 102,
            51, 0, 153, 102, 51, 204, 153, 102, 255, 102, 0, 255, 102, 51, 255, 51, 0, 204, 51, 0, 255});
        put(K_GRAY, 204 / 255.0, 204 / 255.0, 204 / 255.0, "kGray");
        put(K_GRAY + 1, 153 / 255.0, 153 / 255.0, 153 / 255.0, "kGray+1");
        put(K_GRAY + 2, 102 / 255.0, 102 / 255.0, 102 / 255.0, "kGray+2");
        put(K_GRAY + 3, 51 / 255.0, 51 / 255.0, 51 / 255.0, "kGray+3");
    }

    private RootColors() {
    }

    private static void put(int index, double r, double g, double b, String name) {
        TABLE.put(index, new Color((float) r, (float) g, (float) b));
        if (name != null) NAMES.put(index, name);
    }

    private static void circle(int offset, String name, int[] rgb) {
        for (int n = 0; n < 15; n++) {
            final int c = offset + n - 10;
            if (TABLE.containsKey(c)) continue;
            TABLE.put(c, new Color(rgb[3 * n], rgb[3 * n + 1], rgb[3 * n + 2]));
            NAMES.put(c, n > 10 ? name + "+" + (n - 10) : n < 10 ? name + "-" + (10 - n) : name);
        }
    }

    private static void rectangle(int offset, String name, int[] rgb) {
        for (int n = 0; n < 20; n++) {
            final int c = offset + n - 9;
            if (TABLE.containsKey(c)) continue;
            TABLE.put(c, new Color(rgb[3 * n], rgb[3 * n + 1], rgb[3 * n + 2]));
            NAMES.put(c, n > 9 ? name + "+" + (n - 9) : n < 9 ? name + "-" + (9 - n) : name);
        }
    }

    /** TColor::HLStoRGB: hue in degrees, lightness and saturation 0..1. */
    static double[] hlsToRgb(double hue, double light, double satur) {
        final double rh;
        final double rl = light;
        final double rs = satur;
        final double rm2 = rl <= 0.5 ? rl * (1 + rs) : rl + rs - rl * rs;
        final double rm1 = 2 * rl - rm2;
        if (rs == 0) return new double[]{rl, rl, rl};
        rh = hue;
        return new double[]{hlsValue(rm1, rm2, rh + 120), hlsValue(rm1, rm2, rh), hlsValue(rm1, rm2, rh - 120)};
    }

    private static double hlsValue(double n1, double n2, double hue) {
        if (hue > 360) hue -= 360;
        if (hue < 0) hue += 360;
        if (hue < 60) return n1 + (n2 - n1) * hue / 60;
        if (hue < 180) return n2;
        if (hue < 240) return n1 + (n2 - n1) * (240 - hue) / 60;
        return n1;
    }

    /** The colour of an index; black for one ROOT does not define. */
    public static Color color(int index) {
        final Color c = TABLE.get(index);
        return c == null ? Color.BLACK : c;
    }

    public static boolean defined(int index) {
        return TABLE.containsKey(index);
    }

    /** The index whose colour is nearest, which is what TColor::GetColor answers. */
    public static int index(Color c) {
        if (c == null) return 0;
        int best = 1;
        double bd = Double.MAX_VALUE;
        for (Map.Entry<Integer, Color> e : TABLE.entrySet()) {
            final Color k = e.getValue();
            final double d = sq(k.getRed() - c.getRed()) + sq(k.getGreen() - c.getGreen()) + sq(k.getBlue() - c.getBlue());
            if (d < bd) {
                bd = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** "kRed+2", or the number when ROOT gives it no name. */
    public static String name(int index) {
        final String n = NAMES.get(index);
        return n == null ? Integer.toString(index) : n;
    }

    /** The indices shown in a colour chooser, in ROOT's order: the basic ones, then the wheel. */
    public static Map<Integer, Color> all() {
        return java.util.Collections.unmodifiableMap(TABLE);
    }

    /** A colour index written as ROOT accepts it: a number, a name of the wheel (kRed+2), or #rrggbb. */
    public static Color parse(String text) {
        if (text == null) return null;
        final String t = text.strip();
        if (t.startsWith("#") && (t.length() == 7 || t.length() == 9)) {
            try {
                final int rgb = Integer.parseInt(t.substring(1, 7), 16);
                return new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (Map.Entry<Integer, String> e : NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(t)) return TABLE.get(e.getKey());
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(k[A-Za-z]+)\\s*([+-]\\s*\\d+)?").matcher(t);
        if (m.matches()) {
            final Integer base = baseOf(m.group(1));
            if (base != null) {
                final int k = base + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2).replace(" ", "")));
                return TABLE.containsKey(k) ? TABLE.get(k) : null;
            }
        }
        try {
            final int k = Integer.parseInt(t);
            return TABLE.containsKey(k) ? TABLE.get(k) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer baseOf(String name) {
        return switch (name) {
            case "kWhite" -> K_WHITE;
            case "kBlack" -> K_BLACK;
            case "kGray", "kGrey" -> K_GRAY;
            case "kRed" -> K_RED;
            case "kGreen" -> K_GREEN;
            case "kBlue" -> K_BLUE;
            case "kYellow" -> K_YELLOW;
            case "kMagenta" -> K_MAGENTA;
            case "kCyan" -> K_CYAN;
            case "kOrange" -> K_ORANGE;
            case "kSpring" -> K_SPRING;
            case "kTeal" -> K_TEAL;
            case "kAzure" -> K_AZURE;
            case "kViolet" -> K_VIOLET;
            case "kPink" -> K_PINK;
            default -> null;
        };
    }
}
