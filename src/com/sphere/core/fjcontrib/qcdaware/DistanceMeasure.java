package com.sphere.core.fjcontrib.qcdaware;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;

/**
 * The distance underlying a QCD-aware clustering,
 * fastjet::contrib::QCDAwarePlugin::DistanceMeasure: kt, anti-kt, C/A, or
 * the flavour-kt of hep-ph/0601139. The flavour factors multiply it.
 */
public interface DistanceMeasure {

    double dij(PseudoJet pi, PseudoJet pj);

    double diB(PseudoJet pi);

    double R();

    String algname();

    /** kt: min(pt_i^2, pt_j^2) dR^2 / R^2. */
    static DistanceMeasure kt(double r) {
        return new DistanceMeasure() {
            public double dij(PseudoJet pi, PseudoJet pj) {
                final double drbyR2 = pi.squaredDistance(pj) / (r * r);
                return Math.min(pi.perp2(), pj.perp2()) * drbyR2;
            }
            public double diB(PseudoJet pi) { return pi.perp2(); }
            public double R() { return r; }
            public String algname() { return "kt"; }
        };
    }

    /** anti-kt: dR^2 / R^2 / max(pt_i^2, pt_j^2). */
    static DistanceMeasure antikt(double r) {
        return new DistanceMeasure() {
            public double dij(PseudoJet pi, PseudoJet pj) {
                final double drbyR2 = pi.squaredDistance(pj) / (r * r);
                return 1.0 / Math.max(pi.perp2(), pj.perp2()) * drbyR2;
            }
            public double diB(PseudoJet pi) { return 1.0 / pi.perp2(); }
            public double R() { return r; }
            public String algname() { return "anti-kt"; }
        };
    }

    /** Cambridge/Aachen: dR^2 / R^2. */
    static DistanceMeasure cambridge(double r) {
        return new DistanceMeasure() {
            public double dij(PseudoJet pi, PseudoJet pj) { return pi.squaredDistance(pj) / (r * r); }
            public double diB(PseudoJet pi) { return 1.0; }
            public double R() { return r; }
            public String algname() { return "Cambridge-Aachen"; }
        };
    }

    /**
     * Flavour-kt: when the softer object is a quark, kt^2 becomes
     * max(pt)^alpha min(pt)^(2-alpha) (Banfi, Salam and Zanderighi).
     */
    static DistanceMeasure flavourKt(double r, double alpha) {
        if (alpha < 0.0 || alpha > 2.0) {
            throw new FastJetException("FlavourKtMeasure: alpha exponent must be in [0,2], got " + alpha);
        }
        return new DistanceMeasure() {
            private boolean isFlav(PseudoJet p) {
                final int ui = p.userIndex();
                final int pdg = Math.abs(ui > 0 ? ui % 1000000 : ui % (-1000000));
                return pdg >= 1 && pdg <= 6;
            }
            public double dij(PseudoJet pi, PseudoJet pj) {
                final double drbyR2 = pi.squaredDistance(pj) / (r * r);
                final boolean flav = isFlav(pi.perp2() < pj.perp2() ? pi : pj);
                final double kt2 = flav
                    ? CRMath.pow(Math.max(pi.perp(), pj.perp()), alpha) * CRMath.pow(Math.min(pi.perp(), pj.perp()), 2.0 - alpha)
                    : Math.min(pi.perp2(), pj.perp2());
                return kt2 * drbyR2;
            }
            public double diB(PseudoJet pi) { return pi.perp2(); }
            public double R() { return r; }
            public String algname() { return "Flavour-kt"; }
        };
    }
}
