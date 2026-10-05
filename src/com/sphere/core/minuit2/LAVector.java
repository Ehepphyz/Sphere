package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * Minuit2's vector (MnAlgebraicVector): n doubles with value semantics. The
 * C++ builds its arithmetic through expression templates whose evaluation is
 * a fixed sequence of BLAS-like kernels (copy then scale, then y += a*x...);
 * the methods here are those kernels, and the code calling them spells the
 * sequence each C++ expression runs, so that every rounding is the C++'s.
 */
public final class LAVector implements MnPrint.Printable {

    final double[] data;

    public LAVector(int n) {
        data = new double[n];
    }

    /** A copy of the values (LAVector(std::span)). */
    public LAVector(double[] values) {
        data = values.clone();
    }

    private LAVector(double[] values, boolean own) {
        data = values;
    }

    static LAVector wrap(double[] values) {
        return new LAVector(values, true);
    }

    public int size() {
        return data.length;
    }

    public double get(int i) {
        return data[i];
    }

    public void set(int i, double v) {
        data[i] = v;
    }

    /** The values themselves (Data()): changes go into the vector. */
    public double[] data() {
        return data;
    }

    public double[] toArray() {
        return data.clone();
    }

    public LAVector copy() {
        return new LAVector(data.clone(), true);
    }

    /** operator*=(scal): Mndscal. */
    public LAVector scale(double f) {
        MnMatrix.mndscal(data.length, f, data, 0);
        return this;
    }

    /** operator+=(LAVector): Mndaxpy with 1. */
    public LAVector plusAssign(LAVector m) {
        MnMatrix.mndaxpy(data.length, 1., m.data, data);
        return this;
    }

    /** operator-=(LAVector): Mndaxpy with -1. */
    public LAVector minusAssign(LAVector m) {
        MnMatrix.mndaxpy(data.length, -1., m.data, data);
        return this;
    }

    /** operator+=(f * v): Mndaxpy with f (the C++ object being a copy, never the same storage). */
    public LAVector plusAssign(double f, LAVector m) {
        MnMatrix.mndaxpy(data.length, f, m.data, data);
        return this;
    }

    /** Overwrites with another vector of the same size (operator=). */
    public LAVector assign(LAVector v) {
        if (v.data.length != data.length) throw new IllegalArgumentException("LAVector sizes differ");
        System.arraycopy(v.data, 0, data, 0, data.length);
        return this;
    }

    @Override
    public void print(COStream os) {
        MnMatrix.print(os, this);
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}
