package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fjcontrib.valencia.ValenciaPlugin;

import java.io.BufferedReader;
import java.util.List;

/** The example program of ValenciaPlugin 2.0.2. */
final class ValenciaExamples {

    private ValenciaExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("ValenciaPlugin", "example", "single-event.dat", ValenciaExamples::example));
    }

    private static void print(Cout o, String title, List<PseudoJet> jets) {
        o.p(title).endl();
        for (PseudoJet j : jets) o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).endl();
        o.endl();
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Selector.absRapMax(4.0).apply(Events.readEvent(in));
        final JetDefinition jetDef = new JetDefinition(new ValenciaPlugin(1.2, 0.8));
        final ClusterSequence cs = new ClusterSequence(event, jetDef);
        print(o, "hard jets (pT > 20 GeV) in inclusive clustering ", cs.inclusiveJets(20.));
        print(o, "hard jets in exclusive N=4 clustering ", cs.exclusiveJets(4));
        print(o, "hard jets in exclusive clustering up to d = 500", cs.exclusiveJets(500.));
    }
}
