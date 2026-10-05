package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.LongDouble;

/** A long double (x87, 64-bit significand): strtold to read, setprecision(18) (%.18Lg) to write. */
public class LongDoubleAttribute extends Attribute {

    private LongDouble val = LongDouble.ZERO;

    public LongDoubleAttribute() {
    }

    public LongDoubleAttribute(LongDouble val) {
        this.val = val;
    }

    public LongDoubleAttribute(double val) {
        this.val = LongDouble.of(val);
    }

    @Override
    public boolean fromString(String att) {
        val = LongDouble.parsePrefix(att);
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        return val.format('g', 18, false, false);
    }

    public LongDouble value() {
        return val;
    }

    public void setValue(LongDouble d) {
        val = d;
        setIsParsed(true);
    }
}
