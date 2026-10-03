package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.ClusterSequenceAreaBase;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;

import java.util.ArrayList;
import java.util.List;

/**
 * rho as the median of pt/area over the jets of a clustering with areas,
 * fastjet::JetMedianBackgroundEstimator, with sigma from the one-sigma
 * quantile and rho_m from the transverse-mass density.
 */
public class JetMedianBackgroundEstimator extends BackgroundEstimatorBase {

    static {
        Citations.use("rho"); // listed in the console's Citations menu once used
    }

    private static final LimitedWarning WARNINGS = new LimitedWarning();
    private static final LimitedWarning WARNINGS_ZERO_AREA = new LimitedWarning();
    private static final LimitedWarning WARNINGS_PRELIMINARY = new LimitedWarning();

    /** What an estimate also records: the reference jet and the jets and empty area used. */
    public static final class Extras {
        PseudoJet referenceJet = new PseudoJet();
        int nJetsUsed;
        double nEmptyJets;
        double emptyArea;

        public PseudoJet referenceJet() { return referenceJet; }
        public int nJetsUsed() { return nJetsUsed; }
        public double nEmptyJets() { return nEmptyJets; }
        public double emptyArea() { return emptyArea; }
    }

    private Selector rhoRange;
    private JetDefinition jetDef;
    private AreaDefinition areaDef;
    private List<PseudoJet> includedJets = new ArrayList<>();
    private boolean useArea4vector;
    private boolean provideFj2Sigma;
    private FunctionOfPseudoJet<Double> jetDensityClass;
    private boolean enableRhoM = true;
    private ClusterSequenceAreaBase csab;

    public JetMedianBackgroundEstimator(Selector rhoRange, JetDefinition jetDef, AreaDefinition areaDef) {
        this.rhoRange = rhoRange;
        this.jetDef = jetDef;
        this.areaDef = areaDef;
        reset();
        checkJetAlgGoodForMedian();
    }

    public JetMedianBackgroundEstimator(Selector rhoRange, ClusterSequenceAreaBase csa) {
        this.rhoRange = rhoRange;
        this.jetDef = new JetDefinition();
        reset();
        setClusterSequence(csa);
    }

    public JetMedianBackgroundEstimator(Selector rhoRange) {
        this.rhoRange = rhoRange;
        this.jetDef = new JetDefinition();
        reset();
    }

    public JetMedianBackgroundEstimator() {
        this(Selector.identity());
    }

    @Override
    public void setParticles(List<PseudoJet> particles) {
        setParticlesWithSeed(particles, null);
    }

    @Override
    public void setParticlesWithSeed(List<PseudoJet> particles, int[] seed) {
        if (jetDef.jetAlgorithm() == JetAlgorithm.UNDEFINED) {
            throw new FastJetException("JetMedianBackgroundEstimator::set_particles can only be called if you set the jet (and area) definition explicitly through the class constructor");
        }
        final ClusterSequenceArea csa = seed == null
            ? new ClusterSequenceArea(particles, jetDef, areaDef)
            : new ClusterSequenceArea(particles, jetDef, areaDef.withFixedSeed(seed));
        includedJets = csa.inclusiveJets();
        csab = csa;
        cacheAvailable = false;
    }

    public void setClusterSequence(ClusterSequenceAreaBase csa) {
        if (!csa.hasExplicitGhosts() && !rhoRange.hasFiniteArea()) {
            throw new FastJetException("JetMedianBackgroundEstimator: either an area with explicit ghosts (recommended) or a Selector with finite area is needed (to allow for the computation of the empty area)");
        }
        csab = csa;
        checkJetAlgGoodForMedian();
        includedJets = csa.inclusiveJets();
        cacheAvailable = false;
    }

