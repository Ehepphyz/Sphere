package com.sphere.core.mathcore;

import java.io.PrintStream;

/**
 * ROOT::Math::IOptions: the options of a numerical algorithm, by name, of
 * three kinds (real, integer, named). The setters of this base report an
 * error; {@link GenAlgoOptions} keeps the values.
 */
public abstract class IOptions {

    /** A copy of the options. */
    public abstract IOptions Clone();

    public void SetValue(String name, double val) {
        SetRealValue(name, val);
    }

    public void SetValue(String name, int val) {
        SetIntValue(name, val);
    }

    public void SetValue(String name, String val) {
        SetNamedValue(name, val);
    }

    /** The real option, 0 (with an error) when there is none. */
    public double RValue(String name) {
        final double[] val = {0};
        if (!GetRealValue(name, val)) MathError.errorMsgVal("IOptions::RValue", " return 0 - real option not found", "name", name);
        return val[0];
    }

    public int IValue(String name) {
        final int[] val = {0};
        if (!GetIntValue(name, val)) MathError.errorMsgVal("IOptions::IValue", " return 0 - integer option not found", "name", name);
        return val[0];
    }

    public String NamedValue(String name) {
        final String[] val = {""};
        if (!GetNamedValue(name, val)) {
            MathError.errorMsgVal("IOptions::NamedValue", " return empty string - named option not found", "name", name);
        }
        return val[0];
    }

    public boolean GetRealValue(String name, double[] val) {
        return false;
    }

    public boolean GetIntValue(String name, int[] val) {
        return false;
    }

    public boolean GetNamedValue(String name, String[] val) {
        return false;
    }

    public void SetRealValue(String name, double val) {
        MathError.errorMsg("IOptions::SetRealValue", "Invalid setter method called");
    }

    public void SetIntValue(String name, int val) {
        MathError.errorMsg("IOptions::SetIntValue", "Invalid setter method called");
    }

    public void SetNamedValue(String name, String val) {
        MathError.errorMsg("IOptions::SetNamedValue", "Invalid setter method called");
    }

    public void Print(PrintStream os) {
        MathError.infoMsg("IOptions::Print", "it is not implemented");
    }

    public void Print() {
        Print(System.out);
    }
}
