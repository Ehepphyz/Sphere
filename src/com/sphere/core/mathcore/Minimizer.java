package com.sphere.core.mathcore;

import com.sphere.core.mathcore.fit.ParameterSettings;

import java.util.List;

/**
 * ROOT::Math::Minimizer (math/mathcore): the interface of every minimizer of
 * ROOT, Minuit (TMinuitMinimizer), Minuit2 (Minuit2Minimizer), Fumili,
 * Linear..., with ROOT's default behaviour where a minimizer does not
 * implement a method (an error message and false).
 *
 * <p>The objective function is an {@link IMultiGenFunction} (an
 * {@link IMultiGradFunction} when it computes its gradient). The variables
 * are set by index with a name, a value and a step, possibly limits or
 * fixed. The options are a {@link MinimizerOptions}, from the defaults.
 *
 * <p>References become arrays of one element: {@code GetMinosError(i, errLow,
 * errUp, option)} fills errLow[0] and errUp[0]; Scan and Contour read and
 * update the number of points in nstep[0] and npoints[0].
 */
public abstract class Minimizer {

    /** Whether the errors were validated (Hesse run, for Minuit). */
    protected boolean fValidError = false;
    protected MinimizerOptions fOptions = new MinimizerOptions();
    protected int fStatus = -1;

    public void Clear() {
    }

    /** The function to minimize. */
    public abstract void SetFunction(IMultiGenFunction func);

    /** A function of ndim variables. */
    public void SetFunction(int ndim, java.util.function.ToDoubleFunction<double[]> f) {
        SetFunction(IMultiGenFunction.of(ndim, f));
    }

    /** The Hessian matrix of the function, for minimizers that use it (packed upper triangle). */
    public void SetHessianFunction(java.util.function.BiFunction<double[], double[], Boolean> hfunc) {
    }

    /** SetVariables: each setting by kind (fixed, double bound, lower, upper, free); the number set. */
    public int SetVariables(List<ParameterSettings> vars) {
        int ivar = 0;
        for (ParameterSettings v : vars) {
            final boolean iret;
            if (v.IsFixed()) iret = SetFixedVariable(ivar, v.Name(), v.Value());
            else if (v.IsDoubleBound()) iret = SetLimitedVariable(ivar, v.Name(), v.Value(), v.StepSize(), v.LowerLimit(), v.UpperLimit());
            else if (v.HasLowerLimit()) iret = SetLowerLimitedVariable(ivar, v.Name(), v.Value(), v.StepSize(), v.LowerLimit());
            else if (v.HasUpperLimit()) iret = SetUpperLimitedVariable(ivar, v.Name(), v.Value(), v.StepSize(), v.UpperLimit());
            else iret = SetVariable(ivar, v.Name(), v.Value(), v.StepSize());
            if (iret) ivar++;
        }
        return ivar;
    }

    public abstract boolean SetVariable(int ivar, String name, double val, double step);

    /** Initial second derivatives. */
    public boolean SetCovarianceDiag(double[] d2, int n) {
        return false;
    }

    /** Initial covariance, the upper triangle row by row. */
    public boolean SetCovariance(double[] cov, int nrow) {
        return false;
    }

    public boolean SetLowerLimitedVariable(int ivar, String name, double val, double step, double lower) {
        return SetLimitedVariable(ivar, name, val, step, lower, Double.POSITIVE_INFINITY);
    }

    public boolean SetUpperLimitedVariable(int ivar, String name, double val, double step, double upper) {
        return SetLimitedVariable(ivar, name, val, step, Double.NEGATIVE_INFINITY, upper);
    }

    public boolean SetLimitedVariable(int ivar, String name, double val, double step, double lower, double upper) {
        MathError.warnMsg("Minimizer::SetLimitedVariable", "Setting of limited variable not implemented - set as unlimited");
        return SetVariable(ivar, name, val, step);
    }

    public boolean SetFixedVariable(int ivar, String name, double val) {
        MathError.errorMsg("Minimizer::SetFixedVariable", "Setting of fixed variable not implemented");
        return false;
    }

    public boolean SetVariableValue(int ivar, double value) {
        MathError.errorMsg("Minimizer::SetVariableValue", "Set of a variable value not implemented");
        return false;
    }

    /**
     * The values of the variables. As ROOT's loop ({@code i <= NDim()}) it also sets the
     * variable NDim(), with x[NDim()] when x is that long, else 0 (C++ reads past the array).
     */
    public boolean SetVariableValues(double[] x) {
        boolean ret = true;
        int i = 0;
        while (i <= NDim() && ret) {
            ret &= SetVariableValue(i, i < x.length ? x[i] : 0.0);
            i++;
        }
        return ret;
    }

    public boolean SetVariableStepSize(int ivar, double value) {
        MathError.errorMsg("Minimizer::SetVariableStepSize", "Setting an existing variable step size not implemented");
        return false;
    }

    public boolean SetVariableLowerLimit(int ivar, double lower) {
        MathError.errorMsg("Minimizer::SetVariableLowerLimit", "Setting an existing variable limit not implemented");
        return false;
    }

    public boolean SetVariableUpperLimit(int ivar, double upper) {
        MathError.errorMsg("Minimizer::SetVariableUpperLimit", "Setting an existing variable limit not implemented");
        return false;
    }

    public boolean SetVariableLimits(int ivar, double lower, double upper) {
        return SetVariableLowerLimit(ivar, lower) && SetVariableUpperLimit(ivar, upper);
    }

    public boolean FixVariable(int ivar) {
        MathError.errorMsg("Minimizer::FixVariable", "Fixing an existing variable not implemented");
        return false;
    }

