package com.sphere.core.golem95.port;

/**
 * A complex number of the translated Golem95, with gfortran's arithmetic.
 *
 * <p>Immutable. Multiplication is gfortran's inline (ac - bd) + i(ad + bc); division is Smith's
 * algorithm as GCC expands it for Fortran (the branch on |c| < |d|, the ratio, then two divisions);
 * an integer power is libgcc's and libgfortran's repeated squaring. A product with a real number
 * multiplies both parts, as GCC does for mixed operands. So the translation computes what the
 * Fortran computes, operation for operation.
 */
public final class Cx {

    public static final Cx ZERO = new Cx(0.0, 0.0);
    public static final Cx ONE = new Cx(1.0, 0.0);
    public static final Cx I = new Cx(0.0, 1.0);

    public final double re;
    public final double im;

    public Cx(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public static Cx of(double re) {
        return new Cx(re, 0.0);
    }

    public static Cx add(Cx a, Cx b) {
        return new Cx(a.re + b.re, a.im + b.im);
    }

    public static Cx add(Cx a, double b) {
        return new Cx(a.re + b, a.im);
    }

    public static Cx add(double a, Cx b) {
        return new Cx(a + b.re, b.im);
    }

    public static Cx sub(Cx a, Cx b) {
        return new Cx(a.re - b.re, a.im - b.im);
    }

    public static Cx sub(Cx a, double b) {
        return new Cx(a.re - b, a.im);
    }

    public static Cx sub(double a, Cx b) {
        return new Cx(a - b.re, -b.im);
    }

    public static Cx mul(Cx a, Cx b) {
        return new Cx(a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re);
    }

    public static Cx mul(Cx a, double b) {
        return new Cx(a.re * b, a.im * b);
    }

    public static Cx mul(double a, Cx b) {
        return new Cx(a * b.re, a * b.im);
    }

    /** Smith's division, as GCC expands complex division for Fortran. */
    public static Cx div(Cx a, Cx b) {
        final double ar = a.re, ai = a.im, br = b.re, bi = b.im;
        double tr, ti, ratio, div;
        if (Math.abs(br) < Math.abs(bi)) {
            ratio = br / bi;
            div = (br * ratio) + bi;
            tr = (ar * ratio) + ai;
            ti = (ai * ratio) - ar;
        } else {
            ratio = bi / br;
            div = (bi * ratio) + br;
            tr = (ai * ratio) + ar;
            ti = ai - (ar * ratio);
        }
        return new Cx(tr / div, ti / div);
    }

    public static Cx div(Cx a, double b) {
        return new Cx(a.re / b, a.im / b);
    }

    public static Cx div(double a, Cx b) {
        return div(new Cx(a, 0.0), b);
    }

    public static Cx neg(Cx a) {
        return new Cx(-a.re, -a.im);
    }

    public static Cx conj(Cx a) {
        return new Cx(a.re, -a.im);
    }

    public static double abs(Cx a) {
        return Math.hypot(a.re, a.im);
    }

    public static boolean eq(Cx a, Cx b) {
        return a.re == b.re && a.im == b.im;
    }

    /** The principal square root, without overflow for large parts. */
    public static Cx sqrt(Cx z) {
        final double x = z.re, y = z.im;
        if (x == 0.0 && y == 0.0) {
            return new Cx(0.0, y);
        }
        final double t = Math.sqrt((Math.abs(x) + Math.hypot(x, y)) * 0.5);
        if (x >= 0.0) {
            return new Cx(t, y / (2.0 * t));
        }
        return new Cx(Math.abs(y) / (2.0 * t), Math.copySign(t, y));
    }

    /** The principal logarithm; near |z| = 1 the real part through log1p, as the C library does. */
    public static Cx log(Cx z) {
        final double x = z.re, y = z.im;
        final double arg = Math.atan2(y, x);
        double ax = Math.abs(x), ay = Math.abs(y);
        if (ax < ay) {
            final double t = ax;
            ax = ay;
            ay = t;
        }
        final double h = Math.hypot(ax, ay);
        final double lr;
        if (h > 0.71 && h < 1.42) {
            lr = 0.5 * Math.log1p((ax - 1.0) * (ax + 1.0) + ay * ay);
        } else {
            lr = Math.log(h);
        }
        return new Cx(lr, arg);
    }

    public static Cx exp(Cx z) {
        final double e = Math.exp(z.re);
        if (z.im == 0.0) {
            return new Cx(e, z.im);
        }
        return new Cx(e * Math.cos(z.im), e * Math.sin(z.im));
    }

    public static Cx sin(Cx z) {
        return new Cx(Math.sin(z.re) * Math.cosh(z.im), Math.cos(z.re) * Math.sinh(z.im));
    }

    public static Cx cos(Cx z) {
        return new Cx(Math.cos(z.re) * Math.cosh(z.im), -Math.sin(z.re) * Math.sinh(z.im));
    }

    /** libgfortran's pow_c8_i4: repeated squaring, the reciprocal first for a negative power. */
    public static Cx powi(Cx a, int b) {
        Cx pow = ONE;
        Cx x = a;
        if (b != 0) {
            long u;
            if (b < 0) {
                u = -(long) b;
                x = div(pow, x);
            } else {
                u = b;
            }
            for (;;) {
                if ((u & 1) != 0) {
                    pow = mul(pow, x);
                }
                u >>= 1;
                if (u != 0) {
                    x = mul(x, x);
                } else {
                    break;
                }
            }
        }
        return pow;
    }

    /** cpow: exp(w log z). */
    public static Cx pow(Cx z, Cx w) {
        if (z.re == 0.0 && z.im == 0.0) {
            return (w.re == 0.0 && w.im == 0.0) ? ONE : ZERO;
        }
        return exp(mul(w, log(z)));
    }

    public static Cx pow(Cx z, double w) {
        return pow(z, new Cx(w, 0.0));
    }

    public static Cx pow(double z, Cx w) {
        return pow(new Cx(z, 0.0), w);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cx c && Double.compare(c.re, re) == 0 && Double.compare(c.im, im) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(re) * 31 + Double.hashCode(im);
    }

    @Override
    public String toString() {
        return "(" + re + "," + im + ")";
    }
}
