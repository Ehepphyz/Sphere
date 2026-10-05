package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CStr;

/** An unsigned long long: strtoull with base 0 to read, the unsigned decimal to write. */
public class ULongLongAttribute extends Attribute {

    private long val;

    public ULongLongAttribute() {
    }

    public ULongLongAttribute(long val) {
        this.val = val;
    }

    @Override
    public boolean fromString(String att) {
        val = CStr.strtoul(att, 0, 0);
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        return Long.toUnsignedString(val);
    }

    public long value() {
        return val;
    }

    public void setValue(long i) {
        val = i;
        setIsParsed(true);
    }
}
