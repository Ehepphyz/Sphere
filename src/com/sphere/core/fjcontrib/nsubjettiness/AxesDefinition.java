package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fjcontrib.internal.GlibcRandom;
import com.sphere.core.fjcontrib.nsubjettiness.ExtraRecombiners.GeneralEtSchemeRecombiner;
import com.sphere.core.fjcontrib.nsubjettiness.ExtraRecombiners.WinnerTakeAllRecombiner;

import java.util.ArrayList;
import java.util.List;

import static com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.f2;

/**
 * How the N axes are found, AxesDefinition (Nsubjettiness 2.3.2): seeds
 * from a jet algorithm (exclusive, inclusive-hardest, or N+m choose N), then
 * no refining, a one-pass minimisation of tau, or several passes from
 * jiggled seeds. The definitions are the nested classes, with the C++
 * names: {@code new KT_Axes()}, {@code new OnePass_WTA_KT_Axes()},
 * {@code new GenET_GenKT_Axes(delta, p, R0)}...
 */
public abstract class AxesDefinition {

    public static final int UNDEFINED_REFINE = -1;
    public static final int NO_REFINING = 0;
    public static final int ONE_PASS = 1;
    public static final int MULTI_PASS = 100;

    protected int nPass = UNDEFINED_REFINE;
    protected int nAttempts;
    protected double accuracy;
    protected double noiseRange;
    protected boolean needsManualAxes;

    protected AxesDefinition() {
    }

    /** The seed axes (the measure may be used to choose among several, never to refine). */
    public abstract List<PseudoJet> getStartingAxes(int nJets, List<PseudoJet> inputs, MeasureDefinition measure);

    public abstract String shortDescription();

    public abstract String description();

    public abstract AxesDefinition create();

    /** The seeds refined by the passes this definition asks for. */
    public List<PseudoJet> getRefinedAxes(int nJets, List<PseudoJet> inputs, List<PseudoJet> seedAxes,
                                          MeasureDefinition measure) {
        if (nJets != seedAxes.size()) throw new FastJetException("AxesDefinition: " + nJets + " seed axes expected");
        if (nPass == 0) {
            return seedAxes;
        } else if (nPass == 1) {
            if (measure == null) throw new FastJetException("AxesDefinition:  One-pass minimization requires specifying a MeasureDefinition.");
            return measure.getOnePassAxes(nJets, inputs, seedAxes, nAttempts, accuracy);
        } else {
            if (measure == null) throw new FastJetException("AxesDefinition:  Multi-pass minimization requires specifying a MeasureDefinition.");
            return getMultiPassAxes(nJets, inputs, seedAxes, measure);
        }
    }

    public List<PseudoJet> getAxes(int nJets, List<PseudoJet> inputs, MeasureDefinition measure) {
        return getRefinedAxes(nJets, inputs, getStartingAxes(nJets, inputs, measure), measure);
    }

    public int nPass() { return nPass; }

    public boolean givesRandomizedResults() { return nPass > 1; }

    public boolean needsManualAxes() { return needsManualAxes; }

    public void setNPass(int nPass, int nAttempts, double accuracy, double noiseRange) {
        this.nPass = nPass;
        this.nAttempts = nAttempts;
        this.accuracy = accuracy;
        this.noiseRange = noiseRange;
        if (nPass < 0) throw new FastJetException("AxesDefinition requires a nPass >= 0");
    }

    public void setNPass(int nPass) {
        setNPass(nPass, 1000, 0.0001, 1.0);
    }

    protected void copyPasses(AxesDefinition o) {
        nPass = o.nPass;
        nAttempts = o.nAttempts;
        accuracy = o.accuracy;
        noiseRange = o.noiseRange;
        needsManualAxes = o.needsManualAxes;
    }

