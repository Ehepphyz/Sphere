package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.scjet.ScJet;

import java.io.BufferedReader;
import java.util.List;

/** The example program of ScJet 1.1.0. */
final class ScJetExamples {

    private ScJetExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("ScJet", "example", "single-event.dat", ScJetExamples::example));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(new ScJet(1.0)));
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets());
        o.printf("%5s %10s %10s %10s %10s %10s %10s\n", "jet #", "rapidity", "phi", "pt", "m", "e", "n constituents");
        for (int i = 0; i < jets.size(); ++i) {
            final PseudoJet j = jets.get(i);
            o.printf("%5u %10.3f %10.3f %10.3f %10.3f %10.3f %8u\n", i, j.rap(), j.phi(), j.perp(), j.m(), j.e(), j.constituents().size());
        }
    }
}