    public boolean ReleaseVariable(int ivar) {
        MathError.errorMsg("Minimizer::ReleaseVariable", "Releasing an existing variable not implemented");
        return false;
    }

    public boolean IsFixedVariable(int ivar) {
        MathError.errorMsg("Minimizer::IsFixedVariable", "Querying an existing variable not implemented");
        return false;
    }

    public boolean GetVariableSettings(int ivar, ParameterSettings pars) {
        MathError.errorMsg("Minimizer::GetVariableSettings", "Querying an existing variable not implemented");
        return false;
    }

    public boolean SetVariableInitialRange(int ivar, double mininitial, double maxinitial) {
        return false;
    }

    /** Minimizes; true when the minimum found is valid. */
    public abstract boolean Minimize();

    public abstract double MinValue();

    public abstract double[] X();

    public double Edm() {
        return -1;
    }

    public double[] MinGradient() {
        return null;
    }

    public int NCalls() {
        return 0;
    }

    public int NIterations() {
        return NCalls();
    }

    public abstract int NDim();

    public int NFree() {
        return NDim();
    }

    public boolean ProvidesError() {
        return false;
    }

    public double[] Errors() {
        return null;
    }

    public double CovMatrix(int ivar, int jvar) {
        return 0;
    }

    /** The covariance, NDim x NDim row by row (zeros for fixed variables). */
    public boolean GetCovMatrix(double[] covMat) {
        return false;
    }

    /** The Hessian, the inverse of the covariance, NDim x NDim row by row. */
    public boolean GetHessianMatrix(double[] hMat) {
        return false;
    }

    /** 0 not computed, 1 approximate, 2 forced positive-definite, 3 accurate. */
    public int CovMatrixStatus() {
        return 0;
    }

    public double Correlation(int i, int j) {
        final double tmp = CovMatrix(i, i) * CovMatrix(j, j);
        return (tmp < 0) ? 0 : CovMatrix(i, j) / Math.sqrt(tmp);
    }

    public double[] GlobalCC() {
        return new double[0];
    }

    /** The Minos errors of variable ivar; option -1 lower only, +1 upper only. */
    public boolean GetMinosError(int ivar, double[] errLow, double[] errUp, int option) {
        MathError.errorMsg("Minimizer::GetMinosError", "Minos Error not implemented");
        return false;
    }

    public boolean GetMinosError(int ivar, double[] errLow, double[] errUp) {
        return GetMinosError(ivar, errLow, errUp, 0);
    }

    public boolean Hesse() {
        MathError.errorMsg("Minimizer::Hesse", "Hesse not implemented");
        return false;
    }

    /** The function along variable ivar at nstep[0] points (x, y); xmin = xmax = 0: around the minimum. */
    public boolean Scan(int ivar, int[] nstep, double[] x, double[] y, double xmin, double xmax) {
        MathError.errorMsg("Minimizer::Scan", "Scan not implemented");
        return false;
    }

    public boolean Scan(int ivar, int[] nstep, double[] x, double[] y) {
        return Scan(ivar, nstep, x, y, 0, 0);
    }

    /** npoints[0] points of the contour of ivar and jvar at MinValue() + ErrorDef(). */
    public boolean Contour(int ivar, int jvar, int[] npoints, double[] xi, double[] xj) {
        MathError.errorMsg("Minimizer::Contour", "Contour not implemented");
        return false;
    }

    public void PrintResults() {
    }

    public String VariableName(int ivar) {
        return "";
    }

    public int VariableIndex(String name) {
        MathError.errorMsg("Minimizer::VariableIndex", "Getting variable index from name not implemented");
        return -1;
    }

    /* ---- configuration ---- */

    public int PrintLevel() {
        return fOptions.PrintLevel();
    }

    public int MaxFunctionCalls() {
        return fOptions.MaxFunctionCalls();
    }

    public int MaxIterations() {
        return fOptions.MaxIterations();
    }

    public double Tolerance() {
        return fOptions.Tolerance();
    }

    public double Precision() {
        return fOptions.Precision();
    }

    public int Strategy() {
        return fOptions.Strategy();
    }

    public int Status() {
        return fStatus;
    }

    public int MinosStatus() {
        return -1;
    }

    public double ErrorDef() {
        return fOptions.ErrorDef();
    }

    public boolean IsValidError() {
        return fValidError;
    }

    /** A copy of the options. */
    public MinimizerOptions Options() {
        return new MinimizerOptions(fOptions);
    }

    public void SetPrintLevel(int level) {
        fOptions.SetPrintLevel(level);
    }

    /** Ignored when not positive, as in ROOT. */
    public void SetMaxFunctionCalls(int maxfcn) {
        if (maxfcn > 0) fOptions.SetMaxFunctionCalls(maxfcn);
    }

    public void SetMaxIterations(int maxiter) {
        if (maxiter > 0) fOptions.SetMaxIterations(maxiter);
    }

    public void SetTolerance(double tol) {
        fOptions.SetTolerance(tol);
    }

    public void SetPrecision(double prec) {
        fOptions.SetPrecision(prec);
    }

    public void SetStrategy(int strategyLevel) {
        fOptions.SetStrategy(strategyLevel);
    }

    public void SetErrorDef(double up) {
        fOptions.SetErrorDef(up);
    }

    public void SetValidError(boolean on) {
        fValidError = on;
    }

    public void SetOptions(MinimizerOptions opt) {
        fOptions.assign(opt);
    }

    public void SetExtraOptions(IOptions extraOptions) {
        fOptions.SetExtraOptions(extraOptions);
    }

    public void SetDefaultOptions() {
        fOptions.ResetToDefaultOptions();
    }
}