    /** Several one-pass minimisations from seeds jiggled within the noise range; the best is kept. */
    protected List<PseudoJet> getMultiPassAxes(int nJets, List<PseudoJet> inputJets, List<PseudoJet> seedAxes,
                                               MeasureDefinition measure) {
        List<PseudoJet> bestAxes = measure.getOnePassAxes(nJets, inputJets, seedAxes, nAttempts, accuracy);
        double bestTau = measure.result(inputJets, bestAxes);
        for (int l = 1; l < nPass; l++) {
            final List<PseudoJet> noiseAxes = new ArrayList<>(nJets);
            for (int k = 0; k < nJets; k++) noiseAxes.add(jiggle(bestAxes.get(k)));
            final List<PseudoJet> testAxes = measure.getOnePassAxes(nJets, inputJets, noiseAxes, nAttempts, accuracy);
            final double testTau = measure.result(inputJets, testAxes);
            if (testTau < bestTau) {
                bestTau = testTau;
                bestAxes = testAxes;
            }
        }
        return bestAxes;
    }

    /** An axis moved at random within the noise range, with the C library's rand(). */
    protected PseudoJet jiggle(PseudoJet axis) {
        final double phiNoise = ((double) GlibcRandom.rand() / (double) GlibcRandom.RAND_MAX) * noiseRange * 2.0 - noiseRange;
        final double rapNoise = ((double) GlibcRandom.rand() / (double) GlibcRandom.RAND_MAX) * noiseRange * 2.0 - noiseRange;
        double newPhi = axis.phi() + phiNoise;
        if (newPhi >= 2.0 * Math.PI) newPhi -= 2.0 * Math.PI;
        if (newPhi <= -2.0 * Math.PI) newPhi += 2.0 * Math.PI;
        final PseudoJet newAxis = new PseudoJet(0, 0, 0, 0);
        newAxis.resetPtYPhiM(axis.perp(), axis.rap() + rapNoise, newPhi, 0.0);
        return newAxis;
    }

    /* ================================================================== */
    /* Seeds from jet algorithms                                          */
    /* ================================================================== */

    private static final LimitedWarning EXCL_TOO_FEW = new LimitedWarning();
    private static final LimitedWarning COMB_TOO_FEW = new LimitedWarning();
    private static final LimitedWarning HARD_TOO_FEW = new LimitedWarning();

    /** JetDefinitionWrapper: a definition with its own recombiner. */
    static JetDefinition withRecombiner(JetAlgorithm alg, double r, double extra, Recombiner recombiner) {
        final JetDefinition def = new JetDefinition(alg, r, extra);
        def.setRecombiner(recombiner);
        return def;
    }

    static JetDefinition withRecombiner(JetAlgorithm alg, double r, Recombiner recombiner, Strategy strategy) {
        return new JetDefinition(alg, r, recombiner, strategy);
    }

    /** Axes from an exclusive clustering to N jets, ExclusiveJetAxes. */
    public static class ExclusiveJetAxes extends AxesDefinition {
        protected final JetDefinition def;

        public ExclusiveJetAxes(JetDefinition def) {
            this.def = def;
            setNPass(NO_REFINING);
        }

        @Override
        public List<PseudoJet> getStartingAxes(int nJets, List<PseudoJet> inputs, MeasureDefinition measure) {
            final JetDefinition d = new JetDefinition(def);
            if (!inputs.isEmpty()) d.setPrecision(inputs.get(0).precision());
            final List<PseudoJet> axesTemp = new ArrayList<>(new ClusterSequence(inputs, d).exclusiveJetsUpTo(nJets));
            if (axesTemp.size() < nJets) {
                EXCL_TOO_FEW.warn("ExclusiveJetAxes::get_starting_axes:  Fewer than N axes found; results are unpredictable.");
                while (axesTemp.size() < nJets) axesTemp.add(new PseudoJet());
            }
            final List<PseudoJet> axes = new ArrayList<>(nJets);
            for (int i = 0; i < nJets; i++) {
                final PseudoJet a = new PseudoJet();
                a.resetMomentum(axesTemp.get(i));
                axes.add(a);
            }
            return axes;
        }

        @Override
        public String shortDescription() { return "ExclAxes"; }

        @Override
        public String description() { return "ExclAxes: " + def.description(); }

