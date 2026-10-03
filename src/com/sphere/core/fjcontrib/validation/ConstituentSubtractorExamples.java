package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fjcontrib.constituentsubtractor.ConstituentSubtractor;
import com.sphere.core.fjcontrib.constituentsubtractor.IterativeConstituentSubtractor;
import com.sphere.core.fjcontrib.constituentsubtractor.RescalingClasses;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of ConstituentSubtractor 1.4.7. */
final class ConstituentSubtractorExamples {

    private static final String C = "ConstituentSubtractor";
    private static final String DATA = "Pythia-Zp2jets-lhc-pileup-1ev.dat";

    private ConstituentSubtractorExamples() {
    }

    static List<Example> all() {
        final List<Example> l = new ArrayList<>();
        l.add(Example.of(C, "example_jet_by_jet", DATA, ConstituentSubtractorExamples::jetByJet));
        l.add(Example.of(C, "example_event_wide", DATA, ConstituentSubtractorExamples::eventWide));
        l.add(Example.of(C, "example_iterative", DATA, ConstituentSubtractorExamples::iterative));
        l.add(Example.of(C, "example_background_rescaling", DATA, ConstituentSubtractorExamples::backgroundRescaling));
        l.add(Example.of(C, "example_whole_event_using_charged_info", DATA, ConstituentSubtractorExamples::charged));
        return l;
    }

    /** The JetWidth of functions.hh. */
    static final FunctionOfPseudoJet<Double> WIDTH = jet -> {
        if (!jet.hasConstituents()) return -0.1;
        double width = 1e-6;
        double ptSum = 0;
        final List<PseudoJet> constituents = jet.constituents();
        if (constituents.size() < 2) return width;
        for (PseudoJet c : constituents) {
            final double dR = Math.sqrt(c.squaredDistance(jet));
            final double pt = c.pt();
            width += dR * pt;
            ptSum += pt;
        }
        return width / ptSum;
    };

