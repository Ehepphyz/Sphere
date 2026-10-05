package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * A covariance matrix as the user sees it: the lower triangle of n x n packed
 * in n(n+1)/2 values, with value semantics.
 */
public final class MnUserCovariance implements MnPrint.Printable {

    private double[] data;
    private int nrow;

    public MnUserCovariance() {
        data = new double[0];
        nrow = 0;
    }

    public MnUserCovariance(int n) {
        data = new double[n * (n + 1) / 2];
        nrow = n;
    }

    /** A copy of packed values. */
    public MnUserCovariance(double[] packed, int nrow) {
        if (packed.length != nrow * (nrow + 1) / 2) throw new IllegalArgumentException("packed size");
        this.data = packed.clone();
        this.nrow = nrow;
    }

    public MnUserCovariance(MnUserCovariance c) {
        data = c.data.clone();
        nrow = c.nrow;
    }

    public double get(int row, int col) {
        if (row >= nrow || col >= nrow) throw new IndexOutOfBoundsException(row + "," + col + " of " + nrow);
        return data[LASymMatrix.index(row, col)];
    }

    public void set(int row, int col, double v) {
        if (row >= nrow || col >= nrow) throw new IndexOutOfBoundsException(row + "," + col + " of " + nrow);
        data[LASymMatrix.index(row, col)] = v;
    }

    public void scale(double f) {
        for (int i = 0; i < data.length; i++) data[i] *= f;
    }

    /** The packed values (a copy). */
    public double[] data() {
        return data.clone();
    }

    double[] raw() {
        return data;
    }

    public int nrow() {
        return nrow;
    }

    public int size() {
        return data.length;
    }

    /** operator&lt;&lt;: each row, then the correlations. */
    @Override
    public void print(COStream os) {
        final int pr = os.precision();
        os.precision(6);
        final int n = nrow;
        for (int i = 0; i < n; i++) {
            os.put('\n');
            for (int j = 0; j < n; j++) {
                os.width(13);
                os.put(get(i, j));
            }
            os.put(" | ");
            final double di = get(i, i);
            for (int j = 0; j < n; j++) {
                final double dj = get(j, j);
                os.width(13);
                os.put(get(i, j) / Math.sqrt(Math.abs(di * dj)));
            }
        }
        os.precision(pr);
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}
