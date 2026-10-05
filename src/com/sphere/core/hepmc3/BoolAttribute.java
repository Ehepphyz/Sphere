package com.sphere.core.hepmc3;

/** A bool: exactly "1" or "0" to read, "1" or "0" to write. */
public class BoolAttribute extends Attribute {

    private boolean val;

    public BoolAttribute() {
    }

    public BoolAttribute(boolean val) {
        this.val = val;
    }

    @Override
    public boolean fromString(String att) {
        if (att.length() != 1) return false;
        if (att.equals("1")) {
            val = true;
            return true;
        }
        if (att.equals("0")) {
            val = false;
            return true;
        }
        setIsParsed(true);
        return false;
    }

    @Override
    public String serialize() {
        return val ? "1" : "0";
    }

    public boolean value() {
        return val;
    }

    public void setValue(boolean i) {
        val = i;
        setIsParsed(true);
    }
}
