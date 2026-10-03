package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.flavorcone.FlavorConePlugin;
import com.sphere.core.fjcontrib.internal.StdSort;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example program of FlavorCone 1.0.0. */
final class FlavorConeExamples {

    private FlavorConeExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("FlavorCone", "example", "single-event.dat", FlavorConeExamples::example));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        List<PseudoJet> event = Events.readEvent(in);
        o.p("# Read an event with ").p(event.size()).p(" particles").endl();
        for (int i = 0; i < event.size(); ++i) event.get(i).setUserIndex(i);
        event = StdSort.sortedByPt(event);
        if (event.size() < 2) return;
        final List<PseudoJet> seeds = new ArrayList<>();
        o.p("# Setting two seeds for clustering").endl();
        seeds.add(event.get(0));
        seeds.add(event.get(1));
        final FlavorConePlugin jdf = new FlavorConePlugin(seeds, 0.5);
        o.p("# Running ").p(jdf.description()).endl();
        final ClusterSequence jcs = new ClusterSequence(event, new JetDefinition(jdf));
        final List<PseudoJet> jets = jcs.inclusiveJets(0);
        o.p("#").setw(9).p("Seed ID").setw(10).p("px").setw(10).p("py").setw(10).p("pz").setw(10).p("e").setw(10).p("const.").endl();
        final FlavorConePlugin.Extras extras = (FlavorConePlugin.Extras) jcs.extras();
        for (PseudoJet jet : jets) {
            o.setw(10).p(extras.seed(jet).userIndex());
            for (int icmp = 0; icmp < 4; ++icmp) o.setw(10).p(jet.get(icmp));
            o.setw(10).p(jet.constituents().size());
            o.endl();
        }
    }
}
