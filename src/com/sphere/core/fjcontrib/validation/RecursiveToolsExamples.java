package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.BackgroundEstimatorBase;
import com.sphere.core.fastjet.tools.Filter;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.Subtractor;
import com.sphere.core.fjcontrib.recursivetools.BottomUpSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.IteratedSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.ModifiedMassDropTagger;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase.RecursionChoice;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase.StructureType;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase.SymmetryMeasure;
import com.sphere.core.fjcontrib.recursivetools.SoftDrop;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of RecursiveTools 2.0.4, ported line for line. */
@SuppressWarnings("deprecation")
final class RecursiveToolsExamples {

    private static final String C = "RecursiveTools";
    private static final double INF = Double.POSITIVE_INFINITY;

    private RecursiveToolsExamples() {
    }

    static List<Example> all() {
        final List<Example> l = new ArrayList<>();
        l.add(Example.of(C, "example_mmdt", "single-event.dat", RecursiveToolsExamples::mmdt));
        l.add(Example.of(C, "example_mmdt_sub", "Pythia-Zp2jets-lhc-pileup-1ev.dat", RecursiveToolsExamples::mmdtSub));
        l.add(Example.of(C, "example_mmdt_ee", "single-ee-event.dat", RecursiveToolsExamples::mmdtEe));
        l.add(Example.of(C, "example_recluster", "single-event.dat", RecursiveToolsExamples::recluster));
        l.add(Example.of(C, "example_softdrop", "single-event.dat", RecursiveToolsExamples::softdrop));
        l.add(Example.of(C, "example_advanced_usage", "single-event.dat", RecursiveToolsExamples::advanced));
        l.add(Example.of(C, "example_recursive_softdrop", "single-event.dat", RecursiveToolsExamples::recursiveSoftdrop));
        l.add(Example.of(C, "example_bottomup_softdrop", "single-event.dat", RecursiveToolsExamples::bottomUp));
        l.add(Example.of(C, "example_isd", "single-event.dat", RecursiveToolsExamples::isd));
        return l;
    }

    /** The operator<< of most examples. */
    static void jet(Cout o, PseudoJet j) {
        if (j.isZero()) {
            o.p(" 0 ");
        } else {
            o.p(" pt = ").p(j.pt()).p(" m = ").p(j.m()).p(" y = ").p(j.rap()).p(" phi = ").p(j.phi());
        }
    }

    private static List<PseudoJet> inclusive(List<PseudoJet> event, JetDefinition def, double ptmin) {
        return PseudoJet.sortedByPt(new ClusterSequence(event, def).inclusiveJets(ptmin));
    }

    static void mmdt(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = inclusive(event, new JetDefinition(JetAlgorithm.CAMBRIDGE, 1.0), 20.0);
        final ModifiedMassDropTagger tagger = new ModifiedMassDropTagger(0.10);
        o.p("tagger is: ").p(tagger.description()).endl();
        for (PseudoJet j : jets) {
            final PseudoJet tagged = tagger.result(j);
            o.endl();
            o.p("original jet: ");
            jet(o, j);
            o.endl().p("tagged   jet: ");
            jet(o, tagged);
            o.endl();
            if (tagged.isZero()) continue;
            final StructureType s = (StructureType) tagged.structure();
            o.p("  delta_R between subjets: ").p(s.deltaR()).endl();
            o.p("  symmetry measure(z):     ").p(s.symmetry()).endl();
            o.p("  mass drop(mu):           ").p(s.mu()).endl();
            final double rfilt = Math.min(0.3, s.deltaR() * 0.5);
            final Filter filter = new Filter(rfilt, Selector.nHardest(3));
            o.p("filtered jet: ");
            jet(o, filter.result(tagged));
            o.endl().endl();
        }
    }

