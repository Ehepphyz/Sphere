package com.sphere.core.minuit;

import com.sphere.core.mathcore.IMultiGenFunction;
import com.sphere.core.mathcore.IMultiGradFunction;
import com.sphere.core.mathcore.Minimizer;
import com.sphere.core.mathcore.fit.ParameterSettings;
import com.sphere.core.matrix.TDecompLU;
import com.sphere.core.rootbase.TError;

import java.util.Arrays;
import java.util.Locale;

/**
 * ROOT's TMinuitMinimizer (math/minuit): the {@link Minimizer} interface on
 * {@link TMinuit}, the minimizer ROOT names "Minuit" (algorithms Migrad,
 * Simplex, Minimize, MigradImproved, Scan, Seek). Translated from root-master
 * statement for statement: the TMinuit commands it sends, the status it
 * makes of them (migrad + 10 minos + 100 hesse + 1000 improve), the
 * covariance with zeros for fixed parameters, the Hessian inverted as
 * TMatrixDSym::Invert does (TDecompLU).
 *
 * <p>As in ROOT, every TMinuitMinimizer shares one TMinuit (gMinuit) unless
 * {@link #UseStaticMinuit} is turned off, and the function is held per
 * thread.
 */
public class TMinuitMinimizer extends Minimizer {

    /** ROOT::Minuit::EMinimizerType. */
    public enum EMinimizerType { kMigrad, kSimplex, kCombined, kMigradImproved, kScan, kSeek }

    private static final ThreadLocal<IMultiGenFunction> fgFunc = new ThreadLocal<>();
    private static TMinuit fgMinuit = null;
    private static boolean fgUsed = false;
    private static boolean fgUseStaticMinuit = true;

    private boolean fUsed;
    private boolean fMinosRun;
    private int fDim;
    private int fMinosStatus = -1;
    private double[] fParams = new double[0];
    private double[] fErrors = new double[0];
    private double[] fCovar = new double[0];
    private EMinimizerType fType;
    private TMinuit fMinuit;

    public TMinuitMinimizer() {
        this(EMinimizerType.kMigrad, 0);
    }

    public TMinuitMinimizer(EMinimizerType type, int ndim) {
        fUsed = false;
        fMinosRun = false;
        fDim = ndim;
        fType = type;
        fMinuit = null;
        if (fDim > 0) InitTMinuit(fDim);
    }

    /** By name, case ignored: Migrad (default), Simplex, Minimize, MigradImproved, Scan, Seek. */
    public TMinuitMinimizer(String type, int ndim) {
        fUsed = false;
        fMinosRun = false;
        fDim = ndim;
        fMinuit = null;
        final String algoname = type.toLowerCase(Locale.ROOT);
        EMinimizerType algoType = EMinimizerType.kMigrad;
        if (algoname.equals("simplex")) algoType = EMinimizerType.kSimplex;
        if (algoname.equals("minimize")) algoType = EMinimizerType.kCombined;
        if (algoname.equals("migradimproved")) algoType = EMinimizerType.kMigradImproved;
        if (algoname.equals("scan")) algoType = EMinimizerType.kScan;
        if (algoname.equals("seek")) algoType = EMinimizerType.kSeek;
        fType = algoType;
        if (fDim > 0) InitTMinuit(fDim);
    }

    public TMinuitMinimizer(String type) {
        this(type, 0);
    }

    /** Whether every TMinuitMinimizer uses one TMinuit (gMinuit); returns the previous setting. */
    public static synchronized boolean UseStaticMinuit(boolean on) {
        final boolean prev = fgUseStaticMinuit;
        fgUseStaticMinuit = on;
        return prev;
    }

    public static boolean UseStaticMinuit() {
        return UseStaticMinuit(true);
    }

    /** The TMinuit this minimizer drives (null before SetFunction). */
    public TMinuit GetTMinuit() {
        return fMinuit;
    }

