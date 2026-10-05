package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.List;

/**
 * The parameters both ways, external (the user's) and internal (Minuit's),
 * with the covariance when there is one, the function value and EDM: what a
 * minimization starts from and what it gives back. Value semantics: the
 * copy constructor copies, and the C++ copies wherever it assigns one.
 */
public final class MnUserParameterState implements MnPrint.Printable {

    private boolean valid;
    private boolean covarianceValid;
    private int covStatus;
    private double fval;
    private double edm;
    private int nfcn;
    private MnUserParameters parameters;
    private MnUserCovariance covariance;
    private final List<Double> intParameters;
    private MnUserCovariance intCovariance;

    /** An empty, invalid state. */
    public MnUserParameterState() {
        valid = false;
        covStatus = -1;
        parameters = new MnUserParameters();
        covariance = new MnUserCovariance();
        intParameters = new ArrayList<>();
        intCovariance = new MnUserCovariance();
    }

    /** Free parameters p0, p1... */
    public MnUserParameterState(double[] par, double[] err) {
        this();
        valid = true;
        parameters = new MnUserParameters(par, err);
        for (double p : par) intParameters.add(p);
    }

    public MnUserParameterState(MnUserParameters par) {
        this();
        valid = true;
        parameters = new MnUserParameters(par);
        for (MinuitParameter ipar : minuitParameters()) {
            if (ipar.isConst() || ipar.isFixed()) continue;
            if (ipar.hasLimits()) intParameters.add(ext2int(ipar.number(), ipar.value()));
            else intParameters.add(ipar.value());
        }
    }

    /**
     * Free parameters with a covariance; the errors are the square roots of
     * its diagonal (the C++ reads them before setting it, which an assertion
     * stops in a debug build: here the covariance given is what is read).
     */
    public MnUserParameterState(double[] par, MnUserCovariance cov) {
        this();
        valid = true;
        for (double p : par) intParameters.add(p);
        final double[] err = new double[par.length];
        for (int i = 0; i < par.length; i++) {
            if (!(cov.get(i, i) > 0.)) throw new IllegalArgumentException("covariance diagonal " + i + " not positive");
            err[i] = Math.sqrt(cov.get(i, i));
        }
        parameters = new MnUserParameters(par, err);
        addCovariance(cov);
    }

    public MnUserParameterState(MnUserParameters par, MnUserCovariance cov) {
        this(par);
        addCovariance(cov);
    }

    /** The external state of an internal minimum state: values, errors from the inverse Hessian, covariance. */
    public MnUserParameterState(MinimumState st, double up, MnUserTransformation trafo) {
        this();
        valid = st.isValid();
        covStatus = -1;
        fval = st.fval();
        edm = st.edm();
        nfcn = st.nfcn();
        for (MinuitParameter ipar : trafo.parameters()) {
            if (ipar.isConst()) {
                add(ipar.name(), ipar.value());
            } else if (ipar.isFixed()) {
                add(ipar.name(), ipar.value(), ipar.error());
                if (ipar.hasLimits()) {
                    if (ipar.hasLowerLimit() && ipar.hasUpperLimit()) setLimits(ipar.name(), ipar.lowerLimit(), ipar.upperLimit());
                    else if (ipar.hasLowerLimit() && !ipar.hasUpperLimit()) setLowerLimit(ipar.name(), ipar.lowerLimit());
                    else setUpperLimit(ipar.name(), ipar.upperLimit());
                }
                fix(ipar.name());
            } else if (ipar.hasLimits()) {
                final int i = trafo.intOfExt(ipar.number());
                final double err = st.error().isValid() ? Math.sqrt(2. * up * st.error().invHessian().get(i, i))
                    : st.parameters().dirin().get(i);
                add(ipar.name(), trafo.int2ext(i, st.vec().get(i)), trafo.int2extError(i, st.vec().get(i), err));
                if (ipar.hasLowerLimit() && ipar.hasUpperLimit()) setLimits(ipar.name(), ipar.lowerLimit(), ipar.upperLimit());
                else if (ipar.hasLowerLimit() && !ipar.hasUpperLimit()) setLowerLimit(ipar.name(), ipar.lowerLimit());
                else setUpperLimit(ipar.name(), ipar.upperLimit());
            } else {
                final int i = trafo.intOfExt(ipar.number());
                final double err = st.error().isValid() ? Math.sqrt(2. * up * st.error().invHessian().get(i, i))
                    : st.parameters().dirin().get(i);
                add(ipar.name(), st.vec().get(i), err);
            }
        }
        covarianceValid = st.error().isValid();
        covStatus = -1;
        if (st.error().isAvailable()) covStatus = 0;
        if (covarianceValid) {
            covariance = trafo.int2extCovariance(st.vec(), st.error().invHessian());
            final LASymMatrix ih = st.error().invHessian();
            intCovariance = new MnUserCovariance(ih.data, ih.nrow());
            covariance.scale(2. * up);
            if (!st.error().isNotPosDef()) covStatus = 1;
        }
        if (st.error().isMadePosDef()) covStatus = 2;
        if (st.error().isAccurate()) covStatus = 3;
    }

