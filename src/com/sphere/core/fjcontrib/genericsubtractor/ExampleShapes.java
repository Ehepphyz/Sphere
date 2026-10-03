package com.sphere.core.fjcontrib.genericsubtractor;

import com.sphere.core.fastjet.ClusterSequenceActiveAreaExplicitGhosts;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;

import java.util.ArrayList;
import java.util.List;

/** The shapes GenericSubtractor ships as examples (ExampleShapes.hh), each under its C++ name. */
public final class ExampleShapes {

    private ExampleShapes() {
    }

    public static final class Pt implements FunctionOfPseudoJet<Double> {
        @Override public String description() { return "jet pt"; }
        @Override public Double result(PseudoJet jet) { return jet.pt(); }
    }

    public static final class ScalarPt implements FunctionOfPseudoJet<Double> {
        @Override public String description() { return "jet scalar pt"; }

        @Override
        public Double result(PseudoJet jet) {
            if (!jet.hasConstituents()) throw new FastJetException("ScalarPt can only be applied on jets for which the constituents are known.");
            double ptsum = 0.0;
            for (PseudoJet c : jet.constituents()) ptsum += c.pt();
            return ptsum;
        }
    }

    public static final class Mt implements FunctionOfPseudoJet<Double> {
        @Override public String description() { return "jet transverse mass"; }
        @Override public Double result(PseudoJet jet) { return jet.mt(); }
    }

    public static final class Mass implements FunctionOfPseudoJet<Double> {
        @Override public String description() { return "jet mass"; }
        @Override public Double result(PseudoJet jet) { return jet.m(); }
    }

    public static final class MassSquare implements FunctionOfPseudoJet<Double> {
        @Override public String description() { return "jet mass squared"; }
        @Override public Double result(PseudoJet jet) { return jet.m2(); }
    }

    /** The kt distance between the two kt subjets. */
    public static final class KtDij extends ShapeWithPartition {
        @Override public String description() { return "kt distance between the 2 kt subjets"; }

        @Override
        public PseudoJet partition(PseudoJet jet) {
            if (!jet.hasConstituents()) throw new FastJetException("KtDij can only be applied on jets for which the constituents are known.");
            final List<PseudoJet> constits = jet.constituents();
            if (constits.size() < 2) return PseudoJet.join(PseudoJet.ptYPhiM(1, 0, 0, 0), PseudoJet.ptYPhiM(1, 0, 0, 0));
            final List<PseudoJet> particles = new ArrayList<>();
            final List<PseudoJet> ghosts = new ArrayList<>();
            Selector.isPureGhost().sift(constits, ghosts, particles);
            final double ghostArea = ghosts.isEmpty() ? 0.01 : ghosts.get(0).area();
            final ClusterSequenceActiveAreaExplicitGhosts cs = new ClusterSequenceActiveAreaExplicitGhosts(particles,
                new JetDefinition(JetAlgorithm.KT, 999.9), ghosts, ghostArea);
            final PseudoJet ktjet = Selector.nHardest(1).apply(cs.inclusiveJets()).get(0);
            final PseudoJet[] parents = ktjet.parents();
            return parents == null ? PseudoJet.join(new PseudoJet(), new PseudoJet()) : PseudoJet.join(parents[0], parents[1]);
        }

        @Override
        public double resultFromPartition(PseudoJet partit) {
            if (!partit.hasPieces()) throw new FastJetException("KtDij::result_from_partition can only be computed for composite jets");
            final List<PseudoJet> pieces = partit.pieces();
            if (pieces.size() != 2) throw new FastJetException("KtDij::result_from_partition can only be computed for composite jets made of 2 pieces");
            return pieces.get(0).ktDistance(pieces.get(1));
        }
    }

    /** sum pt DeltaR^(2 - alpha) / sum pt. */
    public static final class Angularity implements FunctionOfPseudoJet<Double> {
        private final double alpha;

        public Angularity() { this(1.0); }
        public Angularity(double alpha) { this.alpha = alpha; }

        @Override public String description() { return "Angularity with alpha=" + Fmt.g(alpha); }

        @Override
        public Double result(PseudoJet jet) {
            if (!jet.hasConstituents()) throw new FastJetException("Angularities can only be applied on jets for which the constituents are known.");
            double num = 0.0, den = 0.0;
            for (PseudoJet c : jet.constituents()) {
                final double pt = c.pt();
                num += pt * CRMath.pow(c.squaredDistance(jet), 1 - alpha / 2);
                den += pt;
            }
            return num / den;
        }
    }

