package com.sphere.core.fjcontrib.sdfplugin;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.JadePlugin;
import com.sphere.core.fastjet.tools.Recluster;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.ifnplugin.FlavHistory;
import com.sphere.core.fjcontrib.ifnplugin.FlavInfo;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase;
import com.sphere.core.fjcontrib.recursivetools.SoftDrop;

import java.util.List;

/**
 * Soft Drop Flavour, fastjet::contrib::SDFlavourCalc (SDFPlugin 1.0.1;
 * S. Caletti, A.J. Larkoski, S. Marzani and D. Reichelt, "Practical jet
 * flavour through NNLO", Eur. Phys. J. C 82 (2022) 632, arXiv:2205.01109).
 *
 * <p>The flavour of a jet is the net flavour of the constituents that survive
 * Soft Drop after the jet is reclustered with the JADE algorithm, which makes
 * it infrared and collinear safe to NNLO. The jet itself is left as it is:
 * only its user information changes, to a FlavHistory of that flavour. The
 * constituents must carry flavour (a FlavRecombiner on the jet definition
 * gives it to them).
 */
public class SDFlavourCalc {

    static {
        ContribCitations.use("sdf");
    }

    private final double beta;
    private final double zcut;
    private final double r;
    private final SoftDrop softDrop;
    private final JetDefinition jade;

    /** beta = 2, zcut = 0.1, R = 0.4, the defaults of the paper. */
    public SDFlavourCalc() {
        this(2, 0.1, 0.4);
    }

    /** @param beta must be strictly positive */
    public SDFlavourCalc(double beta, double zcut, double r) {
        this.beta = beta;
        this.zcut = zcut;
        this.r = r;
        this.jade = new JetDefinition(new JadePlugin());
        this.softDrop = new SoftDrop(beta, zcut, RecursiveSymmetryCutBase.SymmetryMeasure.SCALAR_Z, r,
            Double.POSITIVE_INFINITY, RecursiveSymmetryCutBase.RecursionChoice.LARGER_PT, null);
        this.softDrop.setReclustering(true, new Recluster(jade));
    }

    public double beta() { return beta; }
    public double zcut() { return zcut; }
    public double R() { return r; }

    /** The Soft Drop Flavour of a jet, without touching it. */
    public FlavInfo flavourOf(PseudoJet jet) {
        final ClusterSequence cs = new ClusterSequence(jet.constituents(), jade);
        final List<PseudoJet> one = cs.exclusiveJetsUpTo(1);
        if (one.isEmpty()) return new FlavInfo();
        final PseudoJet groomed = softDrop.result(one.get(0));
        if (groomed == null || !groomed.hasConstituents() || groomed.constituents().isEmpty()) return new FlavInfo();
        final List<PseudoJet> kept = groomed.constituents();
        FlavInfo flav = FlavHistory.currentFlavourOf(kept.get(0));
        for (int i = 1; i < kept.size(); i++) flav = flav.plus(FlavHistory.currentFlavourOf(kept.get(i)));
        return flav;
    }

    /** Sets the jet's user information to a FlavHistory of its Soft Drop Flavour. */
    public void apply(PseudoJet jet) {
        jet.setUserInfo(new FlavHistory(flavourOf(jet)));
    }

    /** The same for each jet of a list. */
    public void apply(List<PseudoJet> jets) {
        for (PseudoJet j : jets) apply(j);
    }

    public String description() {
        return "Soft Drop Flavour (JADE reclustering, beta = " + com.sphere.core.fastjet.Fmt.g(beta) + ", zcut = "
            + com.sphere.core.fastjet.Fmt.g(zcut) + ", R0 = " + com.sphere.core.fastjet.Fmt.g(r) + ")";
    }
}
