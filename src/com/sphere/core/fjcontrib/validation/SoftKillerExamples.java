package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fjcontrib.softkiller.SoftKiller;

import java.io.BufferedReader;
import java.util.List;

/** The example program of SoftKiller 1.0.0. */
final class SoftKillerExamples {

    private SoftKillerExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("SoftKiller", "example", "Pythia-Zp2jets-lhc-pileup-1ev.dat", SoftKillerExamples::example));
    }

    private static void print(Cout o, List<PseudoJet> jets) {
        for (PseudoJet j : jets) o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).p(", mass = ").p(j.m()).endl();
        o.endl();
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final Events.HardAndFull ev = Events.readHardAndFull(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final double rapmax = 5.0;
        final List<PseudoJet> hard = Selector.absRapMax(rapmax).apply(ev.hard());
        final List<PseudoJet> full = Selector.absRapMax(rapmax).apply(ev.full());
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        final Selector selJets = Selector.nHardest(2).times(Selector.absRapMax(3.0));
        final List<PseudoJet> hardJets = selJets.apply(new ClusterSequence(hard, jetDef).inclusiveJets());
        final List<PseudoJet> fullJets = selJets.apply(new ClusterSequence(full, jetDef).inclusiveJets());
        final SoftKiller sk = new SoftKiller(rapmax, 0.4);
        final SoftKiller.Result r = sk.apply(full);
        o.p("# Ran the following soft killer: ").p(sk.description()).endl();
        final List<PseudoJet> killJets = selJets.apply(new ClusterSequence(r.reducedEvent(), jetDef).inclusiveJets());
        o.setprecision(4);
        o.p("Soft Killer applied a pt threshold of ").p(r.ptThreshold()).endl();
        o.p("# original hard jets").endl();
        print(o, hardJets);
        o.p("# original full jets").endl();
        print(o, fullJets);
        o.p("# jets after applying the soft killer").endl();
        print(o, killJets);
    }
}
