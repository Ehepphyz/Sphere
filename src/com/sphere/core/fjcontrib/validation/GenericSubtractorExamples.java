package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.Subtractor;
import com.sphere.core.fjcontrib.genericsubtractor.ExampleShapes;
import com.sphere.core.fjcontrib.genericsubtractor.GenericSubtractor;
import com.sphere.core.fjcontrib.genericsubtractor.GenericSubtractorInfo;
import com.sphere.core.fjcontrib.genericsubtractor.ShapeWithComponents;

import java.io.BufferedReader;
import java.util.List;

/** The example programs of GenericSubtractor 1.3.1. */
final class GenericSubtractorExamples {

    private static final String C = "GenericSubtractor";
    private static final String DATA = "Pythia-Zp2jets-lhc-pileup-1ev.dat";

    private GenericSubtractorExamples() {
    }

    static List<Example> all() {
        return List.of(
            Example.of(C, "example", DATA, GenericSubtractorExamples::example),
            Example.of(C, "example_with_components", DATA, GenericSubtractorExamples::withComponents));
    }

    private static AreaDefinition areaDef() {
        return new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(Selector.absRapMax(4.0)));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final Events.HardAndFull ev = Events.readHardAndFull(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final List<PseudoJet> hard = Selector.absRapMax(4.0).apply(ev.hard());
        final List<PseudoJet> full = Selector.absRapMax(4.0).apply(ev.full());
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.7);
        final AreaDefinition areaDef = areaDef();
        final ClusterSequenceArea csHard = new ClusterSequenceArea(hard, jetDef, areaDef);
        final ClusterSequenceArea csFull = new ClusterSequenceArea(full, jetDef, areaDef);
        final Selector selJets = Selector.nHardest(2).times(Selector.absRapMax(3.0));
        final List<PseudoJet> hardJets = selJets.apply(csHard.inclusiveJets());
        final List<PseudoJet> fullJets = selJets.apply(csFull.inclusiveJets());
        new ClusterSequenceArea(full, jetDef, areaDef);
        final JetMedianBackgroundEstimator bge = new JetMedianBackgroundEstimator(Selector.absRapMax(3.0),
            new JetDefinition(JetAlgorithm.KT, 0.4), areaDef);
        bge.setParticles(full);
        final Subtractor subtractor = new Subtractor(bge);
        final ExampleShapes.Angularity shape = new ExampleShapes.Angularity(1.0);
        final GenericSubtractor genSub = new GenericSubtractor(bge);
        final GenericSubtractorInfo info = new GenericSubtractorInfo();
        o.p(genSub.description()).endl();
        o.setprecision(4);
        o.p("# original hard jets").endl();
        for (PseudoJet j : hardJets) o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).p(", angularity = ").p(shape.result(j)).endl();
        o.endl();
        o.p("# unsubtracted full jets").endl();
        for (PseudoJet j : fullJets) o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).p(", angularity = ").p(shape.result(j)).endl();
        o.endl();
        o.p("# subtracted full jets").endl();
        for (PseudoJet j : fullJets) {
            final PseudoJet s = subtractor.result(j);
            final double sub = genSub.apply(shape, j, info);
            o.p("pt = ").p(s.pt()).p(", rap = ").p(s.rap()).p(", angularity = ").p(sub).endl();
            o.p("  rho  = ").p(info.rho()).endl();
            o.p("  rhom = ").p(info.rhom()).endl();
            o.p("  1st derivative: ").p(info.firstDerivative()).endl();
            o.p("  2nd derivative: ").p(info.secondDerivative()).endl();
            o.p("  unsubtracted: ").p(info.unsubtracted()).endl();
            o.p("  1st order: ").p(info.firstOrderSubtracted()).endl();
            o.p("# step used: ").p(info.ghostScaleUsed()).endl();
        }
        o.endl();
    }

    /** The NSubjettinessRatio of example_with_components: tau_N/tau_{N-1} from two subtracted numerators. */
    static final class NSubjettinessRatio extends ShapeWithComponents {
        private final int n;

        NSubjettinessRatio(int n) {
            this.n = n;
        }

        @Override public String description() { return "N-subjettiness ratio from components"; }
        @Override public int nComponents() { return 2; }

        @Override
        public double[] components(PseudoJet jet) {
            return new double[]{new ExampleShapes.NSubjettinessNumerator(n).result(jet),
                new ExampleShapes.NSubjettinessNumerator(n - 1).result(jet)};
        }

        @Override
        public double resultFromComponents(double[] c) {
            return c[0] / c[1];
        }

        @Override
        public FunctionOfPseudoJet<Double> componentShape(int index) {
            return new ExampleShapes.NSubjettinessNumerator(n - index);
        }
    }

    static void withComponents(BufferedReader in, Cout o, String[] args) throws Exception {
        final Events.HardAndFull ev = Events.readHardAndFull(in);
        o.p("# ").p(ev.nsub() - 1).p(" pileup events on top of the hard event").endl();
        final List<PseudoJet> hard = Selector.absRapMax(4.0).apply(ev.hard());
        final List<PseudoJet> full = Selector.absRapMax(4.0).apply(ev.full());
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 0.7);
        final AreaDefinition areaDef = areaDef();
        final JetMedianBackgroundEstimator bge = new JetMedianBackgroundEstimator(Selector.absRapMax(3.0),
            new JetDefinition(JetAlgorithm.KT, 0.4), areaDef);
        final Subtractor subtractor = new Subtractor(bge);
        final Selector selJets = Selector.nHardest(2).times(Selector.absRapMax(3.0));
        final List<PseudoJet> hardJets = selJets.apply(new ClusterSequenceArea(hard, jetDef, areaDef).inclusiveJets());
        final List<PseudoJet> fullJets = selJets.apply(new ClusterSequenceArea(full, jetDef, areaDef).inclusiveJets());
        final NSubjettinessRatio tau21 = new NSubjettinessRatio(2);
        final GenericSubtractor genSub = new GenericSubtractor(bge);
        bge.setParticles(full);
        o.p("# original hard jets").endl();
        for (PseudoJet j : hardJets) o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).p(", tau21 = ").p(tau21.result(j)).endl();
        o.endl();
        o.p("# unsubtracted full jets").endl();
        for (PseudoJet j : fullJets) o.p("pt = ").p(j.pt()).p(", rap = ").p(j.rap()).p(", tau21 = ").p(tau21.result(j)).endl();
        o.endl();
        o.p("# subtracted full jets").endl();
        for (PseudoJet j : fullJets) {
            final PseudoJet s = subtractor.result(j);
            final double sub = genSub.apply(tau21, j);
            o.p("pt = ").p(s.pt()).p(", rap = ").p(s.rap()).p(", tau21 = ").p(sub).endl();
        }
        o.endl();
    }
}
