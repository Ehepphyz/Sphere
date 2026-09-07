package com.sphere.components.imaging.svg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Path2D;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Paint and stroke settings for one element, inherited from its parent and then
 * overridden by the element's own presentation attributes and `style`.
 */
public final class SvgStyle implements Cloneable {

    public static final String NONE = "none";

    public String fill = "#000000";
    public String stroke = NONE;
    public double strokeWidth = 1.0;
    public double opacity = 1.0;
    public double fillOpacity = 1.0;
    public double strokeOpacity = 1.0;
    public int windingRule = Path2D.WIND_NON_ZERO;
    public int lineCap = BasicStroke.CAP_BUTT;
    public int lineJoin = BasicStroke.JOIN_MITER;
    public double miterLimit = 4.0;
    public float[] dashArray;
    public double dashOffset;

    public String fontFamily = "SansSerif";
    public double fontSize = 12.0;
    public boolean bold;
    public boolean italic;
    public String textAnchor = "start";

    @Override
    public SvgStyle clone() {
        try {
            SvgStyle copy = (SvgStyle) super.clone();
            copy.dashArray = dashArray == null ? null : dashArray.clone();
            return copy;
        } catch (CloneNotSupportedException e) {
            return new SvgStyle();
        }
    }

    public boolean hasFill() {
        return fill != null && !NONE.equalsIgnoreCase(fill);
    }

    public boolean hasStroke() {
        return stroke != null && !NONE.equalsIgnoreCase(stroke) && strokeWidth > 0;
    }

    public BasicStroke toStroke(double scale) {
        final float width = (float) Math.max(strokeWidth * scale, 0.01);
        if (dashArray == null || dashArray.length == 0) {
            return new BasicStroke(width, lineCap, lineJoin, (float) Math.max(miterLimit, 1.0));
        }
        float[] scaled = new float[dashArray.length];
        boolean positive = false;
        for (int i = 0; i < dashArray.length; i++) {
            scaled[i] = (float) Math.max(dashArray[i] * scale, 0.01);
            if (scaled[i] > 0.01f) {
                positive = true;
            }
        }
        if (!positive) {
            return new BasicStroke(width, lineCap, lineJoin, (float) Math.max(miterLimit, 1.0));
        }
        return new BasicStroke(width, lineCap, lineJoin,
                               (float) Math.max(miterLimit, 1.0),
                               scaled, (float) (dashOffset * scale));
    }

    // ---- color -------------------------------------------------------------

    private static final Map<String, Color> NAMED = new HashMap<>();

    static {
        NAMED.put("black", Color.BLACK);
        NAMED.put("white", Color.WHITE);
        NAMED.put("red", new Color(0xFF0000));
        NAMED.put("green", new Color(0x008000));
        NAMED.put("lime", new Color(0x00FF00));
        NAMED.put("blue", new Color(0x0000FF));
        NAMED.put("yellow", new Color(0xFFFF00));
        NAMED.put("cyan", new Color(0x00FFFF));
        NAMED.put("aqua", new Color(0x00FFFF));
        NAMED.put("magenta", new Color(0xFF00FF));
        NAMED.put("fuchsia", new Color(0xFF00FF));
        NAMED.put("gray", new Color(0x808080));
        NAMED.put("grey", new Color(0x808080));
        NAMED.put("silver", new Color(0xC0C0C0));
        NAMED.put("maroon", new Color(0x800000));
        NAMED.put("olive", new Color(0x808000));
        NAMED.put("navy", new Color(0x000080));
        NAMED.put("purple", new Color(0x800080));
        NAMED.put("teal", new Color(0x008080));
        NAMED.put("orange", new Color(0xFFA500));
        NAMED.put("pink", new Color(0xFFC0CB));
        NAMED.put("brown", new Color(0xA52A2A));
        NAMED.put("gold", new Color(0xFFD700));
        NAMED.put("indigo", new Color(0x4B0082));
        NAMED.put("violet", new Color(0xEE82EE));
        NAMED.put("darkgray", new Color(0xA9A9A9));
        NAMED.put("darkgrey", new Color(0xA9A9A9));
        NAMED.put("lightgray", new Color(0xD3D3D3));
        NAMED.put("lightgrey", new Color(0xD3D3D3));
        NAMED.put("transparent", new Color(0, 0, 0, 0));
    }

