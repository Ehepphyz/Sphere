package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's Genfun::Argument (GenericFunctions/Argument.hh): the point a function of several variables is
 * evaluated at, its coordinates from 0.
 */
public final class Argument {

    private double[] data;

    public Argument() {
        this(0);
    }

    public Argument(int ndim) {
        data = new double[ndim];
    }

    public Argument(Argument right) {
        data = right.data.clone();
    }

    /** operator=. */
    public Argument assign(Argument right) {
        if (this != right) data = right.data.clone();
        return this;
    }

    public double get(int i) {
        return data[i];
    }

    public void set(int i, double v) {
        data[i] = v;
    }

    public int dimension() {
        return data.length;
    }

    /** operator&lt;&lt;: each coordinate followed by a comma. */
    public COStream print(COStream os) {
        for (double v : data) os.put(v).put(",");
        return os;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