    static void mmdtSub(BufferedReader in, Cout o, String[] args) throws Exception {
        final double r = 1.0, rapmax = 5.0, ghostArea = 0.01;
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.CAMBRIDGE, r);
        final AreaDefinition areaDef = new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS,
            new GhostedAreaSpec(Selector.absRapMax(rapmax), 1, ghostArea, GhostedAreaSpec.DEF_GRID_SCATTER,
                GhostedAreaSpec.DEF_PT_SCATTER, GhostedAreaSpec.DEF_MEAN_GHOST_PT));
        o.p("# ").p(jetDef.description()).endl();
        o.p("# ").p(areaDef.description()).endl();
        final GridMedianBackgroundEstimator bge = new GridMedianBackgroundEstimator(rapmax, 0.55);
        bge.setRescalingClass(new BackgroundEstimatorBase.BackgroundRescalingYPolynomial(1.1685397, 0, -0.0246807, 0, 5.94119e-05));
        final Subtractor subtractor = new Subtractor(bge);
        final Events.HardAndFull ev = Events.readHardAndFull(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        o.p("# read a hard event with ").p(ev.hard().size()).p(" particles");
        o.p(", centre-of-mass energy = ").p(Selector.identity().sum(ev.hard()).m());
        o.endl();
        o.p("# read a full event with ").p(ev.full().size()).p(" particles").endl();
        final ClusterSequenceArea csaHard = new ClusterSequenceArea(ev.hard(), jetDef, areaDef);
        final ClusterSequenceArea csaFull = new ClusterSequenceArea(ev.full(), jetDef, areaDef);
        final List<PseudoJet> hardJets = PseudoJet.sortedByRapidity(Selector.nHardest(2).apply(csaHard.inclusiveJets()));
        final List<PseudoJet> fullJets = PseudoJet.sortedByRapidity(Selector.nHardest(2).apply(csaFull.inclusiveJets()));
        bge.setParticles(ev.full());
        o.endl().p("-----------------------------------------").endl().p("No pileup, no subtraction").endl();
        mmdtAnalysis(o, hardJets, null);
        o.endl().p("-----------------------------------------").endl().p("Pileup, no subtraction").endl();
        mmdtAnalysis(o, fullJets, null);
        o.endl().p("-----------------------------------------").endl().p("Pileup, with subtraction").endl();
        mmdtAnalysis(o, fullJets, subtractor);
    }

    private static void mmdtAnalysis(Cout o, List<PseudoJet> jets, Subtractor subtractor) {
        final ModifiedMassDropTagger tagger = new ModifiedMassDropTagger(0.10);
        o.p("tagger is: ").p(tagger.description()).endl();
        tagger.setSubtractor(subtractor);
        tagger.setInputJetIsSubtracted(true);
        for (PseudoJet j0 : jets) {
            final PseudoJet j = subtractor != null ? subtractor.result(j0) : j0;
            final PseudoJet tagged = tagger.result(j);
            o.endl();
            o.p("original jet");
            jet(o, j);
            o.endl().p("tagged   jet");
            jet(o, tagged);
            o.endl();
            final StructureType s = (StructureType) tagged.validatedStructure();
            if (!tagged.isZero()) {
                o.p("  delta_R between subjets: ").p(s.deltaR()).endl();
                o.p("  symmetry measure(z):     ").p(s.symmetry()).endl();
                o.p("  mass drop(mu):           ").p(s.mu()).endl();
            }
            final double rfilt = Math.min(0.3, s.deltaR() * 0.5);
            final Filter filter = new Filter(rfilt, Selector.nHardest(3));
            filter.setSubtractor(subtractor);
            o.p("filtered jet: ");
            jet(o, filter.result(tagged));
            o.endl().endl();
        }
    }

    static void mmdtEe(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(JetAlgorithm.EE_GENKT, 1.0, 0.0));
        final List<PseudoJet> jets = PseudoJet.sortedByE(Selector.eMin(10.0).apply(cs.inclusiveJets()));
        final ModifiedMassDropTagger tagger = new ModifiedMassDropTagger(0.20, SymmetryMeasure.COS_THETA_E, INF,
            RecursionChoice.LARGER_E, null);
        o.p("tagger is: ").p(tagger.description()).endl();
        for (PseudoJet j : jets) {
            final PseudoJet tagged = tagger.result(j);
            o.endl();
            o.p("original jet: ");
            eeJet(o, j);
            o.endl().p("tagged   jet: ");
            eeJet(o, tagged);
            o.endl();
            if (tagged.isZero()) continue;
            final StructureType s = (StructureType) tagged.structure();
            o.p("  delta_R between subjets: ").p(s.deltaR()).endl();
            o.p("  symmetry measure(z):     ").p(s.symmetry()).endl();
            o.p("  mass drop(mu):           ").p(s.mu()).endl();
            o.endl();
        }
    }

    private static void eeJet(Cout o, PseudoJet j) {
        if (j.isZero()) o.p(" 0 ");
        else o.p(" E = ").p(j.pt()).p(" m = ").p(j.m());
    }

    private static void recJet(Cout o, PseudoJet j) {
        if (j.isZero()) {
            o.p(" 0 ");
        } else {
            o.p(" pt = ").p(j.pt()).p(" m = ").p(j.m()).p(" y = ").p(j.rap()).p(" phi = ").p(j.phi())
                .p(" ClusSeq = ").p(j.hasAssociatedClusterSequence() ? "yes" : "no");
        }
    }

    static void recluster(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final double r = 1.0, ptmin = 20.0, rsub = 0.3;
        o.p("--------------------------------------------------").endl();
        final JetDefinition akt = new JetDefinition(JetAlgorithm.ANTIKT, r);
        final PseudoJet jetAkt = inclusive(event, akt, ptmin).get(0);
        o.p("Starting from a jet obtained from: ").p(akt.description()).endl().p("  ");
        recJet(o, jetAkt);
        o.endl().endl();
        final com.sphere.core.fjcontrib.recursivetools.Recluster caInf =
            new com.sphere.core.fjcontrib.recursivetools.Recluster(JetAlgorithm.CAMBRIDGE, JetDefinition.MAX_ALLOWABLE_R);
        final com.sphere.core.fjcontrib.recursivetools.Recluster caSub =
            new com.sphere.core.fjcontrib.recursivetools.Recluster(JetAlgorithm.CAMBRIDGE, rsub, false);
        final com.sphere.core.fjcontrib.recursivetools.Recluster ktSub =
            new com.sphere.core.fjcontrib.recursivetools.Recluster(JetAlgorithm.KT, rsub, false);
        for (int pass = 0; pass < 2; pass++) {
            final PseudoJet start;
            if (pass == 0) {
                start = jetAkt;
            } else {
                o.p("--------------------------------------------------").endl();
                final JetDefinition ca = new JetDefinition(JetAlgorithm.CAMBRIDGE, r);
                start = inclusive(event, ca, ptmin).get(0);
                o.p("Starting from a jet obtained from: ").p(ca.description()).endl().p("  ");
                recJet(o, start);
                o.endl().endl();
            }
            PseudoJet rec = caInf.result(start);
            o.p("Reclustering with: ").p(caInf.description()).endl().p("  ");
            recJet(o, rec);
            o.endl().endl();
            for (com.sphere.core.fjcontrib.recursivetools.Recluster rc : new com.sphere.core.fjcontrib.recursivetools.Recluster[]{caSub, ktSub}) {
                rec = rc.result(start);
                o.p("Reclustering with: ").p(rc.description()).endl().p("  ");
                recJet(o, rec);
                o.endl();
                o.p("   subjets: ").endl();
                for (PseudoJet p : rec.pieces()) {
                    o.p("    ");
                    recJet(o, p);
                    o.endl();
                }
                o.endl();
            }
        }
    }

    static void softdrop(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = inclusive(event, new JetDefinition(JetAlgorithm.ANTIKT, 1.0), 20.0);
        final SoftDrop sd = new SoftDrop(2.0, 0.10);
        o.p("SoftDrop groomer is: ").p(sd.description()).endl();
        for (PseudoJet j : jets) {
            final PseudoJet sdJet = sd.result(j);
            o.endl();
            o.p("original    jet: ");
            jet(o, j);
            o.endl().p("SoftDropped jet: ");
            jet(o, sdJet);
            o.endl();
            final StructureType s = (StructureType) sdJet.structure();
            o.p("  delta_R between subjets: ").p(s.deltaR()).endl();
            o.p("  symmetry measure(z):     ").p(s.symmetry()).endl();
            o.p("  mass drop(mu):           ").p(s.mu()).endl();
        }
    }

    /** SoftDropStruct of example_advanced_usage. */
    private record SdStruct(String name, double beta, double zcut, SymmetryMeasure sym, double r0, double mu,
                            RecursionChoice rc, JetAlgorithm alg, boolean tagging, SoftDrop sd) {
        static SdStruct of(String name, double beta, double zcut, SymmetryMeasure sym, double r0, double mu,
                           RecursionChoice rc, JetAlgorithm alg, boolean tagging) {
            final SoftDrop sd = new SoftDrop(beta, zcut, sym, r0, mu, rc, null);
            if (alg != JetAlgorithm.CAMBRIDGE) {
                sd.setReclustering(true, new com.sphere.core.fastjet.tools.Recluster(alg, JetDefinition.MAX_ALLOWABLE_R,
                    com.sphere.core.fastjet.tools.Recluster.Keep.KEEP_ONLY_HARDEST));
            }
            if (tagging) sd.setTaggingMode(true);
            sd.setVerboseStructure(true);
            return new SdStruct(name, beta, zcut, sym, r0, mu, rc, alg, tagging, sd);
        }

        static SdStruct of(String name, double beta, double zcut, SymmetryMeasure sym, double r0, double mu,
                           RecursionChoice rc, JetAlgorithm alg) {
            return of(name, beta, zcut, sym, r0, mu, rc, alg, false);
        }

        String symName() {
            return switch (sym) {
                case SCALAR_Z -> "scalar_z";
                case VECTOR_Z -> "vector_z";
                case Y -> "y";
                default -> "unknown";
            };
        }

        String rcName() {
            return switch (rc) {
                case LARGER_PT -> "larger_pt";
                case LARGER_MT -> "larger_mt";
                case LARGER_M -> "larger_m";
                default -> "unknown";
            };
        }

        String reclName() {
            return switch (alg) {
                case KT -> "KT";
                case CAMBRIDGE -> "CA";
                default -> "unknown";
            };
        }
    }

    static void advanced(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = inclusive(event, new JetDefinition(JetAlgorithm.ANTIKT, 1.0), 20.0);
        final SymmetryMeasure sz = SymmetryMeasure.SCALAR_Z;
        final RecursionChoice lpt = RecursionChoice.LARGER_PT;
        final JetAlgorithm ca = JetAlgorithm.CAMBRIDGE;
        final JetAlgorithm kt = JetAlgorithm.KT;
        final List<SdStruct> v = new ArrayList<>();
        v.add(SdStruct.of("beta=2.0 zcut=.1", 2.0, 0.10, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("beta=1.0 zcut=.1", 1.0, 0.10, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("beta=0.5 zcut=.1", 0.5, 0.10, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("beta=2.0 zcut=.2", 2.0, 0.20, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("beta=1.0 zcut=.2", 1.0, 0.20, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("beta=0.5 zcut=.2", 0.5, 0.20, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("MMDT-like zcut=.1", 0.0, 0.10, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("MMDT-like zcut=.2", 0.0, 0.20, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("MMDT-like zcut=.3", 0.0, 0.30, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("MMDT-like zcut=.4", 0.0, 0.40, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("beta=-2.0 zcut=.05", -2.0, 0.05, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("beta=-1.0 zcut=.05", -1.0, 0.05, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("beta=-0.5 zcut=.05", -0.5, 0.05, sz, 1.0, INF, lpt, ca, true));
        v.add(SdStruct.of("b=.5 z=.3 R0=1.0", 0.5, 0.30, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("b=.5 z=.3 R0=0.5", 0.5, 0.30, sz, 0.5, INF, lpt, ca));
        v.add(SdStruct.of("b=.5 z=.3 R0=0.2", 0.5, 0.30, sz, 0.2, INF, lpt, ca));
        v.add(SdStruct.of("b=2 z=.4 scalar_z", 2.0, 0.4, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("b=2 z=.4 vector_z", 2.0, 0.4, SymmetryMeasure.VECTOR_Z, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("b=2 z=.4 y", 2.0, 0.4, SymmetryMeasure.Y, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("b=3 z=.2 larger_pt", 3.0, 0.20, sz, 1.0, INF, lpt, ca));
        v.add(SdStruct.of("b=3 z=.2 larger_mt", 3.0, 0.20, sz, 1.0, INF, RecursionChoice.LARGER_MT, ca));
        v.add(SdStruct.of("b=3 z=.2 larger_m", 3.0, 0.20, sz, 1.0, INF, RecursionChoice.LARGER_M, ca));
        v.add(SdStruct.of("b=2 z=.1 mu=1.0", 2.0, 0.10, sz, 1.0, 1.0, lpt, ca));
        v.add(SdStruct.of("b=2 z=.1 mu=0.8", 2.0, 0.10, sz, 1.0, 0.8, lpt, ca));
        v.add(SdStruct.of("b=2 z=.1 mu=0.5", 2.0, 0.10, sz, 1.0, 0.5, lpt, ca));
        v.add(SdStruct.of("b=2.0 z=.2 kT", 2.0, 0.20, sz, 1.0, INF, lpt, kt));
        v.add(SdStruct.of("b=1.0 z=.2 kT", 1.0, 0.20, sz, 1.0, INF, lpt, kt));
        v.add(SdStruct.of("b=0.5 z=.2 kT", 0.5, 0.20, sz, 1.0, INF, lpt, kt));
        v.add(SdStruct.of("b=2.0 z=.4 kT", 2.0, 0.40, sz, 1.0, INF, lpt, kt));
        v.add(SdStruct.of("b=1.0 z=.4 kT", 1.0, 0.40, sz, 1.0, INF, lpt, kt));
        v.add(SdStruct.of("b=0.5 z=.4 kT", 0.5, 0.40, sz, 1.0, INF, lpt, kt));

        final String rule = "---------------------------------------------------------------------------------------------";
        o.p(rule).endl().p("Soft Drops to be tested:").endl().p(rule).endl();
        o.setw(18).p("name").setw(8).p("beta").setw(8).p("z_cut").setw(9).p("sym").setw(8).p("R0").setw(8).p("mu")
            .setw(10).p("recurse").setw(8).p("reclust").setw(8).p("mode").endl();
        o.setprecision(3).fixed();
        for (SdStruct s : v) {
            o.setw(18).p(s.name()).setw(8).p(s.beta()).setw(8).p(s.zcut()).setw(9).p(s.symName()).setw(8).p(s.r0())
                .setw(8).p(s.mu()).setw(10).p(s.rcName()).setw(8).p(s.reclName()).setw(8).p(s.tagging() ? "tag" : "groom").endl();
        }
        o.p(rule).endl();
        for (int ijet = 0; ijet < jets.size(); ijet++) {
            o.p(rule).endl().p("Analyzing Jet ").p(ijet + 1).p(":").endl().p(rule).endl();
            o.setw(18).p("name").setw(10).p("pt").setw(9).p("m").setw(8).p("y").setw(8).p("phi").setw(8).p("constit")
                .setw(8).p("delta_R").setw(8).p("sym").setw(8).p("mu").setw(8).p("mxdropz").endl();
            final PseudoJet orig = jets.get(ijet);
            o.setprecision(4).fixed();
            o.setw(18).p("Original Jet").setw(10).p(orig.pt()).setw(9).p(orig.m()).setw(8).p(orig.rap()).setw(8).p(orig.phi())
                .setw(8).p(orig.constituents().size()).endl();
            for (SdStruct s : v) {
                final PseudoJet sdJet = s.sd().result(orig);
                o.setw(18).p(s.name());
                if (!sdJet.isZero()) {
                    final StructureType st = (StructureType) sdJet.structure();
                    o.setw(10).p(sdJet.pt()).setw(9).p(sdJet.m()).setw(8).p(sdJet.rap()).setw(8).p(sdJet.phi())
                        .setw(8).p(sdJet.constituents().size()).setw(8).p(st.deltaR()).setw(8).p(st.symmetry())
                        .setw(8).p(st.mu()).setw(8).p(st.maxDroppedSymmetry());
                } else {
                    o.p(" ---- untagged jet ----");
                }
                o.endl();
            }
        }
    }

    static void recursiveSoftdrop(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final double r = 1.0;
        final List<PseudoJet> jets = inclusive(event, new JetDefinition(JetAlgorithm.ANTIKT, r), 100.0);
        final RecursiveSoftDrop rsd = new RecursiveSoftDrop(0.5, 0.2, 4, r);
        rsd.setVerboseStructure(true);
        rsd.setDynamicalR0(true);
        o.p("RecursiveSoftDrop groomer is: ").p(rsd.description()).endl();
        for (PseudoJet j : jets) {
            final PseudoJet rsdJet = rsd.result(j);
            o.endl();
            o.p("original             jet: ");
            jet(o, j);
            o.endl().p("RecursiveSoftDropped jet: ");
            jet(o, rsdJet);
            o.endl();
            o.endl().p("Prongs with clustering information").endl().p("----------------------------------").endl();
            printProngs(o, rsdJet, " ");
            o.endl().p("Prongs without clustering information").endl().p("-------------------------------------").endl();
            printRawProngs(o, rsdJet);
            o.p("Groomed prongs information:").endl();
            o.p("index            zg        thetag").endl();
            final List<double[]> ztg = ((StructureType) rsdJet.structure()).sortedZgAndThetag();
            for (int i = 0; i < ztg.size(); i++) {
                o.setw(5).p(i + 1).setw(14).p(ztg.get(i)[0]).setw(14).p(ztg.get(i)[1]).endl();
            }
        }
    }

    private static void printProngs(Cout o, PseudoJet jet, String prefix) {
        if (prefix.length() == 1) {
            o.fixed().setprecision(4).p(" ").setw(14).p(" ").setw(8).p("branch").setw(14).p("branch")
                .setw(10).p("N_groomed").setw(11).p("max loc").setw(22).p("substructure").endl();
            o.p(" ").setw(14).p(" ").setw(8).p("pt").setw(14).p("mass").setw(5).p("loc").setw(5).p("tot")
                .setw(11).p("zdrop").setw(11).p("zg").setw(11).p("thetag").endl();
        }
        final StructureType s = (StructureType) jet.validatedStructure();
        final double dR = s.deltaR();
        o.p(" ").left().setw(14).p(prefix.substring(0, prefix.length() - 1) + "+--> ").right()
            .setw(8).p(jet.pt()).setw(14).p(jet.m()).setw(5).p(s.droppedCount(false)).setw(5).p(s.droppedCount())
            .setw(11).p(s.maxDroppedSymmetry(false));
        if (s.hasSubstructure()) {
            o.setw(11).p(s.symmetry()).setw(11).p(s.deltaR());
        }
        o.endl();
        if (dR >= 0) {
            final List<PseudoJet> pieces = jet.pieces();
            printProngs(o, pieces.get(0), prefix + " |");
            printProngs(o, pieces.get(1), prefix + "  ");
        }
    }

    private static void printRawProngs(Cout o, PseudoJet jet) {
        o.p("(Raw) list of prongs:").endl();
        if (!(jet.structure() instanceof StructureType)) {
            o.p("  None (bad structure)").endl();
            return;
        }
        o.setw(5).p(" ").setw(11).p("pt").setw(14).p("mass").endl();
        final List<PseudoJet> prongs = RecursiveSoftDrop.recursiveSoftDropProngs(jet);
        for (int i = 0; i < prongs.size(); i++) {
            final PseudoJet prong = prongs.get(i);
            o.fixed().setw(5).p(i).setw(11).p(prong.pt()).setw(14).p(prong.m()).endl();
        }
        o.endl();
    }

    static void bottomUp(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = inclusive(event, new JetDefinition(JetAlgorithm.ANTIKT, 1.0), 100.0);
        final BottomUpSoftDrop busd = new BottomUpSoftDrop(1.0, 0.2);
        o.p("BottomUpSoftDrop groomer is: ").p(busd.description()).endl();
        for (PseudoJet j : jets) {
            final PseudoJet g = busd.result(j);
            o.endl();
            o.p("original            jet: ");
            jet(o, j);
            o.endl().p("BottomUpSoftDropped jet: ");
            jet(o, g);
            o.endl();
        }
    }

    static void isd(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final double r = 0.5;
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(JetAlgorithm.ANTIKT, r));
        final List<PseudoJet> jets = Selector.ptMin(200.0).apply(PseudoJet.sortedByPt(cs.inclusiveJets()));
        final double zcut = 1.0 / 1000 / r;
        final double beta = -1.0;
        final IteratedSoftDrop isd = new IteratedSoftDrop(beta, zcut, 0.0, r);
        final IteratedSoftDrop isdEe = new IteratedSoftDrop(beta, zcut, RecursiveSymmetryCutBase.SymmetryMeasure.THETA_E,
            0.0, r, INF, RecursionChoice.LARGER_E, null);
        final String rule = "---------------------------------------------------";
        o.p(rule).endl().p("Iterated Soft Drop").endl().p(rule).endl().endl();
        o.p("Computing with:").endl();
        o.p("     ").p(isd.description()).endl();
        o.p("     ").p(isdEe.description()).endl();
        o.endl();
        for (int ijet = 0; ijet < jets.size(); ijet++) {
            o.p(rule).endl().p("Processing Jet ").p(ijet).endl().p(rule).endl().endl();
            o.p("Jet pT: ").p(jets.get(ijet).pt()).p(" GeV").endl().endl();
            final IteratedSoftDrop.Info syms = isd.result(jets.get(ijet));
            o.p("Soft Drop Multiplicity (pt_R measure, beta=").p(beta).p(", z_cut=").p(zcut).p("):").endl();
            o.p(syms.multiplicity()).endl().endl();
            o.p("Symmetry Factors (pt_R measure, beta=").p(beta).p(", z_cut=").p(zcut).p("):").endl();
            for (int i = 0; i < syms.size(); i++) o.p(syms.get(i)[0]).p(" ");
            o.endl().endl();
            o.p("Soft Drop Angularities (pt_R measure, beta=").p(beta).p(", z_cut=").p(zcut).p("):").endl();
            o.p("  alpha = 0,   kappa = 0 :  ").p(syms.angularity(0.0, 0.0)).endl();
            o.p("  alpha = 0,   kappa = 2 :  ").p(syms.angularity(0.0, 2.0)).endl();
            o.p("  alpha = 0.5, kappa = 1 :  ").p(syms.angularity(0.5)).endl();
            o.p("  alpha = 1,   kappa = 1 :  ").p(syms.angularity(1.0)).endl();
            o.p("  alpha = 2,   kappa = 1 :  ").p(syms.angularity(2.0)).endl();
            o.endl();
            final IteratedSoftDrop.Info symsEe = isdEe.result(jets.get(ijet));
            o.p("Symmetry Factors (E_theta measure, beta=").p(beta).p(", z_cut=").p(zcut).p("):").endl();
            for (int i = 0; i < symsEe.size(); i++) o.p(symsEe.get(i)[0]).p(" ");
            o.endl().endl();
        }
    }
}
