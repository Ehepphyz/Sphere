package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.List;

/**
 * The user's parameters: declared by name with a value and an error (the
 * initial step), optionally limited, fixed or constant; numbered in the order
 * they are added. Value semantics: the copy constructor copies.
 */
public final class MnUserParameters implements MnPrint.Printable {

    private MnUserTransformation transformation;

    public MnUserParameters() {
        transformation = new MnUserTransformation();
    }

    public MnUserParameters(double[] par, double[] err) {
        transformation = new MnUserTransformation(par, err);
    }

    public MnUserParameters(MnUserParameters p) {
        transformation = new MnUserTransformation(p.transformation);
    }

    public MnUserTransformation trafo() {
        return transformation;
    }

    public int variableParameters() {
        return transformation.variableParameters();
    }

    public List<MinuitParameter> parameters() {
        return transformation.parameters();
    }

    public double[] params() {
        return transformation.params();
    }

    public double[] errors() {
        return transformation.errors();
    }

    public MinuitParameter parameter(int n) {
        return transformation.parameter(n);
    }

    public boolean add(String name, double val, double err) {
        return transformation.add(name, val, err);
    }

    public boolean add(String name, double val, double err, double low, double up) {
        return transformation.add(name, val, err, low, up);
    }

    public boolean add(String name, double val) {
        return transformation.add(name, val);
    }

    public void fix(int n) {
        transformation.fix(n);
    }

    public void release(int n) {
        transformation.release(n);
    }

    public void removeLimits(int n) {
        transformation.removeLimits(n);
    }

    public void setValue(int n, double val) {
        transformation.setValue(n, val);
    }

    public void setError(int n, double err) {
        transformation.setError(n, err);
    }

    public void setLimits(int n, double low, double up) {
        transformation.setLimits(n, low, up);
    }

    public void setUpperLimit(int n, double up) {
        transformation.setUpperLimit(n, up);
    }

    public void setLowerLimit(int n, double low) {
        transformation.setLowerLimit(n, low);
    }

    public void setName(int n, String name) {
        transformation.setName(n, name);
    }

    public double value(int n) {
        return transformation.value(n);
    }

    public double error(int n) {
        return transformation.error(n);
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

    public void setError(String name, double err) {
        setError(index(name), err);
    }

    public void setLimits(String name, double low, double up) {
        setLimits(index(name), low, up);
    }

    public void setUpperLimit(String name, double up) {
        transformation.setUpperLimit(index(name), up);
    }

    public void setLowerLimit(String name, double low) {
        transformation.setLowerLimit(index(name), low);
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
        return transformation.index(name);
    }

    public String name(int n) {
        return transformation.name(n);
    }

    public MnMachinePrecision precision() {
        return transformation.precision();
    }

    public void setPrecision(double eps) {
        transformation.setPrecision(eps);
    }

    /** operator&lt;&lt;: the table of parameters. */
    @Override
    public void print(COStream os) {
        os.put("\n  Pos |    Name    |  type   |      Value       |    Error +/-");
        final int pr = os.precision();
        final double eps2 = precision().eps2();
        for (MinuitParameter p : parameters()) {
            os.put("\n").setw(5).put(p.number()).put(" | ").setw(10).put(p.name()).put(" |");
            if (p.isConst()) os.put("  const  |");
            else if (p.isFixed()) os.put("  fixed  |");
            else if (p.hasLimits()) os.put(" limited |");
            else os.put("  free   |");
            os.precision(MnMatrix.PRECISION);
            os.width(MnMatrix.WIDTH);
            os.put(p.value()).put(" | ").setw(12);
            if (p.error() > 0) {
                os.put(p.error());
                if (p.hasLimits()) {
                    if (Math.abs(p.value() - p.lowerLimit()) < eps2) {
                        os.put(" (at lower limit)");
                    } else if (Math.abs(p.value() - p.upperLimit()) < eps2) {
                        os.put(" (at upper limit)");
                    }
                }
            }
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