    public static final class AngularityNumerator implements FunctionOfPseudoJet<Double> {
        private final double alpha;

        public AngularityNumerator() { this(1.0); }
        public AngularityNumerator(double alpha) { this.alpha = alpha; }

        @Override public String description() { return "Angularity numerator with alpha=" + Fmt.g(alpha); }

        @Override
        public Double result(PseudoJet jet) {
            if (!jet.hasConstituents()) throw new FastJetException("Angularities can only be applied on jets for which the constituents are known.");
            double num = 0.0;
            for (PseudoJet c : jet.constituents()) num += c.pt() * CRMath.pow(c.squaredDistance(jet), 1 - alpha / 2);
            return num;
        }
    }

    /** The energy-energy correlator sum_{i&gt;j} pt_i pt_j DeltaR_ij^beta / (sum pt)^2. */
    public static final class TauEEC implements FunctionOfPseudoJet<Double> {
        private final double beta;

        public TauEEC() { this(1.0); }
        public TauEEC(double beta) { this.beta = beta; }

        public double beta() { return beta; }
        @Override public String description() { return "Energy-energy correlator with beta=" + Fmt.g(beta); }

        @Override
        public Double result(PseudoJet jet) {
            final List<PseudoJet> c = jet.constituents();
            double num = 0.0, den = 0.0;
            for (int i = 0; i < c.size(); i++) {
                final double pti = c.get(i).pt();
                for (int j = 0; j < i; j++) num += pti * c.get(j).pt() * CRMath.pow(c.get(i).squaredDistance(c.get(j)), 0.5 * beta);
                den += pti;
            }
            return num / (den * den);
        }
    }

    /** The numerator of TauEEC (abstract in the C++, usable here). */
    public static final class TauEECNumerator implements FunctionOfPseudoJet<Double> {
        private final double beta;

        public TauEECNumerator() { this(1.0); }
        public TauEECNumerator(double beta) { this.beta = beta; }

        @Override public String description() { return "Numerator of Energy-energy correlator with beta=" + Fmt.g(beta); }

        @Override
        public Double result(PseudoJet jet) {
            final List<PseudoJet> c = jet.constituents();
            double t = 0.0;
            for (int i = 0; i < c.size(); i++) {
                for (int j = 0; j < i; j++) {
                    t += Math.sqrt(c.get(i).perp2() * c.get(j).perp2()) * CRMath.pow(c.get(i).squaredDistance(c.get(j)), 0.5 * beta);
                }
            }
            return t;
        }
    }

    /** sum pt min_a DeltaR, with the N exclusive kt axes found once. */
    public static final class NSubjettinessNumerator extends ShapeWithPartition {
        private final int n;

        public NSubjettinessNumerator(int n) { this.n = n; }

        @Override public String description() { return "N-subjettiness numerator"; }

        @Override
        public PseudoJet partition(PseudoJet jet) {
            if (!jet.hasConstituents()) throw new FastJetException("N-subjettiness can only be computed for jets with available constituents");
            final List<PseudoJet> particles = new ArrayList<>();
            final List<PseudoJet> ghosts = new ArrayList<>();
            Selector.isPureGhost().sift(jet.constituents(), ghosts, particles);
            final double ghostArea = ghosts.isEmpty() ? 0.01 : ghosts.get(0).area();
            final ClusterSequenceActiveAreaExplicitGhosts cs = new ClusterSequenceActiveAreaExplicitGhosts(particles,
                new JetDefinition(JetAlgorithm.KT, 999.0), ghosts, ghostArea);
            return PseudoJet.join(cs.exclusiveJetsUpTo(n));
        }

        @Override
        public double resultFromPartition(PseudoJet partit) {
            if (!partit.hasPieces()) throw new FastJetException("NSubjettinessNumerator::result_from_partition can only be computed for composite jets");
            final List<PseudoJet> axes = partit.pieces();
            if (axes.size() < n) return 0.0;
            if (axes.size() > n) throw new FastJetException("NSubjettinessNumerator::result_from_partition can only be computed for composite jets made of N pieces");
            double sum = 0.0;
            for (PseudoJet c : partit.constituents()) {
                double mind2 = Double.MAX_VALUE;
                for (PseudoJet a : axes) mind2 = Math.min(mind2, c.squaredDistance(a));
                sum += Math.sqrt(c.pt2() * mind2);
            }
            return sum;
        }
    }
}
