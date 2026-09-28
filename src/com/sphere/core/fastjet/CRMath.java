package com.sphere.core.fastjet;

/**
 * Correctly rounded double functions, taken from the double-double ones.
 *
 * {@link Math} only promises one or two units in the last place, and its
 * answers need not be those of the C library FastJet was built against. A
 * result computed to 106 bits and rounded once is the double nearest the
 * true value, which is what glibc returns in all but the rarest cases, so the
 * {@link Precision#DOUBLE} mode uses these to stay in step with the C++.
 */
public final class CRMath {

    private CRMath() {
    }

    public static double log(double x) {
        if (!(x > 0.0) || Double.isInfinite(x)) {
            return Math.log(x);
        }
        return DD.log(x).hi;
    }

    public static double exp(double x) {
        if (Double.isNaN(x) || x <= -745.2 || x >= 709.8) {
            return Math.exp(x);
        }
        return new DD(x).exp().hi;
    }

    public static double atan2(double y, double x) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)
                || (y == 0.0 && x == 0.0)) {
            return Math.atan2(y, x);
        }
        if (y == 0.0) {
            // Keep the sign of zero: atan2(-0, -1) is -pi in C as in Java.
            return x > 0 ? y : Math.copySign(Math.PI, y);
        }
        return DD.atan2(y, x).hi;
    }

    public static double atan(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0.0) {
            return Math.atan(x);
        }
        return new DD(x).atan().hi;
    }

    public static double sin(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0.0 || Math.abs(x) > 1e6) {
            return Math.sin(x);
        }
        return new DD(x).sin().hi;
    }

    public static double cos(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x) > 1e6) {
            return Math.cos(x);
        }
        return new DD(x).cos().hi;
    }

    public static double tan(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0.0 || Math.abs(x) > 1e6) {
            return Math.tan(x);
        }
        return new DD(x).tan().hi;
    }

    public static double acos(double x) {
        if (Double.isNaN(x) || Math.abs(x) >= 1.0) {
            return Math.acos(x);
        }
        return new DD(x).acos().hi;
    }

    public static double asin(double x) {
        if (Double.isNaN(x) || Math.abs(x) >= 1.0 || x == 0.0) {
            return Math.asin(x);
        }
        return new DD(x).asin().hi;
    }

    public static double sinh(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0.0 || Math.abs(x) > 700.0) {
            return Math.sinh(x);
        }
        return new DD(x).sinh().hi;
    }

    public static double cosh(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x) > 700.0) {
            return Math.cosh(x);
        }
        return new DD(x).cosh().hi;
    }

    public static double pow(double x, double p) {
        if (p == 1.0) return x;
        if (p == 0.0) return 1.0;
        if (!(x > 0.0) || Double.isInfinite(x) || Double.isNaN(p) || Double.isInfinite(p)) {
            return Math.pow(x, p);
        }
        final DD r = new DD(x).pow(p);
        if (Double.isInfinite(r.hi) || Double.isNaN(r.hi) || r.hi == 0.0
                || Math.abs(r.hi) < Double.MIN_NORMAL * 0x1p60) {
            return Math.pow(x, p);
        }
        return r.hi;
    }
}
