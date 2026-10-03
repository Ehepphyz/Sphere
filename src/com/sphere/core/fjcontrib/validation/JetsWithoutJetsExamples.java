package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.Filter;
import com.sphere.core.fjcontrib.jetswithoutjets.EventStorage;
import com.sphere.core.fjcontrib.jetswithoutjets.FunctionOfVectorOfPseudoJets;
import com.sphere.core.fjcontrib.jetswithoutjets.JetLikeEventShape;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets.EventShapeDensity_JetAxes;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets.ShapeJetMultiplicity;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets.ShapeJetMultiplicity_MultiplePtCutValues;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets.ShapeJetMultiplicity_MultipleRValues;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets.ShapeMissingPt;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets.ShapeScalarPt;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of JetsWithoutJets 1.0.0. */
final class JetsWithoutJetsExamples {

    private static final String C = "JetsWithoutJets";

    private JetsWithoutJetsExamples() {
    }

    static List<Example> all() {
        return List.of(
            Example.of(C, "example_basic_usage", "single-event.dat", JetsWithoutJetsExamples::basic),
            Example.of(C, "example_advanced_usage", "single-event.dat", JetsWithoutJetsExamples::advanced));
    }

    private static List<PseudoJet> header(BufferedReader in, Cout o) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("#########").endl();
        o.p("## Read an event with ").p(event.size()).p(" particles").endl();
        return event;
    }

    static void basic(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> input = header(in, o);
        final double rjet = 1.0;
        final double pTcut = 200.0;
        final double rsub = 0.2;
        final double fcut = 0.05;
        final ShapeJetMultiplicity nj = new ShapeJetMultiplicity(rjet, pTcut);
        final ShapeScalarPt ht = new ShapeScalarPt(rjet, pTcut);
        final ShapeMissingPt htMiss = new ShapeMissingPt(rjet, pTcut);
        o.setprecision(6);
        o.p("#########").endl().p("## Example of basic event shape analysis").endl().p("#########").endl();
        o.p("Jet parameters: R_jet=").p(rjet).p(", pTcut=").p(pTcut).endl();
        o.p("#####").endl();
        o.p("N_jet=").p(nj.result(input)).endl();
        o.p("H_T=").p(ht.result(input)).endl();
        o.p("Missing H_T=").p(htMiss.result(input)).endl();

        final Selector eventShapeTrimmer = JetsWithoutJets.selectorShapeTrimming(rjet, pTcut, rsub, fcut);
        final JetsWithoutJets.JetShapeTrimmer jetShapeTrimmer = new JetsWithoutJets.JetShapeTrimmer(rsub, fcut);
        final Filter treeTrimmer = new Filter(rsub, Selector.ptFractionMin(fcut));
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, rjet);
        final ClusterSequence cs = new ClusterSequence(input, jetDef);
        final PseudoJet hardest = PseudoJet.sortedByPt(cs.inclusiveJets(pTcut)).get(0);
        final ClusterSequence csEstrim = new ClusterSequence(eventShapeTrimmer.apply(input), jetDef);
        final PseudoJet hardestEstrim = PseudoJet.sortedByPt(csEstrim.inclusiveJets(pTcut)).get(0);
        final double untrimmedMass = hardest.m();
        final double treeTrimmedMass = treeTrimmer.result(hardest).m();
        final double jetShapeTrimmedMass = jetShapeTrimmer.result(hardest).m();
        final double eventShapeTrimmedMass = hardestEstrim.m();
        o.p("#########").endl().p("## Example of different trimming methods").endl().p("#########").endl();
        o.p("Anti-kT jet parameters: R_jet=").p(rjet).p(", pTcut=").p(pTcut).endl();
        o.p("Trimming parameters: R_sub=").p(rsub).p(", fcut=").p(fcut).endl();
        o.p("#########").endl();
        o.p("Untrimmed Mass = ").p(untrimmedMass).endl();
        o.p("Tree Trimmed Mass = ").p(treeTrimmedMass).endl();
        o.p("Jet Shape Trimmed Mass = ").p(jetShapeTrimmedMass).endl();
        o.p("Event Shape Trimmed Mass = ").p(eventShapeTrimmedMass).endl();

        final ShapeJetMultiplicity njTrim = new ShapeJetMultiplicity(rjet, pTcut, rsub, fcut);
        final ShapeScalarPt htTrim = new ShapeScalarPt(rjet, pTcut, rsub, fcut);
        final ShapeMissingPt htMissTrim = new ShapeMissingPt(rjet, pTcut, rsub, fcut);
        o.p("#########").endl().p("## Example of trimmed event shapes").endl().p("#########").endl();
        o.p("Jet parameters: R_jet=").p(rjet).p(", pTcut=").p(pTcut).endl();
        o.p("Trimming parameters: R_sub=").p(rsub).p(", fcut=").p(fcut).endl();
        o.p("#########").endl();
        o.p("N_jet_trim (using built-in)=").p(njTrim.result(input)).endl();
        o.p("N_jet_trim (using trimmer)=").p(nj.result(eventShapeTrimmer.apply(input))).endl();
        o.p("H_T_trim (using built-in)=").p(htTrim.result(input)).endl();
        o.p("H_T_trim (using trimmer)=").p(ht.result(eventShapeTrimmer.apply(input))).endl();
        o.p("Missing H_T_trim (using built-in)=").p(htMissTrim.result(input)).endl();
        o.p("Missing H_T_trim (using trimmer)=").p(htMiss.result(eventShapeTrimmer.apply(input))).endl();
        o.p("#########").endl();
    }

    private static void axis(Cout o, String title, List<PseudoJet> axes, List<Double> weights) {
        o.p(title).endl();
        o.p("Rap_hardest_jet=").p(axes.get(0).rap()).endl();
        o.p("Phi_hardest_jet=").p(axes.get(0).phi()).endl();
        o.p("pt_weight_hardest_jet=").p(axes.get(0).pt()).endl();
        o.p("N_jet_weight_hardest_jet=").p(weights.get(0)).endl();
    }

    static void advanced(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> input = header(in, o);
        final double rjet = 1.0;
        final double pTcut = 200.0;
        final double rsub = 0.2;
        final double fcut = 0.05;
        final double ptsubcut = 50.0;
        final ShapeJetMultiplicity_MultiplePtCutValues njAllPt = new ShapeJetMultiplicity_MultiplePtCutValues(rjet);
        final ShapeJetMultiplicity_MultiplePtCutValues njAllPtTrim = new ShapeJetMultiplicity_MultiplePtCutValues(rjet, rsub, fcut);
        njAllPt.setInput(input);
        njAllPtTrim.setInput(input);
        o.p("#########").endl().p("## Example of multiple pT analysis").endl().p("#########").endl();
        o.p("Jet parameters: R_jet=").p(rjet).p(", pTcut=").p(pTcut).p(" (unless otherwise stated)").endl();
        o.p("Trimming parameters: R_sub=").p(rsub).p(", fcut=").p(fcut).endl();
        o.p("#####").endl();
        o.p("N_jet from full pT_cut function=").p(njAllPt.eventShapeFor(pTcut)).endl();
        o.p("N_jet_trim from full pT_cut function=").p(njAllPtTrim.eventShapeFor(pTcut)).endl();
        o.p("#####").endl();
        o.p("Multiple pT_cut").endl();
        for (double c : new double[]{10, 20, 50, 100, 150}) o.p("pT_cut=").p(c).p(", N_jet=").p(njAllPt.eventShapeFor(c)).endl();
        o.p("#####").endl();
        o.p("pT of the hardest jet=").p(njAllPt.ptCutFor(1)).endl();
        o.p("pT of the hardest trimmed jet=").p(njAllPtTrim.ptCutFor(1)).endl();

        final ShapeJetMultiplicity_MultipleRValues njAllR = new ShapeJetMultiplicity_MultipleRValues(pTcut);
        final ShapeJetMultiplicity_MultipleRValues njAllRTrim = new ShapeJetMultiplicity_MultipleRValues(pTcut, rsub, fcut);
        njAllR.setInput(input);
        njAllRTrim.setInput(input);
        o.p("#########").endl().p("## Example of multiple R analysis").endl().p("#########").endl();
        o.p("Jet parameters: R_jet=").p(rjet).p(" (unless otherwise stated), pTcut=").p(pTcut).endl();
        o.p("Trimming parameters: R_sub=").p(rsub).p(", fcut=").p(fcut).endl();
        o.p("#####").endl();
        o.p("N_jet from full R function=").p(njAllR.eventShapeFor(rjet)).endl();
        o.p("N_jet_trim from full R function=").p(njAllRTrim.eventShapeFor(rjet)).endl();
        o.p("#####").endl();
        o.p("Multiple Rjet").endl();
        for (double r : new double[]{0.2, 0.4, 0.6, 0.8, 1}) o.p("R=").p(r).p(", N_jet=").p(njAllR.eventShapeFor(r)).endl();

        final EventShapeDensity_JetAxes axesShape = new EventShapeDensity_JetAxes(rjet, pTcut);
        axesShape.setInput(input);
        final List<PseudoJet> axes = axesShape.axes();
        final List<Double> njw = axesShape.njetWeights();
        axesShape.setGlobalConsistencyCheck(true);
        axesShape.findAxesAndWeights();
        final List<PseudoJet> axesGc = axesShape.axes();
        final List<Double> njwGc = axesShape.njetWeights();
        final EventShapeDensity_JetAxes axesShapeCA = new EventShapeDensity_JetAxes(rjet, pTcut, JetAlgorithm.CAMBRIDGE);
        axesShapeCA.setInput(input);
        o.p("#########").endl().p("## Hardest jet axis and weights").endl().p("#########").endl();
        axis(o, "Without global consistency and anti-kt metric:", axes, njw);
        o.p("#").endl();
        axis(o, "With global consistency and anti-kt metric:", axesGc, njwGc);
        o.p("#").endl();
        axis(o, "Without global consistency and cambridge metric:", axesShapeCA.axes(), axesShapeCA.njetWeights());

        final List<JetLikeEventShape> shapes = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        shapes.add(new ShapeJetMultiplicity(rjet, pTcut));
        names.add("Njet");
        shapes.add(new ShapeJetMultiplicity(rjet, pTcut, rsub, fcut));
        names.add("Njet_trim");
        shapes.add(new ShapeScalarPt(rjet, pTcut));
        names.add("HT");
        shapes.add(new ShapeScalarPt(rjet, pTcut, rsub, fcut));
        names.add("HT_trim");
        shapes.add(new ShapeMissingPt(rjet, pTcut));
        names.add("MissHT");
        shapes.add(new ShapeMissingPt(rjet, pTcut, rsub, fcut));
        names.add("MissHT_trim");
        shapes.add(new JetsWithoutJets.ShapeSummedMass(rjet, pTcut));
        names.add("SigmaM");
        shapes.add(new JetsWithoutJets.ShapeSummedMass(rjet, pTcut, rsub, fcut));
        names.add("SigmaM_trim");
        shapes.add(new JetsWithoutJets.ShapeSummedMassSquared(rjet, pTcut));
        names.add("SigmaM2");
        shapes.add(new JetsWithoutJets.ShapeSummedMassSquared(rjet, pTcut, rsub, fcut));
        names.add("SigmaM2_trim");
        shapes.add(new JetsWithoutJets.ShapeTrimmedSubjetMultiplicity(rjet, pTcut, rsub, fcut, ptsubcut));
        names.add("SigmaNsub_trim");
        for (int n = -2; n <= 2; n++) {
            shapes.add(new JetsWithoutJets.ShapeScalarPtToN(n, rjet, pTcut));
            shapes.add(new JetsWithoutJets.ShapeScalarPtToN(n, rjet, pTcut, rsub, fcut));
            names.add("GenHT_" + n);
            names.add("GenHT_" + n + "_trim");
        }
        o.p("#########").endl().p("## Examples showing more general shapes").endl().p("#########").endl();
        o.p("Jet parameters: R_jet=").p(rjet).p(", pTcut=").p(pTcut).endl();
        o.p("Trimming parameters: R_sub=").p(rsub).p(", fcut=").p(fcut).endl();
        o.p("#####").endl();
        for (int i = 0; i < shapes.size(); i++) {
            final JetLikeEventShape shape = shapes.get(i);
            o.p(shape.description()).endl();
            shape.setUseLocalStorage(false);
            final double noStorage = shape.result(input);
            shape.setUseLocalStorage(true);
            final double yesStorage = shape.result(input);
            o.p(names.get(i)).p("=").p(noStorage).endl();
            o.p("(Local Storage works? ").p(noStorage == yesStorage ? "Yes" : "No!!").p(")").endl();
            o.p("#").endl();
        }

        final JetLikeEventShape htNew = new JetLikeEventShape(new JetsWithoutJets.FunctionScalarPtSum(), rjet, pTcut);
        final ShapeScalarPt ht = new ShapeScalarPt(rjet, pTcut);
        o.p("#########").endl().p("## Example of JetLikeEventShape defined with an external measurement").endl().p("#########").endl();
        o.p("HT_new = ").p(htNew.result(input)).endl();
        o.p("compare to built-in HT = ").p(ht.result(input)).endl();

        final EventStorage storage = new EventStorage(rjet, pTcut);
        storage.establishStorage(input);
        final List<FunctionOfVectorOfPseudoJets<Double>> measurements = List.of(new JetsWithoutJets.FunctionUnity(),
            new JetsWithoutJets.FunctionScalarPtSum(), new JetsWithoutJets.FunctionScalarPtSumToN(2),
            new JetsWithoutJets.FunctionInvariantMass(), new JetsWithoutJets.FunctionInvariantMassSquared());
        final String[] names1 = {"Njet", "HT", "HT^2", "SigmaM", "SigmaM2"};
        o.p("#########").endl().p("## Example of multiple measurements using a single storage").endl().p("#########").endl();
        for (int i = 0; i < measurements.size(); i++) {
            final JetLikeEventShape shape = new JetLikeEventShape(measurements.get(i), rjet, pTcut);
            o.p("#").endl();
            o.p(shape.description()).endl();
            o.p(names1[i]).p("=").p(shape.result(storage)).endl();
        }
        o.p("#########").endl();
    }
}
