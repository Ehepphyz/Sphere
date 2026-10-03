package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fjcontrib.subjetcounting.SubjetCountingCA;
import com.sphere.core.fjcontrib.subjetcounting.SubjetCountingKt;

import java.io.BufferedReader;
import java.util.List;

/** The example program of SubjetCounting 1.0.1. */
final class SubjetCountingExamples {

    private SubjetCountingExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("SubjetCounting", "example", "single-event.dat", SubjetCountingExamples::example));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 1.50, RecombinationScheme.E_SCHEME, Strategy.BEST);
        final ClusterSequence cs = new ClusterSequence(event, jetDef);
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets());
        o.p("Event has a total of ").p(jets.size()).p(" anti-Kt jets with jet radius 1.50").endl();
        o.p("Going to compute n_Kt and n_CA for each such jet (with two different sets of parameters");
        o.p(" for each observable)").endl();
        final double fKt1 = 0.06, fKt2 = 0.12, ptCut1 = 40.0, ptCut2 = 50.0;
        final double mass1 = 30.0, mass2 = 50.0, ycut1 = 0.10, ycut2 = 0.15, rMin = 0.15;
        final SubjetCountingKt kt1 = new SubjetCountingKt(fKt1, ptCut1);
        final SubjetCountingKt kt2 = new SubjetCountingKt(fKt2, ptCut2);
        final SubjetCountingCA ca1 = new SubjetCountingCA(mass1, ycut1, rMin, ptCut1);
        final SubjetCountingCA ca2 = new SubjetCountingCA(mass2, ycut2, rMin, ptCut2);
        for (int k = 0; k < jets.size(); k++) {
            final PseudoJet j = jets.get(k);
            o.printf("n_Kt(jet %d; f_Kt = %.2f, pt_cut = %.1f GeV) = %d\n", k + 1, fKt1, ptCut1, kt1.result(j));
            o.printf("n_Kt(jet %d; f_Kt = %.2f, pt_cut = %.1f GeV) = %d\n", k + 1, fKt2, ptCut2, kt2.result(j));
            o.printf("n_CA(jet %d; mass_cut_off = %.1f, ycut = %.2f, R_min = %.2f, pt_cut = %.1f GeV) = %d\n",
                k + 1, mass1, ycut1, rMin, ptCut1, ca1.result(j));
            o.printf("n_CA(jet %d; mass_cut_off = %.1f, ycut = %.2f, R_min = %.2f, pt_cut = %.1f GeV) = %d\n",
                k + 1, mass2, ycut2, rMin, ptCut2, ca2.result(j));
        }
    }
}
