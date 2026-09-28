package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

import java.util.List;

/**
 * Jet shapes: generalised angularities lambda^kappa_beta = sum z_i^kappa
 * (Delta_i / R)^beta (A. Larkoski, J. Thaler, W. Waalewijn, JHEP 11 (2014)
 * 129), with their usual names (multiplicity, pTD, Les Houches
 * angularity, width or girth, thrust-like mass), and the jet pull vector
 * (J. Gallicchio, M. Schwartz, PRL 105 (2010) 022001).
 *
 * Sums are carried in double-double.
 */
public final class JetShapes {

    private JetShapes() {
    }

    /** lambda^kappa_beta of a jet of radius R, about the jet axis. */
    public static double angularity(PseudoJet jet, double kappa, double beta, double R) {
        final List<PseudoJet> c = jet.constituents();
        DD ptSum = DD.ZERO;
        for (PseudoJet p : c) ptSum = ptSum.add(Kin.pt(p));
        if (ptSum.isZero()) return 0.0;
        DD sum = DD.ZERO;
        for (PseudoJet p : c) {
            final DD z = Kin.pt(p).div(ptSum);
            final DD d = Kin.dR2(p, jet).sqrt().div(R);
            sum = sum.add(Kin.pow(z, kappa).mul(Kin.pow(d, beta)));
        }
        return sum.doubleValue();
    }

    public static FunctionOfPseudoJet<Double> angularity(double kappa, double beta, double R) {
        return FunctionOfPseudoJet.of("angularity lambda^" + Fmt.g(kappa) + "_" + Fmt.g(beta) + " (R=" + Fmt.g(R) + ")",
            j -> angularity(j, kappa, beta, R));
    }

    /** pTD = sqrt(sum pt_i^2) / sum pt_i. */
    public static double ptD(PseudoJet jet) {
        return Math.sqrt(angularity(jet, 2, 0, 1));
    }

    /** The Les Houches angularity, lambda^1_0.5. */
    public static double lesHouchesAngularity(PseudoJet jet, double R) {
        return angularity(jet, 1, 0.5, R);
    }

    /** The width (girth), lambda^1_1 times R: sum pt_i Delta_i / sum pt_i. */
    public static double girth(PseudoJet jet) {
        return angularity(jet, 1, 1, 1);
    }

    /** lambda^1_2. */
    public static double thrustLike(PseudoJet jet, double R) {
        return angularity(jet, 1, 2, R);
    }

    /** The pull vector (in rapidity, phi): sum (pt_i |r_i| / pt_J) r_i. */
    public static double[] pull(PseudoJet jet) {
        Citations.use("pull");
        final List<PseudoJet> c = jet.constituents();
        final DD ptJ = Kin.pt(jet);
        if (ptJ.isZero()) return new double[]{0, 0};
        DD ty = DD.ZERO;
        DD tphi = DD.ZERO;
        final DD yJ = jet.rapDD();
        for (PseudoJet p : c) {
            final DD dy = p.rapDD().sub(yJ);
            final DD dphi = Kin.dphi(p, jet);
            final DD r = dy.sqr().add(dphi.sqr()).sqrt();
            final DD w = Kin.pt(p).mul(r).div(ptJ);
            ty = ty.add(w.mul(dy));
            tphi = tphi.add(w.mul(dphi));
        }
        return new double[]{ty.doubleValue(), tphi.doubleValue()};
    }

    /** The pull angle of jet a towards jet b: between a's pull vector and the direction a -> b. */
    public static double pullAngle(PseudoJet a, PseudoJet b) {
        final double[] t = pull(a);
        final double dy = b.rap() - a.rap();
        final double dphi = Kin.dphi(b, a).doubleValue();
        final double dot = t[0] * dy + t[1] * dphi;
        final double cross = t[0] * dphi - t[1] * dy;
        return Math.abs(Math.atan2(cross, dot));
    }
}
