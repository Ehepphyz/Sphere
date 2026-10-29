package com.sphere.core.clhep.matrix;

/**
 * CLHEP's free matrix functions (Matrix/src/MatrixLinear.cc; Matrix.h, SymMatrix.h): Householder and
 * Givens transformations, the QR decomposition and the solutions and inverse it gives, the
 * tridiagonalization and diagonalization of symmetric matrices, the condition number, the closest point
 * of lines.
 *
 * <p>The functions that take a pointer in C++ change their argument here too; qr_solve, qr_inverse and
 * condition of a matrix work on a copy, as CLHEP's const forms do (the InPlace forms are its pointer ones).
 */
public final class MatrixLinear {

    private MatrixLinear() {
    }

    private static int sign(double x) {
        return (x > 0 ? 1 : -1);
    }

    /** R x = b for an upper-triangular R, b replaced by x. */
    public static void back_solve(HepMatrix R, HepVector b) {
        b.set(b.num_row(), b.get(b.num_row()) / R.get(b.num_row(), b.num_row()));
        final int n = R.num_col();
        final int nb = b.num_row();
        int br = b.num_row() - 2;
        int Rrr = (nb - 2) * (n + 1);
        for (int r = b.num_row() - 1; r >= 1; --r) {
            int bc = br + 1;
            int Rrc = Rrr + 1;
            for (int c = r + 1; c <= b.num_row(); c++) b.m[br] -= R.m[Rrc++] * b.m[bc++];
            b.m[br] /= R.m[Rrr];
            if (r > 1) {
                br--;
                Rrr -= n + 1;
            }
        }
    }

    /** R X = B for an upper-triangular R, column after column, B replaced by X. */
    public static void back_solve(HepMatrix R, HepMatrix b) {
        final int n = R.num_col();
        final int nb = b.num_row();
        final int nc = b.num_col();
        int bbi = (nb - 2) * nc;
        for (int i = 1; i <= b.num_col(); i++) {
            b.set(b.num_row(), i, b.get(b.num_row(), i) / R.get(b.num_row(), b.num_row()));
            int Rrr = (nb - 2) * (n + 1);
            int bri = bbi;
            for (int r = b.num_row() - 1; r >= 1; --r) {
                int bci = bri + nc;
                int Rrc = Rrr + 1;
                for (int c = r + 1; c <= b.num_row(); c++) {
                    b.m[bri] -= R.m[Rrc++] * b.m[bci];
                    if (c < b.num_row()) bci += nc;
                }
                b.m[bri] /= R.m[Rrr];
                if (r > 1) {
                    Rrr -= (n + 1);
                    bri -= nc;
                }
            }
            bbi++;
        }
    }

    public static void col_givens(HepMatrix A, double c, double ds, int k1, int k2) {
        col_givens(A, c, ds, k1, k2, 1, 0);
    }

    /** The Givens rotation (c, s) of columns k1 and k2, rows row_min..row_max (0: to the last). */
    public static void col_givens(HepMatrix A, double c, double ds, int k1, int k2, int row_min, int row_max) {
        if (row_max <= 0) row_max = A.num_row();
        final int n = A.num_col();
        int Ajk1 = (row_min - 1) * n + k1 - 1;
        int Ajk2 = (row_min - 1) * n + k2 - 1;
        for (int j = row_min; j <= row_max; j++) {
            final double tau1 = A.m[Ajk1];
            final double tau2 = A.m[Ajk2];
            A.m[Ajk1] = c * tau1 - ds * tau2;
            A.m[Ajk2] = ds * tau1 + c * tau2;
            if (j < row_max) {
                Ajk1 += n;
                Ajk2 += n;
            }
        }
    }

