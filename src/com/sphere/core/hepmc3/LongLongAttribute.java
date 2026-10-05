package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CStr;

/** A long long: atoll to read, std::to_string to write. */
public class LongLongAttribute extends Attribute {

    private long val;

    public LongLongAttribute() {
    }

    public LongLongAttribute(long val) {
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
