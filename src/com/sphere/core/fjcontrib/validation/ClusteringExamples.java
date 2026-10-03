package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fjcontrib.clusteringveto.ClusteringVetoPlugin;
import com.sphere.core.fjcontrib.dynamicr.DynamicR;
import com.sphere.core.fjcontrib.qcdaware.DistanceMeasure;
import com.sphere.core.fjcontrib.qcdaware.QCDAwarePlugin;
import com.sphere.core.fjcontrib.signalfree.SignalFreeBackgroundEstimator;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The example programs of ClusteringVetoPlugin 1.0.0, DynamicR 1.0.2,
 * QCDAwarePlugin 2.0.0 and SignalFreeBackgroundEstimator 1.0.1.
 */
final class ClusteringExamples {

    private ClusteringExamples() {
    }

    static List<Example> all() {
        return List.of(
            Example.of("ClusteringVetoPlugin", "example", "single-event.dat", ClusteringExamples::clusteringVeto),
            Example.of("DynamicR", "example", "pythia8_Zq_vshort.dat", ClusteringExamples::dynamicR),
            Example.of("QCDAwarePlugin", "example", "single-event.dat", ClusteringExamples::qcdAware),
            Example.of("SignalFreeBackgroundEstimator", "example", "Pythia-Zp2jets-lhc-pileup-1ev.dat",
                ClusteringExamples::signalFree));
    }

    /* ------------------------------------------------------------------ */
    /* ClusteringVetoPlugin                                                */
    /* ------------------------------------------------------------------ */

