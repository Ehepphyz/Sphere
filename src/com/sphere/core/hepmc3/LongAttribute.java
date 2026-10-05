package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CStr;

/** A long (64 bits, as on Linux): atol to read, std::to_string to write. */
public class LongAttribute extends Attribute {

    private long val;

    public LongAttribute() {
    }

    public LongAttribute(long val) {
        this.val = val;
    }

    @Override
    public boolean fromString(String att) {
        val = CStr.atol(att, 0);
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        return Long.toString(val);
    }

    public long value() {
        return val;
    }

    public void setValue(long l) {
        val = l;
        setIsParsed(true);
    }
}
