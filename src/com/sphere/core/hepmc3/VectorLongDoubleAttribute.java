package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.LongDouble;

import java.util.ArrayList;
import java.util.List;

/** A vector of long doubles, written by std::to_string (%Lf); read with an istringstream until it fails, written joined by blanks. */
public class VectorLongDoubleAttribute extends Attribute {

    private List<LongDouble> val = new ArrayList<>();

    public VectorLongDoubleAttribute() {
    }

    public VectorLongDoubleAttribute(List<LongDouble> val) {
        this.val = new ArrayList<>(val);
    }

    @Override
    public boolean fromString(String att) {
        val.clear();
        final CIStream ds = new CIStream(att);
        while (true) {
            final LongDouble datafoo = readLongDouble(ds);
            if (ds.fail()) break;
            val.add(datafoo);
        }
        setIsParsed(true);
        return true;
    }

    @Override
    public String serialize() {
        final StringBuilder att = new StringBuilder();
        for (final LongDouble a : val) {
            if (att.length() > 0) att.append(' ');
            att.append(a.format('f', 6, false, false));
        }
        return att.toString();
    }

    /** A copy of the values. */
    public List<LongDouble> value() {
        return new ArrayList<>(val);
    }

    public void setValue(List<LongDouble> i) {
        val = new ArrayList<>(i);
        setIsParsed(true);
    }

    /** >> long double: the next blank-separated word read as strtold reads it; failed when it is no number. */
    private static LongDouble readLongDouble(CIStream ds) {
        final int before = ds.position();
        final double probe = ds.nextDouble();
        if (ds.fail()) return LongDouble.ZERO;
        final String text = ds.consumedSince(before).trim();
        return Double.isFinite(probe) ? LongDouble.parse(text.startsWith("+") ? text.substring(1) : text) : LongDouble.of(probe);
    }
}
