package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;

/**
 * One node of the e+e- Lund diagram, LundEEDeclustering: energies and
 * three-dimensional angles in place of pt and (y, phi), with the plane it
 * belongs to, its depth, the plane of its leaf and the azimuth psibar.
 */
public class LundEEDeclustering {

    private final int iplane;
    private double psi;
    private final double psibar;
    private final double lnkt;
    private final double eta;
    private final double m;
    private final double z;
    private final double kt;
    private final double kappa;
    private final double sinTheta;
    private final PseudoJet pair;
    private final PseudoJet harder;
    private final PseudoJet softer;
    private final int depth;
    private final int leafIplane;
    private final int signS;

    protected LundEEDeclustering(PseudoJet pair, PseudoJet j1, PseudoJet j2, int iplane, double psi, double psibar,
                                 int depth, int leafIplane, int signS) {
        this.iplane = iplane;
        this.psi = psi;
        this.psibar = psibar;
        this.m = pair.m();
        this.pair = pair;
        this.depth = depth;
        this.leafIplane = leafIplane;
        this.signS = signS;
        final double omc = LundEEHelpers.oneMinusCostheta(j1, j2);
        if (omc > Math.sqrt(Math.ulp(1.0))) {
            final double cosTheta = 1.0 - omc;
            final double theta = CRMath.acos(cosTheta);
            sinTheta = CRMath.sin(theta);
            eta = -CRMath.log(CRMath.tan(theta / 2.0));
        } else {
            final double theta = Math.sqrt(2. * omc);
            sinTheta = theta;
            eta = -CRMath.log(theta / 2);
        }
        if (j1.modp2() > j2.modp2()) {
            harder = j1;
            softer = j2;
        } else {
            harder = j2;
            softer = j1;
        }
        final double softerModp = softer.modp();
        z = softerModp / (softerModp + harder.modp());
        kt = softerModp * sinTheta;
        lnkt = CRMath.log(kt);
        kappa = z * sinTheta;
    }

    public PseudoJet pair() { return pair; }
    public PseudoJet harder() { return harder; }
    public PseudoJet softer() { return softer; }
    public double m() { return m; }
    /** The effective pseudorapidity of the emission. */
    public double eta() { return eta; }
    public double sinTheta() { return sinTheta; }
    /** softer |p| / (softer |p| + harder |p|). */
    public double z() { return z; }
    /** softer |p| sin theta. */
    public double kt() { return kt; }
    public double lnkt() { return lnkt; }
    public double kappa() { return kappa; }
    /** The plane this declustering belongs to. */
    public int iplane() { return iplane; }
    /** 0 for the primary plane, 1 for the first leaves... */
    public int depth() { return depth; }
    /** The plane of the leaf of the softer branch, -1 when not followed. */
    public int leafIplane() { return leafIplane; }
    /** +1 for the jet of larger pz, -1 for the other. */
    public int signS() { return signS; }
    /** The azimuth psibar; differences between declusterings are what is meaningful. */
    public double psibar() { return psibar; }
    /** The older azimuth, deprecated in the contrib (not rotation invariant). */
    public double psi() { return psi; }
    public void setPsi(double value) { psi = value; }

    /** (eta, ln kt). */
    public double[] lundCoordinates() {
        return new double[]{eta, lnkt};
    }

    @Override
    public String toString() {
        return "kt = " + Fmt.g(kt) + " z = " + Fmt.g(z) + " eta = " + Fmt.g(eta) + " psi = " + Fmt.g(psi)
            + " psibar = " + Fmt.g(psibar) + " m = " + Fmt.g(m) + " iplane = " + iplane + " depth = " + depth
            + " leaf_iplane = " + leafIplane;
    }
}
