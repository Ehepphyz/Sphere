package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fjcontrib.cmpplugin.CMPPlugin;
import com.sphere.core.fjcontrib.ghsalgo.GHSAlgo;
import com.sphere.core.fjcontrib.ifnplugin.FlavHistory;
import com.sphere.core.fjcontrib.ifnplugin.FlavInfo;
import com.sphere.core.fjcontrib.ifnplugin.FlavRecombiner;
import com.sphere.core.fjcontrib.ifnplugin.IFNPlugin;
import com.sphere.core.fjcontrib.internal.StdSort;
import com.sphere.core.fjcontrib.sdfplugin.SDFlavourCalc;

import java.io.BufferedReader;
import java.util.List;

/**
 * The example programs of the flavour contribs of fjcontrib 1.104:
 * IFNPlugin 1.0.4, CMPPlugin 1.0.0, GHSAlgo 1.0.0 and SDFPlugin 1.0.1,
 * each on pythia8_Zq_vshort.dat with the arguments make check gives them.
 */
final class FlavourExamples {

    private static final String DATA = "pythia8_Zq_vshort.dat";
    private static final String RULE = "\n#---------------------------------------------------------------\n";

    private FlavourExamples() {
    }

    static List<Example> all() {
        return List.of(
            Example.of("IFNPlugin", "example-IFN", DATA, FlavourExamples::ifn, "-1", "-1"),
            Example.of("CMPPlugin", "example-CMP", DATA, FlavourExamples::cmp, "-1", "-1"),
            Example.of("GHSAlgo", "example-GHS", DATA, FlavourExamples::ghs, "10", "2"),
            Example.of("SDFPlugin", "example-SDF", DATA, FlavourExamples::sdf, "-1", "-1"));
    }

    /** "pt=... rap=... phi=..., flav = [..]" */
    private static void kinematics(Cout o, PseudoJet j) {
        o.p("pt=").p(j.pt()).p(" rap=").p(j.rap()).p(" phi=").p(j.phi());
        o.p(", flav = ").p(FlavHistory.currentFlavourOf(j).description()).endl();
    }

    private static void constituents(Cout o, PseudoJet jet) {
        o.p("constituents:").endl();
        for (PseudoJet c : StdSort.sortedByPt(jet.constituents())) {
            o.p("  pt = ").setw(10).p(c.pt());
            o.p(", orig. flav = ").setw(8).p(FlavHistory.initialFlavourOf(c).description());
            o.p(", final flav = ").setw(8).p(FlavHistory.currentFlavourOf(c).description());
            o.endl();
        }
    }

    /** Only b flavour, as the CMP and GHS examples keep. */
    private static Object onlyB(int pdg) {
        return new FlavHistory(new FlavInfo(pdg).onlyFlav(5));
    }

    static void ifn(BufferedReader in, Cout o, String[] args) throws Exception {
        final long nevmax = Events.unsignedArg(args, 0, 2);
        final long njetmax = Events.unsignedArg(args, 1, 1);
        final JetDefinition base = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        base.setRecombiner(new FlavRecombiner());
        final double alpha = 2.0;
        final double omega = 3.0 - alpha;
        final JetDefinition ifnDef = new JetDefinition(new IFNPlugin(base, alpha, omega, FlavRecombiner.FlavSummation.NET));
        o.p("base jet definition: ").p(base.description()).endl();
        o.p("IFN jet definition:  ").p(ifnDef.description()).endl();
        for (int iev = 0; iev < 10 && iev < nevmax; iev++) {
            final List<PseudoJet> event = Events.readFlavourEvent(in, FlavHistory::new);
            o.p(RULE).p("# read event ").p(iev).p(" with ").p(event.size()).p(" particles").endl();
            final List<PseudoJet> baseJets = base.cluster(event);
            final List<PseudoJet> ifnJets = ifnDef.cluster(event);
            if (baseJets.size() != ifnJets.size()) throw new IllegalStateException("IFN changed the number of jets");
            for (int ijet = 0; ijet < baseJets.size() && ijet < njetmax; ijet++) {
                o.endl();
                o.p("base jet ").p(ijet).p(": ");
                kinematics(o, baseJets.get(ijet));
                o.p("IFN jet  ").p(ijet).p(": ");
                kinematics(o, ifnJets.get(ijet));
                constituents(o, ifnJets.get(ijet));
            }
        }
    }

