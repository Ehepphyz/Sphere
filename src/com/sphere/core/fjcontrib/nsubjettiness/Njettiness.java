package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.AntiKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.Manual_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.MultiPass_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_AntiKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_Manual_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_WTA_CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_WTA_KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.WTA_CA_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.WTA_KT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.NormalizedCutoffMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.NormalizedMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.UnnormalizedCutoffMeasure;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.UnnormalizedMeasure;

import java.util.ArrayList;
import java.util.List;

/**
 * The core of every N-(sub)jettiness calculation, Njettiness: an
 * AxesDefinition and a MeasureDefinition together give tau_N of a set of
 * particles, with the axes, the seeds and the partition it found.
 *
 * As in the C++ the last results are kept in the object, so one instance is
 * not to be shared between threads.
 */
public class Njettiness {

    static {
        ContribCitations.use("nsubjettiness");
    }

    private static final LimitedWarning OLD_MEASURE_WARNING = new LimitedWarning();
    private static final LimitedWarning OLD_AXES_WARNING = new LimitedWarning();

    private final AxesDefinition axesDef;
    private final MeasureDefinition measureDef;

    private TauComponents currentTauComponents = new TauComponents();
    private List<PseudoJet> currentAxes = new ArrayList<>();
    private List<PseudoJet> seedAxes = new ArrayList<>();
    private TauPartition currentPartition = new TauPartition();

    public Njettiness(AxesDefinition axesDef, MeasureDefinition measureDef) {
        this.axesDef = axesDef.create();
        this.measureDef = measureDef.create();
    }

    public AxesDefinition axesDefinition() { return axesDef; }
    public MeasureDefinition measureDefinition() { return measureDef; }

    /** The axes, for a manual AxesDefinition. */
    public void setAxes(List<PseudoJet> myAxes) {
        if (axesDef.needsManualAxes()) {
            currentAxes = new ArrayList<>(myAxes);
        } else {
            throw new FastJetException("You can only use setAxes for manual AxesDefinitions");
        }
    }

    /** Everything about tau_N for these particles; kept for the accessors below. */
    public TauComponents getTauComponents(int nJets, List<PseudoJet> inputJets) {
        if (inputJets.size() <= nJets) {
            currentAxes = new ArrayList<>(inputJets);
            while (currentAxes.size() < nJets) currentAxes.add(new PseudoJet(0.0, 0.0, 0.0, 0.0));
            currentTauComponents = new TauComponents(TauComponents.TauMode.UNDEFINED_SHAPE, new double[0], 0.0, 1.0,
                currentAxes, currentAxes);
            seedAxes = currentAxes;
            currentPartition = new TauPartition(nJets);
        } else {
            if (axesDef.needsManualAxes()) {
                seedAxes = currentAxes;
                currentAxes = axesDef.getRefinedAxes(nJets, inputJets, seedAxes, measureDef);
            } else {
                seedAxes = axesDef.getStartingAxes(nJets, inputJets, measureDef);
                currentAxes = axesDef.getRefinedAxes(nJets, inputJets, seedAxes, measureDef);
            }
            currentPartition = measureDef.getPartition(inputJets, currentAxes);
            currentTauComponents = measureDef.componentResultFromPartition(currentPartition, currentAxes);
        }
        return currentTauComponents;
    }

    public double getTau(int nJets, List<PseudoJet> inputJets) {
        return getTauComponents(nJets, inputJets).tau();
    }

    public TauComponents currentTauComponents() { return currentTauComponents; }
    public List<PseudoJet> currentAxes() { return currentAxes; }
    public List<PseudoJet> seedAxes() { return seedAxes; }
    public List<PseudoJet> currentJets() { return currentPartition.jets(); }
    public PseudoJet currentBeam() { return currentPartition.beam(); }
    public TauPartition currentPartition() { return currentPartition; }