        @Override
        public AxesDefinition create() {
            final ExclusiveJetAxes c = new ExclusiveJetAxes(def);
            c.copyPasses(this);
            return c;
        }
    }

    /**
     * N + nExtra exclusive axes, and the N of them giving the smallest tau,
     * ExclusiveCombinatorialJetAxes (subsets taken in std::prev_permutation order).
     */
    public static class ExclusiveCombinatorialJetAxes extends AxesDefinition {
        protected final JetDefinition def;
        protected final int nExtra;

        public ExclusiveCombinatorialJetAxes(JetDefinition def, int nExtra) {
            if (nExtra < 0) throw new FastJetException("Need nExtra >= 0");
            this.def = def;
            this.nExtra = nExtra;
            setNPass(NO_REFINING);
        }

        public ExclusiveCombinatorialJetAxes(JetDefinition def) {
            this(def, 0);
        }

        @Override
        public List<PseudoJet> getStartingAxes(int nJets, List<PseudoJet> inputs, MeasureDefinition measure) {
            final int startingNumber = nJets + nExtra;
            final JetDefinition d = new JetDefinition(def);
            if (!inputs.isEmpty()) d.setPrecision(inputs.get(0).precision());
            final List<PseudoJet> startingAxes = new ArrayList<>(new ClusterSequence(inputs, d).exclusiveJetsUpTo(startingNumber));
            if (startingAxes.size() < nJets) {
                COMB_TOO_FEW.warn("ExclusiveCombinatorialJetAxes::get_starting_axes:  Fewer than N + nExtra axes found; results are unpredictable.");
                while (startingAxes.size() < nJets) startingAxes.add(new PseudoJet());
                while (startingAxes.size() > nJets) startingAxes.remove(startingAxes.size() - 1);
            }
            if (nExtra == 0) return startingAxes;
            final int[] bitmask = new int[startingNumber];
            for (int i = 0; i < nJets; i++) bitmask[i] = 1;
            double minTau = Double.MAX_VALUE;
            List<PseudoJet> finalAxes = new ArrayList<>();
            do {
                final List<PseudoJet> temp = new ArrayList<>();
                for (int i = 0; i < startingAxes.size(); i++) if (bitmask[i] != 0) temp.add(startingAxes.get(i));
                final double tempTau = measure.result(inputs, temp);
                if (tempTau < minTau) {
                    minTau = tempTau;
                    finalAxes = temp;
                }
            } while (prevPermutation(bitmask));
            return finalAxes;
        }

        /** std::prev_permutation. */
        static boolean prevPermutation(int[] a) {
            int i = a.length - 1;
            while (i > 0 && a[i - 1] <= a[i]) i--;
            if (i <= 0) {
                reverse(a, 0, a.length - 1);
                return false;
            }
            int j = a.length - 1;
            while (a[j] >= a[i - 1]) j--;
            final int t = a[i - 1];
            a[i - 1] = a[j];
            a[j] = t;
            reverse(a, i, a.length - 1);
            return true;
        }

        private static void reverse(int[] a, int lo, int hi) {
            while (lo < hi) {
                final int t = a[lo];
                a[lo++] = a[hi];
                a[hi--] = t;
            }
        }

        @Override
        public String shortDescription() { return "ExclCombAxes"; }

        @Override
        public String description() { return "ExclCombAxes: " + def.description(); }

        @Override
        public AxesDefinition create() {
            final ExclusiveCombinatorialJetAxes c = new ExclusiveCombinatorialJetAxes(def, nExtra);
            c.copyPasses(this);
            return c;
        }
    }

    /** The N hardest inclusive jets, HardestJetAxes. */
    public static class HardestJetAxes extends AxesDefinition {
        protected final JetDefinition def;

        public HardestJetAxes(JetDefinition def) {
            this.def = def;
            setNPass(NO_REFINING);
        }

