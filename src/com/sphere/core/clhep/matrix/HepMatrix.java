package com.sphere.core.clhep.matrix;

import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's HepMatrix (Matrix/Matrix.h, .icc, Matrix.cc, MatrixInvert.cc, MatrixEqRotation.cc and the
 * operators of SymMatrix.cc, DiagMatrix.cc and Vector.cc whose left operand is a HepMatrix): a general
 * n x m matrix, its elements row after row.
 *
 * <p>The C++ operators are methods of their left operand (plus, minus, times, div; plusAssign...;
 * assign for operator=; negate for the unary minus); the static times(double, HepMatrix) is a * M.
 */
public class HepMatrix extends HepGenMatrix {

    /** A function of an element and its row and column (apply). */
    @FunctionalInterface
    public interface ElementFunction {
        double apply(double x, int row, int col);
    }

    double[] m;
    int nrow;
    int ncol;
    int size_;

    public HepMatrix() {
        m = new double[0];
        nrow = 0;
        ncol = 0;
        size_ = 0;
    }

    /** p x q zeros. */
    public HepMatrix(int p, int q) {
        m = new double[p * q];
        nrow = p;
        ncol = q;
        size_ = nrow * ncol;
    }

    /** p x q zeros (init 0) or the identity (init 1, a square matrix). */
    public HepMatrix(int p, int q, int init) {
        this(p, q);
        if (size_ > 0) {
            switch (init) {
                case 0:
                    break;
                case 1:
                    if (ncol == nrow) {
                        for (int step = 0; step < size_; step += (ncol + 1)) m[step] = 1.0;
                    } else {
                        error("Invalid dimension in HepMatrix(int,int,1).");
                    }
                    break;
                default:
                    error("Matrix: initialization must be either 0 or 1.");
            }
        }
    }

    /** p x q numbers of the distribution, row after row. */
    public HepMatrix(int p, int q, HepRandom r) {
        this(p, q);
        for (int a = 0; a < size_; a++) m[a] = r.call();
    }

    public HepMatrix(HepMatrix hm1) {
        nrow = hm1.nrow;
        ncol = hm1.ncol;
        size_ = hm1.size_;
        m = hm1.m.clone();
    }

    public HepMatrix(HepSymMatrix hm1) {
        nrow = hm1.nrow;
        ncol = hm1.nrow;
        m = new double[nrow * ncol];
        size_ = nrow * ncol;
        int sjk = 0;
        for (int j = 0; j != nrow; ++j) {
            for (int k = 0; k <= j; ++k) {
                m[j * ncol + k] = hm1.m[sjk];
                if (k != j) m[k * nrow + j] = hm1.m[sjk];
                ++sjk;
            }
        }
    }

    public HepMatrix(HepDiagMatrix hm1) {
        nrow = hm1.nrow;
        ncol = hm1.nrow;
        m = new double[nrow * ncol];
        size_ = nrow * ncol;
        final int n = num_row();
        int mr = 0;
        for (int r = 0; r < n; r++) m[(n + 1) * r] = hm1.m[mr++];
    }

    /** A column. */
    public HepMatrix(HepVector hm1) {
        nrow = hm1.nrow;
        ncol = 1;
        size_ = nrow;
        m = hm1.m.clone();
    }

    @Override
    public int num_row() {
        return nrow;
    }

    @Override
    public int num_col() {
        return ncol;
    }

    @Override
    protected int num_size() {
        return size_;
    }

    @Override
    public double get(int row, int col) {
        return m[(row - 1) * ncol + col - 1];
    }

    @Override
    public void set(int row, int col, double value) {
        m[(row - 1) * ncol + col - 1] = value;
    }

    /* ---- compound assignments ---- */

    public HepMatrix timesAssign(double t) {
        for (int a = 0; a < m.length; a++) m[a] *= t;
        return this;
    }

    public HepMatrix divAssign(double t) {
        for (int a = 0; a < m.length; a++) m[a] /= t;
        return this;
    }