    private static void printJets(Cout o, List<PseudoJet> jets, ClusterSequence cs) {
        o.printf("%5s %10s %10s %10s %10s %10s\n", "jet #", "pt", "rap", "phi", "m", "last d_ij");
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            o.printf("%5u %10.3f %10.3f %10.3f %10.3f %10.3f\n", i, j.pt(), j.rap(), j.phi(), j.m(),
                cs.exclusiveSubdmerge(j, 1));
        }
    }

    static void clusteringVeto(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final double mu = 30.;
        final double theta = 0.7;
        final double maxR = 1.0;
        final double ptmin = 5.;
        {
            final JetDefinition def = new JetDefinition(new ClusteringVetoPlugin(mu, theta, maxR,
                ClusteringVetoPlugin.ClusterType.CALIKE));
            final ClusterSequence cs = new ClusterSequence(event, def);
            o.endl().p("Run ").p(def.description()).endl();
            final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(ptmin));
            o.p("Inclusive jets with pT > ").p(ptmin).p(" GeV").endl();
            printJets(o, jets, cs);
        }
        {
            final JetDefinition kt = new JetDefinition(new ClusteringVetoPlugin(mu, theta, maxR,
                ClusteringVetoPlugin.ClusterType.KTLIKE));
            final ClusterSequence csKt = new ClusterSequence(event, kt);
            o.endl().p("Run ").p(kt.description()).endl();
            o.p("Inclusive jets with pT > ").p(ptmin).p(" GeV").endl()
                .p(" number of jets: ").p(csKt.inclusiveJets(ptmin).size()).endl();
            final JetDefinition akt = new JetDefinition(new ClusteringVetoPlugin(mu, theta, maxR,
                ClusteringVetoPlugin.ClusterType.AKTLIKE));
            final ClusterSequence csAkt = new ClusterSequence(event, akt);
            o.endl().p("Run ").p(akt.description()).endl();
            o.p("Inclusive jets with pT > ").p(ptmin).p(" GeV").endl()
                .p(" number of jets: ").p(csAkt.inclusiveJets(ptmin).size()).endl();
        }
        {
            final ClusteringVetoPlugin plugin = new ClusteringVetoPlugin(mu, theta, maxR,
                ClusteringVetoPlugin.ClusterType.CALIKE);
            plugin.setVetoFunction((j1, j2) -> {
                if (j1.deltaR(j2) < 0.3) return ClusteringVetoPlugin.VetoResult.CLUSTER;
                if (0.5 * j1.plus(j2).m() > Math.max(j1.m(), j2.m())) return ClusteringVetoPlugin.VetoResult.VETO;
                return ClusteringVetoPlugin.VetoResult.NOVETO;
            });
            final JetDefinition def = new JetDefinition(plugin);
            final ClusterSequence cs = new ClusterSequence(event, def);
            o.endl().p("Run ").p(def.description()).endl();
            final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(ptmin));
            o.p("Inclusive jets with pT > ").p(ptmin).p(" GeV").endl();
            printJets(o, jets, cs);
        }
    }

    /* ------------------------------------------------------------------ */
    /* DynamicR                                                            */
    /* ------------------------------------------------------------------ */

    private static void dynamicRTable(Cout o, DynamicR plugin, List<PseudoJet> particles, double radius, double ptmin) {
        final JetDefinition def = new JetDefinition(plugin);
        final ClusterSequence cs = new ClusterSequence(particles, def);
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(ptmin));
        o.p("Output of ").p(def.description()).p(".").endl();
        o.setw(5).right().p("jet #").setw(10).right().p("rapidity").setw(10).right().p("phi")
            .setw(15).right().p("pt (GeV)").setw(10).right().p("Rd").endl();
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            double rd = radius;
            if (j.hasUserInfo(DynamicR.JetInfo.class)) rd = j.userInfo(DynamicR.JetInfo.class).Rd();
            o.setw(5).right().p(i + 1);
            o.setw(10).right().fixed().setprecision(2).p(j.rap());
            o.setw(10).right().fixed().setprecision(2).p(j.phi());
            o.setw(15).right().fixed().setprecision(2).p(j.pt());
            o.setw(10).right().fixed().setprecision(2).p(rd).endl();
        }
        o.p("------------------------------------------------------------").endl();
        o.endl();
    }

    static void dynamicR(BufferedReader in, Cout o, String[] args) throws Exception {
        final double radius = 0.5;
        final double ptJetMin = 10.0;
        for (int iEvent = 0; iEvent < 10; iEvent++) {
            final List<PseudoJet> particles = Events.readUntilEmptyLine(in);
            o.p("############################################################").endl();
            o.p("Event # ").p(iEvent + 1).p(".").endl();
            o.p("------------------------------------------------------------").endl();
            o.p("# read an event with ").p(particles.size()).p(" particles").endl();
            o.p("------------------------------------------------------------").endl();
            if (particles.isEmpty()) continue;
            dynamicRTable(o, DynamicR.drak(radius), particles, radius, ptJetMin);
            dynamicRTable(o, DynamicR.drca(radius), particles, radius, ptJetMin);
            dynamicRTable(o, DynamicR.drkt(radius), particles, radius, ptJetMin);
        }
    }

    /* ------------------------------------------------------------------ */
    /* QCDAwarePlugin                                                      */
    /* ------------------------------------------------------------------ */

    private static int flavourLabel(int idx) {
        final int l = (idx % 10) - 3;
        return Math.abs(l) <= 3 && l != 0 ? l : 21;
    }

    static void qcdAware(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = new ArrayList<>();
        int idx = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#END")) break;
            if (line.startsWith("#")) continue;
            final double[] v = Events.numbers(line, 4);
            final PseudoJet p = Events.particle(v[0], v[1], v[2], v[3]);
            p.setUserIndex(flavourLabel(idx));
            event.add(p);
            idx++;
        }
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final QCDAwarePlugin plugin = new QCDAwarePlugin(DistanceMeasure.antikt(0.4));
        o.p("using ").p(plugin.description()).endl();
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(plugin));
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets());
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet pj = jets.get(i);
            o.p("parton jet ").p(i).p(" pt eta phi e label:").endl()
                .p(pj.pt()).p(" ").p(pj.eta()).p(" ").p(pj.phi()).p(" ").p(pj.e()).p(" ").p(pj.userIndex()).endl();
        }
    }

    /* ------------------------------------------------------------------ */
    /* SignalFreeBackgroundEstimator                                       */
    /* ------------------------------------------------------------------ */

    static void signalFree(BufferedReader in, Cout o, String[] args) throws Exception {
        final double maxEta = 4;
        final SignalFreeBackgroundEstimator bge = new SignalFreeBackgroundEstimator(maxEta, 0.55);
        bge.setSignalSeedParameters(0.3, 0.4);
        bge.setJetRhoMin(5, 8);
        bge.setJetRhoMinCharged(20);
        bge.setWindowParameters(0.5, 0.4, 0.1);

        final Events.WithCharged ev = Events.readWithCharged(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final Selector eta = Selector.absEtaMax(maxEta);
        final List<PseudoJet> hard = eta.apply(ev.hard());
        final List<PseudoJet> full = eta.apply(ev.full());
        final List<PseudoJet> hardCharged = eta.apply(ev.hardCharged());
        o.p("# read an event with ").p(hard.size()).p(" signal particles and ").p(full.size() - hard.size())
            .p(" background particles with pseudo-rapidity |eta|<4").endl();

        // the jets of the C++ example, clustered though not printed
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.7);
        final Selector selJets = Selector.nHardest(3).times(Selector.absEtaMax(3));
        selJets.apply(new ClusterSequence(hard, jetDef).inclusiveJets());
        selJets.apply(new ClusterSequence(full, jetDef).inclusiveJets());

        bge.setParticles(full, List.of(), -1, hardCharged);
        o.p("obtained rho with SignalFreeBackgroundEstimator: ").p(bge.rho()).endl();
    }
}
