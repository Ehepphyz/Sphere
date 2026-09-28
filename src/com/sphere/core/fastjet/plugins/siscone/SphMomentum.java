package com.sphere.core.fastjet.plugins.siscone;

/**
 * siscone_spherical::CSphmomentum: a {@link Sph3} with an energy and
 * indices. Its copy constructor copies everything ({@link #copy()}), its
 * assignment all but the indices ({@link #assign}); its += and -= add the
 * four-vector and XOR the reference, leaving the cached norm and angles.
 */
public final class SphMomentum extends Sph3 {

    public double E;
    public int parentIndex;
    public int index;

    /** The default constructor. */
    public SphMomentum() {
        index = -1;
    }

    public SphMomentum(double px, double py, double pz, double e) {
        super(px, py, pz);
        this.E = e;
        buildThetaPhi();
    }

    /** CSphmomentum(CSph3vector&, E): a fresh vector (norm built, no reference). */
    SphMomentum(Sph3 v, double e) {
        super(v.px, v.py, v.pz);
        this.E = e;
    }

    public SphMomentum copy() {
        final SphMomentum c = new SphMomentum();
        c.assign(this);
        c.parentIndex = parentIndex;
        c.index = index;
        return c;
    }

    void assign(SphMomentum v) {
        assign3(v);
        E = v.E;
    }

    SphMomentum plus(SphMomentum v) {
        final SphMomentum t = copy();
        t.add(v);
        return t;
    }

    void add(SphMomentum v) {
        px += v.px;
        py += v.py;
        pz += v.pz;
        E += v.E;
        ref.xor(v.ref);
    }

    void subtract(SphMomentum v) {
        px -= v.px;
        py -= v.py;
        pz -= v.pz;
        E -= v.E;
        ref.xor(v.ref);
    }
}