    public MnUserParameterState(MnUserParameterState s) {
        valid = s.valid;
        covarianceValid = s.covarianceValid;
        covStatus = s.covStatus;
        fval = s.fval;
        edm = s.edm;
        nfcn = s.nfcn;
        parameters = new MnUserParameters(s.parameters);
        covariance = new MnUserCovariance(s.covariance);
        intParameters = new ArrayList<>(s.intParameters);
        intCovariance = new MnUserCovariance(s.intCovariance);
    }

    public MnUserParameterState copy() {
        return new MnUserParameterState(this);
    }

    public MnUserParameters parameters() {
        return parameters;
    }

    public MnUserCovariance covariance() {
        return covariance;
    }

    public MnGlobalCorrelationCoeff globalCC() {
        if (!covarianceValid) return new MnGlobalCorrelationCoeff();
        final int n = intCovariance.nrow();
        final LASymMatrix invHessian = new LASymMatrix(n);
        for (int i = 0; i < n; ++i) {
            for (int j = 0; j <= i; ++j) invHessian.set(i, j, intCovariance.get(i, j));
        }
        return new MnGlobalCorrelationCoeff(invHessian);
    }

    /** The inverse of the covariance; its diagonal inverted when the matrix does not invert. */
    public MnUserCovariance hessian() {
        final MnPrint print = new MnPrint("MnUserParameterState::Hessian");
        final LASymMatrix mat = new LASymMatrix(covariance.nrow());
        System.arraycopy(covariance.raw(), 0, mat.data, 0, mat.data.length);
        final int ifail = MnMatrix.invert(mat);
        if (ifail != 0) {
            print.warn("Inversion failed; return diagonal matrix");
            final MnUserCovariance tmp = new MnUserCovariance(covariance.nrow());
            for (int i = 0; i < covariance.nrow(); i++) tmp.set(i, i, 1. / covariance.get(i, i));
            return tmp;
        }
        return new MnUserCovariance(mat.data, covariance.nrow());
    }

    public double[] intParameters() {
        final double[] r = new double[intParameters.size()];
        for (int i = 0; i < r.length; i++) r[i] = intParameters.get(i);
        return r;
    }

    public MnUserCovariance intCovariance() {
        return intCovariance;
    }

    public int covarianceStatus() {
        return covStatus;
    }

    public MnUserTransformation trafo() {
        return parameters.trafo();
    }

    public boolean isValid() {
        return valid;
    }

    public boolean hasCovariance() {
        return covarianceValid;
    }

    public double fval() {
        return fval;
    }

    public double edm() {
        return edm;
    }

    public int nfcn() {
        return nfcn;
    }

    /* ---- facade of MnUserParameters and MnUserTransformation -------------------- */