    /** The Householder reflection of a's columns, its vector a column of v. */
    public static void col_house(HepMatrix a, HepMatrix v, double vnormsq, int row, int col, int row_start,
                                 int col_start) {
        final double beta = -2 / vnormsq;
        final HepVector w = new HepVector(a.num_col() - col + 1, 0);
        int wptr = 0;
        final int n = a.num_col();
        final int nv = v.num_col();
        int acrb = (col - 1) * n + (row - 1);
        int c;
        for (c = col; c <= a.num_col(); c++) {
            int vp = (row_start - 1) * nv + (col_start - 1);
            int acr = acrb;
            for (int r = row; r <= a.num_row(); r++) {
                w.m[wptr] += a.m[acr++] * v.m[vp];
                vp += nv;
            }
            wptr++;
            if (c < a.num_col()) acrb += n;
        }
        w.timesAssign(beta);
        int arcb = (row - 1) * n + col - 1;
        wptr = 0;
        for (int r = row; r <= a.num_row(); r++) {
            int arc = arcb;
            int vp = (row_start - 1) * nv + col_start;
            for (c = col; c <= a.num_col(); c++) {
                a.m[arc++] += v.m[vp] * w.m[wptr];
                vp += nv;
            }
            wptr++;
            if (r < a.num_row()) arcb += n;
        }
    }

    public static void col_house(HepMatrix a, HepMatrix v, int row, int col, int row_start, int col_start) {
        double normsq = 0;
        for (int i = row_start; i <= row_start + a.num_row() - row; i++) normsq += v.get(i, col) * v.get(i, col);
        col_house(a, v, normsq, row, col, row_start, col_start);
    }

    /** The ratio of the largest to the smallest absolute eigenvalue. */
    public static double condition(HepSymMatrix hm) {
        final HepSymMatrix mcopy = new HepSymMatrix(hm);
        diagonalize(mcopy);
        double max;
        double min;
        max = min = Math.abs(mcopy.get(1, 1));
        final int n = mcopy.num_row();
        int mii = 2;
        for (int i = 2; i <= n; i++) {
            if (max < Math.abs(mcopy.m[mii])) max = Math.abs(mcopy.m[mii]);
            if (min > Math.abs(mcopy.m[mii])) min = Math.abs(mcopy.m[mii]);
            if (i < n) mii += i + 1;
        }
        return max / min;
    }

    /**
     * One implicit QR step on the tridiagonal t, rows begin..end. As CLHEP writes it, aq is the value
     * (not the element) after tkp1k plus one.
     */
    public static void diag_step(HepSymMatrix t, int begin, int end) {
        double d = (t.fast(end - 1, end - 1) - t.fast(end, end)) / 2;
        final double mu = t.fast(end, end) - t.fast(end, end - 1) * t.fast(end, end - 1)
            / (d + sign(d) * Math.sqrt(d * d + t.fast(end, end - 1) * t.fast(end, end - 1)));
        double x = t.fast(begin, begin) - mu;
        double z = t.fast(begin + 1, begin);
        final double[] tm = t.m;
        int tkk = (begin + 2) * (begin - 1) / 2;
        int tkp1k = tkk + begin;
        int tkp2k = tkk + 2 * begin + 1;
        for (int k = begin; k <= end - 1; k++) {
            final double[] cs = givens(x, z);
            final double c = cs[0];
            final double ds = cs[1];
            if (k != begin) {
                tm[tkk - 1] = tm[tkk - 1] * c - tm[tkp1k - 1] * ds;
                tm[tkp1k - 1] = 0;
            }
            final double ap = tm[tkk];
            final double bp = tm[tkp1k];
            final double aq = tm[tkp1k] + 1;
            tm[tkk] = ap * c * c - 2 * c * bp * ds + aq * ds * ds;
            tm[tkp1k] = c * ap * ds + bp * c * c - bp * ds * ds - ds * aq * c;
            tm[tkp1k + 1] = ap * ds * ds + 2 * c * bp * ds + aq * c * c;
            if (k < end - 1) {
                final double bq = tm[tkp2k + 1];
                tm[tkp2k] = -bq * ds;
                tm[tkp2k + 1] = bq * c;
                x = tm[tkp1k];
                z = tm[tkp2k];
                tkk += k + 1;
                tkp1k += k + 2;
            }
            if (k < end - 2) tkp2k += k + 3;
        }
    }

