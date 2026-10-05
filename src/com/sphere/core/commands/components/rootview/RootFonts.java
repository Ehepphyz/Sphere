package com.sphere.components.rootview;

import java.awt.Font;

/**
 * ROOT's text fonts: a code 10 * family + precision. The families are those
 * of TTF's table (1 Times italic ... 15 Symbol italic); a precision of 3
 * gives the size in pixels, any other a fraction of the pad's smaller side.
 */
public final class RootFonts {

    /** The family names ROOT's font chooser shows, in the order of their numbers 1 to 15. */
    public static final String[] FAMILIES = {
        "Times italic", "Times bold", "Times bold italic", "Helvetica", "Helvetica italic", "Helvetica bold",
        "Helvetica bold italic", "Courier", "Courier italic", "Courier bold", "Courier bold italic", "Symbol",
        "Times", "Wingdings", "Symbol italic"};

    private RootFonts() {
    }

    /** The Java font of a ROOT font code at a pixel size. */
    public static Font font(int code, float pixels) {
        final int family = Math.max(1, Math.min(15, code / 10 == 0 ? 4 : code / 10));
        final String name;
        int style = Font.PLAIN;
        switch (family) {
            case 1 -> {
                name = Font.SERIF;
                style = Font.ITALIC;
            }
            case 2 -> {
                name = Font.SERIF;
                style = Font.BOLD;
            }
            case 3 -> {
                name = Font.SERIF;
                style = Font.BOLD | Font.ITALIC;
            }
            case 5 -> {
                name = Font.SANS_SERIF;
                style = Font.ITALIC;
            }
            case 6 -> {
                name = Font.SANS_SERIF;
                style = Font.BOLD;
            }
            case 7 -> {
                name = Font.SANS_SERIF;
                style = Font.BOLD | Font.ITALIC;
            }
            case 8 -> name = Font.MONOSPACED;
            case 9 -> {
                name = Font.MONOSPACED;
                style = Font.ITALIC;
            }
            case 10 -> {
                name = Font.MONOSPACED;
                style = Font.BOLD;
            }
            case 11 -> {
                name = Font.MONOSPACED;
                style = Font.BOLD | Font.ITALIC;
            }
            case 12, 15 -> {
                name = Font.SERIF;
                style = family == 15 ? Font.ITALIC : Font.PLAIN;
            }
            case 13 -> name = Font.SERIF;
            default -> name = Font.SANS_SERIF;
        }
        return new Font(name, style, 12).deriveFont(Math.max(6f, pixels));
    }

    /**
     * Pixels of the screen per pixel of ROOT's canvas. A precision 3 size
     * counts the canvas's pixels, which the view shows larger or smaller than
     * ROOT drew them; the canvas view sets it before each paint.
     */
    static double scale = 1;

    /** The pixel size of a ROOT text size in a pad: pixels at precision 3, else a fraction of the pad's smaller side. */
    public static float pixels(int code, double size, int padWidth, int padHeight) {
        if (code % 10 == 3) return (float) (size * scale);
        return (float) (size * Math.min(padWidth, padHeight));
    }

    public static String describe(int code) {
        final int family = code / 10;
        final String f = family >= 1 && family <= 15 ? FAMILIES[family - 1] : "font " + family;
        return code + " — " + f + (code % 10 == 3 ? ", size in pixels" : "");
    }
}
