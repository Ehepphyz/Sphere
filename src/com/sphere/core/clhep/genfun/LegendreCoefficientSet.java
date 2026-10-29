package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.Arrays;

/**
 * CLHEP's Genfun::LegendreCoefficientSet (LegendreCoefficientSet.hh, .icc): the complex coefficients of a
 * Legendre expansion, l = 0..LMAX.
 */
public class LegendreCoefficientSet {

    private Complex[] data;

    public LegendreCoefficientSet(int LMAX) {
        data = new Complex[LMAX + 1];
        Arrays.fill(data, Complex.ZERO);
    }

    public LegendreCoefficientSet(LegendreCoefficientSet right) {
        data = right.data.clone();
    }

    public LegendreCoefficientSet assign(LegendreCoefficientSet source) {
        if (this != source) data = source.data.clone();
        return this;
    }

    public int getLMax() {
        return data.length - 1;
    }

    public Complex get(int l) {
        return data[l];
    }

    public void set(int l, Complex c) {
        data[l] = c;
    }

    /** operator&lt;&lt;. */
    public COStream print(COStream o) {
        for (int l = 0; l <= getLMax(); l++) {
            o.put(l).put(" mag: ");
            get(l).print(o).put("\n");
        }
        o.put("\n");
        return o;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
