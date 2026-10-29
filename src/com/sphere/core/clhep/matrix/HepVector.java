package com.sphere.core.clhep.matrix;

import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's HepVector (Matrix/Vector.h, .icc, Vector.cc): a column vector of any length, its elements by
 * (row) from 1 or [row] from 0; solve(A, b) solves A x = b.
 */
public class HepVector extends HepGenMatrix {

    /** A function of an element and its row (apply). */
    @FunctionalInterface
    public interface ElementFunction {
        double apply(double x, int row);
    }

    double[] m;
    int nrow;

    public HepVector() {
        m = new double[0];
        nrow = 0;
    }

    /** p zeros. */
    public HepVector(int p) {
        m = new double[p];
        nrow = p;
    }

    /** Zeros (init 0) or ones (init 1). */
    public HepVector(int p, int init) {
        this(p);
        switch (init) {
            case 0:
                break;
            case 1:
                for (int i = 0; i < nrow; i++) m[i] = 1.0;
                break;
            default:
                error("Vector: initialization must be either 0 or 1.");
        }
    }

    /** p numbers drawn from the distribution. */
    public HepVector(int p, HepRandom r) {
        this(p);
        for (int a = 0; a < nrow; a++) m[a] = r.call();
    }

    public HepVector(HepVector hm1) {
        nrow = hm1.nrow;
        m = hm1.m.clone();
    }

    /** From an n x 1 matrix. */
    public HepVector(HepMatrix hm1) {
        nrow = hm1.nrow;
        m = new double[hm1.nrow];
        if (hm1.num_col() != 1) error("Vector::Vector(Matrix) : Matrix is not Nx1");
        m = hm1.m.clone();
    }

    @Override
    public int num_row() {
        return nrow;
    }

    @Override
    public int num_col() {
        return 1;
    }

    @Override
    protected int num_size() {
        return nrow;
    }

    /** operator()(row): from 1, unchecked. */
    public double get(int row) {
        return m[row - 1];
    }

    public void set(int row, double v) {
        m[row - 1] = v;
    }

    /** operator()(row, col): the column is not looked at. */
    @Override
    public double get(int row, int col) {
        return m[row - 1];
    }

    @Override
    public void set(int row, int col, double value) {
        m[row - 1] = value;
    }

    /** operator[](row): from 0. */
    public double at(int row) {
        return m[row];
    }

    public void setAt(int row, double v) {
        m[row] = v;
    }

    public double normsq() {
        return dot(this, this);
    }

    public double norm() {
        return Math.sqrt(normsq());
    }

    /* ---- compound assignments ---- */

    public HepVector timesAssign(double t) {
        for (int a = 0; a < num_size(); a++) m[a] *= t;
        return this;
    }

    public HepVector divAssign(double t) {
        for (int a = 0; a < num_size(); a++) m[a] /= t;
        return this;
    }

