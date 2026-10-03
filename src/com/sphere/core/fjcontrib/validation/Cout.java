package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.Fmt;

/**
 * A C++ output stream, as the fjcontrib examples use it: {@code <<} of
 * strings, integers and doubles with the manipulators setprecision, setw,
 * fixed, scientific, left and right, and printf. Numbers are written from
 * their exact binary value, rounded as glibc rounds, so that the text is
 * the C++'s character for character.
 */
public final class Cout {

    private enum FloatField { DEFAULT, FIXED, SCIENTIFIC }

    private final StringBuilder out = new StringBuilder();
    private int precision = 6;
    private FloatField floatField = FloatField.DEFAULT;
    private int width;
    private boolean left;
    private char fill = ' ';
    private boolean showpos;

    /** Everything written so far. */
    public String text() {
        return out.toString();
    }

    @Override
    public String toString() {
        return out.toString();
    }

    /* ---- manipulators ---- */

    public Cout setprecision(int p) {
        precision = p;
        return this;
    }

    public int precision() {
        return precision;
    }

    public Cout fixed() {
        floatField = FloatField.FIXED;
        return this;
    }

    public Cout scientific() {
        floatField = FloatField.SCIENTIFIC;
        return this;
    }

    public Cout defaultfloat() {
        floatField = FloatField.DEFAULT;
        return this;
    }

    public Cout setw(int w) {
        width = w;
        return this;
    }

    public Cout left() {
        left = true;
        return this;
    }

    public Cout right() {
        left = false;
        return this;
    }

    public Cout setfill(char c) {
        fill = c;
        return this;
    }

    public Cout showpos(boolean on) {
        showpos = on;
        return this;
    }

    public Cout endl() {
        out.append('\n');
        return this;
    }

    /** The state as a C++ program saves it with flags() and precision(). */
    public Object[] saveState() {
        return new Object[]{precision, floatField, left, fill, showpos};
    }

    /** cout.flags(): the format flags, without the precision (which flags() does not hold). */
    public Object[] flags() {
        return new Object[]{floatField, left, showpos};
    }

    /** cout.flags(f). */
    public Cout flags(Object[] f) {
        floatField = (FloatField) f[0];
        left = (Boolean) f[1];
        showpos = (Boolean) f[2];
        return this;
    }

    public void restoreState(Object[] s) {
        precision = (Integer) s[0];
        floatField = (FloatField) s[1];
        left = (Boolean) s[2];
        fill = (Character) s[3];
        showpos = (Boolean) s[4];
    }

    /* ---- insertion ---- */

    private Cout pad(String s) {
        if (width > s.length()) {
            final String padding = String.valueOf(fill).repeat(width - s.length());
            if (left) {
                out.append(s).append(padding);
            } else {
                out.append(padding).append(s);
            }
        } else {
            out.append(s);
        }
        width = 0;
        return this;
    }

    public Cout p(String s) {
        return pad(s);
    }

    public Cout p(char c) {
        return pad(String.valueOf(c));
    }

    public Cout p(int v) {
        return pad((showpos && v >= 0 ? "+" : "") + v);
    }

    public Cout p(long v) {
        return pad((showpos && v >= 0 ? "+" : "") + v);
    }

    public Cout p(boolean b) {
        return pad(b ? "1" : "0");
    }

    public Cout p(double x) {
        String s = switch (floatField) {
            case FIXED -> Fmt.f(x, 0, precision);
            case SCIENTIFIC -> Fmt.e(x, 0, precision);
            default -> Fmt.g(x, precision);
        };
        if (showpos && !s.startsWith("-") && !Double.isNaN(x)) s = "+" + s;
        return pad(s);
    }

    public Cout p(Object o) {
        if (o instanceof Double d) return p(d.doubleValue());
        if (o instanceof Float f) return p(f.doubleValue());
        if (o instanceof Integer i) return p(i.intValue());
        if (o instanceof Long l) return p(l.longValue());
        if (o instanceof Boolean b) return p(b.booleanValue());
        return pad(String.valueOf(o));
    }

    /** Several items in a row. */
    public Cout p(Object... items) {
        for (Object o : items) p(o);
        return this;
    }

    /** printf, with C's conversions. */
    public Cout printf(String format, Object... args) {
        out.append(Printf.format(format, args));
        return this;
    }
}