    protected void InitTMinuit(int dim) {
        synchronized (TMinuitMinimizer.class) {
            if (fMinuit == null || dim > fMinuit.fMaxpar) {
                if (fgUseStaticMinuit) {
                    if (fgMinuit != TMinuit.gMinuit) {
                        // the instance of TMinuitMinimizer still exists (Java has no delete): gMinuit back to it
                        if (fgMinuit != null) TMinuit.gMinuit = fgMinuit;
                    }
                    if (fgMinuit == null) {
                        fgUsed = false;
                        fgMinuit = new TMinuit(dim);
                    } else if (fgMinuit.GetNumPars() != dim) {
                        fgUsed = false;
                        fgMinuit = new TMinuit(dim);
                    }
                    fMinuit = fgMinuit;
                } else {
                    fMinuit = new TMinuit(dim);
                    fgMinuit = fMinuit;
                    fgUsed = false;
                }
            }
        }
        fDim = dim;
        final double[] arglist = new double[1];
        final int[] ierr = {0};
        // TMinuit level is shift by 1 -1 means 0;
        arglist[0] = PrintLevel() - 1;
        fMinuit.mnexcm("SET PRINT", arglist, 1, ierr);
        if (PrintLevel() <= 0) SuppressMinuitWarnings();
    }

    @Override
    public void SetFunction(IMultiGenFunction func) {
        final boolean hasGrad = func.HasGradient();
        fDim = func.NDim();
        InitTMinuit(fDim);
        fgFunc.set(func);
        fMinuit.SetFCN(hasGrad ? TMinuitMinimizer::FcnGrad : TMinuitMinimizer::Fcn);
        final double[] arglist = new double[1];
        final int[] ierr = {0};
        if (hasGrad) {
            arglist[0] = 1;
            fMinuit.mnexcm("SET GRAD", arglist, 1, ierr);
        } else {
            fMinuit.mnexcm("SET NOGrad", arglist, 0, ierr);
        }
    }

    /** The FCN TMinuit calls: the function of this thread. */
    protected static void Fcn(int[] npar, double[] g, double[] f, double[] x, int iflag) {
        f[0] = fgFunc.get().eval(x);
    }

    /** The FCN with the gradient (iflag 2). */
    protected static void FcnGrad(int[] npar, double[] g, double[] f, double[] x, int iflag) {
        final IMultiGradFunction gFunc = (IMultiGradFunction) fgFunc.get();
        f[0] = gFunc.eval(x);
        if (iflag == 2) gFunc.Gradient(x, g);
    }

    @Override
    public boolean SetVariable(int ivar, String name, double val, double step) {
        if (!CheckMinuitInstance()) return false;
        if (fgUseStaticMinuit) fUsed = fgUsed;
        if (fUsed) DoClear();
        DoReleaseFixParameter(ivar);
        final int iret = fMinuit.DefineParameter(ivar, name, val, step, 0., 0.);
        return (iret == 0);
    }

    @Override
    public boolean SetLimitedVariable(int ivar, String name, double val, double step, double lower, double upper) {
        if (!CheckMinuitInstance()) return false;
        if (fgUseStaticMinuit) fUsed = fgUsed;
        if (fUsed) DoClear();
        DoReleaseFixParameter(ivar);
        final int iret = fMinuit.DefineParameter(ivar, name, val, step, lower, upper);
        return (iret == 0);
    }

    @Override
    public boolean SetLowerLimitedVariable(int ivar, String name, double val, double step, double lower) {
        TError.Warning("TMinuitMinimizer::SetLowerLimitedVariable", "not implemented - use as upper limit 1.E7 instead of +inf");
        return SetLimitedVariable(ivar, name, val, step, lower, lower + 1.E7);
    }