    /** Resolves a paint value to a color, or null when it is none or a reference. */
    public static Color toColor(String value, double alpha) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty() || NONE.equalsIgnoreCase(text) || text.startsWith("url(")) {
            return null;
        }
        Color base = parseColor(text);
        if (base == null) {
            return null;
        }
        final int a = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha)) * 255.0);
        if (a >= 255 && base.getAlpha() == 255) {
            return base;
        }
        final int combined = (int) Math.round(a * (base.getAlpha() / 255.0));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), combined);
    }

    private static Color parseColor(String text) {
        final String lower = text.toLowerCase(Locale.ROOT);

        if (lower.startsWith("#")) {
            final String hex = lower.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3), 16);
                    return new Color(r * 17, g * 17, b * 17);
                }
                if (hex.length() == 4) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3), 16);
                    int a = Integer.parseInt(hex.substring(3, 4), 16);
                    return new Color(r * 17, g * 17, b * 17, a * 17);
                }
                if (hex.length() == 6) {
                    return new Color(Integer.parseInt(hex, 16));
                }
                if (hex.length() == 8) {
                    long v = Long.parseLong(hex, 16);
                    return new Color((int) ((v >> 24) & 0xFF), (int) ((v >> 16) & 0xFF),
                                     (int) ((v >> 8) & 0xFF), (int) (v & 0xFF));
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            return null;
        }

        if (lower.startsWith("rgb")) {
            final int open = lower.indexOf('(');
            final int close = lower.indexOf(')');
            if (open < 0 || close < open) {
                return null;
            }
            String[] parts = lower.substring(open + 1, close).split("[,/\\s]+");
            if (parts.length < 3) {
                return null;
            }
            try {
                int r = channel(parts[0]);
                int g = channel(parts[1]);
                int b = channel(parts[2]);
                int a = parts.length > 3 ? alphaChannel(parts[3]) : 255;
                return new Color(r, g, b, a);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return NAMED.get(lower);
    }

    private static int channel(String text) {
        String t = text.trim();
        double value = t.endsWith("%")
            ? Double.parseDouble(t.substring(0, t.length() - 1)) * 2.55
            : Double.parseDouble(t);
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int alphaChannel(String text) {
        String t = text.trim();
        double value = t.endsWith("%")
            ? Double.parseDouble(t.substring(0, t.length() - 1)) / 100.0
            : Double.parseDouble(t);
        return (int) Math.max(0, Math.min(255, Math.round(value * 255.0)));
    }

    /** Parses a length, honoring the units SVG allows on a size attribute. */
    public static double length(String text, double fallback) {
        if (text == null) {
            return fallback;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return fallback;
        }
        double factor = 1.0;
        if (t.endsWith("px")) {
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("pt")) {
            t = t.substring(0, t.length() - 2);
            factor = 96.0 / 72.0;
        } else if (t.endsWith("pc")) {
            t = t.substring(0, t.length() - 2);
            factor = 16.0;
        } else if (t.endsWith("mm")) {
            t = t.substring(0, t.length() - 2);
            factor = 96.0 / 25.4;
        } else if (t.endsWith("cm")) {
            t = t.substring(0, t.length() - 2);
            factor = 96.0 / 2.54;
        } else if (t.endsWith("in")) {
            t = t.substring(0, t.length() - 2);
            factor = 96.0;
        } else if (t.endsWith("%")) {
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Double.parseDouble(t.trim()) * factor;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
