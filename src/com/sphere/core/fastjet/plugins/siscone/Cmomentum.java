package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;

/**
 * siscone::Cmomentum: a four-vector with its (eta, phi), its reference and
 * its indices.
 *
 * The C++ class copies differently depending on how: its copy constructor
 * copies everything ({@link #copy()}), its assignment operator everything
 * but the two indices ({@link #assign}); the port calls the one the C++ code
 * used.
 */
public final class Cmomentum {

    public double px;
    public double py;
    public double pz;
    public double E;
    public double eta;
    public double phi;
    public int parentIndex;
    public int index;
    final Creference ref;

    /** The default constructor. */
    public Cmomentum() {
        ref = new Creference();
        index = -1;
    }

    public Cmomentum(double px, double py, double pz, double e) {
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.E = e;
        ref = new Creference();
        buildEtaPhi();
    }

    /** Cmomentum(eta, phi, ref), whose four-vector the C++ leaves undefined (zero here). */
    Cmomentum(double eta, double phi, Creference r) {
        this.eta = eta;
        this.phi = phi;
        ref = new Creference(r);
    }

    /** The copy constructor. */
    public Cmomentum copy() {
        final Cmomentum c = new Cmomentum();
        c.px = px;
        c.py = py;
        c.pz = pz;
        c.E = E;
        c.eta = eta;
        c.phi = phi;
        c.parentIndex = parentIndex;
        c.index = index;
        c.ref.set(ref);
        return c;
    }

    /** operator=: everything but the indices. */
    void assign(Cmomentum v) {
        px = v.px;
        py = v.py;
        pz = v.pz;
        E = v.E;
        eta = v.eta;
        phi = v.phi;
        ref.set(v.ref);
    }

    /** operator+: a copy of this plus v. */
    Cmomentum plus(Cmomentum v) {
        final Cmomentum tmp = copy();
        tmp.add(v);
        return tmp;
    }

    /** operator+=: the four-vector and the reference, not (eta, phi). */
    void add(Cmomentum v) {
        px += v.px;
        py += v.py;
        pz += v.pz;
        E += v.E;
        ref.xor(v.ref);
    }

    /** operator-=. */
    void subtract(Cmomentum v) {
        px -= v.px;
        py -= v.py;
        pz -= v.pz;
        E -= v.E;
        ref.xor(v.ref);
    }

    public double perp() {
        return Math.sqrt(perp2());
    }

    public double perp2() {
        return px * px + py * py;
    }

    public double perpmass2() {
        return (E - pz) * (E + pz);
    }

    public double Et2() {
        return E * E / (1.0 + pz * pz / perp2());
    }

    public void buildEtaPhi() {
        eta = 0.5 * CRMath.log((E + pz) / (E - pz));
        phi = CRMath.atan2(py, px);
    }

    public Creference ref() {
        return ref;
    }
}
