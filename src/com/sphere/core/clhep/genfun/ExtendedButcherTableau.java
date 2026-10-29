package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLHEP's Genfun::ExtendedButcherTableau (ExtendedButcherTableau.hh, .icc): the tableau of an embedded
 * Runge-Kutta pair, b and bHat giving two estimates whose difference is the error, and the classic pairs
 * as nested classes. (CLHEP names its Cash-Karp tableau after Fehlberg's, kept here.)
 */
public class ExtendedButcherTableau {

    private final List<double[]> _A = new ArrayList<>();
    private double[] _b = new double[0];
    private double[] _bHat = new double[0];
    private double[] _c = new double[0];
    private final String _name;
    private final int _order;
    private final int _orderHat;

    public ExtendedButcherTableau(String name, int order, int orderHat) {
        _name = name;
        _order = order;
        _orderHat = orderHat;
    }

    public ExtendedButcherTableau(ExtendedButcherTableau right) {
        for (double[] row : right._A) _A.add(row.clone());
        _b = right._b.clone();
        _bHat = right._bHat.clone();
        _c = right._c.clone();
        _name = right._name;
        _order = right._order;
        _orderHat = right._orderHat;
    }

    public String name() {
        return _name;
    }

    public int order() {
        return _order;
    }

    public int orderHat() {
        return _orderHat;
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

    public double bHat(int i) {
        return _bHat[i];
    }

    public double c(int i) {
        return _c[i];
    }

    /** A(i, j) = value, growing A as CLHEP does. */
    public void setA(int i, int j, double value) {
        ButcherTableau.grow(_A, i, j);
        _A.get(i)[j] = value;
    }

    public void setB(int i, double value) {
        if (i >= _b.length) _b = Arrays.copyOf(_b, i + 1);
        _b[i] = value;
    }

    public void setBHat(int i, double value) {
        if (i >= _bHat.length) _bHat = Arrays.copyOf(_bHat, i + 1);
        _bHat[i] = value;
    }

    public void setC(int i, double value) {
        if (i >= _c.length) _c = Arrays.copyOf(_c, i + 1);
        _c[i] = value;
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
        o.put("bHat").put("\n");
        for (int j = 0; j < nSteps(); j++) o.put(bHat(j)).put(" ");
        o.put("\n");
        return o;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }

    public static class HeunEulerXtTableau extends ExtendedButcherTableau {
        public HeunEulerXtTableau() {
            super("Heun-Euler Embedded Method", 2, 1);
            setA(1, 0, 1);
            setC(0, 0);
            setC(1, 1);
            setB(0, 1 / 2.0);
            setB(1, 1 / 2.0);
            setBHat(0, 1);
            setBHat(1, 0);
        }
    }

    public static class BogackiShampineXtTableau extends ExtendedButcherTableau {
        public BogackiShampineXtTableau() {
            super("Bogacki-Shampine Embedded Method", 3, 2);
            setA(1, 0, 1 / 2.0);
            setA(2, 0, 0);
            setA(2, 1, 3 / 4.0);
            setA(3, 0, 2 / 9.0);
            setA(3, 1, 1 / 3.0);
            setA(3, 2, 4 / 9.0);
            setC(0, 0);
            setC(1, 1 / 2.);
            setC(2, 3 / 4.);
            setC(3, 1.0);
            setB(0, 2 / 9.0);
            setB(1, 1 / 3.0);
            setB(2, 4 / 9.0);
            setB(3, 0);
            setBHat(0, 7 / 24.0);
            setBHat(1, 1 / 4.0);
            setBHat(2, 1 / 3.0);
            setBHat(3, 1 / 8.0);
        }
    }

    public static class FehlbergRK45F2XtTableau extends ExtendedButcherTableau {
        public FehlbergRK45F2XtTableau() {
            super("FehlbergRK4(5) method formula 2", 4, 5);
            setA(1, 0, 1 / 4.);
            setA(2, 0, 3 / 32.);
            setA(2, 1, 9 / 32.);
            setA(3, 0, 1932 / 2197.);
            setA(3, 1, -7020 / 2197.);
            setA(3, 2, 7296 / 2197.);
            setA(4, 0, 439 / 216.);
            setA(4, 1, -8.);
            setA(4, 2, 3680 / 513.);
            setA(4, 3, -845 / 4104.);
            setA(5, 0, -8 / 27.);
            setA(5, 1, 2.);
            setA(5, 2, -3544 / 2565.);
            setA(5, 3, 1859 / 4104.);
            setA(5, 4, -11 / 40.);
            setC(0, 0);
            setC(1, 1 / 4.);
            setC(2, 3 / 8.);
            setC(3, 12 / 13.);
            setC(4, 1);
            setC(5, 1 / 2.);
            setB(0, 25 / 216.);
            setB(1, 0);
            setB(2, 1408 / 2565.);
            setB(3, 2197 / 4104.);
            setB(4, -1 / 5.);
            setB(5, 0);
            setBHat(0, 16 / 135.);
            setBHat(1, 0);
            setBHat(2, 6656 / 12825.);
            setBHat(3, 28561 / 56430.);
            setBHat(4, -9 / 50.);
            setBHat(5, 2 / 55.);
        }
    }

    public static class CashKarpXtTableau extends ExtendedButcherTableau {
        public CashKarpXtTableau() {
            super("FehlbergRK4(5) method formula 2", 4, 5);
            setA(1, 0, 1 / 5.);
            setA(2, 0, 3 / 40.);
            setA(2, 1, 9 / 40.);
            setA(3, 0, 3 / 10.);
            setA(3, 1, -9 / 10.);
            setA(3, 2, 6 / 5.);
            setA(4, 0, -11 / 54.);
            setA(4, 1, 5 / 2.);
            setA(4, 2, -70 / 27.);
            setA(4, 3, 35 / 27.);
            setA(5, 0, 1631 / 55296.);
            setA(5, 1, 175 / 512.);
            setA(5, 2, 575 / 13824.);
            setA(5, 3, 44275 / 110592.);
            setA(5, 4, 253 / 4096.);
            setC(0, 0);
            setC(1, 1 / 5.0);
            setC(2, 3 / 10.);
            setC(3, 3 / 5.);
            setC(4, 1);
            setC(5, 7 / 8.);
            setB(0, 37 / 378.);
            setB(1, 0);
            setB(2, 250 / 621.);
            setB(3, 125 / 594.);
            setB(4, 0);
            setB(5, 512 / 1771.);
            setBHat(0, 2825 / 27648.);
            setBHat(1, 0);
            setBHat(2, 18575 / 48384.);
            setBHat(3, 13525 / 55296.);
            setBHat(4, 277 / 14336.);
            setBHat(5, 1 / 4.);
        }
    }
}
