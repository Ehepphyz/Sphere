package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/**
 * CLHEP's Genfun::PtRelFcn (PtRelFcn.hh, .cc): a model of the pT of a lepton relative to its jet, a
 * generalized gamma plus a truncated Gaussian, at least 1e-10.
 */
public class PtRelFcn extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;
    private static final double FLT_MIN = Float.MIN_NORMAL;

    private final Parameter _p0;
    private final Parameter _p1;
    private final Parameter _p2;
    private final Parameter _p3;
    private final Parameter _p4;
    private final Parameter _p5;
    private final LogGamma _logGamma = new LogGamma();
    private final Erf _erf = new Erf();

    public PtRelFcn() {
        _p0 = new Parameter("P0", 0, 0, 1);
        _p1 = new Parameter("P1", 0, 0, 2);
        _p2 = new Parameter("P2", 1, 0, 10);
        _p3 = new Parameter("P3", 0, 0, 10);
        _p4 = new Parameter("P4", 1.0, 0.1, 5.0);
        _p5 = new Parameter("P5", 0.0, 0, 50);
    }

    public PtRelFcn(PtRelFcn right) {
        _p0 = new Parameter(right._p0);
        _p1 = new Parameter(right._p1);
        _p2 = new Parameter(right._p2);
        _p3 = new Parameter(right._p3);
        _p4 = new Parameter(right._p4);
        _p5 = new Parameter(right._p5);
    }

    @Override
    public PtRelFcn copy() {
        return new PtRelFcn(this);
    }

    @Override
    public double call(double x) {
        double p0 = _p0.getValue();
        final double p1 = _p1.getValue();
        final double p2 = _p2.getValue();
        final double p3 = _p3.getValue();
        final double p4 = _p4.getValue();
        final double p5 = _p5.getValue();

        if (p0 < 0.0) p0 = FLT_MIN;
        if (p0 > 1.0) p0 = 1.0 - FLT_MIN;
        if (x <= 0.0) return 1.0E-10;

        final double n = (1 + p1) / p3;
        final double a = (1 / p3) * CMath.pow(p2, -n);
        final double norm = 1.0 / (a * CMath.exp(_logGamma.call(n)));

        final double s2 = Math.sqrt(2.0);
        final double retVal = norm * p0 * CMath.pow(x, p1) * CMath.exp(-p2 * CMath.pow(x, p3))
            + (2.0 / (1 + _erf.call(p5 / p4 / s2)) * (1.0 - p0) / (Math.sqrt(2 * M_PI) * p4))
            * CMath.exp(-(x - p5) * (x - p5) / (2.0 * p4 * p4));
        return (retVal < 1.0E-10) ? 1.0E-10 : retVal;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter P0() {
        return _p0;
    }

    public Parameter P1() {
        return _p1;
    }

    public Parameter P2() {
        return _p2;
    }

    public Parameter P3() {
        return _p3;
    }

    public Parameter P4() {
        return _p4;
    }

    public Parameter P5() {
        return _p5;
    }
}
