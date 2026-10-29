package com.sphere.core.clhep.matrix;

import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's HepDiagMatrix (Matrix/DiagMatrix.h, .icc, DiagMatrix.cc): a diagonal matrix, its diagonal
 * only. Off the diagonal an element reads 0; setting one is CLHEP's error.
 */
public class HepDiagMatrix extends HepGenMatrix {

    double[] m;
    int nrow;

    public HepDiagMatrix() {
        m = new double[0];
        nrow = 0;
    }

    /** p x p zeros. */
    public HepDiagMatrix(int p) {
        m = new double[p];
        nrow = p;
    }

    /** Zeros (init 0) or the identity (init 1). */
    public HepDiagMatrix(int p, int init) {
        this(p);
        switch (init) {
            case 0:
                break;
            case 1:
                for (int a = 0; a < p; a++) m[a] = 1.0;
                break;
            default:
                error("DiagMatrix: initialization must be either 0 or 1.");
        }
    }

    /** The diagonal drawn from the distribution. */
    public HepDiagMatrix(int p, HepRandom r) {
        this(p);
        for (int a = 0; a < num_size(); a++) m[a] = r.call();
    }

    public HepDiagMatrix(HepDiagMatrix hm1) {
        nrow = hm1.nrow;
        m = hm1.m.clone();
    }

    @Override
    public int num_row() {
        return nrow;
    }

    @Override
    public int num_col() {
        return nrow;
    }

    @Override
    protected int num_size() {
        return nrow;
    }

    /** fast(row, col) const: the diagonal element, 0 off it. */
    public double fast(int row, int col) {
        if (row == col) return m[col - 1];
        return 0;
    }

    /** fast(row, col) = v: only on the diagonal. */
    public void setFast(int row, int col, double v) {
        if (row != col) error("Index error in HepDiagMatrix::fast(i,j): i != j");
        m[col - 1] = v;
    }

    @Override
    public double get(int row, int col) {
        return fast(col, row);
    }

    @Override
    public void set(int row, int col, double value) {
        setFast(col, row, value);
    }

    /* ---- compound assignments ---- */

    public HepDiagMatrix timesAssign(double t) {
        for (int a = 0; a < num_size(); a++) m[a] *= t;
        return this;
    }

    public HepDiagMatrix divAssign(double t) {
        for (int a = 0; a < num_size(); a++) m[a] /= t;
        return this;
    }

