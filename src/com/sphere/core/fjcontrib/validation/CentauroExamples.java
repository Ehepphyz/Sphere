package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.centauro.CentauroPlugin;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example program of Centauro 1.0.0. */
final class CentauroExamples {

    private CentauroExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("Centauro", "example", "single-event.dat", CentauroExamples::example));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = new ArrayList<>();
        o.p("Particles that are input").endl();
        String line;
        while ((line = in.readLine()) != null) {
            final double[] v = Events.numbers(line, 4);
            o.p(v[0]).p(" ").p(v[1]).p(" ").p(v[2]).p(" ").p(v[3]).endl();
            event.add(Events.particle(v[0], v[1], v[2], v[3]));
        }
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(new CentauroPlugin(1.0)));
        final List<PseudoJet> jets = PseudoJet.sortedByE(cs.inclusiveJets(0));
        o.p("jets in inclusive clustering ").endl();
        for (PseudoJet j : jets) o.p(" rap = ").p(j.rap()).p(" e ").p(j.e()).p(" n constituents ").p(j.constituents().size()).endl();
        o.endl();
    }
}
