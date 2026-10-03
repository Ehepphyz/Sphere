package com.sphere.core.fjcontrib.subjetcounting;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.List;

/**
 * The kt subjet count n_Kt, fastjet::contrib::SubjetCountingKt
 * (SubjetCounting 1.0.1; S. El Hedri, A. Hook, M. Jankowiak and J.G. Wacker,
 * "Learning How to Count", JHEP 1308 (2013) 136, arXiv:1302.1870): the jet is
 * reclustered with kt, stopped at d_cut = (f_Kt pt_jet)^2, and the exclusive
 * subjets above pt_cut are counted.
 *
 * <p>Beyond the C++ contrib, {@link #decisionMargin(PseudoJet)} says how far
 * the count is from changing: the relative distance of d_cut to the nearest
 * kt merging scale, and of the nearest subjet pt to pt_cut.
 */
public class SubjetCountingKt implements FunctionOfPseudoJet<Integer> {

    static {
        ContribCitations.use("subjetcounting");
    }

    private final double fKt;
    private final double ptCut;

    public SubjetCountingKt(double fKt, double ptCut) {
        this.fKt = fKt;
        this.ptCut = ptCut;
    }

    private ClusterSequence recluster(PseudoJet jet) {
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.KT, JetDefinition.MAX_ALLOWABLE_R,
            RecombinationScheme.E_SCHEME, Strategy.BEST);
        return new ClusterSequence(jet.constituents(), jetDef);
    }

    private double ktScale(PseudoJet jet) {
        final double totalJetPt = jet.perp();
        double scale = totalJetPt * totalJetPt * fKt * fKt;
        scale /= JetDefinition.MAX_ALLOWABLE_R * JetDefinition.MAX_ALLOWABLE_R;
        return scale;
    }

    /** The subjets counted, by decreasing pt. */
    public List<PseudoJet> getSubjets(PseudoJet jet) {
        final ClusterSequence cs = recluster(jet);
        final List<PseudoJet> ktJets = PseudoJet.sortedByPt(cs.exclusiveJets(ktScale(jet)));
        final List<PseudoJet> subjets = new ArrayList<>();
        for (PseudoJet k : ktJets) if (k.perp() > ptCut) subjets.add(k);
        return subjets;
    }

    @Override
    public Integer result(PseudoJet jet) {
        if (!jet.hasConstituents()) throw new FastJetException("SubjetCountingKt called on jet with no constituents.");
        return getSubjets(jet).size();
    }

    /**
     * min(|d_cut - d_merge| / d_cut over the kt merging scales, |pt - pt_cut| /
     * pt_cut over the exclusive subjets): the count holds under any change of
     * the jet smaller than this relative amount.
     */
    public double decisionMargin(PseudoJet jet) {
        final ClusterSequence cs = recluster(jet);
        final double dcut = ktScale(jet);
        double margin = Double.POSITIVE_INFINITY;
        final int n = cs.nParticles();
        for (int nj = 0; nj < n; nj++) {
            final double d = cs.exclusiveDmerge(nj);
            if (d > 0 && dcut > 0) margin = Math.min(margin, Math.abs(d - dcut) / dcut);
        }
        if (ptCut > 0) {
            for (PseudoJet k : cs.exclusiveJets(dcut)) margin = Math.min(margin, Math.abs(k.perp() - ptCut) / ptCut);
        }
        return margin;
    }

    @Override
    public String description() {
        return "SubjetCountingKt using parameters f_Kt = " + Fmt.g(fKt) + " and pt_cut = " + Fmt.g(ptCut);
    }
}
