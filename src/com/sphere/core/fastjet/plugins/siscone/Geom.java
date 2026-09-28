package com.sphere.core.fastjet.plugins.siscone;

/** siscone's defines.h and geom_2d.h: constants and small geometry helpers. */
final class Geom {

    private Geom() {
    }

    static final double PT_TSHOLD = 1000.0;
    static final double EPSILON_COLLINEAR = 1e-8;
    static final double EPSILON_COCIRCULAR = 1e-12;
    static final double EPSILON_SPLITMERGE = 1e-12;
    static final double TWOPI = 6.283185307179586476925286766559005768394;
    static final double M_PI = 3.141592653589793238462643383279502884197;

    static double phiInRange(double phi) {
        if (phi <= -M_PI) phi += TWOPI;
        else if (phi > M_PI) phi -= TWOPI;
        return phi;
    }

    static double absDphi(double phi1, double phi2) {
        final double delta = Math.abs(phi1 - phi2);
        return delta > M_PI ? TWOPI - delta : delta;
    }

    static double pow2(double x) {
        return x * x;
    }

    /** get_distance: the squared (eta, phi) distance. */
    static double getDistance(double eta, double phi, Cmomentum v) {
        final double dx = eta - v.eta;
        double dy = Math.abs(phi - v.phi);
        if (dy > M_PI) dy -= TWOPI;
        return dx * dx + dy * dy;
    }
}
