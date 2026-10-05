package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CIStream;

import java.util.ArrayList;
import java.util.List;

/** A vector of chars, read one non-blank character at a time, written as their numbers; read with an istringstream until it fails, written joined by blanks. */
public class VectorCharAttribute extends Attribute {

    private List<Byte> val = new ArrayList<>();

    public VectorCharAttribute() {
    }

    public VectorCharAttribute(List<Byte> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        val.clear();
        final CIStream ds = new CIStream(att);
        while (true) {
            final Byte datafoo = (byte) ds.nextChar();
            if (ds.fail()) break;
            val.add(datafoo);
        }
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final Byte a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(Integer.toString(a));
        }
        return att.toString();
    }

    /** A copy of the values. */
    public List<Byte> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<Byte> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }
}