    @Override
    public boolean SetUpperLimitedVariable(int ivar, String name, double val, double step, double upper) {
        TError.Warning("TMinuitMinimizer::SetUpperLimitedVariable", "not implemented - use as lower limit -1.E7 instead of -inf");
        return SetLimitedVariable(ivar, name, val, step, upper - 1.E7, upper);
    }

    protected boolean CheckMinuitInstance() {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::CheckMinuitInstance", "Invalid TMinuit pointer. Need to call first SetFunction");
            return false;
        }
        return true;
    }

    protected boolean CheckVarIndex(int ivar) {
        if (ivar >= fMinuit.fNu) {
            TError.Error("TMinuitMinimizer::CheckVarIndex", "Invalid parameter index");
            return false;
        }
        return true;
    }

    @Override
    public boolean SetFixedVariable(int ivar, String name, double val) {
        if (!CheckMinuitInstance()) return false;
        if (fgUseStaticMinuit) fUsed = fgUsed;
        if (fUsed) DoClear();
        final double step = (val != 0) ? 0.1 * Math.abs(val) : 0.1;
        int iret = fMinuit.DefineParameter(ivar, name, val, step, 0., 0.);
        if (iret == 0) iret = fMinuit.FixParameter(ivar);
        return (iret == 0);
    }

    @Override
    public boolean SetVariableValue(int ivar, double val) {
        if (!CheckMinuitInstance()) return false;
        final double[] arglist = new double[2];
        final int[] ierr = {0};
        arglist[0] = ivar + 1;
        arglist[1] = val;
        fMinuit.mnexcm("SET PAR", arglist, 2, ierr);
        return (ierr[0] == 0);
    }

    @Override
    public boolean SetVariableStepSize(int ivar, double step) {
        if (!CheckMinuitInstance()) return false;
        final double[] curval = {0}, err = {0}, lowlim = {0}, uplim = {0};
        final int[] iuint = {0};
        final String[] name = {""};
        fMinuit.mnpout(ivar, name, curval, err, lowlim, uplim, iuint);
        if (iuint[0] == -1) return false;
        final int iret = fMinuit.DefineParameter(ivar, name[0], curval[0], step, lowlim[0], uplim[0]);
        return (iret == 0);
    }

    @Override
    public boolean SetVariableLowerLimit(int ivar, double lower) {
        TError.Warning("TMinuitMinimizer::SetVariableLowerLimit", "not implemented - use as upper limit 1.E30 instead of +inf");
        return SetVariableLimits(ivar, lower, 1.E30);
    }

    @Override
    public boolean SetVariableUpperLimit(int ivar, double upper) {
        TError.Warning("TMinuitMinimizer::SetVariableUpperLimit", "not implemented - - use as lower limit -1.E30 instead of +inf");
        return SetVariableLimits(ivar, -1.E30, upper);
    }

    @Override
    public boolean SetVariableLimits(int ivar, double lower, double upper) {
        if (!CheckMinuitInstance()) return false;
        final double[] curval = {0}, err = {0}, lowlim = {0}, uplim = {0};
        final int[] iuint = {0};
        final String[] name = {""};
        fMinuit.mnpout(ivar, name, curval, err, lowlim, uplim, iuint);
        if (iuint[0] == -1) return false;
        final int iret = fMinuit.DefineParameter(ivar, name[0], curval[0], err[0], lower, upper);
        return (iret == 0);
    }

    @Override
    public boolean FixVariable(int ivar) {
        if (!CheckMinuitInstance()) return false;
        if (!CheckVarIndex(ivar)) return false;
        final int iret = fMinuit.FixParameter(ivar);
        return (iret == 0);
    }

    @Override
    public boolean ReleaseVariable(int ivar) {
        if (!CheckMinuitInstance()) return false;
        if (!CheckVarIndex(ivar)) return false;
        final int iret = fMinuit.Release(ivar);
        return (iret == 0);
    }

    @Override
    public boolean IsFixedVariable(int ivar) {
        if (!CheckMinuitInstance()) return false;
        if (!CheckVarIndex(ivar)) return false;
        return (fMinuit.fNiofex[ivar] == 0);
    }

    @Override
    public boolean GetVariableSettings(int ivar, ParameterSettings var) {
        if (!CheckMinuitInstance()) return false;
        if (!CheckVarIndex(ivar)) return false;
        final double[] curval = {0}, err = {0}, lowlim = {0}, uplim = {0};
        final int[] iuint = {0};
        final String[] name = {""};
        fMinuit.mnpout(ivar, name, curval, err, lowlim, uplim, iuint);
        if (iuint[0] == -1) return false;
        var.Set(name[0], curval[0], err[0], lowlim[0], uplim[0]);
        if (IsFixedVariable(ivar)) var.Fix();
        return true;
    }

    @Override
    public String VariableName(int ivar) {
        if (!CheckMinuitInstance()) return "";
        if (!CheckVarIndex(ivar)) return "";
        return fMinuit.fCpnam[ivar];
    }

    @Override
    public int VariableIndex(String name) {
        TError.Error("TMinuitMinimizer::VariableIndex", " find index of a variable from its name  is not implemented in TMinuit");
        return -1;
    }

    /**
     * Minimizes with the algorithm chosen; true when the minimum is valid. Status():
     * migrad (or other) result + 10 minos + 100 hesse + 1000 improve.
     */
    @Override
    public boolean Minimize() {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::Minimize", "invalid TMinuit pointer. Need to call first SetFunction and SetVariable");
            return false;
        }
        if (fMinuit.fNu < fDim) {
            TError.Error("TMinuitMinimizer::Minimize",
                "The total number of defined parameters is different than the function dimension, npar = %d, dim = %d", fMinuit.fNu, fDim);
            return false;
        }
        final int printlevel = PrintLevel();
        if (fMinuit.fNpar <= 0) {
            RetrieveParams();
            fMinuit.fAmin = fgFunc.get().eval(fParams);
            if (printlevel > 0) TError.Info("TMinuitMinimizer::Minimize", "There are no free parameter - just compute the function value");
            return true;
        }
        final double[] arglist = new double[10];
        final int[] ierr = {0};
        arglist[0] = ErrorDef();
        fMinuit.mnexcm("SET Err", arglist, 1, ierr);
        arglist[0] = printlevel - 1;
        fMinuit.mnexcm("SET PRINT", arglist, 1, ierr);
        if (printlevel == 0) fMinuit.mnexcm("SET NOW", arglist, 0, ierr);
        if (Precision() > 0) {
            arglist[0] = Precision();
            fMinuit.mnexcm("SET EPS", arglist, 1, ierr);
        }
        final int strategy = Strategy();
        if (strategy >= 0 && strategy <= 2) {
            arglist[0] = strategy;
            fMinuit.mnexcm("SET STR", arglist, 1, ierr);
        }
        arglist[0] = MaxFunctionCalls();
        arglist[1] = Tolerance();
        int nargs = 2;
        switch (fType) {
            case kMigrad -> fMinuit.mnexcm("MIGRAD", arglist, nargs, ierr);
            case kCombined -> fMinuit.mnexcm("MINIMIZE", arglist, nargs, ierr);
            case kSimplex -> fMinuit.mnexcm("SIMPLEX", arglist, nargs, ierr);
            case kScan -> {
                nargs = 0;
                fMinuit.mnexcm("SCAN", arglist, nargs, ierr);
            }
            case kSeek -> {
                nargs = 1;
                if (arglist[1] >= 1.) nargs = 2;
                fMinuit.mnexcm("SEEK", arglist, nargs, ierr);
            }
            default -> fMinuit.mnexcm("MIGRAD", arglist, nargs, ierr);
        }
        fgUsed = true;
        fUsed = true;
        fStatus = ierr[0];
        final int minErrStatus = ierr[0];
        if (printlevel > 2) TError.Info("TMinuitMinimizer::Minimize", "Finished to run MIGRAD - status %d", ierr[0]);
        if (ierr[0] == 0 && fType == EMinimizerType.kMigradImproved) {
            fMinuit.mnexcm("IMPROVE", arglist, 1, ierr);
            fStatus += 1000 * ierr[0];
            if (printlevel > 2) TError.Info("TMinuitMinimizer::Minimize", "Finished to run IMPROVE - status %d", ierr[0]);
        }
        if (minErrStatus == 0 && (IsValidError() || (strategy >= 1 && CovMatrixStatus() < 3))) {
            fMinuit.mnexcm("HESSE", arglist, 1, ierr);
            fStatus += 100 * ierr[0];
            if (ierr[0] == 0 && CovMatrixStatus() < 2) {
                fStatus += 100 * (CovMatrixStatus() + 1);
            }
            if (printlevel > 2) TError.Info("TMinuitMinimizer::Minimize", "Finished to run HESSE - status %d", ierr[0]);
        }
        RetrieveParams();
        if (minErrStatus == 0) {
            RetrieveErrorMatrix();
            fMinosRun = false;
            return true;
        }
        return false;
    }

    protected void RetrieveParams() {
        if (fParams.length != fDim) fParams = Arrays.copyOf(fParams, fDim);
        if (fErrors.length != fDim) fErrors = Arrays.copyOf(fErrors, fDim);
        for (int i = 0; i < fDim; ++i) {
            final double[] v = {fParams[i]}, e = {fErrors[i]};
            fMinuit.GetParameter(i, v, e);
            fParams[i] = v[0];
            fErrors[i] = e[0];
        }
    }

    protected void RetrieveErrorMatrix() {
        final int nfree = NFree();
        final int ndim2 = fDim * fDim;
        if (fCovar.length != ndim2) fCovar = Arrays.copyOf(fCovar, fDim * fDim);
        if (nfree >= fDim) {
            fMinuit.mnemat(fCovar, fDim);
        } else {
            final double[] tmpMat = new double[nfree * nfree];
            fMinuit.mnemat(tmpMat, nfree);
            int l = 0;
            for (int i = 0; i < fDim; ++i) {
                if (fMinuit.fNiofex[i] > 0) {
                    int m = 0;
                    for (int j = 0; j <= i; ++j) {
                        if (fMinuit.fNiofex[j] > 0) {
                            fCovar[i * fDim + j] = tmpMat[l * nfree + m];
                            fCovar[j * fDim + i] = fCovar[i * fDim + j];
                            m++;
                        }
                    }
                    l++;
                }
            }
        }
    }

    @Override
    public double MinValue() {
        if (fMinuit == null) return 0;
        final double minval = fMinuit.fAmin;
        if (minval == fMinuit.fUndefi) return 0;
        return minval;
    }

    @Override
    public double Edm() {
        if (fMinuit == null) return -1;
        if (fMinuit.fAmin == fMinuit.fUndefi || fMinuit.fEDM == fMinuit.fBigedm) return fMinuit.fUp;
        return fMinuit.fEDM;
    }

    @Override
    public double[] X() {
        return fParams;
    }

    @Override
    public double[] MinGradient() {
        return null;
    }

    @Override
    public int NCalls() {
        if (fMinuit == null) return 0;
        return fMinuit.fNfcn;
    }

    @Override
    public int NDim() {
        return fDim;
    }

    @Override
    public int NFree() {
        if (fMinuit == null) return 0;
        if (fMinuit.fNpar < 0) return 0;
        return fMinuit.fNpar;
    }

    @Override
    public boolean ProvidesError() {
        return true;
    }

    @Override
    public double[] Errors() {
        return fErrors;
    }

    @Override
    public double CovMatrix(int i, int j) {
        return (fCovar.length > (i + fDim * j)) ? fCovar[i + fDim * j] : 0;
    }

    @Override
    public boolean GetCovMatrix(double[] cov) {
        final int covStatus = CovMatrixStatus();
        if (fCovar.length != fDim * fDim || covStatus < 2) {
            TError.Error("TMinuitMinimizer::GetHessianMatrix", "Hessian matrix has not been computed - status %d", covStatus);
            return false;
        }
        System.arraycopy(fCovar, 0, cov, 0, fCovar.length);
        return true;
    }

    @Override
    public boolean GetHessianMatrix(double[] hes) {
        final int covStatus = CovMatrixStatus();
        if (fCovar.length != fDim * fDim || covStatus < 2) {
            TError.Error("TMinuitMinimizer::GetHessianMatrix", "Hessian matrix has not been computed - status %d", covStatus);
            return false;
        }
        final int nfree = NFree();
        // TMatrixDSym mat(nfree), filled by mnemat, then mat.Invert(): TDecompLU::InvertLU of a copy
        final double[] mat = new double[nfree * nfree];
        fMinuit.mnemat(mat, nfree);
        final double[] tmp = mat.clone();
        if (TDecompLU.InvertLU(tmp, nfree, TDecompLU.DBL_EPSILON, null)) {
            System.arraycopy(tmp, 0, mat, 0, tmp.length);
        }
        int l = 0;
        for (int i = 0; i < fDim; ++i) {
            if (fMinuit.fNiofex[i] > 0) {
                int m = 0;
                for (int j = 0; j <= i; ++j) {
                    if (fMinuit.fNiofex[j] > 0) {
                        hes[i * fDim + j] = mat[l * nfree + m];
                        hes[j * fDim + i] = hes[i * fDim + j];
                        m++;
                    }
                }
                l++;
            }
        }
        return true;
    }

    /** 0 not computed, 1 approximate, 2 forced positive-definite, 3 accurate. */
    @Override
    public int CovMatrixStatus() {
        if (fMinuit == null) return 0;
        if (fMinuit.fAmin == fMinuit.fUndefi) return 0;
        return fMinuit.fISW[1];
    }

    @Override
    public double[] GlobalCC() {
        if (fMinuit == null) return new double[0];
        if (fMinuit.fGlobcc == null) return new double[0];
        final double[] out = new double[fMinuit.fNu];
        for (int i = 0; i < fMinuit.fNu; ++i) {
            final int iin = fMinuit.fNiofex[i];
            if (iin < 1) return new double[0];
            out[i] = fMinuit.fGlobcc[iin - 1];
        }
        return out;
    }

    @Override
    public boolean GetMinosError(int i, double[] errLow, double[] errUp, int option) {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::GetMinosError", "invalid TMinuit pointer. Need to call first SetFunction and SetVariable");
            return false;
        }
        if (fMinuit.fNiofex[i] == 0) {
            if (PrintLevel() > 0) {
                TError.Info("TMinuitMinimizer::GetMinosError", "Parameter %s is fixed. There are no Minos error to calculate. Ignored.", VariableName(i));
            }
            errLow[0] = 0;
            errUp[0] = 0;
            return true;
        }
        final double[] arglist = new double[2];
        final int[] ierr = {0};
        if (fMinuit.fUp != ErrorDef()) {
            arglist[0] = ErrorDef();
            fMinuit.mnexcm("SET Err", arglist, 1, ierr);
        }
        if (fMinuit.fISW[4] != (PrintLevel() - 1)) {
            arglist[0] = PrintLevel() - 1;
            fMinuit.mnexcm("SET PRINT", arglist, 1, ierr);
            if (PrintLevel() == 0) fMinuit.mnexcm("SET NOW", arglist, 0, ierr);
        }
        if (fMinuit.fIstrat != Strategy()) {
            arglist[0] = Strategy();
            fMinuit.mnexcm("SET STR", arglist, 1, ierr);
        }
        if (Precision() > 0 && fMinuit.fEpsma2 != Precision()) {
            arglist[0] = Precision();
            fMinuit.mnexcm("SET EPS", arglist, 1, ierr);
        }
        arglist[0] = MaxFunctionCalls();
        arglist[1] = i + 1;
        final int nargs = 2;
        fMinuit.mnexcm("MINOS", arglist, nargs, ierr);
        boolean isValid = (ierr[0] == 0);
        if (isValid && !fMinuit.fCstatu.equals("SUCCESSFUL")) {
            if (fMinuit.fCstatu.equals("FAILURE")) {
                ierr[0] = 5;
                isValid = false;
            }
            if (fMinuit.fCstatu.equals("PROBLEMS")) ierr[0] = 6;
            ierr[0] = 7;
        }
        fStatus += 10 * ierr[0];
        fMinosStatus = ierr[0];
        fMinosRun = true;
        if (fMinuit.fCstatu.equals("SUCCESSFUL")) RetrieveParams();
        final double[] errParab = {0};
        final double[] gcor = {0};
        fMinuit.mnerrs(i, errUp, errLow, errParab, gcor);
        return isValid;
    }

    @Override
    public int MinosStatus() {
        return fMinosStatus;
    }

    protected void DoClear() {
        fMinuit.mncler();
        final double[] val = {3};
        final int[] inseed = {12345};
        fMinuit.mnrn15(val, inseed);
        fUsed = false;
        fgUsed = false;
    }

    protected void DoReleaseFixParameter(int ivar) {
        if (fMinuit == null) return;
        if (fMinuit.GetNumFixedPars() == 0) return;
        if (ivar >= fMinuit.GetNumPars()) return;
        for (int i = 0; i < fMinuit.fNpfix; ++i) {
            if (fMinuit.fIpfix[i] == ivar + 1) {
                fMinuit.Release(ivar);
                return;
            }
        }
    }

    @Override
    public void PrintResults() {
        if (fMinuit == null) return;
        if (PrintLevel() > 2) fMinuit.mnprin(4, fMinuit.fAmin);
        else fMinuit.mnprin(3, fMinuit.fAmin);
    }

    /** SET NOWarnings (or SET WARnings). */
    public void SuppressMinuitWarnings(boolean nowarn) {
        final double[] arglist = {0};
        final int[] ierr = {0};
        if (nowarn) fMinuit.mnexcm("SET NOW", arglist, 0, ierr);
        else fMinuit.mnexcm("SET WAR", arglist, 0, ierr);
    }

    public void SuppressMinuitWarnings() {
        SuppressMinuitWarnings(true);
    }

    @Override
    public boolean Contour(int ipar, int jpar, int[] npoints, double[] x, double[] y) {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::Contour", " invalid TMinuit instance");
            return false;
        }
        final double[] arglist = new double[1];
        final int[] ierr = {0};
        arglist[0] = ErrorDef();
        fMinuit.mnexcm("SET Err", arglist, 1, ierr);
        arglist[0] = PrintLevel() - 1;
        fMinuit.mnexcm("SET PRINT", arglist, 1, ierr);
        if (PrintLevel() == 0) fMinuit.mnexcm("SET NOW", arglist, 0, ierr);
        if (Precision() > 0) {
            arglist[0] = Precision();
            fMinuit.mnexcm("SET EPS", arglist, 1, ierr);
        }
        if (npoints[0] < 4) {
            TError.Error("TMinuitMinimizer::Contour", "Cannot make contour with so few points");
            return false;
        }
        final int[] npfound = {0};
        fMinuit.mncont(ipar, jpar, npoints[0], x, y, npfound);
        if (npfound[0] < 4) {
            TError.Error("TMinuitMinimizer::Contour", "Cannot find more than 4 points");
            return false;
        }
        if (npfound[0] != npoints[0]) {
            TError.Warning("TMinuitMinimizer::Contour", "Returning only %d points ", npfound[0]);
            npoints[0] = npfound[0];
        }
        return true;
    }

    @Override
    public boolean Scan(int ipar, int[] nstep, double[] x, double[] y, double xmin, double xmax) {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::Scan", " invalid TMinuit instance");
            return false;
        }
        if (xmin >= xmax && ipar < fMinuit.GetNumPars()) {
            final double[] val = {0}, err = {0}, xlow = {0}, xup = {0};
            final String[] name = {""};
            final int[] iuint = {0};
            fMinuit.mnpout(ipar, name, val, err, xlow, xup, iuint);
            if (iuint[0] > 0 && err[0] > 0) {
                xmin = val[0] - 2. * err[0];
                xmax = val[0] + 2 * err[0];
            }
        }
        final double[] arglist = new double[4];
        final int[] ierr = {0};
        arglist[0] = PrintLevel() - 1;
        fMinuit.mnexcm("SET PRINT", arglist, 1, ierr);
        if (PrintLevel() == 0) fMinuit.mnexcm("SET NOW", arglist, 0, ierr);
        if (Precision() > 0) {
            arglist[0] = Precision();
            fMinuit.mnexcm("SET EPS", arglist, 1, ierr);
        }
        if (nstep[0] == 0) return false;
        arglist[0] = ipar + 1;
        arglist[1] = nstep[0];
        int nargs = 2;
        if (xmax > xmin) {
            arglist[2] = xmin;
            arglist[3] = xmax;
            nargs = 4;
        }
        fMinuit.mnexcm("SCAN", arglist, nargs, ierr);
        if (ierr[0] != 0) {
            TError.Error("TMinuitMinimizer::Scan", " Error executing command SCAN");
            return false;
        }
        if (!(fMinuit.GetPlot() instanceof TMinuitGraph gr)) {
            TError.Error("TMinuitMinimizer::Scan", " Error in returned graph object");
            return false;
        }
        nstep[0] = Math.min(gr.GetN(), nstep[0]);
        System.arraycopy(gr.GetX(), 0, x, 0, nstep[0]);
        System.arraycopy(gr.GetY(), 0, y, 0, nstep[0]);
        nstep[0] = gr.GetN();
        return true;
    }

    @Override
    public boolean Hesse() {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::Hesse", "invalid TMinuit pointer. Need to call first SetFunction and SetVariable");
            return false;
        }
        final double[] arglist = new double[10];
        final int[] ierr = {0};
        arglist[0] = ErrorDef();
        fMinuit.mnexcm("SET ERR", arglist, 1, ierr);
        final int printlevel = PrintLevel();
        arglist[0] = printlevel - 1;
        fMinuit.mnexcm("SET PRINT", arglist, 1, ierr);
        if (printlevel == 0) fMinuit.mnexcm("SET NOW", arglist, 0, ierr);
        if (Precision() > 0) {
            arglist[0] = Precision();
            fMinuit.mnexcm("SET EPS", arglist, 1, ierr);
        }
        arglist[0] = MaxFunctionCalls();
        fMinuit.mnexcm("HESSE", arglist, 1, ierr);
        fStatus += 100 * ierr[0];
        if (ierr[0] != 0) return false;
        RetrieveParams();
        RetrieveErrorMatrix();
        return true;
    }

    /** SET DEBUG 1 or SET NODEBUG 1. */
    public boolean SetDebug(boolean on) {
        if (fMinuit == null) {
            TError.Error("TMinuitMinimizer::SetDebug", "invalid TMinuit pointer. Need to call first SetFunction and SetVariable");
            return false;
        }
        final int[] ierr = {0};
        final double[] arglist = {1};
        if (on) fMinuit.mnexcm("SET DEBUG", arglist, 1, ierr);
        else fMinuit.mnexcm("SET NODEBUG", arglist, 1, ierr);
        return (ierr[0] == 0);
    }
}
