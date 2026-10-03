package com.sphere.core.fjcontrib.jetswithoutjets;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fastjet.tools.Transformer;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.internal.StdSort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Jets without jets, fastjet::jwj (JetsWithoutJets 1.0.0; D. Bertolini,
 * T. Chan and J. Thaler, "Jet Observables Without Jet Algorithms", JHEP 1404
 * (2014) 013, arXiv:1310.7584): event shapes that reproduce jet observables
 * (jet multiplicity, H_T, missing H_T, summed jet masses, trimmed subjet
 * multiplicity...) from particle neighbourhoods instead of clustered jets,
 * the trimming built on them (event-shape and jet-shape trimming), the same
 * shapes as functions of every pt cut or of every radius at once, and the
 * jet axes the jet-count density points to.
 *
 * <p>With {@link Precision#DOUBLE} every number is the C++ contrib's bit for
 * bit. With the double-double default, the jet multiplicity as a function of
 * R is updated incrementally in double-double arithmetic, in O(N^2) instead
 * of the C++ O(N^3) re-summation, and no less accurately.
 */
public final class JetsWithoutJets {

    static {
        ContribCitations.use("jetswithoutjets");
    }

    private JetsWithoutJets() {
    }

    /* ================================================================== */
    /* Measurements                                                         */
    /* ================================================================== */

    public static final class FunctionUnity implements FunctionOfVectorOfPseudoJets<Double> {
        @Override public Double result(List<PseudoJet> particles) { return 1.0; }
        @Override public String description() { return "Jet unit weight"; }
    }

    public static final class FunctionScalarPtSum implements FunctionOfVectorOfPseudoJets<Double> {
        @Override
        public Double result(List<PseudoJet> particles) {
            double pt = 0.0;
            for (PseudoJet p : particles) pt += p.pt();
            return pt;
        }

        @Override public String description() { return "Jet Scalar Pt"; }
    }

    public static final class FunctionScalarPtSumToN implements FunctionOfVectorOfPseudoJets<Double> {
        private final int n;

        public FunctionScalarPtSumToN(int n) {
            this.n = n;
        }

        @Override
        public Double result(List<PseudoJet> particles) {
            return CRMath.pow(new FunctionScalarPtSum().result(particles), n);
        }

        @Override public String description() { return "Jet Scalar Pt^" + n; }
    }

    private static PseudoJet sum(List<PseudoJet> particles) {
        final PseudoJet j = new PseudoJet(0, 0, 0, 0);
        for (PseudoJet p : particles) j.plusEqual(p);
        return j;
    }

    public static final class FunctionInvariantMass implements FunctionOfVectorOfPseudoJets<Double> {
        @Override public Double result(List<PseudoJet> particles) { return sum(particles).m(); }
        @Override public String description() { return "Jet Mass"; }
    }

    public static final class FunctionInvariantMassSquared implements FunctionOfVectorOfPseudoJets<Double> {
        @Override public Double result(List<PseudoJet> particles) { return sum(particles).m2(); }
        @Override public String description() { return "Jet Mass^2"; }
    }

    /* ================================================================== */
    /* Shapes computed from the storage                                    */
    /* ================================================================== */

    /** Jet counting: the sum of the weights. */
    public static final class ShapeJetMultiplicity extends JetLikeEventShape {
        public ShapeJetMultiplicity(double rjet, double ptcut) {
            super(null, rjet, ptcut);
            setStoreNeighbors(false);
        }

        public ShapeJetMultiplicity(double rjet, double ptcut, double rsub, double fcut) {
            super(null, rjet, ptcut, rsub, fcut);
            setStoreNeighbors(false);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            double m = 0.0;
            for (int i = 0; i < s.size(); i++) if (s.get(i).includeParticle()) m += s.get(i).weight();
            return m;
        }

        @Override public String description() { return "Jet multiplicity as event shape, " + jetParameterString(); }
    }

    /** H_T: the summed scalar pt of the particles in jets. */
    public static final class ShapeScalarPt extends JetLikeEventShape {
        public ShapeScalarPt(double rjet, double ptcut) {
            super(null, rjet, ptcut);
            setStoreNeighbors(false);
        }

        public ShapeScalarPt(double rjet, double ptcut, double rsub, double fcut) {
            super(null, rjet, ptcut, rsub, fcut);
            setStoreNeighbors(false);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            double m = 0.0;
            for (int i = 0; i < s.size(); i++) if (s.get(i).includeParticle()) m += s.get(i).pt();
            return m;
        }

        @Override public String description() { return "Summed scalar pt as event shape, " + jetParameterString(); }
    }

    /** sum over jets of pt_jet^n. */
    public static final class ShapeScalarPtToN extends JetLikeEventShape {
        private final int n;

        public ShapeScalarPtToN(double n, double rjet, double ptcut) {
            super(null, rjet, ptcut);
            this.n = (int) n;
            setStoreNeighbors(false);
        }

        public ShapeScalarPtToN(double n, double rjet, double ptcut, double rsub, double fcut) {
            super(null, rjet, ptcut, rsub, fcut);
            this.n = (int) n;
            setStoreNeighbors(false);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            double m = 0.0;
            for (int i = 0; i < s.size(); i++) {
                final EventStorage.ParticleStorage p = s.get(i);
                if (p.includeParticle()) m += p.pt() * CRMath.pow(p.ptInRjet(), n - 1);
            }
            return m;
        }

        @Override
        public String description() {
            return "Jet Scalar Pt^" + n + "as event shape, " + jetParameterString();
        }
    }

    /** sum over jets of the jet mass. */
    public static final class ShapeSummedMass extends JetLikeEventShape {
        public ShapeSummedMass(double rjet, double ptcut) {
            super(null, rjet, ptcut);
            setStoreNeighbors(false);
            setStoreMass(true);
        }

        public ShapeSummedMass(double rjet, double ptcut, double rsub, double fcut) {
            super(null, rjet, ptcut, rsub, fcut);
            setStoreNeighbors(false);
            setStoreMass(true);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            if (!s.storeMass()) throw new FastJetException("Mass is not stored, can't use this EventStorage");
            double m = 0.0;
            for (int i = 0; i < s.size(); i++) if (s.get(i).includeParticle()) m += s.get(i).weight() * s.get(i).mInRjet();
            return m;
        }

        @Override public String description() { return "Summed jet mass as event shape, " + jetParameterString(); }
    }

    /** sum over jets of the squared jet mass. */
    public static final class ShapeSummedMassSquared extends JetLikeEventShape {
        public ShapeSummedMassSquared(double rjet, double ptcut) {
            super(null, rjet, ptcut);
            setStoreNeighbors(false);
            setStoreMass(true);
        }

        public ShapeSummedMassSquared(double rjet, double ptcut, double rsub, double fcut) {
            super(null, rjet, ptcut, rsub, fcut);
            setStoreNeighbors(false);
            setStoreMass(true);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            if (!s.storeMass()) throw new FastJetException("Mass is not stored, can't use this EventStorage");
            double m = 0.0;
            for (int i = 0; i < s.size(); i++) {
                final EventStorage.ParticleStorage p = s.get(i);
                if (p.includeParticle()) m += p.weight() * p.mInRjet() * p.mInRjet();
            }
            return m;
        }

        @Override public String description() { return "Summed squared jet mass as event shape, " + jetParameterString(); }
    }

    /** The missing pt of the particles in jets. */
    public static final class ShapeMissingPt extends JetLikeEventShape {
        public ShapeMissingPt(double rjet, double ptcut) {
            super(null, rjet, ptcut);
            setStoreNeighbors(false);
        }

        public ShapeMissingPt(double rjet, double ptcut, double rsub, double fcut) {
            super(null, rjet, ptcut, rsub, fcut);
            setStoreNeighbors(false);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            double px = 0.0;
            double py = 0.0;
            for (int i = 0; i < s.size(); i++) {
                if (s.get(i).includeParticle()) {
                    px += s.get(i).px();
                    py += s.get(i).py();
                }
            }
            return Math.sqrt(px * px + py * py);
        }

        @Override public String description() { return "Missing pt as event shape, " + jetParameterString(); }
    }

    /** The number of trimmed subjets: sum pt_i / pt_in_Rsub. */
    public static final class ShapeTrimmedSubjetMultiplicity extends JetLikeEventShape {
        private final double ptsubcut;

        public ShapeTrimmedSubjetMultiplicity(double rjet, double ptcut, double rsub, double fcut, double ptsubcut) {
            super(null, rjet, ptcut, rsub, fcut);
            this.ptsubcut = ptsubcut;
            setStoreNeighbors(false);
        }

        public ShapeTrimmedSubjetMultiplicity(double rjet, double ptcut, double rsub, double fcut) {
            this(rjet, ptcut, rsub, fcut, 0.0);
        }

        @Override
        public double result(EventStorage s) {
            requireConsistent(s);
            double m = 0.0;
            for (int i = 0; i < s.size(); i++) {
                final EventStorage.ParticleStorage p = s.get(i);
                if (p.includeParticle() && p.ptInRsub() > ptsubcut) m += p.pt() / p.ptInRsub();
            }
            return m;
        }

        @Override
        public String description() {
            return "Trimmed subjet multiplicity as event shape, ptsub_cut=" + Fmt.g(ptsubcut) + "and, " + jetParameterString();
        }
    }

    /* ================================================================== */
    /* Trimming                                                             */
    /* ================================================================== */

    private static final class ShapeTrimmingWorker implements Selector.Worker {
        private final double rjet;
        private final double ptcut;
        private final double rsub;
        private final double fcut;
        private final boolean useLocalStorage;

        ShapeTrimmingWorker(double rjet, double ptcut, double rsub, double fcut, boolean useLocalStorage) {
            this.rjet = rjet;
            this.ptcut = ptcut;
            this.rsub = rsub;
            this.fcut = fcut;
            this.useLocalStorage = useLocalStorage;
        }

        @Override
        public boolean pass(PseudoJet jet) {
            throw new FastJetException("Cannot apply this selector worker to an individual jet");
        }

        @Override
        public void terminator(PseudoJet[] jets) {
            final List<PseudoJet> mine = new ArrayList<>();
            final List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < jets.length; i++) {
                if (jets[i] != null) {
                    indices.add(i);
                    mine.add(jets[i]);
                }
            }
            final EventStorage s = new EventStorage(rjet, ptcut, rsub, fcut, useLocalStorage, false);
            s.establishStorage(mine);
            for (int i = 0; i < s.size(); i++) if (!s.get(i).includeParticle()) jets[indices.get(i)] = null;
        }

        @Override public boolean appliesJetByJet() { return false; }

        @Override
        public String description() {
            return "Shape trimmer, R_jet=" + Fmt.g(rjet) + ", pT_cut=" + Fmt.g(ptcut) + ", R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
        }
    }

    private static final class JetShapeTrimmingWorker implements Selector.Worker {
        private final double rsub;
        private final double fcut;

        JetShapeTrimmingWorker(double rsub, double fcut) {
            this.rsub = rsub;
            this.fcut = fcut;
        }

        @Override
        public boolean pass(PseudoJet jet) {
            throw new FastJetException("Cannot apply this selector worker to an individual jet");
        }

        @Override
        public void terminator(PseudoJet[] jets) {
            final List<PseudoJet> mine = new ArrayList<>();
            final List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < jets.length; i++) {
                if (jets[i] != null) {
                    indices.add(i);
                    mine.add(jets[i]);
                }
            }
            final double ptRjet = new FunctionScalarPtSum().result(mine);
            final EventStorage s = new EventStorage(rsub, ptRjet * fcut, false, false);
            s.establishStorage(mine);
            for (int i = 0; i < s.size(); i++) if (!s.get(i).includeParticle()) jets[indices.get(i)] = null;
        }

        @Override public boolean appliesJetByJet() { return false; }

        @Override
        public String description() {
            return "Jet shape trimmer, R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
        }
    }

    /** Event-shape trimming: keeps the particles whose neighbourhood passes the cut and the trimming. */
    public static Selector selectorShapeTrimming(double rjet, double ptcut, double rsub, double fcut) {
        return new Selector(new ShapeTrimmingWorker(rjet, ptcut, rsub, fcut, true));
    }

    /** Jet-shape trimming: within a jet, keeps the particles with pt_in_Rsub >= fcut pt_jet. */
    public static Selector selectorJetShapeTrimming(double rsub, double fcut) {
        return new Selector(new JetShapeTrimmingWorker(rsub, fcut));
    }

    /** Jet-shape trimming of a jet's constituents, fastjet::jwj::JetShapeTrimmer. */
    public static final class JetShapeTrimmer implements Transformer {
        private final double rsub;
        private final double fcut;
        private final Selector selector;

        public JetShapeTrimmer(double rsub, double fcut) {
            this.rsub = rsub;
            this.fcut = fcut;
            this.selector = selectorJetShapeTrimming(rsub, fcut);
        }

        @Override
        public PseudoJet result(PseudoJet original) {
            return PseudoJet.join(selector.apply(original.constituents()));
        }

        public String jetParameterString() {
            return "R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
        }

        @Override public String description() { return "Jet shape trimmer, " + jetParameterString(); }
    }

    /* ================================================================== */
    /* Shapes as functions of the pt cut                                    */
    /* ================================================================== */

    private static final Comparator<double[]> BY_FIRST_DESC = (a, b) -> a[0] > b[0] ? -1 : (b[0] > a[0] ? 1 : 0);

    /** std::lower_bound over arr[n-1] ... arr[0] (the reversed range) of v[k] < val. */
    private static double[] lowerBoundReversed(List<double[]> arr, int k, double val) {
        final int n = arr.size();
        int first = 0;
        int len = n;
        while (len > 0) {
            final int half = len >> 1;
            final int mid = first + half;
            if (arr.get(n - 1 - mid)[k] < val) {
                first = mid + 1;
                len = len - half - 1;
            } else {
                len = half;
            }
        }
        if (first == n) throw new FastJetException("JetsWithoutJets: value beyond the step function");
        return arr.get(n - 1 - first);
    }

    /** std::lower_bound over arr[0] ... arr[n-1] of v[k] < val. */
    private static double[] lowerBound(List<double[]> arr, int k, double val) {
        int first = 0;
        int len = arr.size();
        while (len > 0) {
            final int half = len >> 1;
            final int mid = first + half;
            if (arr.get(mid)[k] < val) {
                first = mid + 1;
                len = len - half - 1;
            } else {
                len = half;
            }
        }
        if (first == arr.size()) throw new FastJetException("JetsWithoutJets: value beyond the step function");
        return arr.get(first);
    }

    /**
     * A jet-like event shape for every pt cut at once,
     * fastjet::jwj::JetLikeEventShape_MultiplePtCutValues: the step function
     * of the shape against the pt cut, and its inverse (the pt cut at which
     * the shape reaches a value, e.g. the pt of the hardest jet).
     */
    public static class JetLikeEventShape_MultiplePtCutValues {
        protected final FunctionOfVectorOfPseudoJets<Double> measurement;
        protected final double rjet;
        protected final double rsub;
        protected final double fcut;
        protected final double offset;
        protected final boolean trim;
        protected boolean useLocalStorage = true;
        protected List<double[]> functionArray = new ArrayList<>();

        public JetLikeEventShape_MultiplePtCutValues(FunctionOfVectorOfPseudoJets<Double> measurement, double rjet, double offset) {
            this.measurement = measurement;
            this.rjet = rjet;
            this.rsub = rjet;
            this.fcut = 1.0;
            this.offset = offset;
            this.trim = false;
        }

        public JetLikeEventShape_MultiplePtCutValues(FunctionOfVectorOfPseudoJets<Double> measurement, double rjet) {
            this(measurement, rjet, 0.0);
        }

        public JetLikeEventShape_MultiplePtCutValues(FunctionOfVectorOfPseudoJets<Double> measurement, double rjet, double rsub,
                                                     double fcut, double offset) {
            this.measurement = measurement;
            this.rjet = rjet;
            this.rsub = rsub;
            this.fcut = fcut;
            this.offset = offset;
            this.trim = true;
        }

        public void setInput(List<PseudoJet> particles) {
            storeLocalInfo(particles);
            buildStepFunction();
        }

        public double eventShapeFor(double ptcut0) {
            if (functionArray.isEmpty()) return 0.0;
            if (ptcut0 <= functionArray.get(0)[0]) return lowerBoundReversed(functionArray, 0, ptcut0)[1];
            return 0.0;
        }

        public double ptCutFor(double eventShape0) {
            final double v = eventShape0 - offset;
            if (functionArray.isEmpty() || v <= 0 || v > functionArray.get(functionArray.size() - 1)[1]) {
                throw new FastJetException("Event shape value not valid");
            }
            return lowerBound(functionArray, 1, v)[0];
        }

        /** The steps (pt cut, shape), by decreasing pt cut. */
        public List<double[]> functionArray() {
            final List<double[]> copy = new ArrayList<>(functionArray.size());
            for (double[] p : functionArray) copy.add(p.clone());
            return copy;
        }

        public void setUseLocalStorage(boolean v) { useLocalStorage = v; }

        public String parameterString() {
            String s = "R_jet=" + Fmt.g(rjet);
            if (trim) s += ", trimming with R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
            return s + ", offset for inverse function=" + Fmt.g(offset);
        }

        public String description() {
            return measurement.description() + "as function of pT_cut, " + parameterString();
        }

        protected void storeLocalInfo(List<PseudoJet> particles) {
            final EventStorage s = new EventStorage(rjet, 0.0, rsub, fcut, useLocalStorage);
            s.establishStorage(particles);
            functionArray = new ArrayList<>();
            for (int i = 0; i < s.size(); i++) {
                functionArray.add(new double[]{s.get(i).ptInRjet(), s.get(i).weight() * measurement.result(s.particlesNearTo(i))});
            }
        }

        protected void buildStepFunction() {
            StdSort.sort(functionArray, BY_FIRST_DESC);
            for (int i = 1; i < functionArray.size(); i++) functionArray.get(i)[1] += functionArray.get(i - 1)[1];
        }
    }

    /** The jet multiplicity for every pt cut at once. */
    public static final class ShapeJetMultiplicity_MultiplePtCutValues extends JetLikeEventShape_MultiplePtCutValues {
        public ShapeJetMultiplicity_MultiplePtCutValues(double rjet, double offset) {
            super(null, rjet, offset);
        }

        public ShapeJetMultiplicity_MultiplePtCutValues(double rjet) {
            this(rjet, 0.5);
        }

        public ShapeJetMultiplicity_MultiplePtCutValues(double rjet, double rsub, double fcut, double offset) {
            super(null, rjet, rsub, fcut, offset);
        }

        public ShapeJetMultiplicity_MultiplePtCutValues(double rjet, double rsub, double fcut) {
            this(rjet, rsub, fcut, 0.5);
        }

        @Override
        public void setInput(List<PseudoJet> particles) {
            final EventStorage s = new EventStorage(rjet, 0.0, rsub, fcut, useLocalStorage, false);
            s.establishStorage(particles);
            functionArray = new ArrayList<>();
            for (int i = 0; i < s.size(); i++) functionArray.add(new double[]{s.get(i).ptInRjet(), s.get(i).weight()});
            buildStepFunction();
        }

        @Override
        public String description() {
            return "Shape jet multiplicity as function of pT_cut, " + parameterString();
        }
    }

    /* ================================================================== */
    /* Jet multiplicity for every radius                                   */
    /* ================================================================== */

    /**
     * The jet multiplicity for every R_jet at once,
     * fastjet::jwj::ShapeJetMultiplicity_MultipleRValues: the particle pairs
     * are removed from the neighbourhoods by decreasing distance, which
     * steps the shape down from R = infinity.
     */
    public static final class ShapeJetMultiplicity_MultipleRValues {
        private final double ptcut;
        private final double rsub;
        private final double fcut;
        private final boolean trim;
        private List<double[]> functionArray = new ArrayList<>();

        public ShapeJetMultiplicity_MultipleRValues(double ptcut) {
            this.ptcut = ptcut;
            this.rsub = 0.0;
            this.fcut = 1.0;
            this.trim = false;
        }

        public ShapeJetMultiplicity_MultipleRValues(double ptcut, double rsub, double fcut) {
            this.ptcut = ptcut;
            this.rsub = rsub;
            this.fcut = fcut;
            this.trim = true;
        }

        public void setInput(List<PseudoJet> particles) {
            buildStepFunction(particles);
        }

        public double eventShapeFor(double rjet0) {
            if (rjet0 < rsub) throw new FastJetException("Rjet < Rsub");
            if (rjet0 < 0) throw new FastJetException("Negative Rjet");
            if (functionArray.isEmpty()) return 0.0;
            if (rjet0 > functionArray.get(0)[0]) return functionArray.get(0)[1];
            return lowerBoundReversed(functionArray, 0, rjet0)[1];
        }

        /** The steps (R, shape), by decreasing R. */
        public List<double[]> functionArray() {
            final List<double[]> copy = new ArrayList<>(functionArray.size());
            for (double[] p : functionArray) copy.add(p.clone());
            return copy;
        }

        public String parameterString() {
            String s = "pT_cut=" + Fmt.g(ptcut);
            if (trim) s += ", trimming with R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
            return s;
        }

        public String description() {
            return "Shape jet multiplicity as function of Rjet, " + parameterString();
        }

        private double term(double pt, double pTR, double pTRsub) {
            if (trim) return pTR >= ptcut && pTRsub / pTR >= fcut ? pt / pTR : 0.0;
            return pTR >= ptcut ? pt / pTR : 0.0;
        }

        private void buildStepFunction(List<PseudoJet> particles) {
            final EventStorage s = new EventStorage(rsub, 0.0, false, false);
            s.establishStorage(particles);
            final int n = s.size();
            final double[][] pairs = new double[n < 2 ? 0 : (int) ((long) n * (n - 1) / 2)][];
            int k = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) pairs[k++] = new double[]{Math.sqrt(s.get(i).deltaRsq(s.get(j))), i, j};
            }
            StdSort.sort(pairs, 0, pairs.length, BY_FIRST_DESC);
            final double[] pt = new double[n];
            final double[] pTRsub = new double[n];
            double totPt = 0.0;
            for (int i = 0; i < n; i++) {
                pt[i] = s.get(i).pt();
                totPt += pt[i];
                pTRsub[i] = s.get(i).ptInRsub();
            }
            final double[] pTR = new double[n];
            java.util.Arrays.fill(pTR, totPt);
            final List<double[]> fa = new ArrayList<>(pairs.length);
            if (Precision.defaultPrecision() == Precision.DOUBLE) {
                // the C++ re-summation, O(N^3), bit for bit
                for (double[] pair : pairs) {
                    final int id1 = (int) pair[1];
                    final int id2 = (int) pair[2];
                    pTR[id1] -= pt[id2];
                    pTR[id2] -= pt[id1];
                    double shape = 0;
                    for (int j = 0; j < n; j++) shape += term(pt[j], pTR[j], pTRsub[j]);
                    fa.add(new double[]{pair[0], shape});
                }
            } else {
                // incremental: only the two particles of the pair change, O(N^2) in double-double
                final double[] t = new double[n];
                DD shape = DD.of(0.0);
                for (int j = 0; j < n; j++) {
                    t[j] = term(pt[j], pTR[j], pTRsub[j]);
                    shape = shape.add(t[j]);
                }
                for (double[] pair : pairs) {
                    final int id1 = (int) pair[1];
                    final int id2 = (int) pair[2];
                    pTR[id1] -= pt[id2];
                    pTR[id2] -= pt[id1];
                    shape = shape.sub(t[id1]).sub(t[id2]);
                    t[id1] = term(pt[id1], pTR[id1], pTRsub[id1]);
                    t[id2] = term(pt[id2], pTR[id2], pTRsub[id2]);
                    shape = shape.add(t[id1]).add(t[id2]);
                    fa.add(new double[]{pair[0], shape.doubleValue()});
                }
            }
            functionArray = fa;
        }
    }

    /* ================================================================== */
    /* Jet axes                                                             */
    /* ================================================================== */

    /** The axis of a set of particles clustered into one jet, fastjet::jwj::FunctionJetAxis. */
    public static final class FunctionJetAxis implements FunctionOfVectorOfPseudoJets<PseudoJet> {
        private final JetDefinition jetDef;

        public FunctionJetAxis(JetDefinition jetDef) {
            this.jetDef = jetDef;
        }

        @Override
        public PseudoJet result(List<PseudoJet> particles) {
            return new ClusterSequence(particles, jetDef).inclusiveJets(0.0).get(0);
        }

        @Override public String description() { return "Jet axis with " + jetDef.description(); }
    }

    /** The light-like direction of a jet with pt = 1, fastjet::jwj::LightLikeAxis. */
    public static final class LightLikeAxis implements FunctionOfPseudoJet<PseudoJet> {
        @Override
        public PseudoJet result(PseudoJet jet) {
            final PseudoJet p = jet.copy();
            p.resetMomentumPtYPhiM(1.0, jet.rap(), jet.phi(), 0.0);
            return p;
        }

        @Override public String description() { return "Light-like version with pT=1"; }
    }

    /**
     * Winner-take-all recombination, fastjet::jwj::WinnerTakeAllRecombiner:
     * the harder input's direction with the summed pt. The result keeps the
     * harder input's user index, which is how the axes are traced back to
     * particles.
     */
    public static final class WinnerTakeAllRecombiner implements Recombiner {
        private final LightLikeAxis lightLikeVersion = new LightLikeAxis();

        @Override public String description() { return "WinnerTakeAll Recombination Scheme"; }

        @Override
        public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
            PseudoJet harder = pa;
            final double pTa = pa.pt();
            final double pTb = pb.pt();
            if (pTa < pTb) harder = pb;
            final PseudoJet direction = lightLikeVersion.result(harder);
            pab.reset(direction.times(pTa + pTb));
        }
    }

    /**
     * The jet axes of the jet-count density, fastjet::jwj::EventShapeDensity_JetAxes:
     * each particle passing the cut points, through a winner-take-all
     * clustering of its neighbourhood, to an axis particle; the axes are
     * weighted by the pt and jet-count weight pointing to them, and the
     * global consistency condition follows the pointers to stable axes.
     */
    public static final class EventShapeDensity_JetAxes {
        private final double rjet;
        private final double ptcut;
        private final JetDefinition jetDef;
        private boolean applyGlobalConsistency;
        private boolean useLocalStorage = true;
        private int n;
        private List<PseudoJet> myParticles = new ArrayList<>();
        private int[] axes = new int[0];
        private double[] njetWeights = new double[0];
        private double[] ptWeights = new double[0];
        private List<PseudoJet> distinctAxes = new ArrayList<>();
        private List<Double> totNjetWeights = new ArrayList<>();
        private final LightLikeAxis lightLikeVersion = new LightLikeAxis();

        public EventShapeDensity_JetAxes(double rjet, double ptcut, JetAlgorithm jetAlgo, boolean applyGlobalConsistency) {
            this.rjet = rjet;
            this.ptcut = ptcut;
            this.jetDef = new JetDefinition(jetAlgo, 2.0 * rjet, new WinnerTakeAllRecombiner(), Strategy.BEST);
            this.applyGlobalConsistency = applyGlobalConsistency;
        }

        public EventShapeDensity_JetAxes(double rjet, double ptcut, JetAlgorithm jetAlgo) {
            this(rjet, ptcut, jetAlgo, false);
        }

        public EventShapeDensity_JetAxes(double rjet, double ptcut, boolean applyGlobalConsistency) {
            this(rjet, ptcut, JetAlgorithm.ANTIKT, applyGlobalConsistency);
        }

        public EventShapeDensity_JetAxes(double rjet, double ptcut) {
            this(rjet, ptcut, JetAlgorithm.ANTIKT, false);
        }

        public void setInput(List<PseudoJet> particles) {
            findLocalAxes(particles);
            findAxesAndWeights();
        }

        public void setGlobalConsistencyCheck(boolean v) { applyGlobalConsistency = v; }
        public void setUseLocalStorage(boolean v) { useLocalStorage = v; }

        public List<PseudoJet> axes() {
            final List<PseudoJet> r = new ArrayList<>(distinctAxes.size());
            for (PseudoJet a : distinctAxes) r.add(a.copy());
            return r;
        }

        public List<Double> njetWeights() { return new ArrayList<>(totNjetWeights); }

        public String parameterString() {
            String s = "R_jet=" + Fmt.g(rjet) + ", pT_cut=" + Fmt.g(ptcut) + ". Local clustering with " + jetDef.description() + ".";
            if (applyGlobalConsistency) s += " Global consistency condition on.";
            return s;
        }

        public String description() { return "Hybrid event shape density for finding jet axes." + parameterString(); }

        private void findLocalAxes(List<PseudoJet> particles) {
            n = particles.size();
            myParticles = new ArrayList<>(n);
            for (int i = 0; i < n; i++) myParticles.add(particles.get(i).copy().setUserIndex(i));
            axes = new int[n];
            njetWeights = new double[n];
            ptWeights = new double[n];
            final EventStorage s = new EventStorage(rjet, ptcut, rjet, 1.0, useLocalStorage);
            s.establishStorage(myParticles);
            final FunctionJetAxis axis = new FunctionJetAxis(jetDef);
            for (int i = 0; i < n; i++) {
                int myAxis = -1;
                double ptW = 0;
                double njetW = 0;
                if (s.get(i).includeParticle()) {
                    myAxis = axis.result(s.particlesNearTo(i)).userIndex();
                    ptW = s.get(i).pt();
                    njetW = s.get(i).weight();
                }
                axes[i] = myAxis;
                ptWeights[i] = ptW;
                njetWeights[i] = njetW;
            }
        }

        private boolean isStable(int thisAxis) {
            return axes[thisAxis] == thisAxis || axes[thisAxis] == -1;
        }

        /** Finds the axes and their weights from the stored local axes. */
        public void findAxesAndWeights() {
            if (applyGlobalConsistency) {
                int nUnstable;
                do {
                    nUnstable = 0;
                    for (int i = 0; i < n; i++) {
                        if (axes[i] != -1 && !isStable(axes[i])) {
                            nUnstable++;
                            axes[i] = axes[axes[i]];
                        }
                    }
                } while (nUnstable > 0);
            }
            final double[] totNjet = new double[n];
            final double[] totPt = new double[n];
            for (int i = 0; i < n; i++) {
                if (axes[i] != -1) {
                    totPt[axes[i]] += ptWeights[i];
                    totNjet[axes[i]] += njetWeights[i];
                }
            }
            final List<PseudoJet> found = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (totPt[i] > 0) found.add(lightLikeVersion.result(myParticles.get(i)).times(totPt[i]));
            }
            distinctAxes = PseudoJet.sortedByPt(found);
            totNjetWeights = new ArrayList<>();
            for (PseudoJet a : distinctAxes) totNjetWeights.add(totNjet[a.userIndex()]);
        }
    }
}
