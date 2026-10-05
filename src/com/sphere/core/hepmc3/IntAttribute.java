package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CStr;

/** An int: atoi to read, std::to_string to write. */
public class IntAttribute extends Attribute {

    private int val;

    public IntAttribute() {
    }

    public IntAttribute(int val) {
        this.val = val;
    }

    @Override
    public boolean fromString(String att) {
        val = CStr.atoi(att);
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        return Integer.toString(val);
    }

    public int value() {
        return val;
    }

    public void setValue(int i) {
        val = i;
        setIsParsed(true);
    }
}