    /* ------------------------------------------------------------------ */
    /* The v1 interface, deprecated in the C++ and kept as it is           */
    /* ------------------------------------------------------------------ */

    /** The axes of the old interface. */
    public enum AxesMode {
        kt_axes, ca_axes, antikt_0p2_axes, wta_kt_axes, wta_ca_axes, onepass_kt_axes, onepass_ca_axes,
        onepass_antikt_0p2_axes, onepass_wta_kt_axes, onepass_wta_ca_axes, min_axes, manual_axes, onepass_manual_axes
    }

    /** The measures of the old interface. */
    public enum MeasureMode {
        normalized_measure, unnormalized_measure, geometric_measure, normalized_cutoff_measure,
        unnormalized_cutoff_measure, geometric_cutoff_measure
    }

    public Njettiness(AxesMode axesMode, MeasureDefinition measureDef) {
        this.axesDef = createAxesDef(axesMode);
        this.measureDef = measureDef.create();
    }

    public Njettiness(AxesMode axesMode, MeasureMode measureMode, int numPara, double para1, double para2, double para3) {
        this.axesDef = createAxesDef(axesMode);
        this.measureDef = createMeasureDef(measureMode, numPara, para1, para2, para3);
    }

    static AxesDefinition createAxesDef(AxesMode axesMode) {
        OLD_AXES_WARNING.warn("Njettiness::createAxesDef:  You are using the old AxesMode way of specifying N-subjettiness axes.  This is deprecated as of v2.1 and will be removed in v3.0.  Please use AxesDefinition instead.");
        return switch (axesMode) {
            case wta_kt_axes -> new WTA_KT_Axes();
            case wta_ca_axes -> new WTA_CA_Axes();
            case kt_axes -> new KT_Axes();
            case ca_axes -> new CA_Axes();
            case antikt_0p2_axes -> new AntiKT_Axes(0.2);
            case onepass_wta_kt_axes -> new OnePass_WTA_KT_Axes();
            case onepass_wta_ca_axes -> new OnePass_WTA_CA_Axes();
            case onepass_kt_axes -> new OnePass_KT_Axes();
            case onepass_ca_axes -> new OnePass_CA_Axes();
            case onepass_antikt_0p2_axes -> new OnePass_AntiKT_Axes(0.2);
            case onepass_manual_axes -> new OnePass_Manual_Axes();
            case min_axes -> new MultiPass_Axes(100);
            case manual_axes -> new Manual_Axes();
        };
    }

    static MeasureDefinition createMeasureDef(MeasureMode mode, int numPara, double para1, double para2, double para3) {
        OLD_MEASURE_WARNING.warn("Njettiness::createMeasureDef:  You are using the old MeasureMode way of specifying N-subjettiness measures.  This is deprecated as of v2.1 and will be removed in v3.0.  Please use MeasureDefinition instead.");
        switch (mode) {
            case normalized_measure:
                if (numPara == 2) return new NormalizedMeasure(para1, para2);
                throw new FastJetException("normalized_measure needs 2 parameters (beta and R0)");
            case unnormalized_measure:
                if (numPara == 1) return new UnnormalizedMeasure(para1);
                throw new FastJetException("unnormalized_measure needs 1 parameter (beta)");
            case normalized_cutoff_measure:
                if (numPara == 3) return new NormalizedCutoffMeasure(para1, para2, para3);
                throw new FastJetException("normalized_cutoff_measure has 3 parameters (beta, R0, Rcutoff)");
            case unnormalized_cutoff_measure:
                if (numPara == 2) return new UnnormalizedCutoffMeasure(para1, para2);
                throw new FastJetException("unnormalized_cutoff_measure has 2 parameters (beta, Rcutoff)");
            default:
                throw new FastJetException("This class has been removed. Please use OriginalGeometricMeasure, ModifiedGeometricMeasure, or ConicalGeometricMeasure with the new Njettiness constructor.");
        }
    }
}
