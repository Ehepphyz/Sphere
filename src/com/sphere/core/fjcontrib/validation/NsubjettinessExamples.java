package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.AntiKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.Comb_GenET_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.Comb_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.Comb_WTA_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.GenET_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.HalfKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.Manual_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.MultiPass_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.MultiPass_Manual_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_AntiKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_GenET_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_HalfKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_Manual_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_WTA_CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_WTA_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_WTA_HalfKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_WTA_KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.WTA_CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.WTA_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.WTA_HalfKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.WTA_KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.ConicalGeometricMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.ConicalMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.DefaultMeasureType;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.ModifiedGeometricMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.NormalizedMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.OriginalGeometricMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.UnnormalizedCutoffMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.UnnormalizedMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.XConeMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.NjettinessExtras;
import com.sphere.core.fjcontrib.nsubjettiness.NjettinessPlugin;
import com.sphere.core.fjcontrib.nsubjettiness.Njettiness;
import com.sphere.core.fjcontrib.nsubjettiness.Nsubjettiness;
import com.sphere.core.fjcontrib.nsubjettiness.NsubjettinessRatio;
import com.sphere.core.fjcontrib.nsubjettiness.TauComponents;
import com.sphere.core.fjcontrib.nsubjettiness.XConePlugin;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of Nsubjettiness 2.3.2 that "make check" runs. */
final class NsubjettinessExamples {

    private static final String C = "Nsubjettiness";
    private static final String RULE85 = "-------------------------------------------------------------------------------------";
    private static final String DASH85 = "- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -";
    private static final String HAT85 = "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^";
    private static final String RULE95 = "-----------------------------------------------------------------------------------------------";
    private static final String DASH95 = "- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -";
    private static final String HAT95 = "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^";

    private NsubjettinessExamples() {
    }

    static List<Example> all() {
        final List<Example> l = new ArrayList<>();
        l.add(Example.of(C, "example_basic_usage", "single-event.dat", NsubjettinessExamples::basic));
        l.add(Example.of(C, "example_advanced_usage", "single-event.dat", NsubjettinessExamples::advanced));
        l.add(Example.of(C, "example_v1p0p3", "single-event.dat", NsubjettinessExamples::v1p0p3));
        return l;
    }

    /** std::max, which keeps its first argument unless it is smaller (so -0.0 stays -0.0). */
    static double cmax(double a, double b) {
        return a < b ? b : a;
    }

    private static List<PseudoJet> antikt1(List<PseudoJet> event) {
        final JetDefinition def = new JetDefinition(JetAlgorithm.ANTIKT, 1.0, RecombinationScheme.E_SCHEME, Strategy.BEST);
        return PseudoJet.sortedByPt(new ClusterSequence(event, def).inclusiveJets());
    }

    private static void kinematics(Cout o, PseudoJet j) {
        o.setprecision(4).setw(10).p(j.rap()).setprecision(4).setw(10).p(j.phi()).setprecision(4).setw(11).p(j.perp())
            .setprecision(4).setw(11).p(cmax(j.m(), 0.0)).setprecision(4).setw(11).p(j.e());
    }

    private static void header(Cout o, String comment, boolean constit, String tauName, int tauWidth, boolean area) {
        o.fixed().right();
        o.p(comment).setw(5).p("jet #").p("   ").setw(10).p("rap").setw(10).p("phi").setw(11).p("pt").setw(11).p("m")
            .setw(11).p("e");
        if (constit) o.setw(11).p("constit");
        if (tauName != null) o.setw(tauWidth).p(tauName);
        if (area) o.setw(10).p("area");
        o.endl();
    }

    /* ------------------------------------------------------------------ */
    /* example_basic_usage                                                 */
    /* ------------------------------------------------------------------ */