    private static void jets(Cout o, String title, List<PseudoJet> jets) {
        o.p(title).endl();
        for (PseudoJet j : jets) {
            o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).p(", mass = ").p(j.m()).p(", width = ").p(WIDTH.result(j)).endl();
        }
        o.endl();
    }

    private static void corrected(Cout o, List<PseudoJet> event) {
        o.endl().p("Corrected particles in the whole event:").endl();
        for (PseudoJet p : event) {
            o.p("pt = ").p(p.pt()).p(", phi = ").p(p.phi()).p(", rap = ").p(p.rap()).p(", |mass| = ").p(Math.abs(p.m())).endl();
        }
        o.endl();
    }

    static void jetByJet(BufferedReader in, Cout o, String[] args) throws Exception {
        final Events.WithCharged ev = Events.readWithCharged(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final List<PseudoJet> hard = Selector.absRapMax(4.0).apply(ev.hard());
        final List<PseudoJet> full = Selector.absRapMax(4.0).apply(ev.full());
        o.p("# read an event with ").p(hard.size()).p(" signal particles and ").p(full.size() - hard.size())
            .p(" background particles with rapidity |y|<4").endl();
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.7);
        final AreaDefinition areaDef = new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS,
            new GhostedAreaSpec(4.0, 1, 0.01));
        final ClusterSequenceArea csHard = new ClusterSequenceArea(hard, jetDef, areaDef);
        final ClusterSequenceArea csFull = new ClusterSequenceArea(full, jetDef, areaDef);
        final Selector selJets = Selector.nHardest(2).times(Selector.absRapMax(3.0));
        final List<PseudoJet> hardJets = selJets.apply(csHard.inclusiveJets());
        final List<PseudoJet> fullJets = selJets.apply(csFull.inclusiveJets());
        new ClusterSequenceArea(full, jetDef, areaDef);
        final JetMedianBackgroundEstimator bge = new JetMedianBackgroundEstimator(Selector.absRapMax(3.0),
            new JetDefinition(JetAlgorithm.KT, 0.4), areaDef);
        bge.setJetDensityClass(JetMedianBackgroundEstimator.scalarPtDensity(1.0));
        bge.setParticles(full);
        final ConstituentSubtractor subtractor = new ConstituentSubtractor(bge);
        o.p(subtractor.description()).endl();
        o.setprecision(4);
        jets(o, "# original hard jets", hardJets);
        jets(o, "# unsubtracted full jets", fullJets);
        o.p("# subtracted full jets").endl();
        for (PseudoJet j : fullJets) {
            final PseudoJet s = subtractor.result(j);
            o.p("pt = ").p(s.pt()).p(", rap = ").p(s.rap()).p(", mass = ").p(s.m()).p(", width = ").p(WIDTH.result(s)).endl();
        }
        o.endl();
    }

    private record Prepared(List<PseudoJet> hardJets, List<PseudoJet> fullJets, List<PseudoJet> full, JetDefinition jetDef,
                            Selector selJets) {
    }

    private static Prepared prepare(Cout o, Events.WithCharged ev, double maxEta) {
        final List<PseudoJet> hard = Selector.absEtaMax(maxEta).apply(ev.hard());
        final List<PseudoJet> full = Selector.absEtaMax(maxEta).apply(ev.full());
        o.p("# read an event with ").p(hard.size()).p(" signal particles and ").p(full.size() - hard.size())
            .p(" background particles with pseudo-rapidity |eta|<4").endl();
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.7);
        final Selector selJets = Selector.nHardest(3).times(Selector.absEtaMax(3));
        return new Prepared(selJets.apply(new ClusterSequence(hard, jetDef).inclusiveJets()),
            selJets.apply(new ClusterSequence(full, jetDef).inclusiveJets()), full, jetDef, selJets);
    }

    private static void finish(Cout o, Prepared p, List<PseudoJet> correctedEvent, int particlePrecision) {
        final List<PseudoJet> correctedJets = p.selJets().apply(new ClusterSequence(correctedEvent, p.jetDef()).inclusiveJets());
        final Object[] f = o.flags();
        o.setprecision(particlePrecision).fixed();
        corrected(o, correctedEvent);
        o.flags(f);
        o.setprecision(4);
        jets(o, "# original hard jets", p.hardJets());
        jets(o, "# unsubtracted full jets", p.fullJets());
        jets(o, "# subtracted full jets", correctedJets);
    }

    static void eventWide(BufferedReader in, Cout o, String[] args) throws Exception {
        final double maxEta = 4;
        final GridMedianBackgroundEstimator bge = new GridMedianBackgroundEstimator(maxEta, 0.5);
        final ConstituentSubtractor subtractor = new ConstituentSubtractor();
        subtractor.setDistanceType(ConstituentSubtractor.Distance.deltaR);
        subtractor.setMaxDistance(0.3);
        subtractor.setAlpha(1);
        subtractor.setGhostArea(0.01);
        subtractor.setMaxEta(maxEta);
        subtractor.setBackgroundEstimator(bge);
        subtractor.setParticleSelector(Selector.ptMax(15));
        subtractor.initialize();
        o.p(subtractor.description()).endl();
        final Events.WithCharged ev = Events.readWithCharged(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final Prepared p = prepare(o, ev, maxEta);
        bge.setParticles(p.full());
        finish(o, p, subtractor.subtractEvent(p.full()), 4);
    }

    static void iterative(BufferedReader in, Cout o, String[] args) throws Exception {
        final double maxEta = 4;
        final GridMedianBackgroundEstimator bge = new GridMedianBackgroundEstimator(maxEta, 0.5);
        final IterativeConstituentSubtractor subtractor = new IterativeConstituentSubtractor();
        subtractor.setDistanceType(ConstituentSubtractor.Distance.deltaR);
        subtractor.setParameters(List.of(0.15, 0.2), List.of(1.0, 1.0));
        subtractor.setGhostRemoval(true);
        subtractor.setGhostArea(0.004);
        subtractor.setMaxEta(maxEta);
        subtractor.setBackgroundEstimator(bge);
        subtractor.setParticleSelector(Selector.ptMax(15));
        subtractor.initialize();
        o.p(subtractor.description()).endl();
        final Events.WithCharged ev = Events.readWithCharged(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final Prepared p = prepare(o, ev, maxEta);
        bge.setParticles(p.full());
        finish(o, p, subtractor.subtractEvent(p.full()), 4);
    }

    static void backgroundRescaling(BufferedReader in, Cout o, String[] args) throws Exception {
        final RescalingClasses.BackgroundRescalingYPhiUsingVectorForY rescaling =
            new RescalingClasses.BackgroundRescalingYPhiUsingVectorForY(0.1, 0.1, 0.001, 0,
                List.of(5.0, 6.0, 5.5, 5.0), List.of(-4.0, -2.0, 0.0, 2.0, 4.0), true);
        rescaling.useRapTerm(true);
        final double maxEta = 4;
        final GridMedianBackgroundEstimator bge = new GridMedianBackgroundEstimator(maxEta, 0.5);
        final IterativeConstituentSubtractor subtractor = new IterativeConstituentSubtractor();
        subtractor.setDistanceType(ConstituentSubtractor.Distance.deltaR);
        subtractor.setParameters(List.of(0.1, 0.2), List.of(0.0, 0.0));
        subtractor.setGhostRemoval(true);
        subtractor.setGhostArea(0.004);
        subtractor.setMaxEta(maxEta);
        subtractor.setRemoveParticlesWithZeroPtAndMass(false);
        subtractor.setBackgroundEstimator(bge);
        subtractor.initialize();
        final Events.WithCharged ev = Events.readWithCharged(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final Prepared p = prepare(o, ev, maxEta);
        bge.setRescalingClass(rescaling);
        bge.setParticles(p.full());
        o.p(subtractor.description()).endl();
        finish(o, p, subtractor.subtractEvent(p.full()), 5);
    }

    static void charged(BufferedReader in, Cout o, String[] args) throws Exception {
        final ConstituentSubtractor subtractor = new ConstituentSubtractor();
        subtractor.setDistanceType(ConstituentSubtractor.Distance.deltaR);
        subtractor.setMaxDistance(0.3);
        subtractor.setAlpha(1);
        subtractor.setGhostArea(0.01);
        subtractor.setDoMassSubtraction();
        subtractor.setRemoveParticlesWithZeroPtAndMass(false);
        subtractor.setGridSizeBackgroundEstimator(0.6);
        o.p(subtractor.description()).endl();
        final Events.WithCharged ev = Events.readWithCharged(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final double maxEta = 4;
        final List<PseudoJet> hard = Selector.absEtaMax(maxEta).apply(ev.hard());
        final List<PseudoJet> full = Selector.absEtaMax(maxEta).apply(ev.full());
        final List<PseudoJet> hardCharged = Selector.absEtaMax(maxEta).apply(ev.hardCharged());
        final List<PseudoJet> bgCharged = Selector.absEtaMax(maxEta).apply(ev.pileupCharged());
        o.p("# read an event with ").p(hard.size()).p(" signal particles, ").p(full.size() - hard.size())
            .p(" background particles, ").p(hardCharged.size()).p(" signal charged particles, and ").p(bgCharged.size())
            .p(" background charged particles").p(" with pseudo-rapidity |eta|<4").endl();
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.7);
        final Selector selJets = Selector.nHardest(3).times(Selector.absEtaMax(3));
        final List<PseudoJet> hardJets = selJets.apply(new ClusterSequence(hard, jetDef).inclusiveJets());
        final List<PseudoJet> fullJets = selJets.apply(new ClusterSequence(full, jetDef).inclusiveJets());
        final List<PseudoJet> corrected = subtractor.subtractEventUsingChargedInfo(full, 1.0, bgCharged, 1.0, hardCharged, maxEta);
        final Object[] f = o.flags();
        o.setprecision(5).fixed();
        corrected(o, corrected);
        final List<PseudoJet> correctedJets = selJets.apply(new ClusterSequence(corrected, jetDef).inclusiveJets());
        o.flags(f);
        o.setprecision(6);
        jets(o, "Original hard jets", hardJets);
        jets(o, "Unsubtracted full jets", fullJets);
        jets(o, "Subtracted full jets", correctedJets);
    }
}
