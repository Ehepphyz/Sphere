package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CompositeJetStructure;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * The rest-frame N-subjettiness tagger of Ji, Kim and Park,
 * fastjet::RestFrameNSubjettinessTagger: two subjets are found in the jet's
 * rest frame, with cuts on their angle to the jet direction and on tau_2.
 *
 * FastJet 3.5.2 returns the first subjet twice as the tagged pieces (the
 * second one is read with index 0 again); here the second subjet is the
 * second piece, as the method describes.
 */
public class RestFrameNSubjettinessTagger implements Transformer {

    /** The tagged jet: its two lab-frame subjets, tau_2 and the largest cos(theta_s). */
    public static final class RestFrameNSubjettinessTaggerStructure extends CompositeJetStructure {
        double tau2;
        double costhetas = 1.0;

        RestFrameNSubjettinessTaggerStructure(List<PseudoJet> pieces) {
            super(pieces, null);
        }

        public double tau2() {
            return tau2;
        }

        public double costhetas() {
            return costhetas;
        }
    }

    private final JetDefinition subjetDef;
    private final double t2cut;
    private final double costscut;
    private final boolean useExclusive;

    public RestFrameNSubjettinessTagger(JetDefinition subjetDef, double tau2cut, double costhetascut,
                                        boolean useExclusive) {
        this.subjetDef = subjetDef;
        this.t2cut = tau2cut;
        this.costscut = costhetascut;
        this.useExclusive = useExclusive;
    }

    public RestFrameNSubjettinessTagger(JetDefinition subjetDef) {
        this(subjetDef, 0.08, 0.8, false);
    }

    @Override
    public String description() {
        return "RestFrameNSubjettiness tagger that performs clustering in the jet rest frame with "
            + subjetDef.description() + ", supplemented with cuts tau_2 < " + Fmt.g(t2cut)
            + " and cos(theta_s) < " + Fmt.g(costscut);
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasConstituents()) {
            throw new FastJetException("The jet you try to tag needs to have accessible constituents");
        }
        final List<PseudoJet> restInput = new ArrayList<>();
        for (PseudoJet c : jet.constituents()) {
            final PseudoJet r = c.copy();
            r.unboost(jet);
            restInput.add(r);
        }
        final ClusterSequence csRest = new ClusterSequence(restInput, subjetDef);
        final List<PseudoJet> subjets = useExclusive ? csRest.exclusiveJets(2)
                                                     : PseudoJet.sortedByE(csRest.inclusiveJets());
        if (subjets.size() < 2) {
            return new PseudoJet();
        }
        final PseudoJet j0 = subjets.get(0);
        final PseudoJet j1 = subjets.get(1);
        final double ct0 = (j0.px() * jet.px() + j0.py() * jet.py() + j0.pz() * jet.pz())
            / Math.sqrt(j0.modp2() * jet.modp2());
        final double ct1 = (j1.px() * jet.px() + j1.py() * jet.py() + j1.pz() * jet.pz())
            / Math.sqrt(j1.modp2() * jet.modp2());
        if (ct0 > costscut || ct1 > costscut) {
            return new PseudoJet();
        }
        double tau2 = 0.0;
        for (PseudoJet r : restInput) {
            tau2 += Math.min(PseudoJet.dotProduct(r, j0), PseudoJet.dotProduct(r, j1));
        }
        tau2 *= 2.0 / jet.m2();
        if (tau2 > t2cut) {
            return new PseudoJet();
        }
        final ClusterSequence csLab = ClusterSequence.transformedCopy(csRest, new Boost(jet));
        final PseudoJet lab1 = csLab.jet(csRest.history().get(j0.clusterHistIndex()).jetpIndex());
        final PseudoJet lab2 = csLab.jet(csRest.history().get(j1.clusterHistIndex()).jetpIndex());
        final RestFrameNSubjettinessTaggerStructure s =
            new RestFrameNSubjettinessTaggerStructure(List.of(lab1, lab2));
        final PseudoJet resultLocal = PseudoJet.join(List.of(lab1, lab2));
        resultLocal.setStructure(s);
        s.tau2 = tau2;
        s.costhetas = Math.max(ct0, ct1);
        return resultLocal;
    }
}
