package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CStr;

/**
 * An unsigned int: strtoul with base 0 (so "0x1f" is hexadecimal and "017"
 * octal) truncated to 32 bits to read, std::to_string to write. The value is
 * the low 32 bits of a long.
 */
public class UIntAttribute extends Attribute {

    private long val;

    public UIntAttribute() {
    }

    public UIntAttribute(long val) {
        this.val = val & 0xFFFFFFFFL;
    }

    @Override
    public boolean fromString(String att) {
        val = CStr.strtoul(att, 0, 0) & 0xFFFFFFFFL;
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

    public void setValue(long i) {
        val = i & 0xFFFFFFFFL;
        setIsParsed(true);
    }
}
