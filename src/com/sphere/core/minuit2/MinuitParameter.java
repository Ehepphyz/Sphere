package com.sphere.core.minuit2;

/**
 * One parameter as the user declares it: number, name, value, error (the
 * initial step), limits, fixed or constant. A constant parameter is given
 * without error and never varies; a fixed one may be released.
 */
public final class MinuitParameter {

    private final int num;
    private double value;
    private double error;
    private final boolean isConst;
    private boolean fix;
    private double loLimit;
    private double upLimit;
    private boolean loLimValid;
    private boolean upLimValid;
    private String name;

    /** A constant parameter. */
    public MinuitParameter(int num, String name, double val) {
        this.num = num;
        this.name = name;
        this.value = val;
        this.isConst = true;
    }

    /** A free parameter. */
    public MinuitParameter(int num, String name, double val, double err) {
        this.num = num;
        this.name = name;
        this.value = val;
        this.error = err;
        this.isConst = false;
    }

    /** A parameter between two limits. */
    public MinuitParameter(int num, String name, double val, double err, double min, double max) {
        this(num, name, val, err);
        if (min == max) throw new IllegalArgumentException("the limits of " + name + " are equal");
        loLimit = min;
        upLimit = max;
        loLimValid = true;
        upLimValid = true;
        if (min > max) {
            loLimit = max;
            upLimit = min;
        }
    }

    public MinuitParameter(MinuitParameter p) {
        num = p.num;
        value = p.value;
        error = p.error;
        isConst = p.isConst;
        fix = p.fix;
        loLimit = p.loLimit;
        upLimit = p.upLimit;
        loLimValid = p.loLimValid;
        upLimValid = p.upLimValid;
        name = p.name;
    }

    public int number() {
        return num;
    }

    public String name() {
        return name;
    }

    public double value() {
        return value;
    }

    public double error() {
        return error;
    }

    public void setName(String n) {
        name = n;
    }

    /** The value, brought back inside the limits. */
    public void setValue(double val) {
        value = val;
        if (loLimValid && val < loLimit) value = loLimit;
        else if (upLimValid && val > upLimit) value = upLimit;
    }

    public void setError(double err) {
        error = err;
    }

    public void setLimits(double low, double up) {
        if (low == up) throw new IllegalArgumentException("the limits of " + name + " are equal");
        loLimit = low;
        upLimit = up;
        loLimValid = true;
        upLimValid = true;
        if (low > up) {
            loLimit = up;
            upLimit = low;
        }
    }

    public void setUpperLimit(double up) {
        loLimit = 0.;
        upLimit = up;
        loLimValid = false;
        upLimValid = true;
    }

    public void setLowerLimit(double low) {
        loLimit = low;
        upLimit = 0.;
        loLimValid = true;
        upLimValid = false;
    }

    public void removeLimits() {
        loLimit = 0.;
        upLimit = 0.;
        loLimValid = false;
        upLimValid = false;
    }

    public void fix() {
        fix = true;
    }

    public void release() {
        fix = false;
    }

    public boolean isConst() {
        return isConst;
    }

    public boolean isFixed() {
        return fix;
    }

    public boolean hasLimits() {
        return loLimValid || upLimValid;
    }

    public boolean hasLowerLimit() {
        return loLimValid;
    }

    public boolean hasUpperLimit() {
        return upLimValid;
    }

    public double lowerLimit() {
        return loLimit;
    }

    public double upperLimit() {
        return upLimit;
    }
}