    public List<MinuitParameter> minuitParameters() {
        return parameters.parameters();
    }

    public double[] params() {
        return parameters.params();
    }

    public double[] errors() {
        return parameters.errors();
    }

    public MinuitParameter parameter(int i) {
        return parameters.parameter(i);
    }

    public void add(String name, double val, double err) {
        if (parameters.add(name, val, err)) {
            intParameters.add(val);
            covarianceValid = false;
            valid = true;
        } else {
            final int i = index(name);
            setValue(i, val);
            if (parameter(i).isConst()) {
                new MnPrint("MnUserParameterState::Add").warn("Cannot modify status of constant parameter", name);
                return;
            }
            setError(i, err);
            if (parameter(i).isFixed()) release(i);
        }
    }

    public void add(String name, double val, double err, double low, double up) {
        if (parameters.add(name, val, err, low, up)) {
            covarianceValid = false;
            intParameters.add(ext2int(index(name), val));
            valid = true;
        } else {
            final int i = index(name);
            setValue(i, val);
            if (parameter(i).isConst()) {
                new MnPrint("MnUserParameterState::Add").warn("Cannot modify status of constant parameter", name);
                return;
            }
            setError(i, err);
            setLimits(i, low, up);
            if (parameter(i).isFixed()) release(i);
        }
    }

    public void add(String name, double val) {
        if (parameters.add(name, val)) valid = true;
        else setValue(name, val);
    }

    /** Sets the covariance (external, of the variable parameters or more, squeezed then). */
    public void addCovariance(MnUserCovariance cov) {
        final int nrow = variableParameters();
        if (cov.nrow() < nrow) throw new IllegalArgumentException("covariance smaller than the variable parameters");
        covariance = new MnUserCovariance(cov);
        MnUserCovariance covsqueezed = new MnUserCovariance();
        if (cov.nrow() > nrow) covsqueezed = MnCovarianceSqueeze.squeeze(cov, nrow);
        else if (cov.nrow() == nrow) covsqueezed = cov;
        final LAVector params = new LAVector(nrow);
        for (int i = 0; i < nrow; i++) params.set(i, parameters.params()[i]);
        final LASymMatrix covmat = new LASymMatrix(nrow);
        for (int i = 0; i < nrow; i++) {
            for (int j = i; j < nrow; j++) covmat.set(i, j, covsqueezed.get(i, j));
        }
        intCovariance = parameters.trafo().ext2intCovariance(params, covmat);
        intCovariance.scale(0.5);
        covarianceValid = true;
        covStatus = 0;
    }

    public void fix(int e) {
        if (!parameter(e).isFixed() && !parameter(e).isConst()) {
            final int i = intOfExt(e);
            if (covarianceValid) {
                covariance = MnCovarianceSqueeze.squeeze(covariance, i);
                intCovariance = MnCovarianceSqueeze.squeeze(intCovariance, i);
            }
            intParameters.remove(i);
        }
        parameters.fix(e);
    }

    public void release(int e) {
        if (parameter(e).isConst() || !parameter(e).isFixed()) return;
        parameters.release(e);
        covarianceValid = false;
        final int i = intOfExt(e);
        if (parameter(e).hasLimits()) intParameters.add(i, ext2int(e, parameter(e).value()));
        else intParameters.add(i, parameter(e).value());
    }

    public void setValue(int e, double val) {
        parameters.setValue(e, val);
        if (!parameter(e).isFixed() && !parameter(e).isConst()) {
            final int i = intOfExt(e);
            if (parameter(e).hasLimits()) intParameters.set(i, ext2int(e, val));
            else intParameters.set(i, val);
        }
    }

    public void setError(int e, double val) {
        parameters.setError(e, val);
    }

    public void setLimits(int e, double low, double up) {
        parameters.setLimits(e, low, up);
        covarianceValid = false;
        if (!parameter(e).isFixed() && !parameter(e).isConst()) {
            final int i = intOfExt(e);
            final double v = intParameters.get(i);
            if (low < v && v < up) intParameters.set(i, ext2int(e, v));
            else if (low >= v) intParameters.set(i, ext2int(e, low + 0.1 * parameter(e).error()));
            else intParameters.set(i, ext2int(e, up - 0.1 * parameter(e).error()));
        }
    }

