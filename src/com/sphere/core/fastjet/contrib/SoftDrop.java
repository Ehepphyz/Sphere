package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.WrappedStructure;
import com.sphere.core.fastjet.tools.Recluster;
import com.sphere.core.fastjet.tools.Transformer;

/**
 * Soft drop (A. Larkoski, S. Marzani, G. Soyez, J. Thaler, JHEP 05 (2014)
 * 146): the jet is reclustered with Cambridge/Aachen and declustered along
 * its harder branch until a splitting satisfies
 * z > z_cut (Delta_R / R0)^beta, the softer branches met before being
 * dropped. beta = 0 is the modified mass-drop tagger (without its mass
 * condition); beta < 0 makes it a tagger that returns an empty jet when no
 * splitting passes, beta >= 0 a groomer that then returns the last particle.
 *
 * Written for Sphere from the paper. With double-double jets z, Delta_R and
 * the condition are evaluated to 106 bits, so a splitting on the edge of the
 * cut is decided by the physics and not by rounding.
 */
public class SoftDrop implements Transformer {

    static {
        Citations.use("softdrop"); // listed in the console's Citations menu once used
    }

    /** How the momentum sharing and the angle of a splitting are measured. */
    public enum Measure {
        /** z = min(pt1, pt2)/(pt1+pt2), angle in (rapidity, phi): hadron colliders. */
        PT_RAPIDITY_PHI,
        /** z = min(E1, E2)/(E1+E2), opening angle of the three-momenta: e+e-. */
        ENERGY_THETA
    }

    /** What the groomed jet knows of its grooming. */
    public static final class SoftDropStructure extends WrappedStructure {
        private final double deltaR;
        private final double symmetry;
        private final double mu;
        private final int dropped;
        private final double maxDroppedSymmetry;

        SoftDropStructure(PseudoJet jet, double deltaR, double symmetry, double mu, int dropped, double maxDropped) {
            super(jet.structure());
            this.deltaR = deltaR;
            this.symmetry = symmetry;
            this.mu = mu;
            this.dropped = dropped;
            this.maxDroppedSymmetry = maxDropped;
        }

        /** The angle of the splitting that passed (0 when none did). */
        public double deltaR() { return deltaR; }
        /** Its momentum sharing z. */
        public double symmetry() { return symmetry; }
        /** The mass of its harder branch over the jet's. */
        public double mu() { return mu; }
        /** How many branches were dropped. */
        public int droppedCount() { return dropped; }
        /** The largest z among them. */
        public double maxDroppedSymmetry() { return maxDroppedSymmetry; }
    }

    private final double beta;
    private final double zcut;
    private final double r0;
    private final Measure measure;

    public SoftDrop(double beta, double zcut) {
        this(beta, zcut, 1.0, Measure.PT_RAPIDITY_PHI);
    }

    public SoftDrop(double beta, double zcut, double r0, Measure measure) {
        this.beta = beta;
        this.zcut = zcut;
        this.r0 = r0;
        if (beta == 0) Citations.use("mmdt");
        this.measure = measure;
    }

    public double beta() { return beta; }
    public double zcut() { return zcut; }
    public double R0() { return r0; }

    @Override
    public String description() {
        return "SoftDrop groomer applying a symmetry cut z > " + Fmt.g(zcut) + " (theta/" + Fmt.g(r0) + ")^"
            + Fmt.g(beta) + (measure == Measure.ENERGY_THETA ? " in energy and opening angle" : "")
            + (beta < 0 ? " [tagging mode]" : "");
    }

    /** The jet as a C/A clustering, reclustering it unless it already is one. */
    static PseudoJet asCambridge(PseudoJet jet, boolean spherical) {
        if (jet.hasAssociatedClusterSequence()) {
            final JetAlgorithm alg = jet.validatedCs().jetDef().jetAlgorithm();
            if (alg == JetAlgorithm.CAMBRIDGE || alg == JetAlgorithm.EE_GENKT && spherical) return jet;
        }
        final JetDefinition ca = spherical
            ? new JetDefinition(JetAlgorithm.EE_GENKT, 4.0, 0.0)
            : new JetDefinition(JetAlgorithm.CAMBRIDGE, JetDefinition.MAX_ALLOWABLE_R);
        ca.setPrecision(jet.precision());
        return new Recluster(ca).result(jet);
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        final boolean sph = measure == Measure.ENERGY_THETA;
        PseudoJet j = asCambridge(jet, sph);
        int dropped = 0;
        double maxDropped = 0.0;
        PseudoJet[] parents;
        while ((parents = j.parents()) != null) {
            PseudoJet p1 = parents[0];
            PseudoJet p2 = parents[1];
            final DD s1 = sph ? Kin.e(p1) : Kin.pt(p1);
            final DD s2 = sph ? Kin.e(p2) : Kin.pt(p2);
            if (s1.lt(s2)) {
                final PseudoJet t = p1;
                p1 = p2;
                p2 = t;
            }
            final DD z = DD.min(s1, s2).div(s1.add(s2));
            final DD angle = sph ? Kin.angle(p1, p2) : Kin.dR2(p1, p2).sqrt();
            final DD cut = new DD(zcut).mul(Kin.pow(angle.div(r0), beta));
            if (z.gt(cut)) {
                final PseudoJet out = j.copy();
                final double mu = j.m2() > 0 ? p1.m() / j.m() : 0.0;
                out.setStructure(new SoftDropStructure(j, angle.doubleValue(), z.doubleValue(), mu, dropped, maxDropped));
                return out;
            }
            dropped++;
            if (z.doubleValue() > maxDropped) maxDropped = z.doubleValue();
            j = p1;
        }
        if (beta < 0) return new PseudoJet();
        final PseudoJet out = j.copy();
        out.setStructure(new SoftDropStructure(j, 0.0, 0.0, 0.0, dropped, maxDropped));
        return out;
    }

    /** The modified mass-drop tagger: soft drop at beta = 0 in tagging mode. */
    public static SoftDrop modifiedMassDrop(double zcut) {
        return new SoftDrop(0.0, zcut) {
            @Override
            public String description() {
                return "modified mass-drop tagger with z > " + Fmt.g(zcut);
            }

            @Override
            public PseudoJet result(PseudoJet jet) {
                final PseudoJet r = super.result(jet);
                return r.structure() instanceof SoftDropStructure s && s.deltaR() > 0 ? r : new PseudoJet();
            }
        };
    }
}