    /** One implicit QR step, the rotations accumulated in u. */
    public static void diag_step(HepSymMatrix t, HepMatrix u, int begin, int end) {
        final double d = (t.fast(end - 1, end - 1) - t.fast(end, end)) / 2;
        final double mu = t.fast(end, end) - t.fast(end, end - 1) * t.fast(end, end - 1)
            / (d + sign(d) * Math.sqrt(d * d + t.fast(end, end - 1) * t.fast(end, end - 1)));
        double x = t.fast(begin, begin) - mu;
        double z = t.fast(begin + 1, begin);
        final double[] tm = t.m;
        int tkk = (begin + 2) * (begin - 1) / 2;
        int tkp1k = tkk + begin;
        int tkp2k = tkk + 2 * begin + 1;
        for (int k = begin; k <= end - 1; k++) {
            final double[] cs = givens(x, z);
            final double c = cs[0];
            final double ds = cs[1];
            col_givens(u, c, ds, k, k + 1);
            if (k != begin) {
                tm[tkk - 1] = tm[tkk - 1] * c - tm[tkp1k - 1] * ds;
                tm[tkp1k - 1] = 0;
            }
            final double ap = tm[tkk];
            final double bp = tm[tkp1k];
            final double aq = tm[tkp1k + 1];
            tm[tkk] = ap * c * c - 2 * c * bp * ds + aq * ds * ds;
            tm[tkp1k] = c * ap * ds + bp * c * c - bp * ds * ds - ds * aq * c;
            tm[tkp1k + 1] = ap * ds * ds + 2 * c * bp * ds + aq * c * c;
            if (k < end - 1) {
                final double bq = tm[tkp2k + 1];
                tm[tkp2k] = -bq * ds;
                tm[tkp2k + 1] = bq * c;
                x = tm[tkp1k];
                z = tm[tkp2k];
                tkk += k + 1;
                tkp1k += k + 2;
            }
            if (k < end - 2) tkp2k += k + 3;
        }
    }

    /** The eigenvalues on hms's diagonal (hms changed); the eigenvectors, the returned matrix's columns. */
    public static HepMatrix diagonalize(HepSymMatrix hms) {
        final double tolerance = 1e-12;
        final HepMatrix u = tridiagonal(hms);
        int begin = 1;
        int end = hms.num_row();
        while (begin != end) {
            int sii = (begin + 2) * (begin - 1) / 2;
            int sip1i = sii + begin;
            for (int i = begin; i <= end - 1; i++) {
                if (Math.abs(hms.m[sip1i]) <= tolerance * (Math.abs(hms.m[sii]) + Math.abs(hms.m[sip1i + 1]))) {
                    hms.m[sip1i] = 0;
                }
                if (i < end - 1) {
                    sii += i + 1;
                    sip1i += i + 2;
                }
            }
            while (begin < end && hms.fast(begin + 1, begin) == 0) begin++;
            while (end > begin && hms.fast(end, end - 1) == 0) end--;
            if (begin != end) diag_step(hms, u, begin, end);
        }
        return u;
    }

    public static HepVector house(HepSymMatrix a) {
        return house(a, 1, 1);
    }

    /** The Householder vector of a's column col from row row. */
    public static HepVector house(HepSymMatrix a, int row, int col) {
        final HepVector v = new HepVector(a.num_row() - row + 1);
        int vp = 0;
        int aci = col * (col - 1) / 2 + row - 1;
        int i;
        for (i = row; i <= col; i++) v.m[vp++] = a.m[aci++];
        for (; i <= a.num_row(); i++) {
            v.m[vp++] = a.m[aci];
            aci += i;
        }
        v.set(1, v.get(1) + sign(a.get(row, col)) * v.norm());
        return v;
    }

    public static HepVector house(HepMatrix a) {
        return house(a, 1, 1);
    }

    public static HepVector house(HepMatrix a, int row, int col) {
        final HepVector v = new HepVector(a.num_row() - row + 1);
        final int n = a.num_col();
        int aic = (row - 1) * n + (col - 1);
        int vp = 0;
        for (int i = row; i <= a.num_row(); i++) {
            v.m[vp++] = a.m[aic];
            aic += n;
        }
        v.set(1, v.get(1) + sign(a.get(row, col)) * v.norm());
        return v;
    }

    public static void house_with_update(HepMatrix a) {
        house_with_update(a, 1, 1);
    }

