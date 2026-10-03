package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.PseudoJet;

/**
 * One node of a jet's Lund plane, LundDeclustering: the pair, its harder
 * and softer subjets, and the variables of the splitting (Delta, z, kt,
 * kappa, psi), cached.
 */
public class LundDeclustering {

    private final double m;
    private final double delta;
    private final double z;
    private final double kt;
    private final double kappa;
    private final double psi;
    private final PseudoJet pair;
    private final PseudoJet harder;
    private final PseudoJet softer;

    protected LundDeclustering(PseudoJet pair, PseudoJet j1, PseudoJet j2) {
        this.m = pair.m();
        this.delta = j1.deltaR(j2);
        this.pair = pair;
        if (j1.pt2() > j2.pt2()) {
            harder = j1;
            softer = j2;
        } else {
            harder = j2;
            softer = j1;
        }
        final double softerPt = softer.pt();
        z = softerPt / (softerPt + harder.pt());
        kt = softerPt * delta;
        psi = CRMath.atan2(softer.rap() - harder.rap(), harder.deltaPhiTo(softer));
        kappa = z * delta;
    }

    /** The sum of the two subjets. */
    public PseudoJet pair() { return pair; }
    public PseudoJet harder() { return harder; }
    public PseudoJet softer() { return softer; }
    public double m() { return m; }
    /** The rapidity-azimuth separation of the subjets. */
    public double Delta() { return delta; }
    /** softer pt / (softer pt + harder pt). */
    public double z() { return z; }
    /** softer pt times Delta. */
    public double kt() { return kt; }
    /** z Delta. */
    public double kappa() { return kappa; }
    /** The azimuth of the softer subjet around the harder. */
    public double psi() { return psi; }

    /** (ln 1/Delta, ln kt), the coordinates of the Lund-plane plots of arXiv:1807.04758. */
    public double[] lundCoordinates() {
        return new double[]{CRMath.log(1.0 / delta), CRMath.log(kt)};
    }
}
