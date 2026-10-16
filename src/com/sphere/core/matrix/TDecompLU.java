package com.sphere.core.matrix;

import com.sphere.core.mathcore.TMath;
import com.sphere.core.rootbase.TError;

/**
 * ROOT's TDecompLU (math/matrix): LU decomposition of a square matrix by
 * Crout's method with implicit row scaling and partial pivoting, and the
 * inversion built on it, operation for operation as ROOT computes them. A
 * matrix is its n x n elements row by row, as TMatrixD stores them.
 *
 * <p>This is the part of TDecompLU that TMatrixDSym::Invert and
 * TMatrixD::Invert use (DecomposeLUCrout, InvertLU), and TDecompBase's
 * DiagProd for the determinant.
 */
public final class TDecompLU {

    private TDecompLU() {
    }

    /** Size of the work arrays ROOT keeps on the stack (larger ones are allocated). */
    static final int kWorkMax = 100;

    /** The machine epsilon of double, the tolerance of a TMatrixD. */
    public static final double DBL_EPSILON = Math.ulp(1.0);

    /**
     * Crout's LU decomposition of lu (n x n) in place: U in the upper triangle, L in multiplier
     * form below; index[j] the row swapped with j, sign[0] the sign of the permutation, nrZeros[0]
     * the diagonal elements smaller than tol. False when the matrix is singular.
     */
    public static boolean DecomposeLUCrout(double[] pLU, int n, int[] index, double[] sign, double tol, int[] nrZeros) {
        final double[] scale = new double[n];
        sign[0] = 1.0;
        nrZeros[0] = 0;
        // Find implicit scaling factors for each row
        for (int i = 0; i < n; i++) {
            final int off_i = i * n;
            double max = 0.0;
            for (int j = 0; j < n; j++) {
                final double tmp = Math.abs(pLU[off_i + j]);
                if (tmp > max) max = tmp;
            }
            scale[i] = (max == 0.0 ? 0.0 : 1.0 / max);
        }
        for (int j = 0; j < n; j++) {
            final int off_j = j * n;
            // Run down jth column from top to diag, to form the elements of U.
            for (int i = 0; i < j; i++) {
                final int off_i = i * n;
                double r = pLU[off_i + j];
                for (int k = 0; k < i; k++) {
                    final int off_k = k * n;
                    r -= pLU[off_i + k] * pLU[off_k + j];
                }
                pLU[off_i + j] = r;
            }
            // Run down jth subdiag to form the residuals after the elimination of the first j-1 subdiags.
            double max = 0.0;
            int imax = 0;
            for (int i = j; i < n; i++) {
                final int off_i = i * n;
                double r = pLU[off_i + j];
                for (int k = 0; k < j; k++) {
                    final int off_k = k * n;
                    r -= pLU[off_i + k] * pLU[off_k + j];
                }
                pLU[off_i + j] = r;
                final double tmp = scale[i] * Math.abs(r);
                if (tmp >= max) {
                    max = tmp;
                    imax = i;
                }
            }
            // Permute current row with imax
            if (j != imax) {
                final int off_imax = imax * n;
                for (int k = 0; k < n; k++) {
                    final double tmp = pLU[off_imax + k];
                    pLU[off_imax + k] = pLU[off_j + k];
                    pLU[off_j + k] = tmp;
                }
                sign[0] = -sign[0];
                scale[imax] = scale[j];
            }
            index[j] = imax;
            // If diag term is not zero divide subdiag to form multipliers.
            if (pLU[off_j + j] != 0.0) {
                if (Math.abs(pLU[off_j + j]) < tol) nrZeros[0]++;
                if (j != n - 1) {
                    final double tmp = 1.0 / pLU[off_j + j];
                    for (int i = j + 1; i < n; i++) {
                        final int off_i = i * n;
                        pLU[off_i + j] *= tmp;
                    }
                }
            } else {
                TError.Error("TDecompLU::DecomposeLUCrout", "matrix is singular");
                return false;
            }
        }
        return true;
    }

