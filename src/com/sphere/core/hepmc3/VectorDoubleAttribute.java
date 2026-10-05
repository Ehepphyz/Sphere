package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CIStream;

import java.util.ArrayList;
import java.util.List;

/** A vector of doubles, written by std::to_string (%f, six decimals: HepMC3 loses the rest); read with an istringstream until it fails, written joined by blanks. */
public class VectorDoubleAttribute extends Attribute {

    private List<Double> val = new ArrayList<>();

    public VectorDoubleAttribute() {
    }

    public VectorDoubleAttribute(List<Double> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        val.clear();
        final CIStream ds = new CIStream(att);
        while (true) {
            final Double datafoo = ds.nextDouble();
            if (ds.fail()) break;
            val.add(datafoo);
        }
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final Double a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(CFormat.sprintf("%f", a));
        }
        return att.toString();
    }

    /** A copy of the values. */
    public List<Double> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<Double> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }
}
