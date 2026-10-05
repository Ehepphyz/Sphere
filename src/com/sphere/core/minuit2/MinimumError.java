package com.sphere.core.minuit2;

/**
 * The inverse of the second derivative matrix (half the covariance) Migrad
 * steps with and updates, with what is known of it: positive-definite, made
 * so, not so, failed; and dcovar, its relative change in the last update.
 */
public final class MinimumError {

    public enum Status { MnUnset, MnPosDef, MnMadePosDef, MnNotPosDef, MnHesseFailed, MnInvertFailed, MnReachedCallLimit }

    private final LASymMatrix matrix;
    private LASymMatrix hessian;
    private final double dcovar;
    private final Status status;

    /** n x n zeros, unset. */
    public MinimumError(int n) {
        matrix = new LASymMatrix(n);
        hessian = new LASymMatrix(0);
        dcovar = 1.0;
        status = Status.MnUnset;
    }

    public MinimumError(LASymMatrix mat, double dcov) {
        matrix = mat.copy();
        hessian = new LASymMatrix(0);
        dcovar = dcov;
        status = Status.MnPosDef;
    }

    /** With the Hessian itself (Fumili has it). */
    public MinimumError(LASymMatrix mat, LASymMatrix hess, double dcov) {
        matrix = mat.copy();
        hessian = hess.copy();
        dcovar = dcov;
        status = Status.MnPosDef;
    }

    public MinimumError(LASymMatrix mat, Status status) {
        matrix = mat.copy();
        hessian = new LASymMatrix(0);
        dcovar = 1.0;
        this.status = status;
    }

    /** The covariance: twice the inverse Hessian. */
    public LASymMatrix matrix() {
        return MnMatrix.scaled(2., matrix);
    }

    public LASymMatrix invHessian() {
        return matrix;
    }

    /** The Hessian: the one given, or the inverse of the inverse, computed once. */
    public LASymMatrix hessian() {
        if (hessian.size() == 0) hessian = invertMatrix(matrix);
        return hessian;
    }

    /** The inverse; when inversion fails, the inverse of the diagonal, and ifail[0] set. */
    public static LASymMatrix invertMatrix(LASymMatrix matrix, int[] ifail) {
        final LASymMatrix tmp = matrix.copy();
        ifail[0] = MnMatrix.invert(tmp);
        if (ifail[0] != 0) {
            new MnPrint("MinimumError::Invert").warn("Inversion fails; return diagonal matrix");
            for (int i = 0; i < matrix.nrow(); ++i) {
                for (int j = 0; j <= i; j++) tmp.set(i, j, i == j ? 1. / matrix.get(i, i) : 0);
            }
        }
        return tmp;
    }

    public static LASymMatrix invertMatrix(LASymMatrix matrix) {
        return invertMatrix(matrix, new int[1]);
    }

    public double dcovar() {
        return dcovar;
    }

    public Status status() {
        return status;
    }

    public boolean isValid() {
        return isAvailable() && (isPosDef() || isMadePosDef() || isNotPosDef());
    }

    public boolean isAccurate() {
        return isPosDef() && dcovar() < 0.1;
    }

    public boolean isPosDef() {
        return status == Status.MnPosDef;
    }

    public boolean isMadePosDef() {
        return status == Status.MnMadePosDef;
    }

    public boolean isNotPosDef() {
        return status == Status.MnNotPosDef;
    }

    public boolean hesseFailed() {
        return status == Status.MnHesseFailed;
    }

    public boolean invertFailed() {
        return status == Status.MnInvertFailed;
    }

    public boolean hasReachedCallLimit() {
        return status == Status.MnReachedCallLimit;
    }

    public boolean isAvailable() {
        return status != Status.MnUnset;
    }
}
