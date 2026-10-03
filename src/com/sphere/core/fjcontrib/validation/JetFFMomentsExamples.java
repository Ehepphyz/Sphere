package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.Subtractor;
import com.sphere.core.fjcontrib.jetffmoments.JetFFMoments;

import java.io.BufferedReader;
import java.util.List;

/** The example program of JetFFMoments 1.0.0. */
final class JetFFMomentsExamples {

    private JetFFMomentsExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("JetFFMoments", "example", "Pythia-Zp2jets-lhc-pileup-1ev.dat", JetFFMomentsExamples::example));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final Events.HardAndFull ev = Events.readHardAndFull(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final List<PseudoJet> hard = Selector.absRapMax(5.0).apply(ev.hard());
        final List<PseudoJet> full = Selector.absRapMax(5.0).apply(ev.full());
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        final JetDefinition jetDefForRho = new JetDefinition(JetAlgorithm.KT, 0.4);
        final AreaDefinition areaDef = new AreaDefinition(AreaDefinition.AreaType.ACTIVE, new GhostedAreaSpec(Selector.absRapMax(5.0)));
        final Selector rhoRange = Selector.doughnut(0.4, 1.2);
        final JetMedianBackgroundEstimator bge = new JetMedianBackgroundEstimator(rhoRange, jetDefForRho, areaDef);
        final Subtractor subtractor = new Subtractor(bge);
        final ClusterSequenceArea csHard = new ClusterSequenceArea(hard, jetDef, areaDef);
        final ClusterSequenceArea csFull = new ClusterSequenceArea(full, jetDef, areaDef);
        final Selector selJets = Selector.nHardest(2).times(Selector.absRapMax(4.0));
        final List<PseudoJet> hardJets = selJets.apply(csHard.inclusiveJets());
        final List<PseudoJet> fullJets = selJets.apply(csFull.inclusiveJets());
        final JetFFMoments unsub = new JetFFMoments(-0.5, 6.0, 14);
        final JetFFMoments sub = new JetFFMoments(-0.5, 6.0, 14, bge);
        final JetFFMoments improved = new JetFFMoments(-0.5, 6.0, 14, bge);
        improved.setImprovedSubtraction(25.0, rhoRange, full, jetDefForRho, areaDef);
        bge.setParticles(hard);
        for (int ijet = 0; ijet < hardJets.size(); ijet++) {
            final PseudoJet jet = hardJets.get(ijet);
            final PseudoJet s = subtractor.result(jet);
            final double[] mu = unsub.result(jet);
            final double[] ms = sub.result(jet);
            o.p("# Fragmentation function moments for hard jet ").p(ijet + 1).p(": (pt,y,phi) = (").p(jet.pt()).p(", ")
                .p(jet.rap()).p(", ").p(jet.phi()).p("), #constituents=").p(jet.constituents().size()).endl();
            o.p("#                        [subtracted hard jet ").p(ijet + 1).p("]:(pt,y,phi) = (").p(s.pt()).p(", ")
                .p(s.rap()).p(", ").p(s.phi()).p(")").endl();
            o.p("# N  M_N(unsubtracted)  M_N(subtracted)").endl();
            for (int n = 0; n < ms.length; n++) o.p(sub.N(n)).p(" ").p(mu[n]).p(" ").p(ms[n]).endl();
            o.endl().endl();
        }
        bge.setParticles(full);
        for (int ijet = 0; ijet < fullJets.size(); ijet++) {
            final PseudoJet jet = fullJets.get(ijet);
            final PseudoJet s = subtractor.result(jet);
            final double[] mu = unsub.result(jet);
            final double[] ms = sub.result(jet);
            final double[] mi = improved.result(jet);
            o.p("# Fragmentation function moments for full jet ").p(ijet + 1).p(": (pt,y,phi) = (").p(jet.pt()).p(", ")
                .p(jet.rap()).p(", ").p(jet.phi()).p("), #constituents=").p(jet.constituents().size()).endl();
            o.p("#                        [subtracted full jet ").p(ijet + 1).p("]:(pt,y,phi) = (").p(s.pt()).p(", ")
                .p(s.rap()).p(", ").p(s.phi()).p(")").endl();
            o.p("# N  M_N(unsubtracted)  M_N(subtracted)  M_N(improved)").endl();
            for (int n = 0; n < ms.length; n++) o.p(sub.N(n)).p(" ").p(mu[n]).p(" ").p(ms[n]).p(" ").p(mi[n]).endl();
            o.endl().endl();
        }
        o.p("# ").p(improved.description()).endl();
    }
}
