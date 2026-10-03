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
 * The Cambridge/Aachen subjet count n_CA, fastjet::contrib::SubjetCountingCA
 * (SubjetCounting 1.0.1; S. El Hedri, A. Hook, M. Jankowiak and J.G. Wacker,
 * "Learning How to Count", JHEP 1308 (2013) 136, arXiv:1302.1870): the C/A tree of the
 * jet is declustered while the mass is above mass_cut_off and the parents
 * are further apart than R_min, following both branches when the softer
 * carries more than ycut of the pt, only the harder otherwise; the hard
 * substructures found above pt_cut are counted.
 *
 * <p>Beyond the C++ contrib, {@link #decisionMargin(PseudoJet)} gives the
 * relative distance of the closest of these decisions to its threshold.
 */
public class SubjetCountingCA implements FunctionOfPseudoJet<Integer> {

    static {
        ContribCitations.use("subjetcounting");
    }

    private final double massCutOff;
    private final double ycut;
    private final double rMin;
    private final double ptCut;

    public SubjetCountingCA(double massCutOff, double ycut, double rMin, double ptCut) {
        this.massCutOff = massCutOff;
        this.ycut = ycut;
        this.rMin = rMin;
        this.ptCut = ptCut;
    }

    private void findHardSubst(PseudoJet thisJet, List<PseudoJet> parts, double[] margin) {
        final PseudoJet[] parents = thisJet.parents();
        final boolean hadParents = parents != null;
        if (margin != null && hadParents && massCutOff > 0) {
            margin[0] = Math.min(margin[0], Math.abs(thisJet.m() - massCutOff) / massCutOff);
        }
        if (thisJet.m() < massCutOff || !hadParents) {
            parts.add(thisJet);
            return;
        }
        PseudoJet parent1 = parents[0];
        PseudoJet parent2 = parents[1];
        final double dist = parent1.plainDistance(parent2);
        if (margin != null && rMin > 0) margin[0] = Math.min(margin[0], Math.abs(dist - rMin * rMin) / (rMin * rMin));
        if (dist < rMin * rMin) {
            parts.add(thisJet);
            return;
        }
        if (parent1.perp() < parent2.perp()) {
            final PseudoJet t = parent1;
            parent1 = parent2;
            parent2 = t;
        }
        final double pt1 = parent1.perp();
        final double pt2 = parent2.perp();
        final double totalpt = pt1 + pt2;
        if (margin != null && ycut > 0 && totalpt > 0) {
            margin[0] = Math.min(margin[0], Math.abs(pt2 - ycut * totalpt) / (ycut * totalpt));
        }
        if (pt2 > ycut * totalpt) {
            findHardSubst(parent1, parts, margin);
            findHardSubst(parent2, parts, margin);
        } else {
            findHardSubst(parent1, parts, margin);
        }
    }

    private List<PseudoJet> hardSubstructures(PseudoJet jet, double[] margin) {
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.CAMBRIDGE, JetDefinition.MAX_ALLOWABLE_R,
            RecombinationScheme.E_SCHEME, Strategy.BEST);
        final ClusterSequence cs = new ClusterSequence(jet.constituents(), jetDef);
        final List<PseudoJet> caJets = PseudoJet.sortedByPt(cs.inclusiveJets());
        final List<PseudoJet> parts = new ArrayList<>();
        findHardSubst(caJets.get(0), parts, margin);
        return parts;
    }

    /** The hard substructures counted, in the order the declustering finds them. */
    public List<PseudoJet> getSubjets(PseudoJet jet) {
        final List<PseudoJet> subjets = new ArrayList<>();
        for (PseudoJet p : hardSubstructures(jet, null)) if (p.perp() > ptCut) subjets.add(p);
        return subjets;
    }

    @Override
    public Integer result(PseudoJet jet) {
        if (!jet.hasConstituents()) throw new FastJetException("SubjetCountingCA called on jet with no constituents.");
        return getSubjets(jet).size();
    }

    /**
     * The smallest relative distance of a decision variable (mass, parent
     * separation squared, softer pt, subjet pt) to its threshold along the
     * declustering: the count holds under changes smaller than this.
     */
    public double decisionMargin(PseudoJet jet) {
        final double[] margin = {Double.POSITIVE_INFINITY};
        for (PseudoJet p : hardSubstructures(jet, margin)) {
            if (ptCut > 0) margin[0] = Math.min(margin[0], Math.abs(p.perp() - ptCut) / ptCut);
        }
        return margin[0];
    }

    @Override
    public String description() {
        return "SubjetCountingCA using parameters mass_cutoff = " + Fmt.g(massCutOff) + ", ycut = " + Fmt.g(ycut)
            + ", Rmin = " + Fmt.g(rMin) + " and pt_cut = " + Fmt.g(ptCut);
    }
}