    /** The Householder reflection that zeros a's column col below row, applied to a. */
    public static void house_with_update(HepMatrix a, int row, int col) {
        final HepVector v = new HepVector(a.num_row() - row + 1);
        int vp = 0;
        final int n = a.num_col();
        int arc = (row - 1) * n + (col - 1);
        int r;
        for (r = row; r <= a.num_row(); r++) {
            v.m[vp++] = a.m[arc];
            if (r < a.num_row()) arc += n;
        }
        double normsq = v.normsq();
        final double norm = Math.sqrt(normsq);
        normsq -= v.get(1) * v.get(1);
        v.set(1, v.get(1) + sign(a.get(row, col)) * norm);
        normsq += v.get(1) * v.get(1);
        a.set(row, col, -sign(a.get(row, col)) * norm);
        if (row < a.num_row()) {
            arc = row * n + (col - 1);
            for (r = row + 1; r <= a.num_row(); r++) {
                a.m[arc] = 0;
                if (r < a.num_row()) arc += n;
            }
            row_house(a, v, normsq, row, col + 1);
        }
    }

    public static void house_with_update(HepMatrix a, HepMatrix v) {
        house_with_update(a, v, 1, 1);
    }

    /** The same, the Householder vector kept in v's column col. */
    public static void house_with_update(HepMatrix a, HepMatrix v, int row, int col) {
        double normsq = 0;
        final int nv = v.num_col();
        final int na = a.num_col();
        int vrc = (row - 1) * nv + (col - 1);
        int arc = (row - 1) * na + (col - 1);
        int r;
        for (r = row; r <= a.num_row(); r++) {
            v.m[vrc] = a.m[arc];
            normsq += v.m[vrc] * v.m[vrc];
            if (r < a.num_row()) {
                vrc += nv;
                arc += na;
            }
        }
        final double norm = Math.sqrt(normsq);
        vrc = (row - 1) * nv + (col - 1);
        normsq -= v.m[vrc] * v.m[vrc];
        v.m[vrc] += sign(a.get(row, col)) * norm;
        normsq += v.m[vrc] * v.m[vrc];
        a.set(row, col, -sign(a.get(row, col)) * norm);
        if (row < a.num_row()) {
            arc = row * na + (col - 1);
            for (r = row + 1; r <= a.num_row(); r++) {
                a.m[arc] = 0;
                if (r < a.num_row()) arc += na;
            }
            row_house(a, v, normsq, row, col + 1, row, col);
        }
    }

    public static void house_with_update2(HepSymMatrix a, HepMatrix v) {
        house_with_update2(a, v, 1, 1);
    }

    /** The Householder vector of a symmetric matrix's column into v; the column zeroed below row. */
    public static void house_with_update2(HepSymMatrix a, HepMatrix v, int row, int col) {
        double normsq = 0;
        final int nv = v.num_col();
        int vrc = (row - 1) * nv + (col - 1);
        int arc = (row - 1) * row / 2 + (col - 1);
        int r;
        for (r = row; r <= a.num_row(); r++) {
            v.m[vrc] = a.m[arc];
            normsq += v.m[vrc] * v.m[vrc];
            if (r < a.num_row()) {
                arc += r;
                vrc += nv;
            }
        }
        final double norm = Math.sqrt(normsq);
        vrc = (row - 1) * nv + (col - 1);
        arc = (row - 1) * row / 2 + (col - 1);
        v.m[vrc] += sign(a.m[arc]) * norm;
        a.m[arc] = -sign(a.m[arc]) * norm;
        arc += row;
        for (r = row + 1; r <= a.num_row(); r++) {
            a.m[arc] = 0;
            if (r < a.num_row()) arc += r;
        }
    }

    /** The inverse by QR, of a copy of A. */
    public static HepMatrix qr_inverse(HepMatrix A) {
        final HepMatrix Atemp = new HepMatrix(A);
        return qr_inverseInPlace(Atemp);
    }

    /** The inverse by QR; A becomes R. */
    public static HepMatrix qr_inverseInPlace(HepMatrix A) {
        if (A.num_row() != A.num_col()) HepGenMatrix.error("qr_inverse: The matrix is not square.");
        final HepMatrix QT = qr_decomp(A).T();
        back_solve(A, QT);
        return QT;
    }