    public HepVector plusAssign(HepMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), 1, hm2.num_col(), "Vector", "+=");
        for (int a = 0; a < num_size(); a++) m[a] += hm2.m[a];
        return this;
    }

    public HepVector plusAssign(HepVector hm2) {
        chkDim1(num_row(), hm2.num_row(), "Vector", "+=");
        for (int a = 0; a < num_size(); a++) m[a] += hm2.m[a];
        return this;
    }

    public HepVector minusAssign(HepMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), 1, hm2.num_col(), "Vector", "-=");
        for (int a = 0; a < num_size(); a++) m[a] -= hm2.m[a];
        return this;
    }

    public HepVector minusAssign(HepVector hm2) {
        chkDim1(num_row(), hm2.num_row(), "Vector", "-=");
        for (int a = 0; a < num_size(); a++) m[a] -= hm2.m[a];
        return this;
    }

    /* ---- operator= ---- */

    public HepVector assign(HepVector hm1) {
        nrow = hm1.nrow;
        m = hm1.m.clone();
        return this;
    }

    public HepVector assign(HepMatrix hm1) {
        if (hm1.num_col() != 1) error("Vector::operator=(Matrix) : Matrix is not Nx1");
        nrow = hm1.nrow;
        m = hm1.m.clone();
        return this;
    }

    public HepVector assign(Hep3Vector v) {
        if (nrow != 3) {
            nrow = 3;
            m = java.util.Arrays.copyOf(m, nrow);
        }
        m[0] = v.x();
        m[1] = v.y();
        m[2] = v.z();
        return this;
    }

    /* ---- operators ---- */

    public HepVector negate() {
        final HepVector hm2 = new HepVector(nrow);
        for (int a = 0; a < num_size(); a++) hm2.m[a] = -m[a];
        return hm2;
    }

    /** v + M: a vector. */
    public HepVector plus(HepMatrix hm2) {
        final HepVector mret = new HepVector(this);
        chkDim2(num_row(), hm2.num_row(), 1, hm2.num_col(), "Vector", "+");
        mret.plusAssign(hm2);
        return mret;
    }

    public HepVector plus(HepVector hm2) {
        final HepVector mret = new HepVector(num_row());
        chkDim1(num_row(), hm2.num_row(), "Vector", "+");
        for (int a = 0; a < num_size(); a++) mret.m[a] = m[a] + hm2.m[a];
        return mret;
    }

    public HepVector minus(HepMatrix hm2) {
        final HepVector mret = new HepVector(this);
        chkDim2(num_row(), hm2.num_row(), 1, hm2.num_col(), "Vector", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    public HepVector minus(HepVector hm2) {
        final HepVector mret = new HepVector(num_row());
        chkDim1(num_row(), hm2.num_row(), "Vector", "-");
        for (int a = 0; a < num_size(); a++) mret.m[a] = m[a] - hm2.m[a];
        return mret;
    }

    public HepVector div(double t) {
        final HepVector mret = new HepVector(this);
        mret.divAssign(t);
        return mret;
    }

    public HepVector times(double t) {
        final HepVector mret = new HepVector(this);
        mret.timesAssign(t);
        return mret;
    }

    /** t * v. */
    public static HepVector times(double t, HepVector hm1) {
        final HepVector mret = new HepVector(hm1);
        mret.timesAssign(t);
        return mret;
    }

    /** v * M (v a column, M a row): the outer product. */
    public HepMatrix times(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), hm2.num_col());
        chkDim1(1, hm2.num_row(), "Vector", "*");
        int mrp = 0;
        for (int hm1p = 0; hm1p < num_row(); hm1p++) {
            for (int hm2p = 0; hm2p < hm2.num_col(); hm2p++) mret.m[mrp++] = m[hm1p] * hm2.m[hm2p];
        }
        return mret;
    }

    /** The concatenation of s1 and s2. */
    public static HepVector dsum(HepVector hm1, HepVector hm2) {
        final HepVector mret = new HepVector(hm1.num_row() + hm2.num_row(), 0);
        mret.sub(1, hm1);
        mret.sub(hm1.num_row() + 1, hm2);
        return mret;
    }

    /** CLHEP's swap: elements and lengths exchanged. */
    public static void swap(HepVector v1, HepVector v2) {
        final double[] t = v1.m;
        v1.m = v2.m;
        v2.m = t;
        final int n = v1.nrow;
        v1.nrow = v2.nrow;
        v2.nrow = n;
    }

    public static double dot(HepVector v1, HepVector v2) {
        if (v1.num_row() != v2.num_row()) {
            error("v1 and v2 need to be the same size in dot(HepVector, HepVector)");
        }
        double d = 0;
        int a = 0;
        int b = 0;
        final int e = a + v1.num_size();
        for (; a < e; ) d += v1.m[a++] * v2.m[b++];
        return d;
    }

    /* ---- functions ---- */

    public HepVector apply(ElementFunction f) {
        final HepVector mret = new HepVector(num_row());
        int a = 0;
        int b = 0;
        for (int ir = 1; ir <= num_row(); ir++) mret.m[b++] = f.apply(m[a++], ir);
        return mret;
    }

    public HepVector sub(int min_row, int max_row) {
        final HepVector vret = new HepVector(max_row - min_row + 1);
        if (max_row > num_row()) error("HepVector::sub: Index out of range");
        int a = 0;
        int b = min_row - 1;
        final int e = vret.num_row();
        for (; a < e; ) vret.m[a++] = m[b++];
        return vret;
    }

    public void sub(int row, HepVector v1) {
        if (row < 1 || row + v1.num_row() - 1 > num_row()) error("HepVector::sub: Index out of range");
        int a = 0;
        int b = row - 1;
        final int e = v1.num_row();
        for (; a < e; ) m[b++] = v1.m[a++];
    }

    /** The transpose: a 1 x n matrix. */
    public HepMatrix T() {
        final HepMatrix mret = new HepMatrix(1, num_row());
        mret.m = m.clone();
        return mret;
    }

    @Override
    public void invert(int[] ierr) {
        error("HepVector::invert: You cannot invert a Vector");
    }

    /** x of a x = v, by dfact's LU decomposition of a; zeros when it fails. */
    public static HepVector solve(HepMatrix a, HepVector v) {
        final HepVector vret = new HepVector(v);
        if (a.ncol != a.nrow) error("Matrix::solve Matrix is not NxN");
        if (a.ncol != v.nrow) error("Matrix::solve Vector has wrong number of rows");
        final int n = a.ncol;
        final int[] ir = new int[Math.max(20, n) + 1];
        final double[] det = new double[1];
        final HepMatrix mt = new HepMatrix(a);
        int i = mt.dfact_matrix(det, ir);
        if (i != 0) {
            for (i = 1; i <= n; i++) vret.set(i, 0);
            return vret;
        }
        double s21;
        double s22;
        final int nxch = ir[n];
        if (nxch != 0) {
            for (int hmm = 1; hmm <= nxch; hmm++) {
                final int ij = ir[hmm];
                i = ij >> 12;
                final int j = ij % 4096;
                final double te = vret.get(i);
                vret.set(i, vret.get(j));
                vret.set(j, te);
            }
        }
        vret.set(1, mt.get(1, 1) * vret.get(1));
        if (n != 1) {
            for (i = 2; i <= n; i++) {
                s21 = -vret.get(i);
                for (int j = 1; j < i; j++) s21 += mt.get(i, j) * vret.get(j);
                vret.set(i, -mt.get(i, i) * s21);
            }
            for (i = 1; i < n; i++) {
                final int nmi = n - i;
                s22 = -vret.get(nmi);
                for (int j = 1; j <= i; j++) s22 += mt.get(nmi, n - j + 1) * vret.get(n - j + 1);
                vret.set(nmi, -s22);
            }
        }
        return vret;
    }

    /** operator&lt;&lt;: a new line, then each element in a field of precision+7 (fixed: +3), on its line. */
    public COStream print(COStream os) {
        os.put("\n");
        final int width = os.flags().floatField() == COStream.FloatField.FIXED ? os.precision() + 3 : os.precision() + 7;
        for (int irow = 1; irow <= num_row(); irow++) {
            os.width(width);
            os.put(get(irow)).put("\n");
        }
        return os;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
