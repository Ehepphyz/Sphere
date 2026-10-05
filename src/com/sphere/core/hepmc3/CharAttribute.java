package com.sphere.core.hepmc3;

/**
 * A char, signed as on x86: read as the first character of the string,
 * written as its number (std::to_string of a char), as HepMC3 does.
 */
public class CharAttribute extends Attribute {

    private byte val;

    public CharAttribute() {
    }

    public CharAttribute(byte val) {
        this.val = val;
    }

    public CharAttribute(char val) {
        this.val = (byte) val;
    }

    @Override
    public boolean fromString(String att) {
        setIsParsed(true);
        if (!att.isEmpty()) {
            val = (byte) att.charAt(0);
            return true;
        }
        return false;
    }

    @Override
    public String serialize() {
        return Integer.toString(val);
    }

    public byte value() {
        return val;
    }

    public void setValue(byte i) {
        val = i;
        setIsParsed(true);
    }
}
