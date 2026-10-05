package com.sphere.core.hepmc3;

/**
 * A string, and the form every attribute takes when read from a file: built
 * from a string it stays "unparsed", so that asking for it as another type
 * parses that string.
 */
public class StringAttribute extends Attribute {

    public StringAttribute() {
    }

    /** Unparsed, as C++'s StringAttribute(const std::string&). */
    public StringAttribute(String st) {
        super(st);
    }

    @Override
    public boolean fromString(String att) {
        setUnparsedString(att);
        return true;
    }

    @Override
    public String serialize() {
        return unparsedString();
    }

    public String value() {
        return unparsedString();
    }

    public void setValue(String s) {
        setUnparsedString(s);
    }
}
