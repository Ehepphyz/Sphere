package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CIStream;

import java.util.ArrayList;
import java.util.List;

/** A vector of ints; read with an istringstream until it fails, written joined by blanks. */
public class VectorIntAttribute extends Attribute {

    private List<Integer> val = new ArrayList<>();

    public VectorIntAttribute() {
    }

    public VectorIntAttribute(List<Integer> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        val.clear();
        final CIStream ds = new CIStream(att);
        while (true) {
            final Integer datafoo = ds.nextInt();
            if (ds.fail()) break;
            val.add(datafoo);
        }
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final Integer a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(Integer.toString(a));
        }
        return att.toString();
    }

    /** A copy of the values. */
    public List<Integer> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<Integer> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }
}
