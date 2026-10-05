package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;

/**
 * The parameters as the user declared them (external) and the variables the
 * minimizer moves (internal): the free and limited parameters, in order, a
 * limited one through {@link MnParameterTransformation}. Value semantics:
 * {@link #MnUserTransformation(MnUserTransformation)} copies.
 */
public final class MnUserTransformation {

    private final MnMachinePrecision precision;
    private final List<MinuitParameter> parameters;
    private final List<Integer> extOfInt;
    private final List<Double> cache;

    public MnUserTransformation() {
        precision = new MnMachinePrecision();
        parameters = new ArrayList<>();
        extOfInt = new ArrayList<>();
        cache = new ArrayList<>();
    }

    /** Free parameters p0, p1... with the values and errors given. */
    public MnUserTransformation(double[] par, double[] err) {
        this();
        for (int i = 0; i < par.length; i++) {
            add("p" + i, par[i], err[i]);
        }
    }

    public MnUserTransformation(MnUserTransformation t) {
        precision = new MnMachinePrecision(t.precision);
        parameters = new ArrayList<>(t.parameters.size());
        for (MinuitParameter p : t.parameters) parameters.add(new MinuitParameter(p));
        extOfInt = new ArrayList<>(t.extOfInt);
        cache = new ArrayList<>(t.cache);
    }

    /** The external values for internal ones: the initial values, the variable ones replaced. */
    public double[] transform(LAVector pstates) {
        final int n = pstates.size();
        final double[] pcache = initialParValues();
        for (int i = 0; i < n; i++) {
            final int e = extOfInt.get(i);
            if (parameters.get(e).hasLimits()) {
                pcache[e] = int2ext(i, pstates.get(i));
            } else {
                pcache[e] = pstates.get(i);
            }
        }
        return pcache;
    }

    public double int2ext(int i, double val) {
        final MinuitParameter p = parameters.get(extOfInt.get(i));
        if (p.hasLimits()) {
            if (p.hasUpperLimit() && p.hasLowerLimit()) {
                return MnParameterTransformation.sinInt2ext(val, p.upperLimit(), p.lowerLimit());
            } else if (p.hasUpperLimit() && !p.hasLowerLimit()) {
                return MnParameterTransformation.upInt2ext(val, p.upperLimit());
            } else {
                return MnParameterTransformation.lowInt2ext(val, p.lowerLimit());
            }
        }
        return val;
    }

    /** The external error for an internal error, half the spread of the two transformed ends. */
    public double int2extError(int i, double val, double err) {
        double dx = err;
        final MinuitParameter p = parameters.get(extOfInt.get(i));
        if (p.hasLimits()) {
            final double ui = int2ext(i, val);
            double du1 = int2ext(i, val + dx) - ui;
            final double du2 = int2ext(i, val - dx) - ui;
            if (p.hasUpperLimit() && p.hasLowerLimit()) {
                if (dx > 1.) du1 = p.upperLimit() - p.lowerLimit();
                dx = 0.5 * (Math.abs(du1) + Math.abs(du2));
            } else {
                dx = 0.5 * (Math.abs(du1) + Math.abs(du2));
            }
        }
        return dx;
    }

    public MnUserCovariance int2extCovariance(LAVector vec, LASymMatrix cov) {
        final MnUserCovariance result = new MnUserCovariance(cov.nrow());
        for (int i = 0; i < vec.size(); i++) {
            double dxdi = 1.;
            if (parameters.get(extOfInt.get(i)).hasLimits()) dxdi = dInt2Ext(i, vec.get(i));
            for (int j = i; j < vec.size(); j++) {
                double dxdj = 1.;
                if (parameters.get(extOfInt.get(j)).hasLimits()) dxdj = dInt2Ext(j, vec.get(j));
                result.set(i, j, dxdi * cov.get(i, j) * dxdj);
            }
        }
        return result;
    }

    public MnUserCovariance ext2intCovariance(LAVector vec, LASymMatrix cov) {
        final MnUserCovariance result = new MnUserCovariance(cov.nrow());
        for (int i = 0; i < vec.size(); i++) {
            double dxdi = 1.;
            if (parameters.get(extOfInt.get(i)).hasLimits()) dxdi = dExt2Int(i, vec.get(i));
            for (int j = i; j < vec.size(); j++) {
                double dxdj = 1.;
                if (parameters.get(extOfInt.get(j)).hasLimits()) dxdj = dExt2Int(j, vec.get(j));
                result.set(i, j, dxdi * cov.get(i, j) * dxdj);
            }
        }
        return result;
    }

    /** The internal value of external parameter i (an external index, as in the C++). */
    public double ext2int(int i, double val) {
        final MinuitParameter p = parameters.get(i);
        if (p.hasLimits()) {
            if (p.hasUpperLimit() && p.hasLowerLimit()) {
                return MnParameterTransformation.sinExt2int(val, p.upperLimit(), p.lowerLimit(), precision);
            } else if (p.hasUpperLimit() && !p.hasLowerLimit()) {
                return MnParameterTransformation.upExt2int(val, p.upperLimit());
            } else {
                return MnParameterTransformation.lowExt2int(val, p.lowerLimit());
            }
        }
        return val;
    }