    /** The point closest to the n lines B[i] + t A[i] (least squares). */
    public static HepVector min_line_dist(HepVector[] A, HepVector[] B, int n) {
        final double small = 1e-10;
        final HepSymMatrix C = new HepSymMatrix(3, 0);
        final HepSymMatrix I = new HepSymMatrix(3, 1);
        final HepVector D = new HepVector(3, 0);
        double t;
        for (int i = 0; i < n; i++) {
            if (Math.abs(t = A[i].normsq()) < small) {
                C.plusAssign(I);
                D.plusAssign(B[i]);
            } else {
                C.plusAssign(HepSymMatrix.vT_times_v(A[i]).times(1 - 2 / t).plus(I));
                D.plusAssign(HepVector.times(HepVector.dot(A[i], B[i]) * (1 - 2 / t), A[i]).plus(B[i]));
            }
        }
        return qr_solve(new HepMatrix(C), D);
    }

    /** Q of A = QR, A becoming R. */
    public static HepMatrix qr_decomp(HepMatrix A) {
        final HepMatrix hsm = new HepMatrix(A.num_row(), A.num_col());
        qr_decomp(A, hsm);
        final HepMatrix Q = new HepMatrix(A.num_row(), A.num_row(), 1);
        for (int j = hsm.num_col(); j >= 1; --j) row_house(Q, hsm, j, j, j, j);
        return Q;
    }

    /** The Householder reflections of the QR decomposition: A becomes R, their vectors into hsm. */
    public static void qr_decomp(HepMatrix A, HepMatrix hsm) {
        for (int i = 1; i <= A.num_col(); i++) house_with_update(A, hsm, i, i);
    }

    public static void row_givens(HepMatrix A, double c, double ds, int k1, int k2) {
        row_givens(A, c, ds, k1, k2, 1, 0);
    }

    /** The Givens rotation (c, s) of rows k1 and k2, columns col_min..col_max (0: to the last). */
    public static void row_givens(HepMatrix A, double c, double ds, int k1, int k2, int col_min, int col_max) {
        if (col_max == 0) col_max = A.num_col();
        final int n = A.num_col();
        int Ak1j = (k1 - 1) * n + (col_min - 1);
        int Ak2j = (k2 - 1) * n + (col_min - 1);
        for (int j = col_min; j <= col_max; j++) {
            final double tau1 = A.m[Ak1j];
            final double tau2 = A.m[Ak2j];
            A.m[Ak1j++] = c * tau1 - ds * tau2;
            A.m[Ak2j++] = ds * tau1 + c * tau2;
        }
    }

    public static void row_house(HepMatrix a, HepVector v, double vnormsq) {
        row_house(a, v, vnormsq, 1, 1);
    }

    /** The Householder reflection of a's rows from row, columns from col, by v. */
    public static void row_house(HepMatrix a, HepVector v, double vnormsq, int row, int col) {
        final double beta = -2 / vnormsq;
        final HepVector w = new HepVector(a.num_col() - col + 1, 0);
        final int na = a.num_col();
        int wptr = 0;
        int arcb = (row - 1) * na + (col - 1);
        int c;
        for (c = col; c <= a.num_col(); c++) {
            int vp = 0;
            int arc = arcb;
            for (int r = row; r <= a.num_row(); r++) {
                w.m[wptr] += a.m[arc] * v.m[vp++];
                if (r < a.num_row()) arc += na;
            }
            wptr++;
            arcb++;
        }
        w.timesAssign(beta);
        arcb = (row - 1) * na + (col - 1);
        int vp = 0;
        for (int r = row; r <= a.num_row(); r++) {
            int wptr2 = 0;
            int arc = arcb;
            for (c = col; c <= a.num_col(); c++) a.m[arc++] += v.m[vp] * w.m[wptr2++];
            vp++;
            if (r < a.num_row()) arcb += na;
        }
    }

