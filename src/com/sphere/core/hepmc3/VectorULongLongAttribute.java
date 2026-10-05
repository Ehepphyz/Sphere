package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CIStream;

import java.util.ArrayList;
import java.util.List;

/** A vector of unsigned long longs, as the bits of longs; read with an istringstream until it fails, written joined by blanks. */
public class VectorULongLongAttribute extends Attribute {

    private List<Long> val = new ArrayList<>();

    public VectorULongLongAttribute() {
    }

    public VectorULongLongAttribute(List<Long> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        val.clear();
        final CIStream ds = new CIStream(att);
        while (true) {
            final Long datafoo = ds.nextUnsigned(64);
            if (ds.fail()) break;
            val.add(datafoo);
        }
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final Long a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(Long.toUnsignedString(a));
        }
        return att.toString();
    }

    /** A copy of the values. */
    public List<Long> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<Long> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }
}