    static void basic(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> antiktJets = antikt1(event);
        for (int j = 0; j < 2; j++) {
            final PseudoJet jet = antiktJets.get(j);
            if (jet.perp() < 200.0) continue;
            o.p(RULE85).endl().p("Analyzing Jet ").p(j + 1).p(":").endl().p(RULE85).endl();
            o.p(RULE85).endl().p("N-subjettiness with Unnormalized Measure (in GeV)").endl()
                .p("beta = 1.0:  Winner-Take-All kT Axes").endl().p("beta = 2.0:  E-Scheme Half-kT Axes").endl().p(RULE85).endl();
            o.setprecision(6).right().fixed();
            o.p(RULE85).endl();
            o.setw(15).p("beta").setw(14).p("tau1").setw(14).p("tau2").setw(14).p("tau3").setw(14).p("tau2/tau1")
                .setw(14).p("tau3/tau2").endl();
            final Nsubjettiness[] b1 = new Nsubjettiness[4];
            final Nsubjettiness[] b2 = new Nsubjettiness[4];
            for (int n = 1; n <= 3; n++) {
                b1[n] = new Nsubjettiness(n, new WTA_KT_Axes(), new UnnormalizedMeasure(1.0));
                b2[n] = new Nsubjettiness(n, new HalfKT_Axes(), new UnnormalizedMeasure(2.0));
            }
            final double t11 = b1[1].result(jet), t21 = b1[2].result(jet), t31 = b1[3].result(jet);
            final double r211 = new NsubjettinessRatio(2, 1, new WTA_KT_Axes(), new UnnormalizedMeasure(1.0)).result(jet);
            final double r321 = new NsubjettinessRatio(3, 2, new WTA_KT_Axes(), new UnnormalizedMeasure(1.0)).result(jet);
            o.setw(15).p(1.0).setw(14).p(t11).setw(14).p(t21).setw(14).p(t31).setw(14).p(r211).setw(14).p(r321).endl();
            final double t12 = b2[1].result(jet), t22 = b2[2].result(jet), t32 = b2[3].result(jet);
            final double r212 = new NsubjettinessRatio(2, 1, new HalfKT_Axes(), new UnnormalizedMeasure(2.0)).result(jet);
            final double r322 = new NsubjettinessRatio(3, 2, new HalfKT_Axes(), new UnnormalizedMeasure(2.0)).result(jet);
            o.setw(15).p(2.0).setw(14).p(t12).setw(14).p(t22).setw(14).p(t32).setw(14).p(r212).setw(14).p(r322).endl();
            for (int beta = 1; beta <= 2; beta++) {
                final Nsubjettiness[] b = beta == 1 ? b1 : b2;
                o.p(HAT85).endl().p("Subjets found using beta = ").p(beta == 1 ? "1.0" : "2.0").p(" tau values").endl();
                for (int n = 1; n <= 3; n++) {
                    basicPrintJets(o, b[n].currentSubjets(), b[n].currentTauComponents(), true);
                    o.p(n < 3 ? DASH85 : RULE85).endl();
                }
                o.p(HAT85).endl().p("Axes used for above beta = ").p(beta == 1 ? "1.0" : "2.0").p(" tau values").endl();
                for (int n = 1; n <= 3; n++) {
                    basicPrintJets(o, b[n].currentAxes(), b[n].currentTauComponents(), false);
                    o.p(n < 3 ? DASH85 : RULE85).endl();
                }
            }
        }
        o.p(RULE85).endl().p("Using the XCone Jet Algorithm").endl().p(RULE85).endl();
        final double r = 0.5;
        for (int pass = 0; pass < 2; pass++) {
            final double beta = pass == 0 ? 1.0 : 2.0;
            final List<List<PseudoJet>> jets = new ArrayList<>();
            final List<List<PseudoJet>> axes = new ArrayList<>();
            for (int n = 2; n <= 4; n++) {
                final ClusterSequence cs = new ClusterSequence(event,
                    new JetDefinition(pass == 0 ? new XConePlugin(n, r, beta) : new XConePlugin(n, r)));
                jets.add(cs.inclusiveJets());
                axes.add(NjettinessExtras.of(cs).axes());
            }
            o.p(RULE85).endl().p("Using beta = ").setprecision(2).p(beta).p(", R = ").setprecision(2).p(r).endl().p(RULE85).endl();
            for (int k = 0; k < 3; k++) {
                printXConeJets(o, jets.get(k), 14);
                if (k < 2) o.p(DASH85).endl();
            }
            o.p(HAT85).endl().p("Axes Used for Above Jets").endl();
            for (int k = 0; k < 3; k++) {
                printXConeAxes(o, axes.get(k), 14);
                if (k < 2) o.p(DASH85).endl();
            }
        }
        o.p(RULE85).endl().p("Done Using the XCone Jet Algorithm").endl().p(RULE85).endl();
    }