    static void cmp(BufferedReader in, Cout o, String[] args) throws Exception {
        final long nevmax = Events.unsignedArg(args, 0, 2);
        final long njetmax = Events.unsignedArg(args, 1, 1);
        final double r = 0.4;
        final FlavRecombiner flavRecombiner = new FlavRecombiner();
        final JetDefinition base = new JetDefinition(JetAlgorithm.ANTIKT, r);
        base.setRecombiner(flavRecombiner);
        final JetDefinition cmpDef = new JetDefinition(new CMPPlugin(r, 0.1,
            CMPPlugin.CorrectionType.SQRT_COSHY_COSPHI_ARGUMENT_A2, CMPPlugin.ClusteringType.DYNAMIC_KTMAX));
        cmpDef.setRecombiner(flavRecombiner);
        o.p("! this analysis considers only b-flavour from input events !").endl();
        o.p("base jet definition: ").p(base.description()).endl();
        o.p("CMP jet definition:  ").p(cmpDef.description()).endl();
        for (int iev = 0; iev < 10 && iev < nevmax; iev++) {
            final List<PseudoJet> event = Events.readFlavourEvent(in, FlavourExamples::onlyB);
            o.p(RULE).p("# read event ").p(iev).p(" with ").p(event.size()).p(" particles").endl();
            final List<PseudoJet> baseJets = base.cluster(event);
            final List<PseudoJet> cmpJets = cmpDef.cluster(event);
            for (int ijet = 0; ijet < baseJets.size() && ijet < njetmax; ijet++) {
                o.endl();
                o.p("base jet ").p(ijet).p(": ");
                kinematics(o, baseJets.get(ijet));
                constituents(o, baseJets.get(ijet));
            }
            for (int ijet = 0; ijet < cmpJets.size() && ijet < njetmax; ijet++) {
                o.endl();
                o.p("CMP jet  ").p(ijet).p(": ");
                kinematics(o, cmpJets.get(ijet));
                constituents(o, cmpJets.get(ijet));
            }
        }
    }

    static void ghs(BufferedReader in, Cout o, String[] args) throws Exception {
        final long nevmax = Events.unsignedArg(args, 0, 2);
        final long njetmax = Events.unsignedArg(args, 1, 1);
        final FlavRecombiner flavRecombiner = new FlavRecombiner();
        final JetDefinition base = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        base.setRecombiner(flavRecombiner);
        final double alpha = 1.0;
        final double omega = 2.0;
        final double ptcut = 15.0;
        o.p("! this analysis considers only b-flavour from input events !").endl();
        o.p("base jet definition: ").p(base.description()).endl();
        o.p("GHS jet definition:  ").endl()
            .p(" alpha = ").p(alpha).endl()
            .p(" omega = ").p(omega).endl()
            .p(" (ptcut = ").p(ptcut).p(" GeV)").endl();
        for (int iev = 0; iev < 10 && iev < nevmax; iev++) {
            final List<PseudoJet> event = Events.readFlavourEvent(in, FlavourExamples::onlyB);
            o.p(RULE).p("# read event ").p(iev).p(" with ").p(event.size()).p(" particles").endl();
            final List<PseudoJet> baseJets = base.cluster(event);
            final List<PseudoJet> ghsJets = GHSAlgo.runGHS(baseJets, ptcut, alpha, omega, flavRecombiner);
            final List<PseudoJet> selected = Selector.ptMin(ptcut).apply(baseJets);
            if (selected.size() != ghsJets.size()) throw new IllegalStateException("GHS changed the number of jets");
            for (int ijet = 0; ijet < selected.size() && ijet < njetmax; ijet++) {
                o.endl();
                o.p("base jet ").p(ijet).p(": ");
                kinematics(o, selected.get(ijet));
                o.p("GHS jet  ").p(ijet).p(": ");
                kinematics(o, ghsJets.get(ijet));
                constituents(o, selected.get(ijet));
            }
        }
    }

    static void sdf(BufferedReader in, Cout o, String[] args) throws Exception {
        final long nevmax = Events.unsignedArg(args, 0, 2);
        final long njetmax = Events.unsignedArg(args, 1, 1);
        final JetDefinition base = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        base.setRecombiner(new FlavRecombiner());
        final SDFlavourCalc calc = new SDFlavourCalc();
        for (int iev = 0; iev < 10 && iev < nevmax; iev++) {
            o.p(RULE).p("# read event ").p(iev);
            final List<PseudoJet> event = Events.readFlavourEvent(in, FlavHistory::new);
            o.p(" with ").p(event.size()).p(" particles").endl();
            final List<PseudoJet> baseJets = base.cluster(event);
            o.p("now do the sd...").endl();
            final List<PseudoJet> sdJets = base.cluster(event);
            calc.apply(sdJets);
            for (int ijet = 0; ijet < baseJets.size() && ijet < njetmax; ijet++) {
                o.endl();
                o.p("base jet ").p(ijet).p(": ");
                kinematics(o, baseJets.get(ijet));
                o.p("SD flav jet  ").p(ijet).p(": ");
                kinematics(o, sdJets.get(ijet));
            }
        }
    }
}
