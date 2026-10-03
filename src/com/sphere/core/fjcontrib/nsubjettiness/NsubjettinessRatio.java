package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

/** tau_N / tau_M with the same axes and measure, NsubjettinessRatio (manual axes not supported). */
public class NsubjettinessRatio implements FunctionOfPseudoJet<Double> {

    private final Nsubjettiness numerator;
    private final Nsubjettiness denominator;

    public NsubjettinessRatio(int n, int m, AxesDefinition axesDef, MeasureDefinition measureDef) {
        if (axesDef.needsManualAxes()) throw new FastJetException("NsubjettinessRatio does not support ManualAxes mode.");
        numerator = new Nsubjettiness(n, axesDef, measureDef);
        denominator = new Nsubjettiness(m, axesDef, measureDef);
    }

    public NsubjettinessRatio(int n, int m, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode) {
        numerator = new Nsubjettiness(n, axesMode, measureMode);
        denominator = new Nsubjettiness(m, axesMode, measureMode);
    }

    public NsubjettinessRatio(int n, int m, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1) {
        numerator = new Nsubjettiness(n, axesMode, measureMode, para1);
        denominator = new Nsubjettiness(m, axesMode, measureMode, para1);
    }

    public NsubjettinessRatio(int n, int m, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode,
                              double para1, double para2) {
        numerator = new Nsubjettiness(n, axesMode, measureMode, para1, para2);
        denominator = new Nsubjettiness(m, axesMode, measureMode, para1, para2);
    }

    public NsubjettinessRatio(int n, int m, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode,
                              double para1, double para2, double para3) {
        numerator = new Nsubjettiness(n, axesMode, measureMode, para1, para2, para3);
        denominator = new Nsubjettiness(m, axesMode, measureMode, para1, para2, para3);
    }

    @Override
    public Double result(PseudoJet jet) {
        final double num = numerator.result(jet);
        final double den = denominator.result(jet);
        return num / den;
    }

    @Override
    public String description() {
        return "N-subjettiness ratio tau_" + numerator.N() + "/tau_" + denominator.N();
    }
}