    private static void basicPrintJets(Cout o, List<PseudoJet> jets, TauComponents components, boolean showTotal) {
        if (jets.isEmpty()) return;
        final double[] subTaus = components.jetPieces();
        final double totalTau = components.tau();
        final boolean useArea = jets.get(0).hasArea();
        final boolean constit = jets.get(0).hasConstituents();
        header(o, "", constit, "tau" + jets.size(), 13, useArea);
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            o.setw(5).p(i + 1).p("   ");
            kinematics(o, j);
            if (j.hasConstituents()) o.setprecision(4).setw(11).p(j.constituents().size());
            o.setprecision(6).setw(13).p(cmax(subTaus[i], 0.0));
            if (useArea) o.setprecision(4).setw(10).p(j.hasArea() ? j.area() : 0.0);
            o.endl();
        }
        if (showTotal) {
            final PseudoJet total = PseudoJet.join(jets);
            o.setw(5).p("total").p("   ");
            kinematics(o, total);
            if (constit) o.setprecision(4).setw(11).p(total.constituents().size());
            o.setprecision(6).setw(13).p(totalTau);
            if (useArea) o.setprecision(4).setw(10).p(total.hasArea() ? total.area() : 0.0);
            o.endl();
        }
    }

    /** PrintXConeJets of the basic example, PrintJets of the advanced one (tau column 14 wide). */
    private static void printXConeJets(Cout o, List<PseudoJet> jets, int tauWidth) {
        if (jets.isEmpty()) return;
        final NjettinessExtras extras = NjettinessExtras.of(jets.get(0));
        final boolean useExtras = extras != null;
        final boolean useArea = jets.get(0).hasArea();
        final boolean useConstit = jets.get(0).hasConstituents();
        header(o, "", useConstit, useExtras ? "tau" + jets.size() : null, tauWidth, useArea);
        final PseudoJet total = new PseudoJet(0, 0, 0, 0);
        int totalConstit = 0;
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            o.setw(5).p(i + 1).p("   ");
            kinematics(o, j);
            if (useConstit) o.setprecision(4).setw(11).p(j.constituents().size());
            if (useExtras) o.setprecision(6).setw(14).p(cmax(extras.subTau(j), 0.0));
            if (useArea) o.setprecision(4).setw(10).p(j.hasArea() ? j.area() : 0.0);
            o.endl();
            total.plusEqual(j);
            if (useConstit) totalConstit += j.constituents().size();
        }
        if (useExtras) {
            final double beamTau = extras.beamTau();
            if (beamTau > 0.0) {
                o.setw(5).p(" beam").p("   ").setw(10).p("").setw(10).p("").setw(11).p("").setw(11).p("").setw(11).p("")
                    .setw(11).p("").setw(14).setprecision(6).p(beamTau).endl();
            }
            o.setw(5).p("total").p("   ");
            kinematics(o, total);
            if (useConstit) o.setprecision(4).setw(11).p(totalConstit);
            o.setprecision(6).setw(14).p(extras.totalTau());
            if (useArea) o.setprecision(4).setw(10).p(total.hasArea() ? total.area() : 0.0);
            o.endl();
        }
    }

    private static void printXConeAxes(Cout o, List<PseudoJet> jets, int tauWidth) {
        if (jets.isEmpty()) return;
        final NjettinessExtras extras = NjettinessExtras.of(jets.get(0));
        final boolean useExtras = extras != null;
        final boolean useArea = jets.get(0).hasArea();
        header(o, "", false, useExtras ? "tau" + jets.size() : null, tauWidth, useArea);
        final PseudoJet total = new PseudoJet(0, 0, 0, 0);
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            o.setw(5).p(i + 1).p("   ");
            kinematics(o, j);
            if (useExtras) o.setprecision(6).setw(14).p(cmax(extras.subTau(j), 0.0));
            if (useArea) o.setprecision(4).setw(10).p(j.hasArea() ? j.area() : 0.0);
            o.endl();
            total.plusEqual(j);
        }
        if (useExtras) {
            final double beamTau = extras.beamTau();
            if (beamTau > 0.0) {
                o.setw(5).p(" beam").p("   ").setw(10).p("").setw(10).p("").setw(11).p("").setw(11).p("").setw(11).p("")
                    .setw(14).setprecision(6).p(beamTau).endl();
            }
            o.setw(5).p("total").p("   ");
            kinematics(o, total);
            o.setprecision(6).setw(14).p(extras.totalTau());
            if (useArea) o.setprecision(4).setw(10).p(total.hasArea() ? total.area() : 0.0);
            o.endl();
        }
    }

    /* ------------------------------------------------------------------ */
    /* example_advanced_usage                                              */
    /* ------------------------------------------------------------------ */

    static void advanced(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final double p = 0.5, delta = 10.0, r0 = 0.2, rcutoff = 0.5;
        final double infinity = Integer.MAX_VALUE;
        final int nExtra = 2, npass = 10;
        final List<AxesDefinition> testAxes = new ArrayList<>();
        testAxes.add(new HalfKT_Axes());
        testAxes.add(new KT_Axes());
        testAxes.add(new CA_Axes());
        testAxes.add(new AntiKT_Axes(r0));
        testAxes.add(new WTA_HalfKT_Axes());
        testAxes.add(new WTA_KT_Axes());
        testAxes.add(new WTA_CA_Axes());
        testAxes.add(new GenKT_Axes(p, r0));
        testAxes.add(new WTA_GenKT_Axes(p, r0));
        testAxes.add(new GenET_GenKT_Axes(delta, p, r0));
        testAxes.add(new OnePass_HalfKT_Axes());
        testAxes.add(new OnePass_KT_Axes());
        testAxes.add(new OnePass_AntiKT_Axes(r0));
        testAxes.add(new OnePass_WTA_HalfKT_Axes());
        testAxes.add(new OnePass_WTA_KT_Axes());
        testAxes.add(new OnePass_GenKT_Axes(p, r0));
        testAxes.add(new OnePass_WTA_GenKT_Axes(p, r0));
        testAxes.add(new OnePass_GenET_GenKT_Axes(delta, p, r0));
        testAxes.add(new Comb_GenKT_Axes(nExtra, p, r0));
        testAxes.add(new Comb_WTA_GenKT_Axes(nExtra, p, r0));
        testAxes.add(new Comb_GenET_GenKT_Axes(nExtra, delta, p, r0));
        testAxes.add(new Manual_Axes());
        testAxes.add(new OnePass_Manual_Axes());
        testAxes.add(new OnePass_CA_Axes());
        testAxes.add(new OnePass_WTA_CA_Axes());
        testAxes.add(new MultiPass_Axes(npass));
        testAxes.add(new MultiPass_Manual_Axes(npass));
        final int numUnchecked = 4;
        for (AxesDefinition a : testAxes) {
            if (a.nPass() == 1) a.setNPass(1, 10000, 0.00001, 1.0);
        }
        final List<AxesDefinition> recommended = List.of(new HalfKT_Axes(), new WTA_KT_Axes());
        final List<AxesDefinition> algorithmAxes = List.of(
            new GenET_GenKT_Axes(1.0, 1.0, rcutoff), new GenET_GenKT_Axes(infinity, 1.0, rcutoff),
            new GenET_GenKT_Axes(1.0, 0.5, rcutoff), new OnePass_GenET_GenKT_Axes(1.0, 1.0, rcutoff),
            new OnePass_GenET_GenKT_Axes(infinity, 1.0, rcutoff), new OnePass_GenET_GenKT_Axes(1.0, 0.5, rcutoff));
        final List<MeasureDefinition> measures = List.of(
            new NormalizedMeasure(1.0, 1.0, DefaultMeasureType.pt_R), new UnnormalizedMeasure(1.0, DefaultMeasureType.pt_R),
            new NormalizedMeasure(2.0, 1.0, DefaultMeasureType.pt_R), new UnnormalizedMeasure(2.0, DefaultMeasureType.pt_R));
        final List<MeasureDefinition> cutoffMeasures = List.of(
            new UnnormalizedCutoffMeasure(1.0, rcutoff, DefaultMeasureType.pt_R),
            new UnnormalizedCutoffMeasure(2.0, rcutoff, DefaultMeasureType.pt_R),
            new ConicalMeasure(1.0, rcutoff), new ConicalMeasure(2.0, rcutoff),
            new OriginalGeometricMeasure(rcutoff), new ModifiedGeometricMeasure(rcutoff),
            new ConicalGeometricMeasure(1.0, 1.0, rcutoff), new ConicalGeometricMeasure(2.0, 1.0, rcutoff),
            new XConeMeasure(1.0, rcutoff), new XConeMeasure(2.0, rcutoff));

        final List<PseudoJet> antiktJets = antikt1(event);
        for (int j = 0; j < 2; j++) {
            if (antiktJets.get(j).perp() < 200) continue;
            o.p(RULE95).endl().p("Analyzing Jet ").p(j + 1).p(":").endl().p(RULE95).endl();
            o.p(RULE95).endl().p("Outputting N-subjettiness Values").endl().p(RULE95).endl();
            o.setprecision(6).right().fixed();
            for (MeasureDefinition measure : measures) {
                o.p(RULE95).endl().p(measure.description()).p(":").endl();
                o.setw(25).p("AxisMode").setw(14).p("tau1").setw(14).p("tau2").setw(14).p("tau3").setw(14).p("tau2/tau1")
                    .setw(14).p("tau3/tau2").endl();
                for (int iA = 0; iA < testAxes.size(); iA++) {
                    final PseudoJet myJet = antiktJets.get(j);
                    final List<PseudoJet> particles = myJet.constituents();
                    final AxesDefinition axesDef = testAxes.get(iA).create();
                    final Nsubjettiness n1 = new Nsubjettiness(1, axesDef, measure);
                    final Nsubjettiness n2 = new Nsubjettiness(2, axesDef, measure);
                    final Nsubjettiness n3 = new Nsubjettiness(3, axesDef, measure);
                    if (axesDef.needsManualAxes()) {
                        final ClusterSequence manual = new ClusterSequence(particles,
                            new JetDefinition(JetAlgorithm.KT, JetDefinition.MAX_ALLOWABLE_R, RecombinationScheme.E_SCHEME, Strategy.BEST));
                        n1.setAxes(manual.exclusiveJets(1));
                        n2.setAxes(manual.exclusiveJets(2));
                        n3.setAxes(manual.exclusiveJets(3));
                    }
                    final double tau1 = n1.result(myJet);
                    final double tau2 = n2.result(myJet);
                    final double tau3 = n3.result(myJet);
                    final double tau21;
                    final double tau32;
                    if (!axesDef.needsManualAxes() && !axesDef.givesRandomizedResults()) {
                        tau21 = new NsubjettinessRatio(2, 1, axesDef, measure).result(myJet);
                        tau32 = new NsubjettinessRatio(3, 2, axesDef, measure).result(myJet);
                    } else {
                        tau21 = tau2 / tau1;
                        tau32 = tau3 / tau2;
                    }
                    final String hashtag = axesDef.givesRandomizedResults() || iA >= testAxes.size() - numUnchecked ? "#" : " ";
                    o.right().p(hashtag).setw(23).p(axesDef.shortDescription()).p(":").setw(14).p(tau1).setw(14).p(tau2)
                        .setw(14).p(tau3).setw(14).p(tau21).setw(14).p(tau32).endl();
                }
            }
            o.p(RULE95).endl().p("Done Outputting N-subjettiness Values").endl().p(RULE95).endl();
            o.p(RULE95).endl().p("Outputting N-subjettiness Subjets").endl().p(RULE95).endl();
            o.setprecision(6).left().fixed();
            for (MeasureDefinition measure : measures) {
                for (AxesDefinition axesDef : recommended) {
                    if (axesDef.givesRandomizedResults()) continue;
                    final PseudoJet myJet = antiktJets.get(j);
                    final TauComponents c1 = new Nsubjettiness(1, axesDef, measure).componentResult(myJet);
                    final TauComponents c2 = new Nsubjettiness(2, axesDef, measure).componentResult(myJet);
                    final TauComponents c3 = new Nsubjettiness(3, axesDef, measure).componentResult(myJet);
                    o.p(RULE95).endl().p(measure.description()).p(":").endl().p(axesDef.description()).p(":").endl();
                    final String comment = axesDef.givesRandomizedResults() ? "#" : "";
                    printJetsWithComponents(o, c1.jets(), comment);
                    o.p(DASH95).endl();
                    printJetsWithComponents(o, c2.jets(), comment);
                    o.p(DASH95).endl();
                    printJetsWithComponents(o, c3.jets(), comment);
                    o.p(HAT95).endl().p("Axes Used for Above Subjets").endl();
                    printAxes95(o, c1.axes(), comment);
                    o.p(DASH95).endl();
                    printAxes95(o, c2.axes(), comment);
                    o.p(DASH95).endl();
                    printAxes95(o, c3.axes(), comment);
                }
            }
            o.p(RULE95).endl().p("Done Outputting N-subjettiness Subjets").endl().p(RULE95).endl();
        }
        o.p(RULE95).endl().p("Using the XCone Jet Algorithm").endl().p(RULE95).endl();
        for (double beta : new double[]{1.0, 2.0}) {
            final List<List<PseudoJet>> jets = new ArrayList<>();
            final List<List<PseudoJet>> axes = new ArrayList<>();
            for (int n = 2; n <= 4; n++) {
                final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(new XConePlugin(n, rcutoff, beta)));
                axes.add(NjettinessExtras.of(cs).axes());
                jets.add(cs.inclusiveJets());
            }
            o.p(RULE95).endl().p("Using beta = ").setprecision(2).p(beta).p(", Rcut = ").setprecision(2).p(rcutoff).endl()
                .p(RULE95).endl();
            for (int k = 0; k < 3; k++) {
                printXConeJets(o, jets.get(k), 14);
                if (k < 2) o.p(DASH95).endl();
            }
            o.p(HAT95).endl().p("Axes Used for Above Jets").endl();
            for (int k = 0; k < 3; k++) {
                printXConeAxes(o, axes.get(k), 14);
                if (k < 2) o.p(DASH95).endl();
            }
        }
        o.p(RULE95).endl().p("Done Using the XCone Jet Algorithm").endl().p(RULE95).endl();
        o.p(RULE95).endl().p("Using N-jettiness as a Jet Algorithm").endl().p(RULE95).endl();
        for (MeasureDefinition measure : cutoffMeasures) {
            for (AxesDefinition axesDef : algorithmAxes) {
                final List<List<PseudoJet>> jets = new ArrayList<>();
                final List<List<PseudoJet>> axes = new ArrayList<>();
                for (int n = 2; n <= 4; n++) {
                    final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(new NjettinessPlugin(n, axesDef, measure)));
                    axes.add(NjettinessExtras.of(cs).axes());
                    jets.add(cs.inclusiveJets());
                }
                o.p(RULE95).endl().p(measure.description()).p(":").endl().p(axesDef.description()).p(":").endl();
                for (int k = 0; k < 3; k++) {
                    printXConeJets(o, jets.get(k), 14);
                    if (k < 2) o.p(DASH95).endl();
                }
                o.p(HAT95).endl().p("Axes Used for Above Jets").endl();
                for (int k = 0; k < 3; k++) {
                    printXConeAxes(o, axes.get(k), 14);
                    if (k < 2) o.p(DASH95).endl();
                }
            }
        }
        o.p(RULE95).endl().p("Done Using N-jettiness as a Jet Algorithm").endl().p(RULE95).endl();
    }

    private static void printJetsWithComponents(Cout o, List<PseudoJet> jets, String comment) {
        final boolean useArea = jets.get(0).hasArea();
        final boolean constit = jets.get(0).hasConstituents();
        header(o, comment, constit, "tau" + jets.size(), 14, useArea);
        final PseudoJet total = new PseudoJet(0, 0, 0, 0);
        double totalTau = 0;
        int totalConstit = 0;
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            final double thisTau = ((TauComponents.StructureType) j.structure()).tau();
            o.p(comment).setw(5).p(i + 1).p("   ");
            kinematics(o, j);
            if (j.hasConstituents()) o.setprecision(4).setw(11).p(j.constituents().size());
            o.setprecision(6).setw(14).p(cmax(thisTau, 0.0));
            if (useArea) o.setprecision(4).setw(10).p(j.hasArea() ? j.area() : 0.0);
            o.endl();
            total.plusEqual(j);
            totalTau += thisTau;
            if (j.hasConstituents()) totalConstit += j.constituents().size();
        }
        o.p(comment).setw(5).p("total").p("   ");
        kinematics(o, total);
        if (constit) o.setprecision(4).setw(11).p(totalConstit);
        o.setprecision(6).setw(14).p(totalTau);
        if (useArea) o.setprecision(4).setw(10).p(total.hasArea() ? total.area() : 0.0);
        o.endl();
    }

    private static void printAxes95(Cout o, List<PseudoJet> jets, String comment) {
        if (jets.isEmpty()) return;
        final boolean useArea = jets.get(0).hasArea();
        header(o, comment, false, null, 14, useArea);
        for (int i = 0; i < jets.size(); i++) {
            o.p(comment).setw(5).p(i + 1).p("   ");
            kinematics(o, jets.get(i));
            if (useArea) o.setprecision(4).setw(10).p(jets.get(i).hasArea() ? jets.get(i).area() : 0.0);
            o.endl();
        }
    }

    /* ------------------------------------------------------------------ */
    /* example_v1p0p3                                                      */
    /* ------------------------------------------------------------------ */

    static void v1p0p3(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> antiktJets = antikt1(event);
        final double beta = 1.0, r0 = 1.0, rcut = 1.0;
        for (int j = 0; j < 2; j++) {
            final PseudoJet jet = antiktJets.get(j);
            if (!(jet.perp() > 200)) continue;
            final double[] tau = new double[4];
            final double[] tauOnePass = new double[4];
            for (int n = 1; n <= 3; n++) {
                tau[n] = new Nsubjettiness(n, Njettiness.AxesMode.kt_axes, beta, r0, rcut).result(jet);
                tauOnePass[n] = new Nsubjettiness(n, Njettiness.AxesMode.onepass_kt_axes, beta, r0, rcut).result(jet);
            }
            final List<List<PseudoJet>> kt = new ArrayList<>();
            final List<List<PseudoJet>> onepass = new ArrayList<>();
            for (int n = 1; n <= 3; n++) {
                kt.add(new ClusterSequence(jet.constituents(),
                    new JetDefinition(new NjettinessPlugin(n, Njettiness.AxesMode.kt_axes, 1.0, 1.0, 1.0))).inclusiveJets());
            }
            for (int n = 1; n <= 3; n++) {
                onepass.add(new ClusterSequence(jet.constituents(),
                    new JetDefinition(new NjettinessPlugin(n, Njettiness.AxesMode.onepass_kt_axes, 1.0, 1.0, 1.0))).inclusiveJets());
            }
            o.printf("-------------------------------------------------------------------------------------").printf("\n");
            o.printf("-------------------------------------------------------------------------------------").printf("\n");
            o.p("Beta = ").p(beta).endl();
            o.p("kT Axes:").endl();
            for (List<PseudoJet> js : kt) v1PrintJets(o, js);
            o.p("One Pass Minimization Axes from kT").endl();
            for (List<PseudoJet> js : onepass) v1PrintJets(o, js);
            o.printf("-------------------------------------------------------------------------------------").printf("\n");
            o.p("Beta = ").p(beta).setprecision(6).endl();
            o.p("     kT: ").p("tau1: ").p(tau[1]).p("  tau2: ").p(tau[2]).p("  tau3: ").p(tau[3]).p("  tau2/tau1: ")
                .p(tau[2] / tau[1]).p("  tau3/tau2: ").p(tau[3] / tau[2]).endl();
            o.p("OnePass: ").p("tau1: ").p(tauOnePass[1]).p("  tau2: ").p(tauOnePass[2]).p("  tau3: ").p(tauOnePass[3])
                .p("  tau2/tau1: ").p(tauOnePass[2] / tauOnePass[1]).p("  tau3/tau2: ").p(tauOnePass[3] / tauOnePass[2]).endl();
            o.endl();
            o.printf("-------------------------------------------------------------------------------------").printf("\n");
            o.printf("-------------------------------------------------------------------------------------").printf("\n");
        }
    }

    private static void v1PrintJets(Cout o, List<PseudoJet> jets) {
        if (jets.isEmpty()) return;
        final NjettinessExtras extras = NjettinessExtras.of(jets.get(0));
        if (jets.get(0).hasArea()) {
            throw new IllegalStateException("no area expected here");
        }
        if (extras == null) {
            o.printf("%5s %10s %10s %10s %10s %10s\n", "jet #", "rapidity", "phi", "pt", "m", "e");
            for (int i = 0; i < jets.size(); i++) {
                final PseudoJet j = jets.get(i);
                o.printf("%5u %10.3f %10.3f %10.3f %10.3f %10.3f\n", i, j.rap(), j.phi(), j.perp(), j.m(), j.e());
            }
        } else {
            final PseudoJet total = new PseudoJet(0, 0, 0, 0);
            o.printf("%5s %10s %10s %10s %10s %10s %10s\n", "jet #", "rapidity", "phi", "pt", "m", "e", "subTau");
            for (int i = 0; i < jets.size(); i++) {
                final PseudoJet j = jets.get(i);
                o.printf("%5u %10.3f %10.3f %10.3f %10.3f %10.3f %10.6f\n", i, j.rap(), j.phi(), j.perp(), j.m(), j.e(),
                    extras.subTau(j));
                total.plusEqual(j);
            }
            o.printf("%5s %10.3f %10.3f %10.3f %10.3f %10.3f %10.6f\n", "total", total.rap(), total.phi(), total.perp(),
                total.m(), total.e(), extras.totalTau());
        }
    }
}