        @Override
        public List<PseudoJet> getStartingAxes(int nJets, List<PseudoJet> inputs, MeasureDefinition measure) {
            final JetDefinition d = new JetDefinition(def);
            if (!inputs.isEmpty()) d.setPrecision(inputs.get(0).precision());
            final List<PseudoJet> axes = new ArrayList<>(PseudoJet.sortedByPt(new ClusterSequence(inputs, d).inclusiveJets()));
            if (axes.size() < nJets) {
                HARD_TOO_FEW.warn("HardestJetAxes::get_starting_axes:  Fewer than N axes found; results are unpredictable.");
            }
            while (axes.size() < nJets) axes.add(new PseudoJet());
            while (axes.size() > nJets) axes.remove(axes.size() - 1);
            return axes;
        }

        @Override
        public String shortDescription() { return "HardAxes"; }

        @Override
        public String description() { return "HardAxes: " + def.description(); }

        @Override
        public AxesDefinition create() {
            final HardestJetAxes c = new HardestJetAxes(def);
            c.copyPasses(this);
            return c;
        }
    }

    /* ---------------------------------------------------------------- */
    /* The named axes                                                   */
    /* ---------------------------------------------------------------- */

    /** Exclusive generalised kt with p = 1/2, E scheme. */
    public static class HalfKT_Axes extends ExclusiveJetAxes {
        public HalfKT_Axes() {
            super(new JetDefinition(JetAlgorithm.GENKT, JetDefinition.MAX_ALLOWABLE_R, 0.5));
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "HalfKT"; }
        @Override public String description() { return "Half KT Axes"; }
        @Override public AxesDefinition create() { final HalfKT_Axes c = new HalfKT_Axes(); c.copyPasses(this); return c; }
    }

    /** Exclusive kt, E scheme. */
    public static class KT_Axes extends ExclusiveJetAxes {
        public KT_Axes() {
            super(new JetDefinition(JetAlgorithm.KT, JetDefinition.MAX_ALLOWABLE_R, RecombinationScheme.E_SCHEME, Strategy.BEST));
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "KT"; }
        @Override public String description() { return "KT Axes"; }
        @Override public AxesDefinition create() { final KT_Axes c = new KT_Axes(); c.copyPasses(this); return c; }
    }

    /** Exclusive Cambridge/Aachen, E scheme. */
    public static class CA_Axes extends ExclusiveJetAxes {
        public CA_Axes() {
            super(new JetDefinition(JetAlgorithm.CAMBRIDGE, JetDefinition.MAX_ALLOWABLE_R, RecombinationScheme.E_SCHEME, Strategy.BEST));
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "CA"; }
        @Override public String description() { return "CA Axes"; }
        @Override public AxesDefinition create() { final CA_Axes c = new CA_Axes(); c.copyPasses(this); return c; }
    }

    /** The hardest inclusive anti-kt jets of radius R0. */
    public static class AntiKT_Axes extends HardestJetAxes {
        protected final double r0;

        public AntiKT_Axes(double r0) {
            super(new JetDefinition(JetAlgorithm.ANTIKT, r0, RecombinationScheme.E_SCHEME, Strategy.BEST));
            this.r0 = r0;
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "AKT" + f2(r0); }
        @Override public String description() { return "Anti-KT Axes (R0 = " + f2(r0) + ")"; }
        @Override public AxesDefinition create() { final AntiKT_Axes c = new AntiKT_Axes(r0); c.copyPasses(this); return c; }
    }

    /** Exclusive generalised kt (p = 1/2), winner take all. */
    public static class WTA_HalfKT_Axes extends ExclusiveJetAxes {
        public WTA_HalfKT_Axes() {
            super(withRecombiner(JetAlgorithm.GENKT, JetDefinition.MAX_ALLOWABLE_R, 0.5, new WinnerTakeAllRecombiner()));
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "WTA, HalfKT"; }
        @Override public String description() { return "Winner-Take-All Half KT Axes"; }
        @Override public AxesDefinition create() { final WTA_HalfKT_Axes c = new WTA_HalfKT_Axes(); c.copyPasses(this); return c; }
    }

