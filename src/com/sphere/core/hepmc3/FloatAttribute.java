package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CStr;

/** A float: atof narrowed to read, setprecision(6) (%.6g of the float) to write. */
public class FloatAttribute extends Attribute {

    private float val;

    public FloatAttribute() {
    }

    public FloatAttribute(float val) {
        this.val = val;
    }

    @Override
    public boolean fromString(String att) {
        val = (float) CStr.atof(att);
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        return CFormat.sprintf("%.6g", (double) val);
    }

    public float value() {
        return val;
    }

    public void setValue(float f) {
        val = f;
        setIsParsed(true);
    }
}