    public HepDiagMatrix plusAssign(HepDiagMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "+=");
        for (int a = 0; a < num_size(); a++) m[a] += hm2.m[a];
        return this;
    }

    public HepDiagMatrix minusAssign(HepDiagMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "-=");
        for (int a = 0; a < num_size(); a++) m[a] -= hm2.m[a];
        return this;
    }

    /* ---- operator= and assign ---- */

    public HepDiagMatrix assign(HepDiagMatrix hm1) {
        nrow = hm1.nrow;
        m = hm1.m.clone();
        return this;
    }

    /** The diagonal of a square matrix. */
    public void assign(HepMatrix hm1) {
        if (hm1.num_row() != nrow) {
            nrow = hm1.num_row();
            m = java.util.Arrays.copyOf(m, nrow);
        }
        int a = 0;
        int b = 0;
        for (int r = 1; r <= nrow; r++) {
            m[b++] = hm1.m[a];
            if (r < nrow) a += (nrow + 1);
        }
    }

    /** The diagonal of a symmetric matrix. */
    public void assign(HepSymMatrix hm1) {
        if (hm1.num_row() != nrow) {
            nrow = hm1.num_row();
            m = java.util.Arrays.copyOf(m, nrow);
        }
        int a = 0;
        int b = 0;
        for (int r = 1; r <= nrow; r++) {
            m[b++] = hm1.m[a];
            if (r < nrow) a += (r + 1);
        }
    }

    /* ---- operators ---- */

    public HepDiagMatrix negate() {
        final HepDiagMatrix hm2 = new HepDiagMatrix(nrow);
        for (int a = 0; a < num_size(); a++) hm2.m[a] = -m[a];
        return hm2;
    }

    /** D + M: a general matrix. */
    public HepMatrix plus(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(hm2);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "+");
        mret.plusAssign(this);
        return mret;
    }

    public HepMatrix minus(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(this);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    public HepDiagMatrix plus(HepDiagMatrix hm2) {
        final HepDiagMatrix mret = new HepDiagMatrix(nrow);
        chkDim1(nrow, hm2.nrow, "DiagMatrix", "+");
        for (int a = 0; a < nrow; a++) mret.m[a] = m[a] + hm2.m[a];
        return mret;
    }

    public HepDiagMatrix minus(HepDiagMatrix hm2) {
        final HepDiagMatrix mret = new HepDiagMatrix(nrow);
        chkDim1(num_row(), hm2.num_row(), "DiagMatrix", "-");
        for (int a = 0; a < nrow; a++) mret.m[a] = m[a] - hm2.m[a];
        return mret;
    }

    /** D + S: a symmetric matrix. */
    public HepSymMatrix plus(HepSymMatrix hm2) {
        final HepSymMatrix mret = new HepSymMatrix(hm2);
        chkDim1(num_row(), hm2.num_row(), "DiagMatrix", "+");
        mret.plusAssign(this);
        return mret;
    }

    public HepSymMatrix minus(HepSymMatrix hm2) {
        final HepSymMatrix mret = new HepSymMatrix(this);
        chkDim1(num_row(), hm2.num_row(), "DiagMatrix", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    public HepDiagMatrix div(double t) {
        final HepDiagMatrix mret = new HepDiagMatrix(this);
        mret.divAssign(t);
        return mret;
    }

    public HepDiagMatrix times(double t) {
        final HepDiagMatrix mret = new HepDiagMatrix(this);
        mret.timesAssign(t);
        return mret;
    }

    /** t * D. */
    public static HepDiagMatrix times(double t, HepDiagMatrix hm1) {
        final HepDiagMatrix mret = new HepDiagMatrix(hm1);
        mret.timesAssign(t);
        return mret;
    }

    /** D * M. */
    public HepMatrix times(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), hm2.num_col());
        chkDim1(num_col(), hm2.num_row(), "DiagMatrix", "*");
        int mit1 = 0;
        int mir = 0;
        int mrr = 0;
        for (int irow = 1; irow <= hm2.num_row(); irow++) {
            for (int icol = 1; icol <= hm2.num_col(); icol++) mret.m[mir++] = hm2.m[mit1++] * m[mrr];
            mrr++;
        }
        return mret;
    }

    public HepDiagMatrix times(HepDiagMatrix hm2) {
        final HepDiagMatrix mret = new HepDiagMatrix(num_row());
        chkDim1(num_col(), hm2.num_row(), "DiagMatrix", "*");
        int a = 0;
        int b = 0;
        int c = 0;
        final int e = num_col();
        for (; a < e; ) mret.m[a++] = m[b++] * hm2.m[c++];
        return mret;
    }

    /** D * v. */
    public HepVector times(HepVector hm2) {
        final HepVector mret = new HepVector(num_row());
        chkDim1(num_col(), hm2.num_row(), "DiagMatrix", "*");
        int mir = 0;
        int mi1 = 0;
        int mi2 = 0;
        for (int icol = 1; icol <= num_col(); icol++) mret.m[mir++] = m[mi1++] * hm2.m[mi2++];
        return mret;
    }

    /** The block-diagonal matrix of hm1 and hm2. */
    public static HepDiagMatrix dsum(HepDiagMatrix hm1, HepDiagMatrix hm2) {
        final HepDiagMatrix mret = new HepDiagMatrix(hm1.num_row() + hm2.num_row(), 0);
        mret.sub(1, hm1);
        mret.sub(hm1.num_row() + 1, hm2);
        return mret;
    }

    /* ---- functions ---- */

    public HepDiagMatrix T() {
        return new HepDiagMatrix(this);
    }

    public HepDiagMatrix apply(HepMatrix.ElementFunction f) {
        final HepDiagMatrix mret = new HepDiagMatrix(num_row());
        int a = 0;
        int b = 0;
        for (int ir = 1; ir <= num_row(); ir++) mret.m[b++] = f.apply(m[a++], ir, ir);
        return mret;
    }

    /** M D MT. CLHEP names its dimension check "similarity" with the quotes. */
    public HepSymMatrix similarity(HepMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(hm1.num_row());
        chkDim1(num_row(), hm1.num_col(), "DiagMatrix", "\"similarity\"");
        int mrc = 0;
        for (int r = 1; r <= mret.num_row(); r++) {
            final int mrr = (r - 1) * hm1.num_col();
            int mc = 0;
            for (int c = 1; c <= r; c++) {
                int mi = 0;
                double tmp = 0;
                int mr = mrr;
                for (int i = 0; i < hm1.num_col(); i++) tmp += hm1.m[mr++] * hm1.m[mc++] * m[mi++];
                mret.m[mrc++] = tmp;
            }
        }
        return mret;
    }

    /** vT D v. */
    public double similarity(HepVector hm1) {
        double mret;
        chkDim1(num_row(), hm1.num_row(), "DiagMatrix", "similarity");
        int mi = 0;
        int mv = 0;
        mret = hm1.m[mv] * hm1.m[mv] * m[mi++];
        mv++;
        for (int i = 2; i <= hm1.num_row(); i++) {
            mret += hm1.m[mv] * hm1.m[mv] * m[mi++];
            mv++;
        }
        return mret;
    }

    /** MT D M. */
    public HepSymMatrix similarityT(HepMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(hm1.num_col());
        chkDim1(num_col(), hm1.num_row(), "DiagMatrix", "similarityT");
        for (int r = 1; r <= mret.num_row(); r++) {
            for (int c = 1; c <= r; c++) {
                int mi = 0;
                double tmp = hm1.get(1, r) * hm1.get(1, c) * m[mi++];
                for (int i = 2; i <= hm1.num_row(); i++) tmp += hm1.get(i, r) * hm1.get(i, c) * m[mi++];
                mret.setFast(r, c, tmp);
            }
        }
        return mret;
    }

    public HepDiagMatrix sub(int min_row, int max_row) {
        final HepDiagMatrix mret = new HepDiagMatrix(max_row - min_row + 1);
        if (max_row > num_row()) error("HepDiagMatrix::sub: Index out of range");
        int a = 0;
        int b = min_row - 1;
        final int e = mret.num_row();
        for (; a < e; ) mret.m[a++] = m[b++];
        return mret;
    }

    public void sub(int row, HepDiagMatrix hm1) {
        if (row < 1 || row + hm1.num_row() - 1 > num_row()) error("HepDiagMatrix::sub: Index out of range");
        int a = 0;
        int b = row - 1;
        final int e = hm1.num_row();
        for (; a < e; ) m[b++] = hm1.m[a++];
    }

    public HepDiagMatrix inverse(int[] ierr) {
        final HepDiagMatrix mTmp = new HepDiagMatrix(this);
        mTmp.invert(ierr);
        return mTmp;
    }

    public HepDiagMatrix inverse() {
        final int[] ierr = new int[1];
        final HepDiagMatrix mt = inverse(ierr);
        if (ierr[0] != 0) throw new RuntimeException("Error in HepDiagMatrix inversion");
        return mt;
    }

    public void invert() {
        final int[] ierr = new int[1];
        invert(ierr);
        if (ierr[0] != 0) throw new RuntimeException("Error in HepDiagMatrix inversion");
    }

    /** Each element's inverse; ierr 1, the matrix unchanged, when one is zero. */
    @Override
    public void invert(int[] ierr) {
        final int n = num_row();
        ierr[0] = 1;
        int i;
        for (i = 0; i < n; i++) {
            if (m[i] == 0) return;
        }
        ierr[0] = 0;
        for (i = 0; i < n; i++) m[i] = 1.0 / m[i];
    }

    public double determinant() {
        double d = 1.0;
        for (int p = 0; p < nrow; p++) d *= m[p];
        return d;
    }

    public double trace() {
        double d = 0.0;
        for (int p = 0; p < nrow; p++) d += m[p];
        return d;
    }

    /** operator&lt;&lt;. */
    public COStream print(COStream os) {
        return printRows(os, this);
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
