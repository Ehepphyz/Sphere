package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CStr;

/** A double: atof to read, an ostream at setprecision(15) (%.15g) to write. */
public class DoubleAttribute extends Attribute {

    private double val;

    public DoubleAttribute() {
    }

    public DoubleAttribute(double val) {
        this.val = val;
    }

    @Override
    public boolean fromString(String att) {
        val = CStr.atof(att);
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        return CFormat.sprintf("%.15g", val);
    }

    public double value() {
        return val;
    }

    public void setValue(double d) {
        val = d;
        setIsParsed(true);
    }
}
