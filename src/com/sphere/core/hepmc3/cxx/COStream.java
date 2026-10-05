package com.sphere.core.hepmc3.cxx;

import java.math.BigDecimal;

/**
 * std::ostream with its formatting state, as libstdc++ writes: a double is
 * printf'd with %.{precision}g (or e, f under scientific, fixed), '+' under
 * showpos; the width applies to the next item only and is then reset; the
 * fill goes on the left unless left or internal is asked.
 *
 * <p>HepMC3 prints its listings and serialises its attributes through
 * ostreams set this way (Print::listing at precision 2 with scientific and
 * showpos, GenHeavyIon at setprecision(8)...), so the text must come out of
 * the same state machine to be the same text.
 */
public class COStream {

    public enum FloatField { NONE, FIXED, SCIENTIFIC }

    public enum Adjust { RIGHT, LEFT, INTERNAL }

    /** A copy of the formatting state, for ios::flags() save and restore. */
    public record Flags(FloatField floatField, boolean showpos, boolean showpoint, boolean uppercase,
                        Adjust adjust, boolean boolalpha) {
    }

    private final StringBuilder buffer;
    private int precision = 6;
    private int width;
    private char fill = ' ';
    private FloatField floatField = FloatField.NONE;
    private boolean showpos;
    private boolean showpoint;
    private boolean uppercase;
    private boolean boolalpha;
    private Adjust adjust = Adjust.RIGHT;

    public COStream() {
        this(new StringBuilder());
    }

    public COStream(StringBuilder into) {
        this.buffer = into;
    }

    /** The text written so far: ostringstream::str(). */
    public String str() {
        return buffer.toString();
    }

    public StringBuilder buffer() {
        return buffer;
    }

    /** Called with each piece of text; a subclass may send it elsewhere. */
    protected void write(CharSequence text) {
        buffer.append(text);
    }

    /* ---- state ------------------------------------------------------ */

    public int precision() {
        return precision;
    }

    public COStream precision(int p) {
        this.precision = p;
        return this;
    }

    /** setprecision(p), as a manipulator in a chain. */
    public COStream setprecision(int p) {
        return precision(p);
    }

    public int width() {
        return width;
    }

    public COStream width(int w) {
        this.width = w;
        return this;
    }

    /** std::setw(w). */
    public COStream setw(int w) {
        return width(w);
    }

    public COStream fill(char c) {
        this.fill = c;
        return this;
    }

    public COStream scientific() {
        floatField = FloatField.SCIENTIFIC;
        return this;
    }

    public COStream fixed() {
        floatField = FloatField.FIXED;
        return this;
    }

    /** unsetf(std::ios::floatfield). */
    public COStream defaultfloat() {
        floatField = FloatField.NONE;
        return this;
    }

    public COStream showpos(boolean on) {
        showpos = on;
        return this;
    }

    public COStream showpoint(boolean on) {
        showpoint = on;
        return this;
    }

    public COStream uppercase(boolean on) {
        uppercase = on;
        return this;
    }

    public COStream boolalpha(boolean on) {
        boolalpha = on;
        return this;
    }

    public COStream left() {
        adjust = Adjust.LEFT;
        return this;
    }

    public COStream right() {
        adjust = Adjust.RIGHT;
        return this;
    }

    public COStream internal() {
        adjust = Adjust.INTERNAL;
        return this;
    }

    public Flags flags() {
        return new Flags(floatField, showpos, showpoint, uppercase, adjust, boolalpha);
    }

    public COStream flags(Flags f) {
        floatField = f.floatField();
        showpos = f.showpos();
        showpoint = f.showpoint();
        uppercase = f.uppercase();
        adjust = f.adjust();
        boolalpha = f.boolalpha();
        return this;
    }

    /* ---- insertion -------------------------------------------------- */

    public COStream put(String s) {
        padded(s == null ? "" : s, 0);
        return this;
    }

    public COStream put(char c) {
        padded(String.valueOf(c), 0);
        return this;
    }

    public COStream put(int v) {
        return put((long) v);
    }

    public COStream put(long v) {
        final String digits = v == Long.MIN_VALUE ? "9223372036854775808" : Long.toString(Math.abs(v));
        final String sign = v < 0 ? "-" : showpos ? "+" : "";
        padded(sign + digits, sign.length());
        return this;
    }

    /** An unsigned 64-bit value held in a long. */
    public COStream putUnsigned(long v) {
        padded(Long.toUnsignedString(v), 0);
        return this;
    }

    public COStream put(boolean b) {
        if (boolalpha) return put(b ? "true" : "false");
        return put(b ? 1 : 0);
    }

    public COStream put(double v) {
        padded(number(v), signLength(v));
        return this;
    }

    /** A float is printed as the double it widens to, at the stream's precision. */
    public COStream put(float v) {
        return put((double) v);
    }

    /** A long double, held exactly. */
    public COStream put(LongDouble v) {
        final String s = v.format(floatField == FloatField.SCIENTIFIC ? 'e' : floatField == FloatField.FIXED ? 'f' : 'g',
            precision, showpoint, uppercase);
        final String signed = !s.startsWith("-") && showpos && !v.isNaN() ? "+" + s : s;
        padded(signed, signed.startsWith("-") || signed.startsWith("+") ? 1 : 0);
        return this;
    }

    /** std::endl: a newline (the flush is the sink's affair). */
    public COStream endl() {
        write("\n");
        return this;
    }

    private int signLength(double v) {
        return (v < 0 || (v == 0 && 1 / v < 0) || showpos) && !Double.isNaN(v) ? 1 : 0;
    }

    private String number(double v) {
        final StringBuilder fmt = new StringBuilder("%");
        if (showpos) fmt.append('+');
        if (showpoint) fmt.append('#');
        final char conv;
        if (floatField == FloatField.SCIENTIFIC) conv = uppercase ? 'E' : 'e';
        else if (floatField == FloatField.FIXED) conv = 'f';
        else conv = uppercase ? 'G' : 'g';
        fmt.append('.').append(precision).append(conv);
        return CFormat.sprintf(fmt.toString(), v);
    }

    private void padded(String s, int signLen) {
        final int w = width;
        width = 0;
        if (s.length() >= w) {
            write(s);
            return;
        }
        final String pad = String.valueOf(fill).repeat(w - s.length());
        switch (adjust) {
            case LEFT -> write(s + pad);
            case INTERNAL -> write(s.substring(0, signLen) + pad + s.substring(signLen));
            default -> write(pad + s);
        }
    }

    /* ---- helpers for the common cases --------------------------------- */

    /** ostream << x at a given precision, default float format: %.{p}g. */
    public static String g(double x, int precision) {
        return CFormat.sprintf("%." + precision + "g", x);
    }

    /** The exact decimal value of a double, for tests. */
    public static String exact(double x) {
        return new BigDecimal(x).toPlainString();
    }
}
