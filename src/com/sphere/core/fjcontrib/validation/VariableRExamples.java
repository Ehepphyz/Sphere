package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.variabler.VariableRPlugin;

import java.io.BufferedReader;
import java.util.List;

/** The example program of VariableR 1.2.1. */
final class VariableRExamples {

    private VariableRExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("VariableR", "example", "single-event.dat", VariableRExamples::example));
    }

    /** The print_jets shared by several contrib examples: pt-sorted jets with rapidity, phi, pt, m, e and constituents. */
    static void printJets(Cout o, ClusterSequence cs, List<PseudoJet> jets) {
        final List<PseudoJet> sorted = PseudoJet.sortedByPt(jets);
        o.printf("%5s %10s %10s %10s %10s %10s %10s\n", "jet #", "rapidity", "phi", "pt", "m", "e", "n constituents");
        for (int i = 0; i < sorted.size(); i++) {
            final PseudoJet j = sorted.get(i);
            o.printf("%5u %10.3f %10.3f %10.3f %10.3f %10.3f %8u\n", i, j.rap(), j.phi(), j.perp(), j.m(), j.e(),
                cs.constituents(j).size());
        }
    }

    private static void run(Cout o, List<PseudoJet> event, VariableRPlugin plugin, double ptmin) {
        final JetDefinition jetDef = new JetDefinition(plugin);
        final ClusterSequence cs = new ClusterSequence(event, jetDef);
        o.p("# Ran ").p(jetDef.description()).endl();
        final List<PseudoJet> jets = cs.inclusiveJets(ptmin);
        o.p("Printing inclusive jets with pt > ").p(ptmin).p(" GeV\n");
        o.p("---------------------------------------\n");
        printJets(o, cs, jets);
        o.endl();
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final double rho = 2000.0;
        final double maxR = 2.0;
        final double ptmin = 5.0;
        run(o, event, new VariableRPlugin(rho, 0.0, maxR, VariableRPlugin.AKTLIKE), ptmin);
        run(o, event, new VariableRPlugin(rho, 0.0, maxR, VariableRPlugin.CALIKE), ptmin);
        final double minR = 0.4;
        run(o, event, new VariableRPlugin(rho, minR, maxR, VariableRPlugin.AKTLIKE, true), ptmin);
        run(o, event, new VariableRPlugin(rho, minR, maxR, VariableRPlugin.AKTLIKE, false), ptmin);
        run(o, event, new VariableRPlugin(rho, minR, maxR, -0.5), ptmin);
    }
}