    /** The same, its vector a column of v from (row_start, col_start). */
    public static void row_house(HepMatrix a, HepMatrix v, double vnormsq, int row, int col, int row_start,
                                 int col_start) {
        final double beta = -2 / vnormsq;
        final HepVector w = new HepVector(a.num_col() - col + 1, 0);
        final int na = a.num_col();
        final int nv = v.num_col();
        int wptr = 0;
        int arcb = (row - 1) * na + (col - 1);
        final int vpcb = (row_start - 1) * nv + (col_start - 1);
        int c;
        for (c = col; c <= a.num_col(); c++) {
            int arc = arcb;
            int vpc = vpcb;
            for (int r = row; r <= a.num_row(); r++) {
                w.m[wptr] += a.m[arc] * v.m[vpc];
                if (r < a.num_row()) {
                    arc += na;
                    vpc += nv;
                }
            }
            wptr++;
            arcb++;
        }
        w.timesAssign(beta);
        arcb = (row - 1) * na + (col - 1);
        int vpc = (row_start - 1) * nv + (col_start - 1);
        for (int r = row; r <= a.num_row(); r++) {
            int arc = arcb;
            int wptr2 = 0;
            for (c = col; c <= a.num_col(); c++) a.m[arc++] += v.m[vpc] * w.m[wptr2++];
            if (r < a.num_row()) {
                arcb += na;
                vpc += nv;
            }
        }
    }

    /** The same, the vector's norm computed (from column col of v, as CLHEP does); nothing for a zero vector. */
    public static void row_house(HepMatrix a, HepMatrix v, int row, int col, int row_start, int col_start) {
        double normsq = 0;
        final int end = row_start + a.num_row() - row;
        for (int i = row_start; i <= end; i++) normsq += v.get(i, col) * v.get(i, col);
        if (normsq != 0) row_house(a, v, normsq, row, col, row_start, col_start);
    }

    /** x of A x = b by QR, on a copy of A. */
    public static HepVector qr_solve(HepMatrix A, HepVector b) {
        final HepMatrix temp = new HepMatrix(A);
        return qr_solveInPlace(temp, b);
    }

    /** x of A x = b by QR; A becomes R. */
    public static HepVector qr_solveInPlace(HepMatrix A, HepVector b) {
        final HepMatrix Q = qr_decomp(A);
        final HepVector b2 = new HepVector(Q.num_col(), 0);
        int b2r = 0;
        int Qr = 0;
        final int n = Q.num_col();
        for (int r = 1; r <= b2.num_row(); r++) {
            int bc = 0;
            int Qcr = Qr;
            for (int c = 1; c <= b.num_row(); c++) {
                b2.m[b2r] += Q.m[Qcr] * b.m[bc++];
                if (c < b.num_row()) Qcr += n;
            }
            b2r++;
            Qr++;
        }
        back_solve(A, b2);
        return b2;
    }

    /** X of A X = B by QR, on a copy of A. */
    public static HepMatrix qr_solve(HepMatrix A, HepMatrix b) {
        final HepMatrix temp = new HepMatrix(A);
        return qr_solveInPlace(temp, b);
    }

    public static HepMatrix qr_solveInPlace(HepMatrix A, HepMatrix b) {
        final HepMatrix Q = qr_decomp(A);
        final HepMatrix b2 = new HepMatrix(Q.num_col(), b.num_col(), 0);
        final int nb = b.num_col();
        final int nq = Q.num_col();
        int b1i = 0;
        int b21i = 0;
        for (int i = 1; i <= b.num_col(); i++) {
            int Q1r = 0;
            int b2ri = b21i;
            for (int r = 1; r <= b2.num_row(); r++) {
                int Qcr = Q1r;
                int bci = b1i;
                for (int c = 1; c <= b.num_row(); c++) {
                    b2.m[b2ri] += Q.m[Qcr] * b.m[bci];
                    if (c < b.num_row()) {
                        Qcr += nq;
                        bci += nb;
                    }
                }
                Q1r++;
                if (r < b2.num_row()) b2ri += nb;
            }
            b1i++;
            b21i++;
        }
        back_solve(A, b2);
        return b2;
    }