    /**
     * Inverts lu (n x n) in place; det[0], when det is not null, gets the determinant. False
     * (and the matrix left as the decomposition made it) when it is singular within tol.
     */
    public static boolean InvertLU(double[] pLU, int n, double tol, double[] det) {
        if (det != null) det[0] = 0.0;
        final int[] index = new int[n];
        final double[] sign = {1.0};
        final int[] nrZeros = {0};
        if (!DecomposeLUCrout(pLU, n, index, sign, tol, nrZeros) || nrZeros[0] > 0) {
            TError.Error("TDecompLU::InvertLU", "matrix is singular, %d diag elements < tolerance of %.4e", nrZeros[0], tol);
            return false;
        }
        if (det != null) {
            final double[] diagv = new double[n];
            for (int i = 0; i < n; i++) diagv[i] = pLU[i * n + i];
            final double[] d1 = {0}, d2 = {0};
            DiagProd(diagv, tol, d1, d2);
            d1[0] *= sign[0];
            det[0] = d1[0] * TMath.power(2.0, d2[0]);
        }
        //  Form inv(U).
        int j;
        for (j = 0; j < n; j++) {
            final int off_j = j * n;
            pLU[off_j + j] = 1. / pLU[off_j + j];
            final double mLU_jj = -pLU[off_j + j];
            // Compute elements 0:j-1 of j-th column (pX = pLU+j).
            int k;
            for (k = 0; k <= j - 1; k++) {
                final int off_k = k * n;
                if (pLU[j + off_k] != 0.0) {
                    final double tmp = pLU[j + off_k];
                    for (int i = 0; i <= k - 1; i++) {
                        final int off_i = i * n;
                        pLU[j + off_i] += tmp * pLU[off_i + k];
                    }
                    pLU[j + off_k] *= pLU[off_k + k];
                }
            }
            for (k = 0; k <= j - 1; k++) {
                final int off_k = k * n;
                pLU[j + off_k] *= mLU_jj;
            }
        }
        // Solve the equation inv(A)*L = inv(U) for inv(A).
        final double[] pWorkD = new double[n];
        for (j = n - 1; j >= 0; j--) {
            // Copy current column j of L to WORK and replace with zeros.
            for (int i = j + 1; i < n; i++) {
                final int off_i = i * n;
                pWorkD[i] = pLU[off_i + j];
                pLU[off_i + j] = 0.0;
            }
            // Compute current column of inv(A).
            if (j < n - 1) {
                int mp = j + 1; // Matrix row ptr
                int tp = j;     // Target vector ptr
                for (int irow = 0; irow < n; irow++) {
                    double sum = 0.;
                    int sp = j + 1; // Source vector ptr
                    for (int icol = 0; icol < n - 1 - j; icol++) sum += pLU[mp++] * pWorkD[sp++];
                    pLU[tp] = -sum + pLU[tp];
                    mp += j + 1;
                    tp += n;
                }
            }
        }
        // Apply column interchanges.
        for (j = n - 1; j >= 0; j--) {
            final int jperm = index[j];
            if (jperm != j) {
                for (int i = 0; i < n; i++) {
                    final int off_i = i * n;
                    final double tmp = pLU[off_i + jperm];
                    pLU[off_i + jperm] = pLU[off_i + j];
                    pLU[off_i + j] = tmp;
                }
            }
        }
        return true;
    }

    /**
     * TDecompBase::DiagProd: the product of the diagonal as d1[0] * 2^d2[0] (d1 the mantissa,
     * d2 the exponent), 0 when an element is not above tol.
     */
    public static void DiagProd(double[] diag, double tol, double[] d1, double[] d2) {
        final double zero = 0.0;
        final double one = 1.0;
        final double four = 4.0;
        final double sixteen = 16.0;
        final double sixteenth = 0.0625;
        final int n = diag.length;
        double t1 = 1.0;
        double t2 = 0.0;
        int niter2 = 0;
        int niter3 = 0;
        for (int i = 0; ((i < n) && (t1 != zero)); i++) {
            if (Math.abs(diag[i]) > tol) {
                t1 *= diag[i];
                while (Math.abs(t1) >= one) {
                    t1 *= sixteenth;
                    t2 += four;
                    niter2++;
                    if (niter2 > 100) break;
                }
                while (Math.abs(t1) < sixteenth) {
                    t1 *= sixteen;
                    t2 -= four;
                    niter3++;
                    if (niter3 > 100) break;
                }
            } else {
                t1 = zero;
                t2 = zero;
            }
        }
        d1[0] = t1;
        d2[0] = t2;
    }
}
