package com.sphere.core.mathcore.fit;

import com.sphere.core.mathcore.MathError;

/**
 * ROOT::Fit::ParameterSettings: a fit parameter, its name, value and step,
 * whether it is fixed, and its limits.
 */
public class ParameterSettings {

    private double fValue = 0.0;
    private double fStepSize = 0.1;
    private boolean fFix = false;
    private double fLowerLimit = 0.0;
    private double fUpperLimit = 0.0;
    private boolean fHasLowerLimit = false;
    private boolean fHasUpperLimit = false;
    private String fName = "";

    public ParameterSettings() {
    }

    public ParameterSettings(String name, double val, double err) {
        fValue = val;
        fStepSize = err;
        fName = name;
    }

    public ParameterSettings(String name, double val, double err, double min, double max) {
        fValue = val;
        fStepSize = err;
        fName = name;
        SetLimits(min, max);
    }

    /** A fixed parameter. */
    public ParameterSettings(String name, double val) {
        fValue = val;
        fStepSize = 0;
        fFix = true;
        fName = name;
    }

    public ParameterSettings(ParameterSettings o) {
        fValue = o.fValue;
        fStepSize = o.fStepSize;
        fFix = o.fFix;
        fLowerLimit = o.fLowerLimit;
        fUpperLimit = o.fUpperLimit;
        fHasLowerLimit = o.fHasLowerLimit;
        fHasUpperLimit = o.fHasUpperLimit;
        fName = o.fName;
    }

    public void Set(String name, double value, double step) {
        SetName(name);
        SetValue(value);
        SetStepSize(step);
    }

    public void Set(String name, double value, double step, double lower, double upper) {
        SetName(name);
        SetValue(value);
        SetStepSize(step);
        SetLimits(lower, upper);
    }

    public void Set(String name, double value) {
        SetName(name);
        SetValue(value);
        Fix();
    }

    public double Value() {
        return fValue;
    }

    public double StepSize() {
        return fStepSize;
    }

    public double LowerLimit() {
        return fLowerLimit;
    }

    public double UpperLimit() {
        return fUpperLimit;
    }

    public boolean IsFixed() {
        return fFix;
    }

    public boolean HasLowerLimit() {
        return fHasLowerLimit;
    }

    public boolean HasUpperLimit() {
        return fHasUpperLimit;
    }

    public boolean IsBound() {
        return fHasLowerLimit || fHasUpperLimit;
    }

    public boolean IsDoubleBound() {
        return fHasLowerLimit && fHasUpperLimit;
    }

    public String Name() {
        return fName;
    }

    public void SetName(String name) {
        fName = name;
    }

    public void Fix() {
        fFix = true;
    }

    public void Release() {
        fFix = false;
    }

    public void SetValue(double val) {
        fValue = val;
    }

    public void SetStepSize(double err) {
        fStepSize = err;
    }

    /** Both limits; none when low &gt; up; fixed when they equal the value. */
    public void SetLimits(double low, double up) {
        if (low > up) {
            RemoveLimits();
            return;
        }
        if (low == up && low == fValue) {
            Fix();
            return;
        }
        if (low > fValue || up < fValue) {
            MathError.infoMsg("ParameterSettings",
                "lower/upper bounds outside current parameter value. The value will be set to (low+up)/2 ");
            fValue = 0.5 * (up + low);
        }
        fLowerLimit = low;
        fUpperLimit = up;
        fHasLowerLimit = true;
        fHasUpperLimit = true;
    }

    public void SetUpperLimit(double up) {
        fLowerLimit = 0.;
        fUpperLimit = up;
        fHasLowerLimit = false;
        fHasUpperLimit = true;
    }

    public void SetLowerLimit(double low) {
        fLowerLimit = low;
        fUpperLimit = 0.;
        fHasLowerLimit = true;
        fHasUpperLimit = false;
    }

    public void RemoveLimits() {
        fLowerLimit = 0.;
        fUpperLimit = 0.;
        fHasLowerLimit = false;
        fHasUpperLimit = false;
    }
}
