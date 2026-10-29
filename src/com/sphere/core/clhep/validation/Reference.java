package com.sphere.core.clhep.validation;

import java.util.Locale;

/**
 * A reference program: the Java twin of a C++ program built against CLHEP, which computes the same
 * things on the same inputs and writes every double as its 64 bits in hexadecimal. The two outputs
 * agree line for line exactly when the port gives CLHEP's bits.
 *
 * <p>The inputs come from xorshift64, the same generator in both programs; each draw is a statement
 * of its own in the C++, whose argument evaluation order is not specified.
 */
public abstract class Reference {

    private long state = 88172645463325252L;
    private final StringBuilder out = new StringBuilder(1 << 20);

    /** The name of the reference, and of its C++ source (name_ref.cc). */
    public abstract String name();

    /** The body of main(). */
    protected abstract void run();

    /** Runs the program and returns what it printed. */
    public final String output() {
        out.setLength(0);
        state = 88172645463325252L;
        run();
        return out.toString();
    }

    /** A double in [0, 1) from 53 bits of xorshift64. */
    protected final double rnd() {
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        return (double) (state >>> 11) * (1.0 / 9007199254740992.0);
    }

    /** A double in (-s, s). */
    protected final double sym(double s) {
        return (2 * rnd() - 1) * s;
    }

    /** A double in [o, o + s). */
    protected final double pos(double s, double o) {
        return rnd() * s + o;
    }

    /** A double, as its 64 bits. */
    protected final void d(String n, double x) {
        out.append(n).append(' ').append(String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(x))).append('\n');
    }

    protected final void i(String n, long v) {
        out.append(n).append(' ').append(v).append('\n');
    }

    /** A boolean, as C++ prints it with %ld: 1 or 0. */
    protected final void i(String n, boolean v) {
        i(n, v ? 1 : 0);
    }

    /** A text, between brackets. */
    protected final void text(String n, String t) {
        out.append(n).append(" [").append(t).append("]\n");
    }

    /** A line as it is. */
    protected final void line(String s) {
        out.append(s).append('\n');
    }
}