    /** a made tridiagonal by Householder reflections, their vectors into hsm's columns. */
    public static void tridiagonal(HepSymMatrix a, HepMatrix hsm) {
        final int nh = hsm.num_col();
        for (int k = 1; k <= a.num_col() - 2; k++) {
            double scale = 0;
            int ajk = k * (k + 5) / 2;
            int j;
            for (j = k + 2; j <= a.num_row(); j++) {
                scale += Math.abs(a.m[ajk]);
                if (j < a.num_row()) ajk += j;
            }
            if (scale == 0) {
                int hsmjkp = k * (nh + 1) - 1;
                for (j = k + 1; j <= hsm.num_row(); j++) {
                    hsm.m[hsmjkp] = 0;
                    if (j < hsm.num_row()) hsmjkp += nh;
                }
            } else {
                house_with_update2(a, hsm, k + 1, k);
                double normsq = 0;
                int hsmrptrkp = k * (nh + 1) - 1;
                int rptr;
                for (rptr = k + 1; rptr <= hsm.num_row(); rptr++) {
                    normsq += hsm.m[hsmrptrkp] * hsm.m[hsmrptrkp];
                    if (rptr < hsm.num_row()) hsmrptrkp += nh;
                }
                final HepVector p = new HepVector(a.num_row() - k, 0);
                rptr = k + 1;
                int pr = 0;
                int r;
                for (r = 1; r <= p.num_row(); r++) {
                    int hsmcptrkp = k * (nh + 1) - 1;
                    int cptr;
                    for (cptr = k + 1; cptr <= rptr; cptr++) {
                        p.m[pr] += a.fast(rptr, cptr) * hsm.m[hsmcptrkp];
                        if (cptr < a.num_col()) hsmcptrkp += nh;
                    }
                    for (; cptr <= a.num_col(); cptr++) {
                        p.m[pr] += a.fast(cptr, rptr) * hsm.m[hsmcptrkp];
                        if (cptr < a.num_col()) hsmcptrkp += nh;
                    }
                    p.m[pr] *= 2 / normsq;
                    rptr++;
                    pr++;
                }
                double pdotv = 0;
                pr = 0;
                hsmrptrkp = k * (nh + 1) - 1;
                for (r = 1; r <= p.num_row(); r++) {
                    pdotv += p.m[pr++] * hsm.m[hsmrptrkp];
                    if (r < p.num_row()) hsmrptrkp += nh;
                }
                pr = 0;
                hsmrptrkp = k * (nh + 1) - 1;
                for (r = 1; r <= p.num_row(); r++) {
                    p.m[pr++] -= pdotv * hsm.m[hsmrptrkp] / normsq;
                    if (r < p.num_row()) hsmrptrkp += nh;
                }
                rptr = k + 1;
                pr = 0;
                hsmrptrkp = k * (nh + 1) - 1;
                for (r = 1; r <= p.num_row(); r++) {
                    int cptr = k + 1;
                    int mpc = 0;
                    int hsmcptrkp = k * (nh + 1) - 1;
                    for (int c = 1; c <= r; c++) {
                        a.setFast(rptr, cptr, a.fast(rptr, cptr)
                            - (hsm.m[hsmrptrkp] * p.m[mpc++] + p.m[pr] * hsm.m[hsmcptrkp]));
                        cptr++;
                        if (c < r) hsmcptrkp += nh;
                    }
                    pr++;
                    rptr++;
                    if (r < p.num_row()) hsmrptrkp += nh;
                }
            }
        }
    }

    /** a made tridiagonal; the orthogonal matrix of the transformation returned. */
    public static HepMatrix tridiagonal(HepSymMatrix a) {
        final HepMatrix U = new HepMatrix(a.num_row(), a.num_col(), 1);
        if (a.num_col() > 2) {
            final HepMatrix hsm = new HepMatrix(a.num_col(), a.num_col() - 2, 0);
            tridiagonal(a, hsm);
            for (int j = hsm.num_col(); j >= 1; --j) row_house(U, hsm, j, j, j, j);
        }
        return U;
    }

    /** The Givens rotation that zeros b in (a, b): {c, s}. */
    public static double[] givens(double a, double b) {
        double c;
        double ds;
        if (b == 0) {
            c = 1;
            ds = 0;
        } else {
            if (Math.abs(b) > Math.abs(a)) {
                final double tau = -a / b;
                ds = 1 / Math.sqrt(1 + tau * tau);
                c = ds * tau;
            } else {
                final double tau = -b / a;
                c = 1 / Math.sqrt(1 + tau * tau);
                ds = c * tau;
            }
        }
        return new double[] {c, ds};
    }
}
