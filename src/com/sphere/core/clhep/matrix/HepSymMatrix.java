package com.sphere.core.clhep.matrix;

import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's HepSymMatrix (Matrix/SymMatrix.h, .icc, SymMatrix.cc, SymMatrixInvert.cc and the operators of
 * DiagMatrix.cc whose left operand is a HepSymMatrix): a symmetric matrix, its lower triangle packed row
 * after row (element (r, c), c &lt;= r, at r(r-1)/2 + c-1).
 *
 * <p>5x5 and 6x6 matrices are inverted by Cholesky while most of this thread's recent ones were positive
 * definite, by Kramer's rule otherwise: CLHEP's adaptive choice, its statistics per thread
 * ({@link #resetThread()} forgets them).
 */
public class HepSymMatrix extends HepGenMatrix {

    double[] m;
    int nrow;
    int size_;

    /** posDefFraction5x5, posDefFraction6x6, adjustment5x5, adjustment6x6 (CLHEP_THREAD_LOCAL). */
    private static final class Adaptive {
        double posDefFraction5x5 = 1.0;
        double posDefFraction6x6 = 1.0;
        double adjustment5x5 = 0.0;
        double adjustment6x6 = 0.0;
    }

    private static final ThreadLocal<Adaptive> ADAPTIVE = ThreadLocal.withInitial(Adaptive::new);

    private static final double CHOLESKY_THRESHOLD_5x5 = .5;
    private static final double CHOLESKY_THRESHOLD_6x6 = .2;
    private static final double CHOLESKY_CREEP_5x5 = .005;
    private static final double CHOLESKY_CREEP_6x6 = .002;

    /** Forgets this thread's statistics of the inversions, as a new thread starts. */
    public static void resetThread() {
        ADAPTIVE.remove();
    }

    public HepSymMatrix() {
        m = new double[0];
        nrow = 0;
        size_ = 0;
    }

    /** p x p zeros. */
    public HepSymMatrix(int p) {
        nrow = p;
        size_ = nrow * (nrow + 1) / 2;
        m = new double[size_];
    }

    /** Zeros (init 0) or the identity (init 1). */
    public HepSymMatrix(int p, int init) {
        this(p);
        switch (init) {
            case 0:
                break;
            case 1:
                for (int i = 0; i < nrow; ++i) m[(i + 1) * i / 2 + i] = 1.0;
                break;
            default:
                error("SymMatrix: initialization must be either 0 or 1.");
        }
    }

    /** The lower triangle's numbers drawn from the distribution, row after row. */
    public HepSymMatrix(int p, HepRandom r) {
        this(p);
        for (int a = 0; a < size_; a++) m[a] = r.call();
    }

    public HepSymMatrix(HepSymMatrix hm1) {
        nrow = hm1.nrow;
        size_ = hm1.size_;
        m = hm1.m.clone();
    }

    public HepSymMatrix(HepDiagMatrix hm1) {
        nrow = hm1.nrow;
        size_ = nrow * (nrow + 1) / 2;
        final int n = num_row();
        m = new double[size_];
        int mrr = 0;
        int mr = 0;
        for (int r = 1; r <= n; r++) {
            m[mrr] = hm1.m[mr++];
            if (r < n) mrr += (r + 1);
        }
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
        return size_;
    }

    /** The element (row, col) with col &lt;= row, unchecked. */
    public double fast(int row, int col) {
        return m[(row * (row - 1)) / 2 + (col - 1)];
    }

    public void setFast(int row, int col, double v) {
        m[(row * (row - 1)) / 2 + (col - 1)] = v;
    }

    @Override
    public double get(int row, int col) {
        return row >= col ? fast(row, col) : fast(col, row);
    }

    @Override
    public void set(int row, int col, double value) {
        if (row >= col) setFast(row, col, value);
        else setFast(col, row, value);
    }

    /* ---- compound assignments ---- */

    public HepSymMatrix timesAssign(double t) {
        for (int a = 0; a < num_size(); a++) m[a] *= t;
        return this;
    }

    public HepSymMatrix divAssign(double t) {
        for (int a = 0; a < num_size(); a++) m[a] /= t;
        return this;
    }

    public HepSymMatrix plusAssign(HepSymMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "+=");
        for (int a = 0; a < num_size(); a++) m[a] += hm2.m[a];
        return this;
    }

    public HepSymMatrix minusAssign(HepSymMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "-=");
        for (int a = 0; a < num_size(); a++) m[a] -= hm2.m[a];
        return this;
    }

    public HepSymMatrix plusAssign(HepDiagMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "+=");
        int a = 0;
        int b = 0;
        for (int i = 1; i <= num_row(); i++) {
            m[a] += hm2.m[b++];
            if (i < num_row()) a += (i + 1);
        }
        return this;
    }

    /** CLHEP's check here is named "+=" (as its source has it). */
    public HepSymMatrix minusAssign(HepDiagMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "+=");
        int a = 0;
        int b = 0;
        for (int i = 1; i <= num_row(); i++) {
            m[a] -= hm2.m[b++];
            if (i < num_row()) a += (i + 1);
        }
        return this;
    }

    /* ---- operator= and assign ---- */

    public HepSymMatrix assign(HepSymMatrix hm1) {
        nrow = hm1.nrow;
        size_ = hm1.size_;
        m = hm1.m.clone();
        return this;
    }

    public HepSymMatrix assign(HepDiagMatrix hm1) {
        if (hm1.nrow != nrow) {
            nrow = hm1.nrow;
            size_ = nrow * (nrow + 1) / 2;
        }
        m = new double[size_];
        int mrr = 0;
        int mr = 0;
        for (int r = 1; r <= nrow; r++) {
            m[mrr] = hm1.m[mr++];
            if (r < nrow) mrr += (r + 1);
        }
        return this;
    }

    /** The lower triangle of a square matrix. */
    public void assign(HepMatrix hm1) {
        if (hm1.nrow != nrow) {
            nrow = hm1.nrow;
            size_ = nrow * (nrow + 1) / 2;
            m = java.util.Arrays.copyOf(m, size_);
        }
        int a = 0;
        int b = 0;
        for (int r = 1; r <= nrow; r++) {
            int d = a;
            for (int c = 1; c <= r; c++) m[b++] = hm1.m[d++];
            if (r < nrow) a += nrow;
        }
    }

    /* ---- operators ---- */

    public HepSymMatrix negate() {
        final HepSymMatrix hm2 = new HepSymMatrix(nrow);
        for (int a = 0; a < num_size(); a++) hm2.m[a] = -m[a];
        return hm2;
    }

    /** S + M: a general matrix. */
    public HepMatrix plus(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(hm2);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "+");
        mret.plusAssign(this);
        return mret;
    }

    public HepMatrix minus(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(this);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    public HepSymMatrix plus(HepSymMatrix hm2) {
        final HepSymMatrix mret = new HepSymMatrix(nrow);
        chkDim1(nrow, hm2.nrow, "SymMatrix", "+");
        for (int a = 0; a < num_size(); a++) mret.m[a] = m[a] + hm2.m[a];
        return mret;
    }

    public HepSymMatrix minus(HepSymMatrix hm2) {
        final HepSymMatrix mret = new HepSymMatrix(num_row());
        chkDim1(num_row(), hm2.num_row(), "SymMatrix", "-");
        for (int a = 0; a < num_size(); a++) mret.m[a] = m[a] - hm2.m[a];
        return mret;
    }

    /** S + D (DiagMatrix.cc). */
    public HepSymMatrix plus(HepDiagMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(this);
        chkDim1(hm1.num_row(), num_row(), "DiagMatrix", "+");
        mret.plusAssign(hm1);
        return mret;
    }

    public HepSymMatrix minus(HepDiagMatrix hm2) {
        final HepSymMatrix mret = new HepSymMatrix(this);
        chkDim1(num_row(), hm2.num_row(), "DiagMatrix", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    public HepSymMatrix div(double t) {
        final HepSymMatrix mret = new HepSymMatrix(this);
        mret.divAssign(t);
        return mret;
    }

    public HepSymMatrix times(double t) {
        final HepSymMatrix mret = new HepSymMatrix(this);
        mret.timesAssign(t);
        return mret;
    }

    /** t * S. */
    public static HepSymMatrix times(double t, HepSymMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(hm1);
        mret.timesAssign(t);
        return mret;
    }

    /** S * M, the symmetric matrix walked in its packed triangle. */
    public HepMatrix times(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), hm2.num_col());
        chkDim1(num_col(), hm2.num_row(), "SymMatrix", "*");
        int step;
        int stept;
        int mit1;
        int mit2;
        int sp;
        int snp;
        double temp;
        int mir = 0;
        for (step = 1, snp = 0; step <= num_row(); snp += step++) {
            for (mit1 = 0; mit1 < hm2.num_col(); mit1++) {
                mit2 = mit1;
                sp = snp;
                temp = 0;
                while (sp < snp + step) {
                    temp += hm2.m[mit2] * m[sp++];
                    if (hm2.num_size() - mit2 > hm2.num_col()) mit2 += hm2.num_col();
                }
                if (step < num_row()) {
                    sp += step - 1;
                    for (stept = step + 1; stept <= num_row(); stept++) {
                        temp += hm2.m[mit2] * m[sp];
                        if (stept < num_row()) {
                            mit2 += hm2.num_col();
                            sp += stept;
                        }
                    }
                }
                mret.m[mir++] = temp;
            }
        }
        return mret;
    }

    /** S * S: a general matrix. */
    public HepMatrix times(HepSymMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), num_row());
        chkDim1(num_col(), hm2.num_row(), "SymMatrix", "*");
        int step1;
        int stept1;
        int step2;
        int stept2;
        int snp1;
        int sp1;
        int snp2;
        int sp2;
        double temp;
        final double[] m1 = m;
        final double[] m2 = hm2.m;
        int mr = 0;
        snp1 = 0;
        for (step1 = 1; step1 <= num_row(); ++step1) {
            snp2 = 0;
            for (step2 = 1; step2 <= hm2.num_row(); ++step2) {
                sp1 = snp1;
                sp2 = snp2;
                snp2 += step2;
                temp = 0;
                if (step1 < step2) {
                    while (sp1 < snp1 + step1) temp += m1[sp1++] * m2[sp2++];
                    sp1 += step1 - 1;
                    for (stept1 = step1 + 1; stept1 != step2 + 1; ++stept1) {
                        temp += m1[sp1] * m2[sp2++];
                        if (stept1 < hm2.num_row()) sp1 += stept1;
                    }
                    if (step2 < hm2.num_row()) {
                        sp2 += step2 - 1;
                        for (stept2 = step2 + 1; stept2 <= hm2.num_row(); stept1++, stept2++) {
                            temp += m1[sp1] * m2[sp2];
                            if (stept2 < hm2.num_row()) {
                                sp1 += stept1;
                                sp2 += stept2;
                            }
                        }
                    }
                } else {
                    while (sp2 < snp2) temp += m1[sp1++] * m2[sp2++];
                    if (step2 < hm2.num_row()) {
                        sp2 += step2 - 1;
                        for (stept2 = step2 + 1; stept2 != step1 + 1; stept2++) {
                            temp += m1[sp1++] * m2[sp2];
                            if (stept2 < num_row()) sp2 += stept2;
                        }
                        if (step1 < num_row()) {
                            sp1 += step1 - 1;
                            for (stept1 = step1 + 1; stept1 <= num_row(); stept1++, stept2++) {
                                temp += m1[sp1] * m2[sp2];
                                if (stept1 < num_row()) {
                                    sp1 += stept1;
                                    sp2 += stept2;
                                }
                            }
                        }
                    }
                }
                mret.m[mr++] = temp;
            }
            if (step1 < num_row()) snp1 += step1;
        }
        return mret;
    }

    /** S * v. */
    public HepVector times(HepVector hm2) {
        final HepVector mret = new HepVector(num_row());
        chkDim1(num_col(), hm2.num_row(), "SymMatrix", "*");
        int sp;
        int snp;
        int vpt;
        double temp;
        int step;
        int stept;
        int vrp = 0;
        for (step = 1, snp = 0; step <= num_row(); ++step) {
            sp = snp;
            vpt = 0;
            snp += step;
            temp = 0;
            while (sp < snp) temp += m[sp++] * hm2.m[vpt++];
            if (step < num_row()) sp += step - 1;
            for (stept = step + 1; stept <= num_row(); stept++) {
                temp += m[sp] * hm2.m[vpt++];
                if (stept < num_row()) sp += stept;
            }
            mret.m[vrp++] = temp;
        }
        return mret;
    }

    /** v vT, as a symmetric matrix. */
    public static HepSymMatrix vT_times_v(HepVector v) {
        final HepSymMatrix mret = new HepSymMatrix(v.num_row());
        int mr = 0;
        for (int vt1 = 0; vt1 < v.num_row(); vt1++) {
            for (int vt2 = 0; vt2 <= vt1; vt2++) mret.m[mr++] = v.m[vt1] * v.m[vt2];
        }
        return mret;
    }

    /** The block-diagonal matrix of hm1 and hm2. */
    public static HepSymMatrix dsum(HepSymMatrix hm1, HepSymMatrix hm2) {
        final HepSymMatrix mret = new HepSymMatrix(hm1.num_row() + hm2.num_row(), 0);
        mret.sub(1, hm1);
        mret.sub(hm1.num_row() + 1, hm2);
        return mret;
    }

    /* ---- functions ---- */

    public HepSymMatrix T() {
        return new HepSymMatrix(this);
    }

    public HepSymMatrix apply(HepMatrix.ElementFunction f) {
        final HepSymMatrix mret = new HepSymMatrix(num_row());
        int a = 0;
        int b = 0;
        for (int ir = 1; ir <= num_row(); ir++) {
            for (int ic = 1; ic <= ir; ic++) mret.m[b++] = f.apply(m[a++], ir, ic);
        }
        return mret;
    }

    /** M S MT. */
    public HepSymMatrix similarity(HepMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(hm1.num_row());
        final HepMatrix temp = hm1.times(this);
        final int n = hm1.num_col();
        int mr = 0;
        int tempr1 = 0;
        for (int r = 1; r <= mret.num_row(); r++) {
            int hm1c1 = 0;
            for (int c = 1; c <= r; c++) {
                double tmp = 0.0;
                int tempri = tempr1;
                int hm1ci = hm1c1;
                for (int i = 1; i <= hm1.num_col(); i++) tmp += temp.m[tempri++] * hm1.m[hm1ci++];
                mret.m[mr++] = tmp;
                hm1c1 += n;
            }
            tempr1 += n;
        }
        return mret;
    }

    /** S1 S S1. */
    public HepSymMatrix similarity(HepSymMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(hm1.num_row());
        final HepMatrix temp = hm1.times(this);
        final int n = hm1.num_col();
        int mr = 0;
        int tempr1 = 0;
        for (int r = 1; r <= mret.num_row(); r++) {
            int hm1c1 = 0;
            int c;
            for (c = 1; c <= r; c++) {
                double tmp = 0.0;
                int tempri = tempr1;
                int hm1ci = hm1c1;
                int i;
                for (i = 1; i < c; i++) tmp += temp.m[tempri++] * hm1.m[hm1ci++];
                for (i = c; i <= hm1.num_col(); i++) {
                    tmp += temp.m[tempri++] * hm1.m[hm1ci];
                    if (i < hm1.num_col()) hm1ci += i;
                }
                mret.m[mr++] = tmp;
                hm1c1 += c;
            }
            tempr1 += n;
        }
        return mret;
    }

    /** vT S v. */
    public double similarity(HepVector hm1) {
        double mret = 0.0;
        final HepVector temp = times(hm1);
        int a = 0;
        int b = 0;
        final int e = a + hm1.num_row();
        for (; a < e; ) mret += temp.m[a++] * hm1.m[b++];
        return mret;
    }

    /** MT S M. */
    public HepSymMatrix similarityT(HepMatrix hm1) {
        final HepSymMatrix mret = new HepSymMatrix(hm1.num_col());
        final HepMatrix temp = times(hm1);
        final int n = hm1.num_col();
        int mrc = 0;
        int temp1r = 0;
        for (int r = 1; r <= mret.num_row(); r++) {
            int m11c = 0;
            for (int c = 1; c <= r; c++) {
                double tmp = 0.0;
                for (int i = 1; i <= hm1.num_row(); i++) {
                    final int tempir = temp1r + n * (i - 1);
                    final int hm1ic = m11c + n * (i - 1);
                    tmp += temp.m[tempir] * hm1.m[hm1ic];
                }
                mret.m[mrc++] = tmp;
                m11c++;
            }
            temp1r++;
        }
        return mret;
    }

    /** The block of rows and columns min_row..max_row. */
    public HepSymMatrix sub(int min_row, int max_row) {
        final HepSymMatrix mret = new HepSymMatrix(max_row - min_row + 1);
        if (max_row > num_row()) error("HepSymMatrix::sub: Index out of range");
        int a = 0;
        int b1 = (min_row + 2) * (min_row - 1) / 2;
        final int rowsize = mret.num_row();
        for (int irow = 1; irow <= rowsize; irow++) {
            int b = b1;
            for (int icol = 0; icol < irow; ++icol) mret.m[a++] = m[b++];
            if (irow < rowsize) b1 += irow + min_row - 1;
        }
        return mret;
    }

    /** hm1 copied in from (row, row). */
    public void sub(int row, HepSymMatrix hm1) {
        if (row < 1 || row + hm1.num_row() - 1 > num_row()) error("HepSymMatrix::sub: Index out of range");
        int a = 0;
        int b1 = (row + 2) * (row - 1) / 2;
        final int rowsize = hm1.num_row();
        for (int irow = 1; irow <= rowsize; ++irow) {
            int b = b1;
            for (int icol = 0; icol < irow; ++icol) m[b++] = hm1.m[a++];
            if (irow < rowsize) b1 += irow + row - 1;
        }
    }

    public HepSymMatrix inverse(int[] ifail) {
        final HepSymMatrix mTmp = new HepSymMatrix(this);
        mTmp.invert(ifail);
        return mTmp;
    }

    public HepSymMatrix inverse() {
        final int[] ierr = new int[1];
        final HepSymMatrix mt = inverse(ierr);
        if (ierr[0] != 0) throw new RuntimeException("Error in HepSymMatrix inversion");
        return mt;
    }

    public void invert() {
        final int[] ierr = new int[1];
        invert(ierr);
        if (ierr[0] != 0) throw new RuntimeException("Error in HepSymMatrix inversion");
    }

    /** The inverse in place: explicit to 6x6, Bunch-Kaufman above; ifail 1 when singular. */
    @Override
    public void invert(int[] ifail) {
        ifail[0] = 0;
        switch (nrow) {
            case 3: {
                double det;
                double temp;
                final double t1, t2, t3;
                final double c11, c12, c13, c22, c23, c33;
                c11 = m[2] * m[5] - m[4] * m[4];
                c12 = m[4] * m[3] - m[1] * m[5];
                c13 = m[1] * m[4] - m[2] * m[3];
                c22 = m[5] * m[0] - m[3] * m[3];
                c23 = m[3] * m[1] - m[4] * m[0];
                c33 = m[0] * m[2] - m[1] * m[1];
                t1 = Math.abs(m[0]);
                t2 = Math.abs(m[1]);
                t3 = Math.abs(m[3]);
                if (t1 >= t2) {
                    if (t3 >= t1) {
                        temp = m[3];
                        det = c23 * c12 - c22 * c13;
                    } else {
                        temp = m[0];
                        det = c22 * c33 - c23 * c23;
                    }
                } else if (t3 >= t2) {
                    temp = m[3];
                    det = c23 * c12 - c22 * c13;
                } else {
                    temp = m[1];
                    det = c13 * c23 - c12 * c33;
                }
                if (det == 0) {
                    ifail[0] = 1;
                    return;
                }
                final double ds = temp / det;
                m[0] = ds * c11;
                m[1] = ds * c12;
                m[2] = ds * c22;
                m[3] = ds * c13;
                m[4] = ds * c23;
                m[5] = ds * c33;
                break;
            }
            case 2: {
                final double det = m[0] * m[2] - m[1] * m[1];
                if (det == 0) {
                    ifail[0] = 1;
                    return;
                }
                final double ds = 1.0 / det;
                m[1] *= -ds;
                final double temp = ds * m[2];
                m[2] = ds * m[0];
                m[0] = temp;
                break;
            }
            case 1:
                if (m[0] == 0) {
                    ifail[0] = 1;
                    return;
                }
                m[0] = 1.0 / m[0];
                break;
            case 5:
                invert5(ifail);
                return;
            case 6:
                invert6(ifail);
                return;
            case 4:
                invert4(ifail);
                return;
            default:
                invertBunchKaufman(ifail);
                return;
        }
    }

    /** The determinant, by the LU decomposition of the full matrix (0 when it fails). */
    public double determinant() {
        final int[] ir = new int[Math.max(20, nrow) + 1];
        final double[] det = new double[1];
        final HepMatrix mt = new HepMatrix(this);
        final int i = mt.dfact_matrix(det, ir);
        if (i == 0) return det[0];
        return 0.0;
    }

    public double trace() {
        double t = 0.0;
        for (int i = 0; i < nrow; i++) t += m[(i + 3) * i / 2];
        return t;
    }

    private void invert4(int[] ifail) {
        SymMatrixInvert.invert4(this, ifail);
    }

    /** Cholesky while the recent 5x5 matrices were positive definite (or a creeping retry), Kramer otherwise. */
    private void invert5(int[] ifail) {
        final Adaptive s = ADAPTIVE.get();
        if (s.posDefFraction5x5 >= CHOLESKY_THRESHOLD_5x5) {
            invertCholesky5(ifail);
            s.posDefFraction5x5 = .9 * s.posDefFraction5x5 + .1 * (1 - ifail[0]);
            if (ifail[0] != 0) invertHaywood5(ifail);
        } else {
            if (s.posDefFraction5x5 + s.adjustment5x5 >= CHOLESKY_THRESHOLD_5x5) {
                invertCholesky5(ifail);
                s.posDefFraction5x5 = .9 * s.posDefFraction5x5 + .1 * (1 - ifail[0]);
                if (ifail[0] != 0) {
                    invertHaywood5(ifail);
                    s.adjustment5x5 = 0;
                }
            } else {
                invertHaywood5(ifail);
                s.adjustment5x5 += CHOLESKY_CREEP_5x5;
            }
        }
    }

    private void invert6(int[] ifail) {
        final Adaptive s = ADAPTIVE.get();
        if (s.posDefFraction6x6 >= CHOLESKY_THRESHOLD_6x6) {
            invertCholesky6(ifail);
            s.posDefFraction6x6 = .9 * s.posDefFraction6x6 + .1 * (1 - ifail[0]);
            if (ifail[0] != 0) invertHaywood6(ifail);
        } else {
            if (s.posDefFraction6x6 + s.adjustment6x6 >= CHOLESKY_THRESHOLD_6x6) {
                invertCholesky6(ifail);
                s.posDefFraction6x6 = .9 * s.posDefFraction6x6 + .1 * (1 - ifail[0]);
                if (ifail[0] != 0) {
                    invertHaywood6(ifail);
                    s.adjustment6x6 = 0;
                }
            } else {
                invertHaywood6(ifail);
                s.adjustment6x6 += CHOLESKY_CREEP_6x6;
            }
        }
    }

    public void invertCholesky5(int[] ifail) {
        SymMatrixInvert.invertCholesky5(this, ifail);
    }

    public void invertCholesky6(int[] ifail) {
        SymMatrixInvert.invertCholesky6(this, ifail);
    }

    public void invertHaywood4(int[] ifail) {
        invert4(ifail);
    }

    public void invertHaywood5(int[] ifail) {
        SymMatrixInvert.invertHaywood5(this, ifail);
    }

    public void invertHaywood6(int[] ifail) {
        SymMatrixInvert.invertHaywood6(this, ifail);
    }

    /** The Bunch-Kaufman diagonal pivoting inversion (any size; CLHEP's default above 6x6). */
    public void invertBunchKaufman(int[] ifail) {
        int i;
        int j;
        int k;
        int is = 0;
        int pivrow;
        final double[] x = new double[Math.max(25, nrow)];
        final int[] piv = new int[Math.max(25, nrow)];
        double temp1;
        double temp2;
        int ip;
        int mjj;
        int iq;
        double lambda;
        double sigma;
        final double alpha = .6404;
        final double epsilon = 32 * Math.ulp(1.0);

        for (i = 0; i < nrow; ++i) piv[i] = i + 1;

        ifail[0] = 0;

        // the factorization, with the pivots
        for (j = 1; j < nrow; j += is) {
            mjj = j * (j - 1) / 2 + j - 1;
            lambda = 0;
            pivrow = j + 1;
            for (i = j + 1; i <= nrow; ++i) {
                ip = (i - 1) * i / 2 + j - 1;
                if (Math.abs(m[ip]) > lambda) {
                    lambda = Math.abs(m[ip]);
                    pivrow = i;
                }
            }
            if (lambda == 0) {
                if (m[mjj] == 0) {
                    ifail[0] = 1;
                    return;
                }
                is = 1;
                m[mjj] = 1. / m[mjj];
            } else {
                if (Math.abs(m[mjj]) >= lambda * alpha) {
                    is = 1;
                    pivrow = j;
                } else {
                    sigma = 0;
                    ip = pivrow * (pivrow - 1) / 2 + j - 1;
                    for (k = j; k < pivrow; k++) {
                        if (Math.abs(m[ip]) > sigma) sigma = Math.abs(m[ip]);
                        ip++;
                    }
                    if (sigma * Math.abs(m[mjj]) >= alpha * lambda * lambda) {
                        is = 1;
                        pivrow = j;
                    } else if (Math.abs(m[pivrow * (pivrow - 1) / 2 + pivrow - 1]) >= alpha * sigma) {
                        is = 1;
                    } else {
                        is = 2;
                    }
                }
                if (pivrow == j) {
                    piv[j - 1] = pivrow;
                    if (m[mjj] == 0) {
                        ifail[0] = 1;
                        return;
                    }
                    temp2 = m[mjj] = 1. / m[mjj];
                    for (i = j + 1; i <= nrow; i++) {
                        temp1 = m[i * (i - 1) / 2 + j - 1] * temp2;
                        ip = i * (i - 1) / 2 + j;
                        for (k = j + 1; k <= i; k++) {
                            m[ip] -= temp1 * m[k * (k - 1) / 2 + j - 1];
                            if (Math.abs(m[ip]) <= epsilon) m[ip] = 0;
                            ip++;
                        }
                    }
                    for (i = j + 1; i <= nrow; ++i) {
                        ip = (i - 1) * i / 2 + j - 1;
                        m[ip] *= temp2;
                    }
                } else if (is == 1) {
                    piv[j - 1] = pivrow;
                    ip = pivrow * (pivrow - 1) / 2 + j;
                    for (i = j + 1; i < pivrow; i++, ip++) {
                        temp1 = m[i * (i - 1) / 2 + j - 1];
                        m[i * (i - 1) / 2 + j - 1] = m[ip];
                        m[ip] = temp1;
                    }
                    temp1 = m[mjj];
                    m[mjj] = m[pivrow * (pivrow - 1) / 2 + pivrow - 1];
                    m[pivrow * (pivrow - 1) / 2 + pivrow - 1] = temp1;
                    ip = (pivrow + 1) * pivrow / 2 + j - 1;
                    iq = ip + pivrow - j;
                    for (i = pivrow + 1; i <= nrow; ip += i, iq += i++) {
                        temp1 = m[iq];
                        m[iq] = m[ip];
                        m[ip] = temp1;
                    }
                    if (m[mjj] == 0) {
                        ifail[0] = 1;
                        return;
                    }
                    temp2 = m[mjj] = 1. / m[mjj];
                    for (i = j + 1; i <= nrow; i++) {
                        temp1 = m[i * (i - 1) / 2 + j - 1] * temp2;
                        ip = i * (i - 1) / 2 + j;
                        for (k = j + 1; k <= i; k++) {
                            m[ip] -= temp1 * m[k * (k - 1) / 2 + j - 1];
                            if (Math.abs(m[ip]) <= epsilon) m[ip] = 0;
                            ip++;
                        }
                    }
                    for (i = j + 1; i <= nrow; ++i) {
                        ip = (i - 1) * i / 2 + j - 1;
                        m[ip] *= temp2;
                    }
                } else {
                    // a 2x2 pivot
                    piv[j - 1] = -pivrow;
                    piv[j] = 0;
                    if (j + 1 != pivrow) {
                        ip = pivrow * (pivrow - 1) / 2 + j + 1;
                        for (i = j + 2; i < pivrow; i++, ip++) {
                            temp1 = m[i * (i - 1) / 2 + j];
                            m[i * (i - 1) / 2 + j] = m[ip];
                            m[ip] = temp1;
                        }
                        temp1 = m[mjj + j + 1];
                        m[mjj + j + 1] = m[pivrow * (pivrow - 1) / 2 + pivrow - 1];
                        m[pivrow * (pivrow - 1) / 2 + pivrow - 1] = temp1;
                        temp1 = m[mjj + j];
                        m[mjj + j] = m[pivrow * (pivrow - 1) / 2 + j - 1];
                        m[pivrow * (pivrow - 1) / 2 + j - 1] = temp1;
                        ip = (pivrow + 1) * pivrow / 2 + j;
                        iq = ip + pivrow - (j + 1);
                        for (i = pivrow + 1; i <= nrow; ip += i, iq += i++) {
                            temp1 = m[iq];
                            m[iq] = m[ip];
                            m[ip] = temp1;
                        }
                    }
                    temp2 = m[mjj] * m[mjj + j + 1] - m[mjj + j] * m[mjj + j];
                    if (temp2 == 0) {
                        StdStreams.cerr().print("SymMatrix::bunch_invert: error in pivot choice\n");
                    }
                    temp2 = 1. / temp2;
                    temp1 = m[mjj];
                    m[mjj] = m[mjj + j + 1] * temp2;
                    m[mjj + j + 1] = temp1 * temp2;
                    m[mjj + j] = -m[mjj + j] * temp2;
                    if (j < nrow - 1) {
                        for (i = j + 2; i <= nrow; i++) {
                            ip = i * (i - 1) / 2 + j - 1;
                            temp1 = m[ip] * m[mjj] + m[ip + 1] * m[mjj + j];
                            if (Math.abs(temp1) <= epsilon) temp1 = 0;
                            temp2 = m[ip] * m[mjj + j] + m[ip + 1] * m[mjj + j + 1];
                            if (Math.abs(temp2) <= epsilon) temp2 = 0;
                            for (k = j + 2; k <= i; k++) {
                                ip = i * (i - 1) / 2 + k - 1;
                                iq = k * (k - 1) / 2 + j - 1;
                                m[ip] -= temp1 * m[iq] + temp2 * m[iq + 1];
                                if (Math.abs(m[ip]) <= epsilon) m[ip] = 0;
                            }
                        }
                        for (i = j + 2; i <= nrow; i++) {
                            ip = i * (i - 1) / 2 + j - 1;
                            temp1 = m[ip] * m[mjj] + m[ip + 1] * m[mjj + j];
                            if (Math.abs(temp1) <= epsilon) temp1 = 0;
                            m[ip + 1] = m[ip] * m[mjj + j] + m[ip + 1] * m[mjj + j + 1];
                            if (Math.abs(m[ip + 1]) <= epsilon) m[ip + 1] = 0;
                            m[ip] = temp1;
                        }
                    }
                }
            }
        }

        if (j == nrow) {
            mjj = j * (j - 1) / 2 + j - 1;
            if (m[mjj] == 0) {
                ifail[0] = 1;
                return;
            } else {
                m[mjj] = 1. / m[mjj];
            }
        }

        // the inverse from the factors
        for (j = nrow; j >= 1; j -= is) {
            mjj = j * (j - 1) / 2 + j - 1;
            if (piv[j - 1] > 0) {
                is = 1;
                if (j < nrow) {
                    ip = (j + 1) * j / 2 - 1;
                    for (i = 0; i < nrow - j; ++i) {
                        ip += i + j;
                        x[i] = m[ip];
                    }
                    for (i = j + 1; i <= nrow; i++) {
                        temp2 = 0;
                        ip = i * (i - 1) / 2 + j;
                        for (k = 0; k <= i - j - 1; k++) temp2 += m[ip++] * x[k];
                        ip -= 1;
                        for (; k < nrow - j; ++k) {
                            ip += j + k;
                            temp2 += m[ip] * x[k];
                        }
                        m[i * (i - 1) / 2 + j - 1] = -temp2;
                    }
                    temp2 = 0;
                    ip = (j + 1) * j / 2 - 1;
                    for (k = 0; k < nrow - j; ++k) {
                        ip += j + k;
                        temp2 += x[k] * m[ip];
                    }
                    m[mjj] -= temp2;
                }
            } else {
                if (piv[j - 1] != 0) StdStreams.cerr().print("error in piv" + piv[j - 1] + "\n");
                is = 2;
                if (j < nrow) {
                    ip = (j + 1) * j / 2 - 1;
                    for (i = 0; i < nrow - j; ++i) {
                        ip += i + j;
                        x[i] = m[ip];
                    }
                    for (i = j + 1; i <= nrow; i++) {
                        temp2 = 0;
                        ip = i * (i - 1) / 2 + j;
                        for (k = 0; k <= i - j - 1; k++) temp2 += m[ip++] * x[k];
                        for (ip += i - 1; k < nrow - j; ip += 1 + j + k++) temp2 += m[ip] * x[k];
                        m[i * (i - 1) / 2 + j - 1] = -temp2;
                    }
                    temp2 = 0;
                    ip = (j + 1) * j / 2 - 1;
                    for (k = 0; k < nrow - j; ++k) {
                        ip += k + j;
                        temp2 += x[k] * m[ip];
                    }
                    m[mjj] -= temp2;
                    temp2 = 0;
                    ip = (j + 1) * j / 2 - 2;
                    for (i = j + 1; i <= nrow; ++i) {
                        ip += i - 1;
                        temp2 += m[ip] * m[ip + 1];
                    }
                    m[mjj - 1] -= temp2;
                    ip = (j + 1) * j / 2 - 2;
                    for (i = 0; i < nrow - j; ++i) {
                        ip += i + j;
                        x[i] = m[ip];
                    }
                    for (i = j + 1; i <= nrow; i++) {
                        temp2 = 0;
                        ip = i * (i - 1) / 2 + j;
                        for (k = 0; k <= i - j - 1; k++) temp2 += m[ip++] * x[k];
                        for (ip += i - 1; k < nrow - j; ip += 1 + j + k++) temp2 += m[ip] * x[k];
                        m[i * (i - 1) / 2 + j - 2] = -temp2;
                    }
                    temp2 = 0;
                    ip = (j + 1) * j / 2 - 2;
                    for (k = 0; k < nrow - j; ++k) {
                        ip += k + j;
                        temp2 += x[k] * m[ip];
                    }
                    m[mjj - j] -= temp2;
                }
            }

            // the interchanges, undone
            pivrow = (piv[j - 1] == 0) ? -piv[j - 2] : piv[j - 1];
            ip = pivrow * (pivrow - 1) / 2 + j;
            for (i = j + 1; i < pivrow; i++, ip++) {
                temp1 = m[i * (i - 1) / 2 + j - 1];
                m[i * (i - 1) / 2 + j - 1] = m[ip];
                m[ip] = temp1;
            }
            temp1 = m[mjj];
            m[mjj] = m[pivrow * (pivrow - 1) / 2 + pivrow - 1];
            m[pivrow * (pivrow - 1) / 2 + pivrow - 1] = temp1;
            if (is == 2) {
                temp1 = m[mjj - 1];
                m[mjj - 1] = m[pivrow * (pivrow - 1) / 2 + j - 2];
                m[pivrow * (pivrow - 1) / 2 + j - 2] = temp1;
            }

            if (pivrow < nrow) {
                ip = (pivrow + 1) * pivrow / 2 + j - 1;
                iq = ip + (pivrow - j);
                for (i = pivrow + 1; i <= nrow; i++) {
                    temp1 = m[iq];
                    m[iq] = m[ip];
                    m[ip] = temp1;
                    if (i < nrow) {
                        ip += i;
                        iq += i;
                    }
                }
            }
        }
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
