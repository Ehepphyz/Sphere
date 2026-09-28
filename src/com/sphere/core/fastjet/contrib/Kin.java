package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.PseudoJet;

/**
 * The kinematic quantities the substructure tools share, each in two
 * flavours: plain double, and double-double (106 bits) when the jets are
 * double-double, so that the angles and momentum fractions the tools cut on
 * are known to 2^-106 relative.
 */
final class Kin {

    private Kin() {
    }

    /** Whether either jet is held in double-double. */
    static boolean dd(PseudoJet a, PseudoJet b) {
        return a.isDD() || b.isDD();
    }

    /** The squared (rapidity, phi) distance. */
    static DD dR2(PseudoJet a, PseudoJet b) {
        if (dd(a, b)) return a.squaredDistanceDD(b);
        return new DD(a.plainDistance(b));
    }

    static DD pt(PseudoJet a) {
        return a.isDD() ? a.ptDD() : new DD(a.pt());
    }

    static DD e(PseudoJet a) {
        return a.isDD() ? a.eDD() : new DD(a.E());
    }

    /** The opening angle between the three-momenta, accurate at small angles (cross-product form). */
    static DD angle(PseudoJet a, PseudoJet b) {
        final DD ax = a.pxDD();
        final DD ay = a.pyDD();
        final DD az = a.pzDD();
        final DD bx = b.pxDD();
        final DD by = b.pyDD();
        final DD bz = b.pzDD();
        final DD cx = ay.mul(bz).sub(az.mul(by));
        final DD cy = az.mul(bx).sub(ax.mul(bz));
        final DD cz = ax.mul(by).sub(ay.mul(bx));
        final DD cross = cx.sqr().add(cy.sqr()).add(cz.sqr()).sqrt();
        final DD dot = ax.mul(bx).add(ay.mul(by)).add(az.mul(bz));
        return DD.atan2(cross, dot);
    }

    /** x^p for x >= 0, exact for p = 0, 1, 2 and 1/2. */
    static DD pow(DD x, double p) {
        if (p == 1.0) return x;
        if (p == 2.0) return x.sqr();
        if (p == 0.0) return DD.ONE;
        if (p == 0.5) return x.sqrt();
        if (x.isZero()) return DD.ZERO;
        return x.pow(p);
    }

    /** The signed azimuthal difference a - b folded into (-pi, pi]. */
    static DD dphi(PseudoJet a, PseudoJet b) {
        DD d = a.phiDD().sub(b.phiDD());
        if (d.gt(DD.PI)) d = d.sub(DD.TWO_PI);
        if (d.le(DD.PI.neg())) d = d.add(DD.TWO_PI);
        return d;
    }
}