    public void setUpperLimit(int e, double up) {
        parameters.setUpperLimit(e, up);
        covarianceValid = false;
        if (!parameter(e).isFixed() && !parameter(e).isConst()) {
            final int i = intOfExt(e);
            final double v = intParameters.get(i);
            if (v < up) intParameters.set(i, ext2int(e, v));
            else intParameters.set(i, ext2int(e, up - 0.1 * parameter(e).error()));
        }
    }

    public void setLowerLimit(int e, double low) {
        parameters.setLowerLimit(e, low);
        covarianceValid = false;
        if (!parameter(e).isFixed() && !parameter(e).isConst()) {
            final int i = intOfExt(e);
            final double v = intParameters.get(i);
            if (low < v) intParameters.set(i, ext2int(e, v));
            else intParameters.set(i, ext2int(e, low + 0.1 * parameter(e).error()));
        }
    }

    public void removeLimits(int e) {
        parameters.removeLimits(e);
        covarianceValid = false;
        if (!parameter(e).isFixed() && !parameter(e).isConst()) intParameters.set(intOfExt(e), value(e));
    }

    public void setName(int e, String name) {
        parameters.setName(e, name);
    }

    public double value(int i) {
        return parameters.value(i);
    }

    public double error(int i) {
        return parameters.error(i);
    }

    public void fix(String name) {
        fix(index(name));
    }

    public void release(String name) {
        release(index(name));
    }

    public void setValue(String name, double val) {
        setValue(index(name), val);
    }

    public void setError(String name, double val) {
        setError(index(name), val);
    }

    public void setLimits(String name, double low, double up) {
        setLimits(index(name), low, up);
    }

    public void setUpperLimit(String name, double up) {
        setUpperLimit(index(name), up);
    }

    public void setLowerLimit(String name, double low) {
        setLowerLimit(index(name), low);
    }

    public void removeLimits(String name) {
        removeLimits(index(name));
    }

    public double value(String name) {
        return value(index(name));
    }

    public double error(String name) {
        return error(index(name));
    }

    public int index(String name) {
        return parameters.index(name);
    }

    public String name(int i) {
        return parameters.name(i);
    }

    public double int2ext(int i, double val) {
        return parameters.trafo().int2ext(i, val);
    }

    public double ext2int(int e, double val) {
        return parameters.trafo().ext2int(e, val);
    }

    public int intOfExt(int ext) {
        return parameters.trafo().intOfExt(ext);
    }

    public int extOfInt(int internal) {
        return parameters.trafo().extOfInt(internal);
    }

    public int variableParameters() {
        return parameters.trafo().variableParameters();
    }

    public MnMachinePrecision precision() {
        return parameters.precision();
    }

    public void setPrecision(double eps) {
        parameters.setPrecision(eps);
    }

    /** operator&lt;&lt;. */
    @Override
    public void print(COStream os) {
        final int pr = os.precision();
        os.precision(MnMatrix.PRECISION);
        os.put("\n  Valid         : ").put(isValid() ? "yes" : "NO").put("\n  Function calls: ").put(nfcn())
            .put("\n  Minimum value : ").put(fval()).put("\n  Edm           : ").put(edm())
            .put("\n  Parameters    : ");
        parameters().print(os);
        os.put("\n  CovarianceStatus: ").put(covarianceStatus()).put("\n  Covariance and correlation matrix: ");
        if (hasCovariance()) covariance().print(os);
        else os.put("matrix is not present or not valid");
        // the C++ computes them twice (a failed inversion warns twice)
        if (globalCC().isValid()) {
            os.put("\n  Global correlation coefficients: ");
            globalCC().print(os);
        }
        os.precision(pr);
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}
