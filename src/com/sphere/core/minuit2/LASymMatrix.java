package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * Minuit2's symmetric matrix (MnAlgebraicSymMatrix): the lower triangle of an
 * n x n matrix packed in n(n+1)/2 doubles, element (i, j) with i &ge; j at
 * j + i(i+1)/2, with value semantics. As for {@link LAVector}, the methods are
 * the kernels the C++ expression templates run.
 */
public final class LASymMatrix implements MnPrint.Printable {

    final double[] data;
    final int nrow;

    public LASymMatrix(int n) {
        nrow = n;
        data = new double[n * (n + 1) / 2];
    }

    private LASymMatrix(int n, double[] packed) {
        nrow = n;
        data = packed;
    }

    /** A matrix over a copy of packed lower-triangle values. */
    public static LASymMatrix ofPacked(int n, double[] packed) {
        if (packed.length != n * (n + 1) / 2) throw new IllegalArgumentException("packed size");
        return new LASymMatrix(n, packed.clone());
    }

    public int nrow() {
        return nrow;
    }

    public int ncol() {
        return nrow;
    }

    /** The number of stored values, n(n+1)/2. */
    public int size() {
        return data.length;
    }

    static int index(int row, int col) {
        return row > col ? col + row * (row + 1) / 2 : row + col * (col + 1) / 2;
    }

    public double get(int row, int col) {
        if (row >= nrow || col >= nrow) throw new IndexOutOfBoundsException(row + "," + col + " of " + nrow);
        return data[index(row, col)];
    }

    public void set(int row, int col, double v) {
        if (row >= nrow || col >= nrow) throw new IndexOutOfBoundsException(row + "," + col + " of " + nrow);
        data[index(row, col)] = v;
    }

    /** The packed values themselves (Data()). */
    public double[] data() {
        return data;
    }

    public LASymMatrix copy() {
        return new LASymMatrix(nrow, data.clone());
    }

    /** operator=(const LASymMatrix&) onto a matrix of the same size: the values are copied. */
    public LASymMatrix assign(LASymMatrix m) {
        if (m.data.length != data.length) throw new IllegalArgumentException("LASymMatrix sizes differ");
        System.arraycopy(m.data, 0, data, 0, data.length);
        return this;
    }

    /** operator*=(scal): Mndscal. */
    public LASymMatrix scale(double f) {
        MnMatrix.mndscal(data.length, f, data, 0);
        return this;
    }

    /** operator+=(LASymMatrix): Mndaxpy with 1. */
    public LASymMatrix plusAssign(LASymMatrix m) {
        MnMatrix.mndaxpy(data.length, 1., m.data, data);
        return this;
    }

    /** operator-=(LASymMatrix): Mndaxpy with -1. */
    public LASymMatrix minusAssign(LASymMatrix m) {
        MnMatrix.mndaxpy(data.length, -1., m.data, data);
        return this;
    }

    /** operator+=(f * M): Mndaxpy with f. */
    public LASymMatrix plusAssign(double f, LASymMatrix m) {
        MnMatrix.mndaxpy(data.length, f, m.data, data);
        return this;
    }

    /** operator+=(f * Outer_product(v)): mndspr adds f v v^T. */
    public LASymMatrix plusOuter(double f, LAVector v) {
        MnMatrix.mndspr(v.size(), f, v.data, data);
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
