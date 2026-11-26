package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;

import java.util.Locale;

/**
 * A one-loop integral as a Laurent series in the dimensional regulator, n = 4 - 2 eps:
 *
 * <pre>
 *   I = N(eps) (mu^2)^eps [ a / eps^2 + b / eps + c ] + O(eps)
 * </pre>
 *
 * with Golem95's normalisation N = r_Gamma = Gamma(1+eps) Gamma(1-eps)^2 / Gamma(1-2eps), which is
 * OneLOop's and QCDLoop's too. The same integral in another normalisation, or at another scale mu,
 * is the same function of eps re-expanded: {@link #in(Convention)} and {@link #atScale(double)} do that
 * exactly, which is also what lets Sphere check an answer against itself (a result computed at mu'
 * must be the one computed at mu, re-expanded).
 *
 * @param a   the coefficient of 1/eps^2
 * @param b   the coefficient of 1/eps
 * @param c   the finite part
 * @param mu2 the scale mu^2 the coefficients refer to
 */
public record Laurent(Cx a, Cx b, Cx c, double mu2) {

    /** The normalisation factored out of a result. */
    public enum Convention {
        /** r_Gamma = Gamma(1+eps) Gamma(1-eps)^2 / Gamma(1-2eps): Golem95, OneLOop, QCDLoop. */
        R_GAMMA("r_Gamma = Gamma(1+eps) Gamma(1-eps)^2/Gamma(1-2eps) (Golem95, OneLOop, QCDLoop)"),
        /** Gamma(1+eps), as many papers write it. */
        GAMMA("Gamma(1+eps)"),
        /** exp(-gamma_E eps), the MS-bar factor. */
        MSBAR("exp(-gamma_E eps), MS-bar");

        private final String text;

        Convention(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        public static Convention parse(String s) {
            final String k = s.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            return switch (k) {
                case "rgamma", "golem", "oneloop", "qcdloop", "cgamma" -> R_GAMMA;
                case "gamma", "gamma1", "gamma1eps" -> GAMMA;
                case "msbar", "ms", "egamma" -> MSBAR;
                default -> throw new IllegalArgumentException("no convention '" + s + "' (r_gamma, gamma, msbar)");
            };
        }
    }

    public static final Laurent ZERO = new Laurent(Cx.ZERO, Cx.ZERO, Cx.ZERO, 1.0);

    /** pi^2/6 = zeta(2): what r_Gamma and Gamma(1+eps) differ by at eps^2. */
    private static final double ZETA2 = Math.PI * Math.PI / 6.0;

    public static Laurent of(Cx a, Cx b, Cx c, double mu2) {
        return new Laurent(a, b, c, mu2);
    }

    /**
     * The same integral with the coefficients for another scale: the series is (mu^2)^eps times a series
     * that does not depend on mu, and (mu'^2)^eps = (mu^2)^eps exp(eps L) with L = log(mu'^2/mu^2), so
     * b' = b + L a and c' = c + L b + L^2/2 a.
     */
    public Laurent atScale(double newMu2) {
        if (!(newMu2 > 0)) {
            throw new IllegalArgumentException("mu^2 must be positive");
        }
        final double l = Math.log(newMu2 / mu2);
        final Cx b2 = Cx.add(b, Cx.mul(l, a));
        final Cx c2 = Cx.add(Cx.add(c, Cx.mul(l, b)), Cx.mul(0.5 * l * l, a));
        return new Laurent(a, b2, c2, newMu2);
    }

    /**
     * The coefficients with another normalisation factored out. r_Gamma/Gamma(1+eps) = 1 - (pi^2/6) eps^2
     * and r_Gamma/exp(-gamma_E eps) = 1 - (pi^2/12) eps^2 + O(eps^3): only the finite part of an integral
     * with a double pole changes.
     */
    public Laurent in(Convention to) {
        return switch (to) {
            case R_GAMMA -> this;
            case GAMMA -> new Laurent(a, b, Cx.sub(c, Cx.mul(ZETA2, a)), mu2);
            case MSBAR -> new Laurent(a, b, Cx.sub(c, Cx.mul(ZETA2 / 2.0, a)), mu2);
        };
    }

    public Laurent plus(Laurent o) {
        return new Laurent(Cx.add(a, o.a), Cx.add(b, o.b), Cx.add(c, o.c), mu2);
    }

    public Laurent minus(Laurent o) {
        return new Laurent(Cx.sub(a, o.a), Cx.sub(b, o.b), Cx.sub(c, o.c), mu2);
    }

    public Laurent times(double x) {
        return new Laurent(Cx.mul(x, a), Cx.mul(x, b), Cx.mul(x, c), mu2);
    }

    /** The largest modulus of the three coefficients. */
    public double size() {
        return Math.max(Cx.abs(a), Math.max(Cx.abs(b), Cx.abs(c)));
    }

    /** The largest difference of a coefficient, relative to the larger series. */
    public double distance(Laurent o) {
        final double scale = Math.max(size(), o.size());
        if (scale == 0) {
            return 0;
        }
        final double d = Math.max(Cx.abs(Cx.sub(a, o.a)), Math.max(Cx.abs(Cx.sub(b, o.b)), Cx.abs(Cx.sub(c, o.c))));
        return d / scale;
    }

    public boolean isFinite() {
        return Double.isFinite(a.re) && Double.isFinite(a.im) && Double.isFinite(b.re) && Double.isFinite(b.im)
            && Double.isFinite(c.re) && Double.isFinite(c.im);
    }

    public static String num(Cx z) {
        return String.format(Locale.ROOT, "(%.10E, %.10E)", z.re, z.im);
    }

    /** As Golem95's demo programs print a result. */
    public String lines() {
        return "1/eps^2 * " + num(a) + "\n+ 1/eps   * " + num(b) + "\n+ 1       * " + num(c);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s/eps^2 + %s/eps + %s  (mu^2 = %s)", num(a), num(b), num(c), mu2);
    }
}
