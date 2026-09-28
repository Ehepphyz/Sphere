package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;

/**
 * siscone::hash_cones: the candidate cones met during the search, indexed by
 * the first word of their reference, each remembering whether it has always
 * passed the stability test. Buckets are singly linked lists filled at the
 * head, and are read back in that order.
 */
final class HashCones {

    static final class Element {
        final Creference ref = new Creference();
        double eta;
        double phi;
        boolean isStable;
        Element next;
    }

    final Element[] hashArray;
    final int mask;
    int nCones;
    private final double r2;

    HashCones(int np, double r2In) {
        nCones = 0;
        int nbits = (int) (CRMath.log(np * r2In * np / 4.0) / CRMath.log(2.0));
        if (nbits < 1) nbits = 1;
        final int size = 1 << nbits;
        hashArray = new Element[size];
        mask = size - 1;
        r2 = r2In;
    }

    /** Inserts a candidate, checking whether the parent and child are where they should be. */
    void insert(Cmomentum v, Cmomentum parent, Cmomentum child, boolean pIo, boolean cIo) {
        final int index = v.ref.r0 & mask;
        Element elm = hashArray[index];
        while (true) {
            if (elm == null) {
                elm = new Element();
                elm.ref.set(v.ref);
                v.buildEtaPhi();
                elm.eta = v.eta;
                elm.phi = v.phi;
                elm.isStable = !((isInside(v, parent) ^ pIo) || (isInside(v, child) ^ cIo));
                elm.next = hashArray[index];
                hashArray[index] = elm;
                nCones++;
                return;
            }
            if (v.ref.sameAs(elm.ref)) {
                if (elm.isStable) {
                    v.buildEtaPhi();
                    elm.isStable = !((isInside(v, parent) ^ pIo) || (isInside(v, child) ^ cIo));
                }
                return;
            }
            elm = elm.next;
        }
    }

    /** Inserts a candidate already known to be stable. */
    void insert(Cmomentum v) {
        final int index = v.ref.r0 & mask;
        Element elm = hashArray[index];
        while (true) {
            if (elm == null) {
                elm = new Element();
                elm.ref.set(v.ref);
                elm.eta = v.eta;
                elm.phi = v.phi;
                elm.isStable = true;
                elm.next = hashArray[index];
                hashArray[index] = elm;
                nCones++;
                return;
            }
            if (v.ref.sameAs(elm.ref)) return;
            elm = elm.next;
        }
    }

    private boolean isInside(Cmomentum centre, Cmomentum v) {
        final double dx = centre.eta - v.eta;
        double dy = Math.abs(centre.phi - v.phi);
        if (dy > Geom.M_PI) dy -= 2.0 * Geom.M_PI;
        return dx * dx + dy * dy < r2;
    }
}
