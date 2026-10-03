package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.NormalizedCutoffMeasure;

import java.util.List;

/**
 * The N-subjettiness jet shape, fastjet::contrib::Nsubjettiness
 * (J. Thaler and K. Van Tilburg, JHEP 03 (2011) 015 and JHEP 02 (2012)
 * 093): N-jettiness of a jet's constituents. For the unnormalised measure,
 * tau_N = sum_i pt_i min_a DeltaR_ia^beta.
 *
 * The recommended axes are KT_Axes, WTA_KT_Axes, OnePass_KT_Axes and
 * OnePass_WTA_KT_Axes (HalfKT variants for beta = 2); the measures
 * NormalizedMeasure(beta, R0), UnnormalizedMeasure(beta) and their cutoff
 * versions.
 */
public class Nsubjettiness implements FunctionOfPseudoJet<Double> {

    private static final LimitedWarning OLD_CONSTRUCTOR_WARNING = new LimitedWarning();

    private final Njettiness njettinessFinder;
    private final int n;

    public Nsubjettiness(int n, AxesDefinition axesDef, MeasureDefinition measureDef) {
        this.njettinessFinder = new Njettiness(axesDef, measureDef);
        this.n = n;
    }

    /** tau_N of the jet's constituents. */
    @Override
    public Double result(PseudoJet jet) {
        return njettinessFinder.getTau(n, jet.constituents());
    }

    /** All the components of tau_N. */
    public TauComponents componentResult(PseudoJet jet) {
        return njettinessFinder.getTauComponents(n, jet.constituents());
    }

    @Override
    public String description() {
        return "N-subjettiness tau_" + n + " with " + njettinessFinder.axesDefinition().description() + " and "
            + njettinessFinder.measureDefinition().description();
    }

    public int N() { return n; }

    public void setAxes(List<PseudoJet> myAxes) { njettinessFinder.setAxes(myAxes); }
    public List<PseudoJet> seedAxes() { return njettinessFinder.seedAxes(); }
    public List<PseudoJet> currentAxes() { return njettinessFinder.currentAxes(); }
    public List<PseudoJet> currentSubjets() { return njettinessFinder.currentJets(); }
    public TauComponents currentTauComponents() { return njettinessFinder.currentTauComponents(); }
    public TauPartition currentPartition() { return njettinessFinder.currentPartition(); }

    /* ---- the v1 constructors, deprecated in the C++ ---- */

    private static void warnOld() {
        OLD_CONSTRUCTOR_WARNING.warn("Nsubjettiness:  You are using the old style constructor.  This is deprecated as of v2.1 and will be removed in v3.0.  Please use the Nsubjettiness constructor based on AxesDefinition and MeasureDefinition instead.");
    }

    public Nsubjettiness(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 0, Double.NaN, Double.NaN, Double.NaN);
        this.n = n;
        warnOld();
    }

    public Nsubjettiness(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 1, para1, Double.NaN, Double.NaN);
        this.n = n;
        warnOld();
    }

    public Nsubjettiness(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1, double para2) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 2, para1, para2, Double.NaN);
        this.n = n;
        warnOld();
    }

    public Nsubjettiness(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1,
                         double para2, double para3) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 3, para1, para2, para3);
        this.n = n;
        warnOld();
    }

    /** The v1.0 constructor, where the normalised cutoff measure was the only one. */
    public Nsubjettiness(int n, Njettiness.AxesMode axesMode, double beta, double r0, double rcutoff) {
        this.njettinessFinder = new Njettiness(axesMode, new NormalizedCutoffMeasure(beta, r0, rcutoff));
        this.n = n;
        warnOld();
    }

    public Nsubjettiness(int n, Njettiness.AxesMode axesMode, double beta, double r0) {
        this(n, axesMode, beta, r0, Double.MAX_VALUE);
    }
}