    public double dInt2Ext(int i, double val) {
        double dd = 1.;
        final MinuitParameter p = parameters.get(extOfInt.get(i));
        if (p.hasLimits()) {
            if (p.hasUpperLimit() && p.hasLowerLimit()) {
                dd = MnParameterTransformation.sinDInt2Ext(val, p.upperLimit(), p.lowerLimit());
            } else if (p.hasUpperLimit() && !p.hasLowerLimit()) {
                dd = MnParameterTransformation.upDInt2Ext(val);
            } else {
                dd = MnParameterTransformation.lowDInt2Ext(val);
            }
        }
        return dd;
    }

    public double d2Int2Ext(int i, double val) {
        double dd = 0.;
        final MinuitParameter p = parameters.get(extOfInt.get(i));
        if (p.hasLimits()) {
            if (p.hasUpperLimit() && p.hasLowerLimit()) {
                dd = MnParameterTransformation.sinD2Int2Ext(val, p.upperLimit(), p.lowerLimit());
            } else if (p.hasUpperLimit() && !p.hasLowerLimit()) {
                dd = MnParameterTransformation.upD2Int2Ext(val);
            } else {
                dd = MnParameterTransformation.lowD2Int2Ext(val);
            }
        }
        return dd;
    }

    public double dExt2Int(int i, double val) {
        double dd = 1.;
        final MinuitParameter p = parameters.get(extOfInt.get(i));
        if (p.hasLimits()) {
            if (p.hasUpperLimit() && p.hasLowerLimit()) {
                dd = MnParameterTransformation.sinDExt2Int(val, p.upperLimit(), p.lowerLimit());
            } else if (p.hasUpperLimit() && !p.hasLowerLimit()) {
                dd = MnParameterTransformation.upDExt2Int(val, p.upperLimit());
            } else {
                dd = MnParameterTransformation.lowDExt2Int(val, p.lowerLimit());
            }
        }
        return dd;
    }

    /** The internal index of a variable external parameter. */
    public int intOfExt(int ext) {
        if (ext >= parameters.size()) throw new IndexOutOfBoundsException("no parameter " + ext);
        final int i = extOfInt.indexOf(ext);
        if (i < 0) throw new IllegalArgumentException("parameter " + ext + " (" + parameters.get(ext).name() + ") is not variable");
        return i;
    }

    public int extOfInt(int internal) {
        return extOfInt.get(internal);
    }

    public List<MinuitParameter> parameters() {
        return parameters;
    }

    public int variableParameters() {
        return extOfInt.size();
    }

    /** The values the parameters were declared or last set with. */
    public double[] initialParValues() {
        final double[] v = new double[cache.size()];
        for (int i = 0; i < v.length; i++) v[i] = cache.get(i);
        return v;
    }

    public MnMachinePrecision precision() {
        return precision;
    }

    public void setPrecision(double eps) {
        precision.setPrecision(eps);
    }

    public double[] params() {
        final double[] r = new double[parameters.size()];
        for (int i = 0; i < r.length; i++) r[i] = parameters.get(i).value();
        return r;
    }

    public double[] errors() {
        final double[] r = new double[parameters.size()];
        for (int i = 0; i < r.length; i++) r[i] = parameters.get(i).error();
        return r;
    }

    public MinuitParameter parameter(int n) {
        return parameters.get(n);
    }

    private int find(String name) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    public boolean add(String name, double val, double err) {
        if (find(name) >= 0) return false;
        extOfInt.add(parameters.size());
        cache.add(val);
        parameters.add(new MinuitParameter(parameters.size(), name, val, err));
        return true;
    }

    public boolean add(String name, double val, double err, double low, double up) {
        if (find(name) >= 0) return false;
        extOfInt.add(parameters.size());
        cache.add(val);
        parameters.add(new MinuitParameter(parameters.size(), name, val, err, low, up));
        return true;
    }

    public boolean add(String name, double val) {
        if (find(name) >= 0) return false;
        cache.add(val);
        parameters.add(new MinuitParameter(parameters.size(), name, val));
        return true;
    }

    public void fix(int n) {
        final int i = extOfInt.indexOf(n);
        if (i >= 0) extOfInt.remove(i);
        parameters.get(n).fix();
    }

    public void release(int n) {
        if (!extOfInt.contains(n)) {
            extOfInt.add(n);
            extOfInt.sort(null);
        }
        parameters.get(n).release();
    }

    public void setValue(int n, double val) {
        parameters.get(n).setValue(val);
        cache.set(n, val);
    }

    public void setError(int n, double err) {
        parameters.get(n).setError(err);
    }

    public void setLimits(int n, double low, double up) {
        if (low == up) throw new IllegalArgumentException("equal limits");
        parameters.get(n).setLimits(low, up);
    }

    public void setUpperLimit(int n, double up) {
        parameters.get(n).setUpperLimit(up);
    }

    public void setLowerLimit(int n, double lo) {
        parameters.get(n).setLowerLimit(lo);
    }

    public void removeLimits(int n) {
        parameters.get(n).removeLimits();
    }

    public void setName(int n, String name) {
        parameters.get(n).setName(name);
    }

    public double value(int n) {
        return parameters.get(n).value();
    }

    public double error(int n) {
        return parameters.get(n).error();
    }

    /** The number of the parameter called name; an exception when there is none. */
    public int index(String name) {
        final int i = find(name);
        if (i < 0) throw new IllegalArgumentException("no parameter called " + name);
        return parameters.get(i).number();
    }

    /** The number of the parameter called name, -1 when there is none. */
    public int findIndex(String name) {
        final int i = find(name);
        return i < 0 ? -1 : parameters.get(i).number();
    }

    public String name(int n) {
        return parameters.get(n).name();
    }
}
