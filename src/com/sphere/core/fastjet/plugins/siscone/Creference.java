package com.sphere.core.fastjet.plugins.siscone;

/**
 * siscone::Creference: a random 96-bit label; the label of a set of
 * particles is the XOR of theirs, so that a cone's contents are known by one
 * comparison.
 */
public final class Creference {

    /** The three unsigned 32-bit words. */
    int r0;
    int r1;
    int r2;

    public Creference() {
    }

    Creference(Creference o) {
        r0 = o.r0;
        r1 = o.r1;
        r2 = o.r2;
    }

    void set(Creference o) {
        r0 = o.r0;
        r1 = o.r1;
        r2 = o.r2;
    }

    void randomize() {
        do {
            final int x1 = (int) Ranlux.get();
            final int x2 = (int) Ranlux.get();
            final int x3 = (int) Ranlux.get();
            final int x4 = (int) Ranlux.get();
            r0 = x1 + ((x4 & 0x00ff0000) << 8);
            r1 = x2 + ((x4 & 0x0000ff00) << 16);
            r2 = x3 + ((x4 & 0x000000ff) << 24);
        } while (isEmpty());
    }

    boolean isEmpty() {
        return r0 == 0 && r1 == 0 && r2 == 0;
    }

    boolean notEmpty() {
        return r0 != 0 || r1 != 0 || r2 != 0;
    }

    /** += and -= alike: XOR. */
    void xor(Creference o) {
        r0 ^= o.r0;
        r1 ^= o.r1;
        r2 ^= o.r2;
    }

    boolean sameAs(Creference o) {
        return r0 == o.r0 && r1 == o.r1 && r2 == o.r2;
    }

    /** The unsigned lexicographic order of the C++ operator<. */
    boolean lessThan(Creference o) {
        final int c0 = Integer.compareUnsigned(r0, o.r0);
        if (c0 != 0) return c0 < 0;
        final int c1 = Integer.compareUnsigned(r1, o.r1);
        if (c1 != 0) return c1 < 0;
        return Integer.compareUnsigned(r2, o.r2) < 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Creference r && sameAs(r);
    }

    @Override
    public int hashCode() {
        return r0 * 31 * 31 + r1 * 31 + r2;
    }
}
