package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLHEP's Genfun::ButcherTableau (ButcherTableau.hh, .icc): the coefficients A, b and c of an explicit
 * Runge-Kutta method, and the classic methods as nested classes. Setting A(i, j) grows the matrix as
 * CLHEP's non-const accessor does (rows resized, so wider rows are cut back).
 */
public class ButcherTableau {

    private final List<double[]> _A = new ArrayList<>();
    private double[] _b = new double[0];
    private double[] _c = new double[0];
    private final String _name;
    private final int _order;

    public ButcherTableau(String name, int order) {
        _name = name;
        _order = order;
    }

    public ButcherTableau(ButcherTableau right) {
        for (double[] row : right._A) _A.add(row.clone());
        _b = right._b.clone();
        _c = right._c.clone();
        _name = right._name;
        _order = right._order;
    }

    public String name() {
        return _name;
    }

    public int order() {
        return _order;
    }

    public int nSteps() {
        return _A.size();
    }

    public double A(int i, int j) {
        return _A.get(i)[j];
    }

    public double b(int i) {
        return _b[i];
    }

    public double c(int i) {
        return _c[i];
    }

    /** A(i, j) = value. */
    public void setA(int i, int j, double value) {
        grow(_A, i, j);
        _A.get(i)[j] = value;
    }

    public void setB(int i, double value) {
        if (i >= _b.length) _b = Arrays.copyOf(_b, i + 1);
        _b[i] = value;
    }

    public void setC(int i, double value) {
        if (i >= _c.length) _c = Arrays.copyOf(_c, i + 1);
        _c[i] = value;
    }

    /** CLHEP's growth of A on the non-const A(i, j), shared with ExtendedButcherTableau. */
    static void grow(List<double[]> a, int i, int j) {
        if (i >= a.size()) {
            final int newSize = i + 1;
            for (int ii = 0; ii < a.size(); ii++) a.set(ii, Arrays.copyOf(a.get(ii), newSize));
            for (int ii = a.size(); ii < newSize; ii++) a.add(new double[newSize]);
            if (j >= a.get(i).length) {
                final int newSize2 = j + 1;
                for (int ii = 0; ii < a.size(); ii++) a.set(ii, Arrays.copyOf(a.get(ii), newSize2));
            }
        }
    }

    /** operator&lt;&lt;. */
    public COStream print(COStream o) {
        o.put("Name ").put(name()).put(" of order ").put(order()).put("\n");
        o.put("A").put("\n");
        for (int i = 0; i < nSteps(); i++) {
            for (int j = 0; j < nSteps(); j++) o.put(A(i, j)).put(" ");
            o.put("\n");
        }
        o.put("\n");
        o.put("c").put("\n");
        for (int j = 0; j < nSteps(); j++) o.put(c(j)).put("\n");
        o.put("\n");
        o.put("b").put("\n");
        for (int j = 0; j < nSteps(); j++) o.put(b(j)).put(" ");
        o.put("\n");
        return o;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }

    public static class EulerTableau extends ButcherTableau {
        public EulerTableau() {
            super("Euler Method", 1);
            setA(0, 0, 0);
            setB(0, 1);
            setC(0, 1);
        }
    }

    public static class MidpointTableau extends ButcherTableau {
        public MidpointTableau() {
            super("Midpoint Method", 2);
            setA(1, 0, 1 / 2.0);
            setC(0, 0);
            setC(1, 1 / 2.0);
            setB(0, 0);
            setB(1, 1);
        }
    }

    public static class TrapezoidTableau extends ButcherTableau {
        public TrapezoidTableau() {
            super("Trapezoid Method", 2);
            setA(1, 0, 1);
            setC(0, 0);
            setC(1, 1);
            setB(0, 1 / 2.0);
            setB(1, 1 / 2.0);
        }
    }

    public static class RK31Tableau extends ButcherTableau {
        public RK31Tableau() {
            super("RK31 Method", 3);
            setA(1, 0, 2 / 3.0);
            setA(2, 0, 1 / 3.0);
            setA(2, 1, 1 / 3.0);
            setC(0, 0);
            setC(1, 2 / 3.0);
            setC(2, 2 / 3.0);
            setB(0, 1 / 4.0);
            setB(1, 0);
            setB(2, 3 / 4.0);
        }
    }

    public static class RK32Tableau extends ButcherTableau {
        public RK32Tableau() {
            super("RK32 Method", 3);
            setA(1, 0, 1 / 2.0);
            setA(2, 0, -1);
            setA(2, 1, 2);
            setC(0, 0);
            setC(1, 1 / 2.0);
            setC(2, 1);
            setB(0, 1 / 6.0);
            setB(1, 2 / 3.0);
            setB(2, 1 / 6.0);
        }
    }

    public static class ClassicalRungeKuttaTableau extends ButcherTableau {
        public ClassicalRungeKuttaTableau() {
            super("Classical Runge Kutta Method", 4);
            setA(1, 0, 1 / 2.0);
            setA(2, 0, 0);
            setA(2, 1, 1 / 2.0);
            setA(3, 0, 0);
            setA(3, 1, 0);
            setA(3, 2, 1);
            setC(0, 0);
            setC(1, 1 / 2.0);
            setC(2, 1 / 2.0);
            setC(3, 1);
            setB(0, 1 / 6.0);
            setB(1, 1 / 3.0);
            setB(2, 1 / 3.0);
            setB(3, 1 / 6.0);
        }
    }

    public static class ThreeEighthsRuleTableau extends ButcherTableau {
        public ThreeEighthsRuleTableau() {
            super("Three-Eighths Rule Method", 4);
            setA(1, 0, 1 / 3.0);
            setA(2, 0, -1 / 3.0);
            setA(2, 1, 1);
            setA(3, 0, 1);
            setA(3, 1, -1);
            setA(3, 2, 1);
            setC(0, 0);
            setC(1, 1 / 3.0);
            setC(2, 2 / 3.0);
            setC(3, 1);
            setB(0, 1 / 8.0);
            setB(1, 3 / 8.0);
            setB(2, 3 / 8.0);
            setB(3, 1 / 8.0);
        }
    }
}
