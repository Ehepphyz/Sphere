package com.sphere.core.clhep.matrix;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's HepGenMatrix (Matrix/GenMatrix.h, .icc, GenMatrix.cc): what HepMatrix, HepSymMatrix,
 * HepDiagMatrix and HepVector share — their dimensions, their elements by (row, col) from 1 or [row][col]
 * from 0, the norms, the error that ends the program.
 *
 * <p>As CLHEP is built (MATRIX_BOUND_CHECK undefined), the elements are not range-checked: an index
 * beyond the matrix reads the element its offset falls on, or fails at the end of the array.
 */
public abstract class HepGenMatrix {

    /** What CLHEP's error() aborts with: here the message, printed as it is, then thrown. */
    public static final class MatrixError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MatrixError(String message) {
            super(message);
        }
    }

    public abstract int num_row();

    public abstract int num_col();

    /** operator()(row, col) const: from 1. */
    public abstract double get(int row, int col);

    /** operator()(row, col) = value. */
    public abstract void set(int row, int col, double value);

    public abstract void invert(int[] ierr);

    protected abstract int num_size();

    /** operator[](r): a row, whose get(c) and set(c, v) are the element (r+1, c+1). */
    public static final class HepGenMatrix_row {
        private final HepGenMatrix a;
        private final int r;

        HepGenMatrix_row(HepGenMatrix a, int r) {
            this.a = a;
            this.r = r;
        }

        public double get(int c) {
            return a.get(r + 1, c + 1);
        }

        public void set(int c, double v) {
            a.set(r + 1, c + 1, v);
        }
    }

    public HepGenMatrix_row row(int r) {
        return new HepGenMatrix_row(this, r);
    }

    /** operator==: the same dimensions and elements. */
    public boolean isEqual(HepGenMatrix o) {
        if (o.num_row() != num_row() || o.num_col() != num_col()) return false;
        for (int k1 = 1; k1 <= num_row(); k1++) {
            for (int k2 = 1; k2 <= num_col(); k2++) {
                if (o.get(k1, k2) != get(k1, k2)) return false;
            }
        }
        return true;
    }

    /** What CLHEP prints before it aborts; then a MatrixError. */
    public static void error(String es) {
        StdStreams.cerr().print(es + "\n");
        StdStreams.cerr().print("---Exiting to System.\n");
        throw new MatrixError(es);
    }

    /** The largest absolute row sum. */
    public static double norm_infinity(HepGenMatrix hm) {
        double max = 0;
        double sum;
        for (int r = 1; r <= hm.num_row(); r++) {
            sum = 0;
            for (int c = 1; c <= hm.num_col(); c++) {
                sum += Math.abs(hm.get(r, c));
            }
            if (sum > max) max = sum;
        }
        return max;
    }

    /** The largest absolute column sum. */
    public static double norm1(HepGenMatrix hm) {
        double max = 0;
        double sum;
        for (int c = 1; c <= hm.num_col(); c++) {
            sum = 0;
            for (int r = 1; r <= hm.num_row(); r++) sum += Math.abs(hm.get(r, c));
            if (sum > max) max = sum;
        }
        return max;
    }

    /**
     * CLHEP's norm: the square root of the largest eigenvalue of A, A(r,c) set (=, not summed, as
     * CLHEP does) to the product of the last row's elements r and c.
     */
    public static double norm(HepGenMatrix hm) {
        final HepSymMatrix A = new HepSymMatrix(hm.num_col(), 0);
        int r;
        for (r = 1; r <= A.num_row(); r++) {
            for (int c = 1; c <= r; c++) {
                for (int i = 1; i <= hm.num_row(); i++) A.setFast(r, c, hm.get(i, r) * hm.get(i, c));
            }
        }
        MatrixLinear.diagonalize(A);
        double max = Math.abs(A.get(1, 1));
        for (r = 2; r <= A.num_row(); r++) {
            if (max < Math.abs(A.get(r, r))) max = Math.abs(A.get(r, r));
        }
        return (Math.sqrt(max));
    }

    /** operator&lt;&lt;: a new line, then the rows, each element in a field of precision+7 (fixed: +3) and a blank. */
    protected static COStream printRows(COStream os, HepGenMatrix q) {
        os.put("\n");
        final int width = os.flags().floatField() == COStream.FloatField.FIXED ? os.precision() + 3 : os.precision() + 7;
        for (int irow = 1; irow <= q.num_row(); irow++) {
            for (int icol = 1; icol <= q.num_col(); icol++) {
                os.width(width);
                os.put(q.get(irow, icol)).put(" ");
            }
            os.put("\n");
        }
        return os;
    }

    /** The CHK_DIM_2 test of the binary operators. */
    static void chkDim2(int r1, int r2, int c1, int c2, String what, String fun) {
        if (r1 != r2 || c1 != c2) error("Range error in " + what + " function " + fun + "(1).");
    }

    /** The CHK_DIM_1 test. */
    static void chkDim1(int c1, int r2, String what, String fun) {
        if (c1 != r2) error("Range error in " + what + " function " + fun + "(2).");
    }
}