    public void setJets(List<PseudoJet> jets) {
        if (jets.isEmpty()) {
            throw new FastJetException("JetMedianBackgroundEstimator::JetMedianBackgroundEstimator: At least one jet is needed to compute the background properties");
        }
        final ClusterSequenceAreaBase c = jets.get(0).validatedCsab();
        for (PseudoJet j : jets) {
            if (!j.hasAssociatedClusterSequence() || j.associatedClusterSequence() != c) {
                throw new FastJetException("JetMedianBackgroundEstimator::set_jets(...): all the jets used to estimate the background properties must share the same ClusterSequence");
            }
        }
        if (!c.hasExplicitGhosts() && !rhoRange.hasFiniteArea()) {
            throw new FastJetException("JetMedianBackgroundEstimator: either an area with explicit ghosts (recommended) or a Selector with finite area is needed (to allow for the computation of the empty area)");
        }
        csab = c;
        checkJetAlgGoodForMedian();
        includedJets = new ArrayList<>(jets);
        cacheAvailable = false;
    }

    public void setSelector(Selector s) {
        rhoRange = s;
        cacheAvailable = false;
    }

    public void setComputeRhoM(boolean enable) {
        enableRhoM = enable;
        cacheAvailable = false;
    }

    public void setUseArea4vector(boolean use) {
        useArea4vector = use;
        cacheAvailable = false;
    }

    public boolean useArea4vector() {
        return useArea4vector;
    }

    public void setProvideFj2Sigma(boolean fj2) {
        provideFj2Sigma = fj2;
        cacheAvailable = false;
    }

    public void setJetDensityClass(FunctionOfPseudoJet<Double> d) {
        WARNINGS_PRELIMINARY.warn("JetMedianBackgroundEstimator::set_jet_density_class: density classes are still preliminary in FastJet 3.1. Their interface may differ in future releases (without guaranteeing backward compatibility). Note that since FastJet 3.1, rho_m and sigma_m are accessible direclty in JetMedianBackgroundEstimator and GridMedianBackgroundEstimator(with no need for a density class).");
        jetDensityClass = d;
        cacheAvailable = false;
    }

    /** The density class set, or null for the default pt / area4vector. */
    public FunctionOfPseudoJet<Double> jetDensityClass() {
        return jetDensityClass;
    }

    @Override
    public void setRescalingClass(FunctionOfPseudoJet<Double> r) {
        super.setRescalingClass(r);
        cacheAvailable = false;
    }

    public void reset() {
        useArea4vector = true;
        provideFj2Sigma = false;
        enableRhoM = true;
        includedJets = new ArrayList<>();
        jetDensityClass = null;
        rescalingClass = null;
        cacheAvailable = false;
    }

    @Override
    public boolean hasSigma() {
        return true;
    }

    @Override
    public boolean hasRhoM() {
        return enableRhoM && jetDensityClass == null;
    }

    private void requireNoReference(String what) {
        if (rhoRange.takesReference()) {
            throw new FastJetException("The background estimation is obtained from a selector that takes a reference jet. "
                + what + "(PseudoJet) should be used in that case");
        }
    }

    private BackgroundEstimate cached() {
        if (!cacheAvailable) {
            cachedEstimate = compute(new PseudoJet());
            cacheAvailable = true;
        }
        return cachedEstimate;
    }

    @Override
    public BackgroundEstimate estimate() {
        requireNoReference("estimate");
        return new BackgroundEstimate(cached());
    }

    @Override
    public BackgroundEstimate estimate(PseudoJet jet) {
        final double f = rescalingClass != null ? rescalingClass.result(jet) : 1.0;
        final BackgroundEstimate local = rhoRange.takesReference() ? compute(jet) : new BackgroundEstimate(cached());
        local.applyRescalingFactor(f);
        return local;
    }

    @Override
    public double rho() {
        requireNoReference("rho");
        return cached().rho();
    }

    @Override
    public double sigma() {
        requireNoReference("sigma");
        return cached().sigma();
    }

    private BackgroundEstimate forJet(PseudoJet jet) {
        if (rhoRange.takesReference()) {
            cachedEstimate = compute(jet);
            cacheAvailable = true;
            return cachedEstimate;
        }
        return cached();
    }

    @Override
    public double rho(PseudoJet jet) {
        final double f = rescalingClass != null ? rescalingClass.result(jet) : 1.0;
        return f * forJet(jet).rho();
    }

