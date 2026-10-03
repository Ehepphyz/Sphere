package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.disgenkt.DISGenktPlugin;

import java.io.BufferedReader;
import java.util.List;

/** The example program of DISGenkt (fastjet-contrib 1.104). */
final class DISGenktExamples {

    private DISGenktExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("DISGenkt", "example", "single-epDIS-event.dat", DISGenktExamples::example));
    }

    private static void output(Cout o, List<PseudoJet> jets, DISGenktPlugin plugin) {
        final int idxMacro = plugin.findIdxMacrojet(jets);
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet jet = jets.get(i);
            o.p(i == idxMacro ? "Macrojet with " : "jet with ");
            o.p("E = ").p(jet.e()).p(", kT = ").p(Math.sqrt(jet.px() * jet.px() + jet.py() * jet.py())).p(", rap = ").p(jet.rap())
                .p(", n constituents ").p(jet.constituents().size()).endl();
        }
        o.endl();
    }

    private static List<PseudoJet> run(Cout o, List<PseudoJet> event, DISGenktPlugin plugin, String title) {
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(plugin));
        o.p(title).endl();
        final List<PseudoJet> jets = cs.inclusiveJets();
        output(o, jets, plugin);
        return jets;
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# Read an event with ").p(event.size()).p(" particles").endl();
        run(o, event, new DISGenktPlugin(0., 1, Math.PI / 2.), "# Output of clustering with p = 0 and R = pi/2");
        run(o, event, new DISGenktPlugin(1, 1, Math.PI / 2.), "# Output of clustering with p = 1 and R = pi/2");
        run(o, event, new DISGenktPlugin(-1, 1, Math.PI / 2.), "# Output of clustering with p = -1 and R = pi/2");
        final DISGenktPlugin last = new DISGenktPlugin(-1, 1, Math.PI / 3.);
        final List<PseudoJet> jets = run(o, event, last, "# Output of clustering with p = -1 and R = pi/3");
        o.p("# Output of clustering with p = -1 and R = pi/3, sorted by largest z-projection").endl();
        output(o, last.sortedByZjet(jets), last);
    }
}