    public HepMatrix plusAssign(HepMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "Matrix", "+=");
        for (int a = 0; a < m.length; a++) m[a] += hm2.m[a];
        return this;
    }

    public HepMatrix minusAssign(HepMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "Matrix", "-=");
        for (int a = 0; a < m.length; a++) m[a] -= hm2.m[a];
        return this;
    }

    public HepMatrix plusAssign(HepSymMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "+=");
        int sjk = 0;
        for (int j = 0; j != nrow; ++j) {
            for (int k = 0; k <= j; ++k) {
                m[j * ncol + k] += hm2.m[sjk];
                if (k != j) m[k * nrow + j] += hm2.m[sjk];
                ++sjk;
            }
        }
        return this;
    }

    public HepMatrix minusAssign(HepSymMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "-=");
        int sjk = 0;
        for (int j = 0; j != nrow; ++j) {
            for (int k = 0; k <= j; ++k) {
                m[j * ncol + k] -= hm2.m[sjk];
                if (k != j) m[k * nrow + j] -= hm2.m[sjk];
                ++sjk;
            }
        }
        return this;
    }

    public HepMatrix plusAssign(HepDiagMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "+=");
        final int n = num_row();
        int mrr = 0;
        int mr = 0;
        for (int r = 1; r <= n; r++) {
            m[mrr] += hm2.m[mr++];
            if (r < n) mrr += (n + 1);
        }
        return this;
    }

    public HepMatrix minusAssign(HepDiagMatrix hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "-=");
        final int n = num_row();
        int mrr = 0;
        int mr = 0;
        for (int r = 1; r <= n; r++) {
            m[mrr] -= hm2.m[mr++];
            if (r < n) mrr += (n + 1);
        }
        return this;
    }

    public HepMatrix plusAssign(HepVector hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), 1, "Vector", "+=");
        for (int a = 0; a < m.length; a++) m[a] += hm2.m[a];
        return this;
    }

    public HepMatrix minusAssign(HepVector hm2) {
        chkDim2(num_row(), hm2.num_row(), num_col(), 1, "Vector", "-=");
        for (int a = 0; a < m.length; a++) m[a] -= hm2.m[a];
        return this;
    }

    /* ---- operator= ---- */

    public HepMatrix assign(HepMatrix hm1) {
        size_ = hm1.nrow * hm1.ncol;
        nrow = hm1.nrow;
        ncol = hm1.ncol;
        m = hm1.m.clone();
        return this;
    }

    public HepMatrix assign(HepSymMatrix hm1) {
        nrow = ncol = hm1.nrow;
        if (nrow * ncol != size_) {
            size_ = nrow * ncol;
            m = java.util.Arrays.copyOf(m, size_);
        }
        int sjk = 0;
        for (int j = 0; j != nrow; ++j) {
            for (int k = 0; k <= j; ++k) {
                m[j * ncol + k] = hm1.m[sjk];
                if (k != j) m[k * nrow + j] = hm1.m[sjk];
                ++sjk;
            }
        }
        return this;
    }

    public HepMatrix assign(HepDiagMatrix hm1) {
        size_ = hm1.nrow * hm1.nrow;
        nrow = hm1.nrow;
        ncol = hm1.nrow;
        final int n = nrow;
        m = new double[size_];
        int mrr = 0;
        int mr = 0;
        for (int r = 1; r <= n; r++) {
            m[mrr] = hm1.m[mr++];
            if (r < n) mrr += (n + 1);
        }
        return this;
    }

    public HepMatrix assign(HepVector hm1) {
        size_ = hm1.nrow;
        nrow = hm1.nrow;
        ncol = 1;
        m = hm1.m.clone();
        return this;
    }

    /** The 3x3 matrix of a rotation (MatrixEqRotation.cc). */
    public HepMatrix assign(HepRotation hm1) {
        if (9 != size_) {
            size_ = 9;
            m = java.util.Arrays.copyOf(m, size_);
        }
        nrow = ncol = 3;
        m[0] = hm1.xx();
        m[1] = hm1.xy();
        m[2] = hm1.xz();
        m[3] = hm1.yx();
        m[4] = hm1.yy();
        m[5] = hm1.yz();
        m[6] = hm1.zx();
        m[7] = hm1.zy();
        m[8] = hm1.zz();
        return this;
    }

    /* ---- operators ---- */

    public HepMatrix negate() {
        final HepMatrix hm2 = new HepMatrix(nrow, ncol);
        for (int a = 0; a < m.length; a++) hm2.m[a] = -m[a];
        return hm2;
    }

    public HepMatrix plus(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(nrow, ncol);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "Matrix", "+");
        for (int a = 0; a < m.length; a++) mret.m[a] = m[a] + hm2.m[a];
        return mret;
    }

    public HepMatrix minus(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), num_col());
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "Matrix", "-");
        for (int a = 0; a < m.length; a++) mret.m[a] = m[a] - hm2.m[a];
        return mret;
    }

    public HepMatrix div(double t) {
        final HepMatrix mret = new HepMatrix(this);
        mret.divAssign(t);
        return mret;
    }

    public HepMatrix times(double t) {
        final HepMatrix mret = new HepMatrix(this);
        mret.timesAssign(t);
        return mret;
    }

    /** t * M. */
    public static HepMatrix times(double t, HepMatrix hm1) {
        final HepMatrix mret = new HepMatrix(hm1);
        mret.timesAssign(t);
        return mret;
    }

    public HepMatrix times(HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(nrow, hm2.ncol, 0);
        chkDim1(ncol, hm2.nrow, "Matrix", "*");
        final int m1cols = ncol;
        final int m2cols = hm2.ncol;
        for (int i = 0; i < nrow; i++) {
            for (int j = 0; j < m1cols; j++) {
                final double temp = m[i * m1cols + j];
                int pt = i * m2cols;
                int pb = m2cols * j;
                final int pblast = pb + m2cols;
                while (pb < pblast) {
                    mret.m[pt] += temp * hm2.m[pb];
                    pb++;
                    pt++;
                }
            }
        }
        return mret;
    }

    public HepMatrix plus(HepSymMatrix hm2) {
        final HepMatrix mret = new HepMatrix(this);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "+");
        mret.plusAssign(hm2);
        return mret;
    }

    public HepMatrix minus(HepSymMatrix hm2) {
        final HepMatrix mret = new HepMatrix(this);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "SymMatrix", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    /** M * S (SymMatrix.cc), the symmetric matrix walked in its packed triangle. */
    public HepMatrix times(HepSymMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), hm2.num_col());
        chkDim1(num_col(), hm2.num_row(), "SymMatrix", "*");
        final double[] s = hm2.m;
        int mit1;
        int mit2 = 0;
        int sp;
        int snp;
        double temp;
        int mir = 0;
        for (mit1 = 0; mit1 < num_row() * num_col(); mit1 = mit2) {
            snp = 0;
            for (int step = 1; step <= hm2.num_row(); ++step) {
                mit2 = mit1;
                sp = snp;
                snp += step;
                temp = 0;
                while (sp < snp) temp += s[sp++] * m[mit2++];
                if (step < hm2.num_row()) {
                    sp += step - 1;
                    for (int stept = step + 1; stept <= hm2.num_row(); stept++) {
                        temp += s[sp] * m[mit2++];
                        if (stept < hm2.num_row()) sp += stept;
                    }
                }
                mret.m[mir++] = temp;
            }
        }
        return mret;
    }

    public HepMatrix plus(HepDiagMatrix hm2) {
        final HepMatrix mret = new HepMatrix(this);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "+");
        mret.plusAssign(hm2);
        return mret;
    }

    public HepMatrix minus(HepDiagMatrix hm2) {
        final HepMatrix mret = new HepMatrix(this);
        chkDim2(num_row(), hm2.num_row(), num_col(), hm2.num_col(), "DiagMatrix", "-");
        mret.minusAssign(hm2);
        return mret;
    }

    /** M * D (DiagMatrix.cc). */
    public HepMatrix times(HepDiagMatrix hm2) {
        final HepMatrix mret = new HepMatrix(num_row(), hm2.num_col());
        chkDim1(num_col(), hm2.num_row(), "DiagMatrix", "*");
        int mit1 = 0;
        int mir = 0;
        for (int irow = 1; irow <= num_row(); irow++) {
            int mcc = 0;
            for (int icol = 1; icol <= num_col(); icol++) mret.m[mir++] = m[mit1++] * hm2.m[mcc++];
        }
        return mret;
    }

    /** M + v: a vector (Vector.cc). */
    public HepVector plus(HepVector hm2) {
        final HepVector mret = new HepVector(hm2);
        chkDim2(num_row(), hm2.num_row(), num_col(), 1, "Vector", "+");
        mret.plusAssign(this);
        return mret;
    }

    /** M - v: a vector (Vector.cc). */
    public HepVector minus(HepVector hm2) {
        final HepVector mret = new HepVector();
        chkDim2(num_row(), hm2.num_row(), num_col(), 1, "Vector", "-");
        mret.assign(this);
        mret.minusAssign(hm2);
        return mret;
    }

    /** M * v (Vector.cc). */
    public HepVector times(HepVector hm2) {
        final HepVector mret = new HepVector(num_row());
        chkDim1(num_col(), hm2.num_row(), "Vector", "*");
        int hm1p;
        int hm2p = 0;
        int vp;
        double temp;
        int m3p = 0;
        for (hm1p = 0; hm1p < num_row() * num_col(); hm1p = hm2p) {
            temp = 0;
            vp = 0;
            hm2p = hm1p;
            while (hm2p < hm1p + num_col()) temp += m[hm2p++] * hm2.m[vp++];
            mret.m[m3p++] = temp;
        }
        return mret;
    }

    /** The block-diagonal matrix of hm1 and hm2. */
    public static HepMatrix dsum(HepMatrix hm1, HepMatrix hm2) {
        final HepMatrix mret = new HepMatrix(hm1.num_row() + hm2.num_row(), hm1.num_col() + hm2.num_col(), 0);
        mret.sub(1, 1, hm1);
        mret.sub(hm1.num_row() + 1, hm1.num_col() + 1, hm2);
        return mret;
    }

    /** CLHEP's swap: the elements exchanged, the dimensions not (as CLHEP does). */
    public static void swap(HepMatrix hm1, HepMatrix hm2) {
        final double[] t = hm1.m;
        hm1.m = hm2.m;
        hm2.m = t;
    }

    /* ---- functions ---- */

    public HepMatrix apply(ElementFunction f) {
        final HepMatrix mret = new HepMatrix(num_row(), num_col());
        int a = 0;
        int b = 0;
        for (int ir = 1; ir <= num_row(); ir++) {
            for (int ic = 1; ic <= num_col(); ic++) mret.m[b++] = f.apply(m[a++], ir, ic);
        }
        return mret;
    }

    /** The transpose. */
    public HepMatrix T() {
        final HepMatrix mret = new HepMatrix(ncol, nrow);
        int pme = 0;
        for (int nr = 0; nr < nrow; ++nr) {
            for (int nc = 0; nc < ncol; ++nc) {
                mret.m[nr + nrow * nc] = m[pme];
                ++pme;
            }
        }
        return mret;
    }

    /** The block of rows min_row..max_row and columns min_col..max_col. */
    public HepMatrix sub(int min_row, int max_row, int min_col, int max_col) {
        final HepMatrix mret = new HepMatrix(max_row - min_row + 1, max_col - min_col + 1);
        if (max_row > num_row() || max_col > num_col()) error("HepMatrix::sub: Index out of range");
        int a = 0;
        final int nc = num_col();
        int b1 = (min_row - 1) * nc + min_col - 1;
        final int rowsize = mret.num_row();
        for (int irow = 1; irow <= rowsize; ++irow) {
            int brc = b1;
            for (int icol = 0; icol < mret.num_col(); ++icol) mret.m[a++] = m[brc++];
            if (irow < rowsize) b1 += nc;
        }
        return mret;
    }

    /** hm1 copied in from (row, col). */
    public void sub(int row, int col, HepMatrix hm1) {
        if (row < 1 || row + hm1.num_row() - 1 > num_row()
            || col < 1 || col + hm1.num_col() - 1 > num_col()) {
            error("HepMatrix::sub: Index out of range");
        }
        int a = 0;
        final int nc = num_col();
        int b1 = (row - 1) * nc + col - 1;
        final int rowsize = hm1.num_row();
        for (int irow = 1; irow <= rowsize; ++irow) {
            int brc = b1;
            for (int icol = 0; icol < hm1.num_col(); ++icol) m[brc++] = hm1.m[a++];
            if (irow < rowsize) b1 += nc;
        }
    }

    public HepMatrix inverse(int[] ierr) {
        final HepMatrix mTmp = new HepMatrix(this);
        mTmp.invert(ierr);
        return mTmp;
    }

    public HepMatrix inverse() {
        final int[] ierr = new int[1];
        final HepMatrix mt = inverse(ierr);
        if (ierr[0] != 0) throw new RuntimeException("Error in HepMatrix inversion");
        return mt;
    }

    public void invert() {
        final int[] ierr = new int[1];
        invert(ierr);
        if (ierr[0] != 0) throw new RuntimeException("Error in HepMatrix inversion");
    }

    /** The inverse in place: explicit for n &lt;= 6, LU decomposition (dfact, dfinv) above; ierr 1 when singular. */
    @Override
    public void invert(int[] ierr) {
        if (ncol != nrow) error("HepMatrix::invert: Matrix is not NxN");
        final int[] ir = new int[Math.max(20, nrow) + 1];
        double t1;
        double t2;
        double t3;
        double det;
        double temp;
        double sd;
        int ifail;
        switch (nrow) {
            case 3: {
                final double c11, c12, c13, c21, c22, c23, c31, c32, c33;
                c11 = m[4] * m[8] - m[5] * m[7];
                c12 = m[5] * m[6] - m[3] * m[8];
                c13 = m[3] * m[7] - m[4] * m[6];
                c21 = m[7] * m[2] - m[8] * m[1];
                c22 = m[8] * m[0] - m[6] * m[2];
                c23 = m[6] * m[1] - m[7] * m[0];
                c31 = m[1] * m[5] - m[2] * m[4];
                c32 = m[2] * m[3] - m[0] * m[5];
                c33 = m[0] * m[4] - m[1] * m[3];
                t1 = Math.abs(m[0]);
                t2 = Math.abs(m[3]);
                t3 = Math.abs(m[6]);
                if (t1 >= t2) {
                    if (t3 >= t1) {
                        temp = m[6];
                        det = c23 * c12 - c22 * c13;
                    } else {
                        temp = m[0];
                        det = c22 * c33 - c23 * c32;
                    }
                } else if (t3 >= t2) {
                    temp = m[6];
                    det = c23 * c12 - c22 * c13;
                } else {
                    temp = m[3];
                    det = c13 * c32 - c12 * c33;
                }
                if (det == 0) {
                    ierr[0] = 1;
                    return;
                }
                final double s1 = temp / det;
                m[0] = s1 * c11;
                m[1] = s1 * c21;
                m[2] = s1 * c31;
                m[3] = s1 * c12;
                m[4] = s1 * c22;
                m[5] = s1 * c32;
                m[6] = s1 * c13;
                m[7] = s1 * c23;
                m[8] = s1 * c33;
                break;
            }
            case 2:
                det = m[0] * m[3] - m[1] * m[2];
                if (det == 0) {
                    ierr[0] = 1;
                    return;
                }
                sd = 1.0 / det;
                temp = sd * m[3];
                m[1] *= -sd;
                m[2] *= -sd;
                m[3] = sd * m[0];
                m[0] = temp;
                break;
            case 1:
                if (m[0] == 0) {
                    ierr[0] = 1;
                    return;
                }
                m[0] = 1.0 / m[0];
                break;
            case 4:
                invertHaywood4(ierr);
                return;
            case 5:
                invertHaywood5(ierr);
                return;
            case 6:
                invertHaywood6(ierr);
                return;
            default: {
                final double[] d = new double[1];
                ifail = dfact_matrix(d, ir);
                if (ifail != 0) {
                    ierr[0] = 1;
                    return;
                }
                dfinv_matrix(ir);
                break;
            }
        }
        ierr[0] = 0;
    }

    protected void invertHaywood4(int[] ifail) {
        MatrixInvert.invertHaywood4(this, ifail);
    }

    protected void invertHaywood5(int[] ifail) {
        MatrixInvert.invertHaywood5(this, ifail);
    }

    protected void invertHaywood6(int[] ifail) {
        MatrixInvert.invertHaywood6(this, ifail);
    }

    /** The determinant, by the LU decomposition of a copy (0 when it fails). */
    public double determinant() {
        if (ncol != nrow) error("HepMatrix::determinant: Matrix is not NxN");
        final int[] ir = new int[Math.max(20, nrow) + 1];
        final double[] det = new double[1];
        final HepMatrix mt = new HepMatrix(this);
        final int i = mt.dfact_matrix(det, ir);
        if (i == 0) return det[0];
        return 0;
    }

    /** The sum of the diagonal (stepping by ncol+1 through the elements, as CLHEP). */
    public double trace() {
        double t = 0.0;
        for (int d = 0; d < m.length; d += (ncol + 1)) t += m[d];
        return t;
    }

    /**
     * CERNLIB's DFACT on this matrix: the LU decomposition in place, ir the row exchanges (ir[n] their
     * number); det[0] the determinant. As in CLHEP, the pivot is searched among the rows j+1..n-1.
     */
    int dfact_matrix(double[] detOut, int[] ir) {
        if (ncol != nrow) error("dfact_matrix: Matrix is not NxN");
        int ifail;
        int jfail;
        final int n = ncol;
        double tf;
        final double g1 = 1.0e-19;
        final double g2 = 1.0e19;
        double p;
        double q;
        double t;
        double s11;
        double s12;
        final double epsilon = 8 * Math.ulp(1.0);
        final int normal = 0;
        final int imposs = -1;
        final int jrange = 0;
        final int jover = 1;
        final int junder = -1;
        ifail = normal;
        jfail = jrange;
        int nxch = 0;
        double det = 1.0;
        int mj = 0;
        int mjj = mj;
        for (int j = 1; j <= n; j++) {
            int k = j;
            p = Math.abs(m[mjj]);
            if (j != n) {
                for (int i = j + 1; i < n; i++) {
                    q = Math.abs(m[mj + n * (i - j) + j - 1]);
                    if (q > p) {
                        k = i;
                        p = q;
                    }
                }
                if (k == j) {
                    if (p <= epsilon) {
                        det = 0;
                        ifail = imposs;
                        jfail = jrange;
                        detOut[0] = det;
                        return ifail;
                    }
                    det = -det;
                }
                int mjl = mj;
                int mkl = (k - 1) * n;
                for (int l = 1; l <= n; l++) {
                    tf = m[mjl];
                    m[mjl++] = m[mkl];
                    m[mkl++] = tf;
                }
                nxch = nxch + 1;
                ir[nxch] = ((j) << 12) + (k);
            } else {
                if (p <= epsilon) {
                    det = 0.0;
                    ifail = imposs;
                    jfail = jrange;
                    detOut[0] = det;
                    return ifail;
                }
            }
            det *= m[mjj];
            m[mjj] = 1.0 / m[mjj];
            t = Math.abs(det);
            if (t < g1) {
                det = 0.0;
                if (jfail == jrange) jfail = junder;
            } else if (t > g2) {
                det = 1.0;
                if (jfail == jrange) jfail = jover;
            }
            if (j != n) {
                int mjk = mj + j;
                for (k = j + 1; k <= n; k++) {
                    final int mk = mj + n * (k - j);
                    final int mkjp = mk + j;
                    s11 = -m[mjk];
                    s12 = -m[mkjp];
                    if (j != 1) {
                        int mik = k - 1;
                        int mijp = j;
                        int mki = mk;
                        int mji = mj;
                        for (int i = 1; i < j; i++) {
                            s11 += m[mik] * m[mji++];
                            s12 += m[mijp] * m[mki++];
                            mik += n;
                            mijp += n;
                        }
                    }
                    m[mjk++] = -s11 * m[mjj];
                    m[mkjp] = -((m[mjj + 1]) * (m[mkjp - 1]) + (s12));
                }
            }
            if (j != n) {
                mj += n;
                mjj += (n + 1);
            }
        }
        if (nxch % 2 == 1) det = -det;
        if (jfail != jrange) det = 0.0;
        ir[n] = nxch;
        detOut[0] = det;
        return 0;
    }

    /** CERNLIB's DFINV: the inverse from dfact's decomposition. */
    int dfinv_matrix(int[] ir) {
        if (num_col() != num_row()) error("dfinv_matrix: Matrix is not NxN");
        final int n = num_col();
        if (n == 1) return 0;

        double s31;
        double s32;
        double s33;
        double s34;

        final int hm11 = 0;
        final int hm12 = hm11 + 1;
        final int hm21 = hm11 + n;
        final int hm22 = hm12 + n;
        m[hm21] = -m[hm22] * m[hm11] * m[hm21];
        m[hm12] = -m[hm12];
        if (n > 2) {
            int mimim = hm11 + n + 1;
            for (int i = 3; i <= n; i++) {
                final int mi = hm11 + (i - 1) * n;
                final int mii = hm11 + (i - 1) * n + i - 1;
                final int ihm2 = i - 2;
                int mj = hm11;
                int mji = mj + i - 1;
                int mij = mi;
                for (int j = 1; j <= ihm2; j++) {
                    s31 = 0.0;
                    s32 = m[mji];
                    int mkj = mj + j - 1;
                    int mik = mi + j - 1;
                    int mjkp = mj + j;
                    int mkpi = mj + n + i - 1;
                    for (int k = j; k <= ihm2; k++) {
                        s31 += m[mkj] * m[mik++];
                        s32 += m[mjkp++] * m[mkpi];
                        mkj += n;
                        mkpi += n;
                    }
                    m[mij] = -m[mii] * ((m[mij - n]) * (m[mii - 1]) + (s31));
                    m[mji] = -s32;
                    mj += n;
                    mji += n;
                    mij++;
                }
                m[mii - 1] = -m[mii] * m[mimim] * m[mii - 1];
                m[mimim + 1] = -m[mimim + 1];
                mimim += (n + 1);
            }
        }
        int mi = hm11;
        int mii = hm11;
        for (int i = 1; i < n; i++) {
            final int ni = n - i;
            int mij = mi;
            int j;
            for (j = 1; j <= i; j++) {
                s33 = m[mij];
                int mikj = mi + j - 1;
                int miik = mii + 1;
                final int min_end = mi + n;
                for (; miik < min_end; ) {
                    mikj += n;
                    s33 += m[mikj] * m[miik++];
                }
                m[mij++] = s33;
            }
            for (j = 1; j <= ni; j++) {
                s34 = 0.0;
                int miik = mii + j;
                for (int k = j; k <= ni; k++) {
                    final int mikij = mii + k * n + j;
                    s34 += m[mikij] * m[miik++];
                }
                m[mii + j] = s34;
            }
            mi += n;
            mii += (n + 1);
        }
        final int nxch = ir[n];
        if (nxch == 0) return 0;
        for (int hmm = 1; hmm <= nxch; hmm++) {
            int k = nxch - hmm + 1;
            final int ij = ir[k];
            final int i = ij >> 12;
            final int j = ij % 4096;
            for (k = 1; k <= n; k++) {
                final int mki = hm11 + (k - 1) * n + i - 1;
                final int mkj = hm11 + (k - 1) * n + j - 1;
                final double ti = m[mki];
                m[mki] = m[mkj];
                m[mkj] = ti;
            }
        }
        return 0;
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