    @Override
    public double sigma(PseudoJet jet) {
        final double f = rescalingClass != null ? rescalingClass.result(jet) : 1.0;
        return f * forJet(jet).sigma();
    }

    @Override
    public double rhoM() {
        if (!hasRhoM()) {
            throw new FastJetException("JetMediamBackgroundEstimator: rho_m requested but rho_m calculation is disabled (either eplicitly or due to the presence of a jet density class).");
        }
        requireNoReference("rho_m");
        return cached().rhoM();
    }

    @Override
    public double sigmaM() {
        if (!hasRhoM()) {
            throw new FastJetException("JetMediamBackgroundEstimator: sigma_m requested but rho_m/sigma_m calculation is disabled (either explicitly or due to the presence of a jet density class).");
        }
        requireNoReference("sigma_m");
        return cached().sigmaM();
    }

    @Override
    public double rhoM(PseudoJet jet) {
        final double f = rescalingClass != null ? rescalingClass.result(jet) : 1.0;
        return f * forJet(jet).rhoM();
    }

    @Override
    public double sigmaM(PseudoJet jet) {
        final double f = rescalingClass != null ? rescalingClass.result(jet) : 1.0;
        return f * forJet(jet).sigmaM();
    }

    public double meanArea() {
        return cachedOrThrow().meanArea();
    }

    public int nJetsUsed() {
        return cachedOrThrow().extras(Extras.class).nJetsUsed();
    }

    public double emptyArea() {
        return cachedOrThrow().extras(Extras.class).emptyArea();
    }

    public double nEmptyJets() {
        return cachedOrThrow().extras(Extras.class).nEmptyJets();
    }

    /** The jets that entered the median. */
    public List<PseudoJet> jetsUsed() {
        List<PseudoJet> tmp;
        if (rhoRange.takesReference()) {
            final PseudoJet ref = cachedOrThrow().extras(Extras.class).referenceJet();
            final Selector local = new Selector(rhoRange.worker());
            local.setReference(ref);
            tmp = local.apply(includedJets);
        } else {
            cached();
            tmp = rhoRange.apply(includedJets);
        }
        final List<PseudoJet> used = new ArrayList<>();
        for (PseudoJet j : tmp) {
            if (j.area() > 0) used.add(j);
        }
        return used;
    }

    private BackgroundEstimate cachedOrThrow() {
        if (rhoRange.takesReference()) {
            if (!cacheAvailable) {
                throw new FastJetException("Calls to JetMedianBackgroundEstimator in cases where the background estimation uses a selector that takes a reference jet need to call a method that fills the cached estimate (rho(jet), sigma(jet), ...).");
            }
            return cachedEstimate;
        }
        return cached();
    }