    /** Exclusive kt, winner take all. */
    public static class WTA_KT_Axes extends ExclusiveJetAxes {
        public WTA_KT_Axes() {
            super(withRecombiner(JetAlgorithm.KT, JetDefinition.MAX_ALLOWABLE_R, new WinnerTakeAllRecombiner(), Strategy.BEST));
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "WTA KT"; }
        @Override public String description() { return "Winner-Take-All KT Axes"; }
        @Override public AxesDefinition create() { final WTA_KT_Axes c = new WTA_KT_Axes(); c.copyPasses(this); return c; }
    }

    /** Exclusive C/A, winner take all. */
    public static class WTA_CA_Axes extends ExclusiveJetAxes {
        public WTA_CA_Axes() {
            super(withRecombiner(JetAlgorithm.CAMBRIDGE, JetDefinition.MAX_ALLOWABLE_R, new WinnerTakeAllRecombiner(), Strategy.BEST));
            setNPass(NO_REFINING);
        }

        @Override public String shortDescription() { return "WTA CA"; }
        @Override public String description() { return "Winner-Take-All CA Axes"; }
        @Override public AxesDefinition create() { final WTA_CA_Axes c = new WTA_CA_Axes(); c.copyPasses(this); return c; }
    }

    /** Exclusive generalised kt of power p. */
    public static class GenKT_Axes extends ExclusiveJetAxes {
        protected final double p;
        protected final double r0;

        public GenKT_Axes(double p, double r0) {
            super(new JetDefinition(JetAlgorithm.GENKT, r0, p));
            this.p = p;
            this.r0 = r0;
            if (p < 0) throw new FastJetException("GenKT_Axes:  Currently only p >=0 is supported.");
            setNPass(NO_REFINING);
        }

        public GenKT_Axes(double p) {
            this(p, JetDefinition.MAX_ALLOWABLE_R);
        }

        @Override public String shortDescription() { return "GenKT Axes"; }
        @Override public String description() { return "General KT (p = " + f2(p) + "), R0 = " + f2(r0); }
        @Override public AxesDefinition create() { final GenKT_Axes c = new GenKT_Axes(p, r0); c.copyPasses(this); return c; }
    }

    /** Exclusive generalised kt of power p, winner take all. */
    public static class WTA_GenKT_Axes extends ExclusiveJetAxes {
        protected final double p;
        protected final double r0;

        public WTA_GenKT_Axes(double p, double r0) {
            super(withRecombiner(JetAlgorithm.GENKT, r0, p, new WinnerTakeAllRecombiner()));
            this.p = p;
            this.r0 = r0;
            if (p < 0) throw new FastJetException("WTA_GenKT_Axes:  Currently only p >=0 is supported.");
            setNPass(NO_REFINING);
        }

        public WTA_GenKT_Axes(double p) {
            this(p, JetDefinition.MAX_ALLOWABLE_R);
        }

        @Override public String shortDescription() { return "WTA, GenKT Axes"; }
        @Override public String description() { return "Winner-Take-All General KT (p = " + f2(p) + "), R0 = " + f2(r0); }
        @Override public AxesDefinition create() { final WTA_GenKT_Axes c = new WTA_GenKT_Axes(p, r0); c.copyPasses(this); return c; }
    }

    /** Exclusive generalised kt of power p, generalised Et recombination of power delta. */
    public static class GenET_GenKT_Axes extends ExclusiveJetAxes {
        protected final double delta;
        protected final double p;
        protected final double r0;

        public GenET_GenKT_Axes(double delta, double p, double r0) {
            super(withRecombiner(JetAlgorithm.GENKT, r0, p, new GeneralEtSchemeRecombiner(delta)));
            this.delta = delta;
            this.p = p;
            this.r0 = r0;
            if (p < 0) throw new FastJetException("GenET_GenKT_Axes:  Currently only p >=0 is supported.");
            if (delta <= 0) throw new FastJetException("GenET_GenKT_Axes:  Currently only delta >0 is supported.");
            setNPass(NO_REFINING);
        }

