package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CIStream;

import java.util.ArrayList;
import java.util.List;

/** A vector of floats, written by std::to_string (%f); read with an istringstream until it fails, written joined by blanks. */
public class VectorFloatAttribute extends Attribute {

    private List<Float> val = new ArrayList<>();

    public VectorFloatAttribute() {
    }

    public VectorFloatAttribute(List<Float> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        val.clear();
        final CIStream ds = new CIStream(att);
        while (true) {
            final Float datafoo = ds.nextFloat();
            if (ds.fail()) break;
            val.add(datafoo);
        }
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final Float a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(CFormat.sprintf("%f", (double) a));
        }
        return att.toString();
    }

    /** A copy of the values. */
    public List<Float> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<Float> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }
}