    private BackgroundEstimate compute(PseudoJet jet) {
        final BackgroundEstimate local = new BackgroundEstimate();
        if (csab == null) {
            throw new FastJetException("JetMedianBackgroundEstimator: no cluster sequence or particles set");
        }
        local.setHasSigma(hasSigma());
        local.setHasRhoM(hasRhoM());
        final Extras extras = new Extras();
        local.setExtras(extras);
        extras.referenceJet = jet;
        List<PseudoJet> selected;
        if (rhoRange.takesReference()) {
            final Selector localRange = new Selector(rhoRange.worker());
            localRange.setReference(jet);
            selected = localRange.apply(includedJets);
        } else {
            selected = rhoRange.apply(includedJets);
        }
        final List<Double> forMedianPt = new ArrayList<>();
        final List<Double> forMedianDt = new ArrayList<>();
        double totalArea = 0.0;
        final boolean doRhoM = hasRhoM();
        int njetsUsed = 0;
        for (PseudoJet current : selected) {
            final double thisArea = useArea4vector ? current.area4vector().perp() : current.area();
            if (thisArea > 0) {
                double medianInputPt = jetDensityClass == null ? current.perp() / thisArea : jetDensityClass.result(current);
                double medianInputDt = 0.0;
                if (doRhoM) {
                    medianInputDt = ptmDensity(current);
                }
                if (rescalingClass != null) {
                    final double resc = rescalingClass.result(current);
                    medianInputPt /= resc;
                    medianInputDt /= resc;
                }
                forMedianPt.add(medianInputPt);
                if (doRhoM) forMedianDt.add(medianInputDt);
                totalArea += thisArea;
                njetsUsed++;
            } else {
                WARNINGS_ZERO_AREA.warn("JetMedianBackgroundEstimator::_compute(...): discarded jet with zero area. Zero-area jets may be due to (i) too large a ghost area (ii) a jet being outside the ghost range (iii) the computation not being done using an appropriate algorithm (kt;C/A).");
            }
        }
        if (forMedianPt.isEmpty()) {
            return local;
        }
        if (!csab.hasExplicitGhosts()) {
            Selector localRange = rhoRange;
            if (rhoRange.takesReference()) {
                localRange = new Selector(rhoRange.worker());
                localRange.setReference(jet);
            }
            extras.emptyArea = csab.emptyArea(localRange);
            extras.nEmptyJets = csab.nEmptyJets(localRange);
        }
        extras.nJetsUsed = njetsUsed;
        final double totalNjets = extras.nJetsUsed + extras.nEmptyJets;
        totalArea += extras.emptyArea;
        double[] ms = medianAndStddev(toArray(forMedianPt), extras.nEmptyJets, provideFj2Sigma);
        local.setRho(ms[0]);
        local.setMeanArea(totalArea / totalNjets);
        local.setSigma(ms[1] * Math.sqrt(Math.max(0.0, local.meanArea())));
        if (doRhoM) {
            ms = medianAndStddev(toArray(forMedianDt), extras.nEmptyJets, provideFj2Sigma);
            local.setRhoM(ms[0]);
            local.setSigmaM(ms[1] * Math.sqrt(Math.max(0.0, local.meanArea())));
        }
        return local;
    }

    /** fastjet::BackgroundJetPtMDensity: sum of (mt - pt) of the constituents over the area. */
    static double ptmDensity(PseudoJet jet) {
        double scalarPtm = 0;
        for (PseudoJet c : jet.constituents()) {
            scalarPtm += c.mperp() - c.perp();
        }
        return scalarPtm / jet.area();
    }

    private static double[] toArray(List<Double> l) {
        final double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private void checkJetAlgGoodForMedian() {
        JetDefinition def = jetDef;
        if (csab != null) {
            def = csab.jetDef();
        }
        final JetAlgorithm a = def.jetAlgorithm();
        if (a != JetAlgorithm.KT && a != JetAlgorithm.CAMBRIDGE && a != JetAlgorithm.CAMBRIDGE_FOR_PASSIVE
                && a != JetAlgorithm.UNDEFINED) {
            WARNINGS.warn("JetMedianBackgroundEstimator: jet_def being used may not be suitable for estimating diffuse backgrounds (good alternatives are kt, cam)");
        }
    }

    @Override
    public String description() {
        return "JetMedianBackgroundEstimator, using " + jetDef.description() + " with "
            + (areaDef == null ? new AreaDefinition().description() : areaDef.description())
            + " and selecting jets with " + rhoRange.description();
    }

    /** fastjet::BackgroundJetPtDensity. */
    public static FunctionOfPseudoJet<Double> ptDensity() {
        return FunctionOfPseudoJet.of("BackgroundJetPtDensity", j -> j.perp() / j.area4vector().perp());
    }

    /** fastjet::BackgroundJetScalarPtDensity, with pt raised to a power. */
    public static FunctionOfPseudoJet<Double> scalarPtDensity(double ptPower) {
        return FunctionOfPseudoJet.of(
            "BackgroundScalarJetPtDensity" + (ptPower != 1.0 ? " with pt_power = " + com.sphere.core.fastjet.Fmt.g(ptPower) : ""),
            j -> {
                double s = 0;
                for (PseudoJet c : Selector.isPureGhost().negate().apply(j.constituents())) {
                    s += Math.pow(c.perp(), ptPower);
                }
                return s / j.area();
            });
    }
}