        public GenET_GenKT_Axes(double delta, double p) {
            this(delta, p, JetDefinition.MAX_ALLOWABLE_R);
        }

        @Override public String shortDescription() { return "GenET, GenKT Axes"; }

        @Override
        public String description() {
            if (delta < Integer.MAX_VALUE) {
                return "General Recombiner (delta = " + f2(delta) + "), General KT (p = " + f2(p) + ") Axes, R0 = " + f2(r0);
            }
            return "Winner-Take-All General KT (p = " + f2(p) + "), R0 = " + f2(r0);
        }

        @Override public AxesDefinition create() { final GenET_GenKT_Axes c = new GenET_GenKT_Axes(delta, p, r0); c.copyPasses(this); return c; }
    }

    /* ---- one-pass minimisation from the seeds above ---- */

    public static class OnePass_HalfKT_Axes extends HalfKT_Axes {
        public OnePass_HalfKT_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass HalfKT"; }
        @Override public String description() { return "One-Pass Minimization from Half KT Axes"; }
        @Override public AxesDefinition create() { final OnePass_HalfKT_Axes c = new OnePass_HalfKT_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_KT_Axes extends KT_Axes {
        public OnePass_KT_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass KT"; }
        @Override public String description() { return "One-Pass Minimization from KT Axes"; }
        @Override public AxesDefinition create() { final OnePass_KT_Axes c = new OnePass_KT_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_CA_Axes extends CA_Axes {
        public OnePass_CA_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass CA"; }
        @Override public String description() { return "One-Pass Minimization from CA Axes"; }
        @Override public AxesDefinition create() { final OnePass_CA_Axes c = new OnePass_CA_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_AntiKT_Axes extends AntiKT_Axes {
        public OnePass_AntiKT_Axes(double r0) { super(r0); setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePassAKT" + f2(r0); }
        @Override public String description() { return "One-Pass Minimization from Anti-KT Axes (R0 = " + f2(r0) + ")"; }
        @Override public AxesDefinition create() { final OnePass_AntiKT_Axes c = new OnePass_AntiKT_Axes(r0); c.copyPasses(this); return c; }
    }

    public static class OnePass_WTA_HalfKT_Axes extends WTA_HalfKT_Axes {
        public OnePass_WTA_HalfKT_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass WTA HalfKT"; }
        @Override public String description() { return "One-Pass Minimization from Winner-Take-All Half KT Axes"; }
        @Override public AxesDefinition create() { final OnePass_WTA_HalfKT_Axes c = new OnePass_WTA_HalfKT_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_WTA_KT_Axes extends WTA_KT_Axes {
        public OnePass_WTA_KT_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass WTA KT"; }
        @Override public String description() { return "One-Pass Minimization from Winner-Take-All KT Axes"; }
        @Override public AxesDefinition create() { final OnePass_WTA_KT_Axes c = new OnePass_WTA_KT_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_WTA_CA_Axes extends WTA_CA_Axes {
        public OnePass_WTA_CA_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass WTA CA"; }
        @Override public String description() { return "One-Pass Minimization from Winner-Take-All CA Axes"; }
        @Override public AxesDefinition create() { final OnePass_WTA_CA_Axes c = new OnePass_WTA_CA_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_GenKT_Axes extends GenKT_Axes {
        public OnePass_GenKT_Axes(double p, double r0) { super(p, r0); setNPass(ONE_PASS); }
        public OnePass_GenKT_Axes(double p) { this(p, JetDefinition.MAX_ALLOWABLE_R); }
        @Override public String shortDescription() { return "OnePass GenKT"; }
        @Override public String description() { return "One-Pass Minimization from General KT (p = " + f2(p) + "), R0 = " + f2(r0); }
        @Override public AxesDefinition create() { final OnePass_GenKT_Axes c = new OnePass_GenKT_Axes(p, r0); c.copyPasses(this); return c; }
    }

    public static class OnePass_WTA_GenKT_Axes extends WTA_GenKT_Axes {
        public OnePass_WTA_GenKT_Axes(double p, double r0) { super(p, r0); setNPass(ONE_PASS); }
        public OnePass_WTA_GenKT_Axes(double p) { this(p, JetDefinition.MAX_ALLOWABLE_R); }
        @Override public String shortDescription() { return "OnePass WTA GenKT"; }
        @Override public String description() { return "One-Pass Minimization from Winner-Take-All General KT (p = " + f2(p) + "), R0 = " + f2(r0); }
        @Override public AxesDefinition create() { final OnePass_WTA_GenKT_Axes c = new OnePass_WTA_GenKT_Axes(p, r0); c.copyPasses(this); return c; }
    }

    public static class OnePass_GenET_GenKT_Axes extends GenET_GenKT_Axes {
        public OnePass_GenET_GenKT_Axes(double delta, double p, double r0) { super(delta, p, r0); setNPass(ONE_PASS); }
        public OnePass_GenET_GenKT_Axes(double delta, double p) { this(delta, p, JetDefinition.MAX_ALLOWABLE_R); }
        @Override public String shortDescription() { return "OnePass GenET, GenKT"; }

        @Override
        public String description() {
            if (delta < Integer.MAX_VALUE) {
                return "One-Pass Minimization from General Recombiner (delta = " + f2(delta) + "), General KT (p = "
                    + f2(p) + ") Axes, R0 = " + f2(r0);
            }
            return "One-Pass Minimization from Winner-Take-All General KT (p = " + f2(p) + "), R0 = " + f2(r0);
        }

        @Override public AxesDefinition create() { final OnePass_GenET_GenKT_Axes c = new OnePass_GenET_GenKT_Axes(delta, p, r0); c.copyPasses(this); return c; }
    }

    /* ---- manual and multi-pass ---- */

    /** Axes given by the user (Njettiness.setAxes). */
    public static class Manual_Axes extends AxesDefinition {
        public Manual_Axes() {
            setNPass(NO_REFINING);
            needsManualAxes = true;
        }

        @Override
        public List<PseudoJet> getStartingAxes(int nJets, List<PseudoJet> inputs, MeasureDefinition measure) {
            throw new FastJetException("Manual_Axes::get_starting_axes should never be called");
        }

        @Override public String shortDescription() { return "Manual"; }
        @Override public String description() { return "Manual Axes"; }
        @Override public AxesDefinition create() { final Manual_Axes c = new Manual_Axes(); c.copyPasses(this); return c; }
    }

    public static class OnePass_Manual_Axes extends Manual_Axes {
        public OnePass_Manual_Axes() { setNPass(ONE_PASS); }
        @Override public String shortDescription() { return "OnePass Manual"; }
        @Override public String description() { return "One-Pass Minimization from Manual Axes"; }
        @Override public AxesDefinition create() { final OnePass_Manual_Axes c = new OnePass_Manual_Axes(); c.copyPasses(this); return c; }
    }

    /** Npass minimisations from jiggled kt seeds (randomised). */
    public static class MultiPass_Axes extends KT_Axes {
        public MultiPass_Axes(int npass) { setNPass(npass); }
        @Override public String shortDescription() { return "MultiPass"; }
        @Override public String description() { return "Multi-Pass Axes (Npass = " + nPass + ")"; }
        @Override public AxesDefinition create() { final MultiPass_Axes c = new MultiPass_Axes(nPass); c.copyPasses(this); return c; }
    }

    public static class MultiPass_Manual_Axes extends Manual_Axes {
        public MultiPass_Manual_Axes(int npass) { setNPass(npass); }
        @Override public String shortDescription() { return "MultiPass Manual"; }
        @Override public String description() { return "Multi-Pass Manual Axes (Npass = " + nPass + ")"; }
        @Override public AxesDefinition create() { final MultiPass_Manual_Axes c = new MultiPass_Manual_Axes(nPass); c.copyPasses(this); return c; }
    }

    /* ---- N + nExtra choose N ---- */

    public static class Comb_GenKT_Axes extends ExclusiveCombinatorialJetAxes {
        protected final double p;
        protected final double r0;

        public Comb_GenKT_Axes(int nExtra, double p, double r0) {
            super(new JetDefinition(JetAlgorithm.GENKT, r0, p), nExtra);
            this.p = p;
            this.r0 = r0;
            if (p < 0) throw new FastJetException("Comb_GenKT_Axes:  Currently only p >=0 is supported.");
            setNPass(NO_REFINING);
        }

        public Comb_GenKT_Axes(int nExtra, double p) {
            this(nExtra, p, JetDefinition.MAX_ALLOWABLE_R);
        }

        @Override public String shortDescription() { return "N Choose M GenKT"; }

        @Override
        public String description() {
            return "N Choose M Minimization (nExtra = " + f2(nExtra) + ") from General KT (p = " + f2(p) + "), R0 = " + f2(r0);
        }

        @Override public AxesDefinition create() { final Comb_GenKT_Axes c = new Comb_GenKT_Axes(nExtra, p, r0); c.copyPasses(this); return c; }
    }

    public static class Comb_WTA_GenKT_Axes extends ExclusiveCombinatorialJetAxes {
        protected final double p;
        protected final double r0;

        public Comb_WTA_GenKT_Axes(int nExtra, double p, double r0) {
            super(withRecombiner(JetAlgorithm.GENKT, r0, p, new WinnerTakeAllRecombiner()), nExtra);
            this.p = p;
            this.r0 = r0;
            if (p < 0) throw new FastJetException("Comb_WTA_GenKT_Axes:  Currently only p >=0 is supported.");
            setNPass(NO_REFINING);
        }

        public Comb_WTA_GenKT_Axes(int nExtra, double p) {
            this(nExtra, p, JetDefinition.MAX_ALLOWABLE_R);
        }

        @Override public String shortDescription() { return "N Choose M WTA GenKT"; }

        @Override
        public String description() {
            return "N Choose M Minimization (nExtra = " + f2(nExtra) + ") from Winner-Take-All General KT (p = " + f2(p)
                + "), R0 = " + f2(r0);
        }

        @Override public AxesDefinition create() { final Comb_WTA_GenKT_Axes c = new Comb_WTA_GenKT_Axes(nExtra, p, r0); c.copyPasses(this); return c; }
    }

    public static class Comb_GenET_GenKT_Axes extends ExclusiveCombinatorialJetAxes {
        protected final double delta;
        protected final double p;
        protected final double r0;

        public Comb_GenET_GenKT_Axes(int nExtra, double delta, double p, double r0) {
            super(withRecombiner(JetAlgorithm.GENKT, r0, p, new GeneralEtSchemeRecombiner(delta)), nExtra);
            this.delta = delta;
            this.p = p;
            this.r0 = r0;
            if (p < 0) throw new FastJetException("Comb_GenET_GenKT_Axes:  Currently only p >=0 is supported.");
            if (delta <= 0) throw new FastJetException("Comb_GenET_GenKT_Axes:  Currently only delta >=0 is supported.");
            setNPass(NO_REFINING);
        }

        public Comb_GenET_GenKT_Axes(int nExtra, double delta, double p) {
            this(nExtra, delta, p, JetDefinition.MAX_ALLOWABLE_R);
        }

        @Override public String shortDescription() { return "N Choose M GenET GenKT"; }

        @Override
        public String description() {
            if (delta < Integer.MAX_VALUE) {
                return "N choose M Minimization (nExtra = " + f2(nExtra) + ") from General Recombiner (delta = " + f2(delta)
                    + "), General KT (p = " + f2(p) + ") Axes, R0 = " + f2(r0);
            }
            return "N choose M Minimization (nExtra = " + f2(nExtra) + ") from Winner-Take-All General KT (p = " + f2(p)
                + "), R0 = " + f2(r0);
        }

        @Override public AxesDefinition create() { final Comb_GenET_GenKT_Axes c = new Comb_GenET_GenKT_Axes(nExtra, delta, p, r0); c.copyPasses(this); return c; }
    }
}
