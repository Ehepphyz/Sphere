package com.sphere.core.fastjet;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * A double-double number: the unevaluated sum {@code hi + lo} of two doubles
 * with {@code |lo| <= ulp(hi)/2}.
 *
 * The pair carries 106 bits of significand, so a value is held to a relative
 * precision of 2^-106 (about 1.2e-32), and an angle of order one to an
 * absolute precision of the same size. That is the arithmetic the jet
 * clustering uses in {@link Precision#DD} mode: rapidities, azimuths, the
 * squared distances between them and the kt distances built on those are all
 * carried at this precision, and so are the four-momenta summed by the
 * recombination.
 *
 * The exact product of two doubles is obtained by Dekker's splitting rather
 * than by {@link Math#fma}: on a processor without a hardware fused
 * multiply-add, as the Ivy Bridge machines are, the JDK emulates it with
 * BigDecimal and it is three orders of magnitude slower.
 *
 * The algorithms are those of the QD library (Hida, Li and Bailey), written
 * again here; the transcendental functions take one Newton step from the
 * double result, or a Taylor series after an argument reduction, and are
 * accurate to a few units of 2^-106.
 */
public final class DD implements Comparable<DD> {

    public final double hi;
    public final double lo;

    /** 2^-104, the spacing the pair can resolve relative to its value. */
    public static final double EPS = 4.93038065763132e-32;

    /** Above this, splitting a double for the exact product would overflow. */
    private static final double SPLIT_THRESH = 6.69692879491417e+299;
    private static final double SPLITTER = 134217729.0; // 2^27 + 1

    public static final DD ZERO = new DD(0.0, 0.0);
    public static final DD ONE = new DD(1.0, 0.0);
    public static final DD TWO = new DD(2.0, 0.0);
    public static final DD HALF = new DD(0.5, 0.0);

    public static final DD PI = ofDecimal(
        "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899");
    public static final DD TWO_PI = PI.mulPow2(2.0);
    public static final DD HALF_PI = PI.mulPow2(0.5);
    public static final DD QUARTER_PI = PI.mulPow2(0.25);
    public static final DD THREE_QUARTER_PI = PI.mul(0.75);
    public static final DD LN2 = ofDecimal(
        "0.69314718055994530941723212145817656807550013436025525412068000949339362196969471");
    public static final DD E = ofDecimal(
        "2.71828182845904523536028747135266249775724709369995957496696762772407663035354759");

    /** 1/n! for n = 0..40, the coefficients of every Taylor series here. */
    private static final DD[] INV_FACT = new DD[41];

    static {
        DD fact = ONE;
        INV_FACT[0] = ONE;
        for (int n = 1; n < INV_FACT.length; n++) {
            fact = fact.mul((double) n);
            INV_FACT[n] = ONE.div(fact);
        }
    }

    public DD(double hi, double lo) {
        this.hi = hi;
        this.lo = lo;
    }

    public DD(double value) {
        this(value, 0.0);
    }

    public static DD of(double value) {
        return value == 0.0 ? ZERO : new DD(value, 0.0);
    }

    /** A pair that may not be normalised yet. */
    public static DD normalised(double hi, double lo) {
        final double s = hi + lo;
        return new DD(s, lo - (s - hi));
    }

    /* ------------------------------------------------------------------ */
    /* Error-free transformations, for code that keeps pairs in arrays     */
    /* ------------------------------------------------------------------ */

    /** The rounding error of s = a + b, so that a + b = s + err exactly. */
    public static double twoSumErr(double a, double b, double s) {
        final double bb = s - a;
        return (a - (s - bb)) + (b - bb);
    }

    /** The same when |a| >= |b| is known. */
    public static double quickTwoSumErr(double a, double b, double s) {
        return b - (s - a);
    }

    /** The rounding error of p = a * b, so that a * b = p + err exactly. */
    public static double twoProdErr(double a, double b, double p) {
        if (Math.abs(a) > SPLIT_THRESH || Math.abs(b) > SPLIT_THRESH) {
            // Scale out of the danger zone; the error scales back exactly.
            final double as = a * 0x1p-28;
            final double bs = b * 0x1p-28;
            final double ps = as * bs;
            return twoProdErr(as, bs, ps) * 0x1p56;
        }
        double t = SPLITTER * a;
        final double ahi = t - (t - a);
        final double alo = a - ahi;
        t = SPLITTER * b;
        final double bhi = t - (t - b);
        final double blo = b - bhi;
        return ((ahi * bhi - p) + ahi * blo + alo * bhi) + alo * blo;
    }

    /** The exact sum of two doubles. */
    public static DD sum(double a, double b) {
        final double s = a + b;
        return new DD(s, twoSumErr(a, b, s));
    }

    /** The exact product of two doubles. */
    public static DD prod(double a, double b) {
        final double p = a * b;
        return new DD(p, twoProdErr(a, b, p));
    }

    /** The exact square of a double. */
    public static DD square(double a) {
        final double p = a * a;
        return new DD(p, twoProdErr(a, a, p));
    }

    /* ------------------------------------------------------------------ */
    /* Arithmetic                                                          */
    /* ------------------------------------------------------------------ */

    public DD add(DD b) {
        double s1 = hi + b.hi;
        double s2 = twoSumErr(hi, b.hi, s1);
        double t1 = lo + b.lo;
        final double t2 = twoSumErr(lo, b.lo, t1);
        s2 += t1;
        double s = s1 + s2;
        s2 = quickTwoSumErr(s1, s2, s);
        s1 = s;
        s2 += t2;
        s = s1 + s2;
        return new DD(s, quickTwoSumErr(s1, s2, s));
    }

    public DD add(double b) {
        final double s1 = hi + b;
        double s2 = twoSumErr(hi, b, s1);
        s2 += lo;
        final double s = s1 + s2;
        return new DD(s, quickTwoSumErr(s1, s2, s));
    }

    public DD sub(DD b) {
        return add(b.neg());
    }

    public DD sub(double b) {
        return add(-b);
    }

    public DD neg() {
        return new DD(-hi, -lo);
    }

    public DD abs() {
        return hi < 0.0 || (hi == 0.0 && lo < 0.0) ? neg() : this;
    }

    public DD mul(DD b) {
        final double p1 = hi * b.hi;
        double p2 = twoProdErr(hi, b.hi, p1);
        p2 += hi * b.lo + lo * b.hi;
        final double s = p1 + p2;
        return new DD(s, quickTwoSumErr(p1, p2, s));
    }

    public DD mul(double b) {
        final double p1 = hi * b;
        double p2 = twoProdErr(hi, b, p1);
        p2 += lo * b;
        final double s = p1 + p2;
        return new DD(s, quickTwoSumErr(p1, p2, s));
    }

    /** Multiplication by a power of two, which is exact. */
    public DD mulPow2(double powerOfTwo) {
        return new DD(hi * powerOfTwo, lo * powerOfTwo);
    }

    public DD sqr() {
        final double p1 = hi * hi;
        double p2 = twoProdErr(hi, hi, p1);
        p2 += 2.0 * hi * lo;
        p2 += lo * lo;
        final double s = p1 + p2;
        return new DD(s, quickTwoSumErr(p1, p2, s));
    }

    public DD div(DD b) {
        final double q1 = hi / b.hi;
        DD r = sub(b.mul(q1));
        final double q2 = r.hi / b.hi;
        r = r.sub(b.mul(q2));
        final double q3 = r.hi / b.hi;
        final double s = q1 + q2;
        final DD q = new DD(s, quickTwoSumErr(q1, q2, s));
        return q.add(q3);
    }

    public DD div(double b) {
        final double q1 = hi / b;
        final DD r1 = sub(prod(q1, b));
        final double q2 = r1.hi / b;
        final DD r2 = r1.sub(prod(q2, b));
        final double q3 = r2.hi / b;
        final double s = q1 + q2;
        return new DD(s, quickTwoSumErr(q1, q2, s)).add(q3);
    }

    public DD reciprocal() {
        return ONE.div(this);
    }

    /* ------------------------------------------------------------------ */
    /* Comparison                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public int compareTo(DD o) {
        if (hi < o.hi) return -1;
        if (hi > o.hi) return 1;
        return Double.compare(lo + 0.0, o.lo + 0.0);
    }

    public boolean lt(DD o) { return hi < o.hi || (hi == o.hi && lo < o.lo); }
    public boolean le(DD o) { return hi < o.hi || (hi == o.hi && lo <= o.lo); }
    public boolean gt(DD o) { return hi > o.hi || (hi == o.hi && lo > o.lo); }
    public boolean ge(DD o) { return hi > o.hi || (hi == o.hi && lo >= o.lo); }
    public boolean lt(double o) { return hi < o || (hi == o && lo < 0.0); }
    public boolean gt(double o) { return hi > o || (hi == o && lo > 0.0); }
    public boolean le(double o) { return hi < o || (hi == o && lo <= 0.0); }
    public boolean ge(double o) { return hi > o || (hi == o && lo >= 0.0); }

    public boolean isZero() { return hi == 0.0; }
    public boolean isNaN() { return Double.isNaN(hi); }
    public boolean isInfinite() { return Double.isInfinite(hi); }
    public int signum() { return hi > 0 ? 1 : hi < 0 ? -1 : (lo > 0 ? 1 : lo < 0 ? -1 : 0); }

    public static DD max(DD a, DD b) { return a.ge(b) ? a : b; }
    public static DD min(DD a, DD b) { return a.le(b) ? a : b; }

    @Override
    public boolean equals(Object other) {
        return other instanceof DD d && d.hi == hi && d.lo == lo;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(hi) * 31 + Double.hashCode(lo);
    }

    public double doubleValue() {
        return hi + lo;
    }

    /* ------------------------------------------------------------------ */
    /* Rounding                                                            */
    /* ------------------------------------------------------------------ */

    public DD floor() {
        double h = Math.floor(hi);
        double l = 0.0;
        if (h == hi) {
            l = Math.floor(lo);
            final double s = h + l;
            l = quickTwoSumErr(h, l, s);
            h = s;
        }
        return new DD(h, l);
    }

    /** The nearest integer, halves rounded up. */
    public DD nint() {
        double h = nintDouble(hi);
        double l;
        if (h == hi) {
            l = nintDouble(lo);
            final double s = h + l;
            l = quickTwoSumErr(h, l, s);
            h = s;
        } else {
            l = 0.0;
            if (Math.abs(h - hi) == 0.5 && lo < 0.0) {
                h -= 1.0;
            }
        }
        return new DD(h, l);
    }

    private static double nintDouble(double d) {
        return d == Math.floor(d) ? d : Math.floor(d + 0.5);
    }

    /* ------------------------------------------------------------------ */
    /* Functions                                                           */
    /* ------------------------------------------------------------------ */

    public DD sqrt() {
        if (hi == 0.0) {
            return ZERO;
        }
        if (hi < 0.0) {
            return new DD(Double.NaN, Double.NaN);
        }
        final double x = 1.0 / Math.sqrt(hi);
        final double ax = hi * x;
        final DD diff = sub(square(ax));
        return sum(ax, diff.hi * (x * 0.5));
    }

    public static DD sqrt(double a) {
        return new DD(a).sqrt();
    }

    public DD exp() {
        final double k = 512.0;
        final double invK = 1.0 / k;
        if (hi <= -709.0) {
            return ZERO;
        }
        if (hi >= 709.0) {
            return new DD(Double.POSITIVE_INFINITY, 0.0);
        }
        if (hi == 0.0 && lo == 0.0) {
            return ONE;
        }
        final double m = Math.floor(hi / LN2.hi + 0.5);
        final DD r = sub(LN2.mul(m)).mulPow2(invK);
        DD p = r.sqr();
        DD s = r.add(p.mulPow2(0.5));
        p = p.mul(r);
        DD t = p.mul(INV_FACT[3]);
        int i = 3;
        do {
            s = s.add(t);
            p = p.mul(r);
            i++;
            t = p.mul(INV_FACT[i]);
        } while (Math.abs(t.hi) > invK * EPS && i < 12);
        s = s.add(t);
        // (1+s)^512 - 1, nine squarings kept in the form 2s + s^2.
        for (int j = 0; j < 9; j++) {
            s = s.mulPow2(2.0).add(s.sqr());
        }
        s = s.add(1.0);
        return new DD(Math.scalb(s.hi, (int) m), Math.scalb(s.lo, (int) m));
    }

    public DD log() {
        if (hi == 1.0 && lo == 0.0) {
            return ZERO;
        }
        if (hi == 0.0) {
            return new DD(Double.NEGATIVE_INFINITY, 0.0);
        }
        if (Double.isInfinite(hi) && hi > 0) {
            return this;
        }
        if (hi < 0.0) {
            return new DD(Double.NaN, Double.NaN);
        }
        // One Newton step for f(x) = exp(x) - a, from the double result.
        final DD x = new DD(Math.log(hi));
        return x.add(mul(x.neg().exp())).sub(1.0);
    }

    public static DD log(double a) {
        return new DD(a).log();
    }

    /** Integer powers exactly by squaring, others through exp and log. */
    public DD pow(double p) {
        if (p == Math.rint(p) && Math.abs(p) <= 1024) {
            return powInt((int) p);
        }
        if (hi == 0.0) {
            return p > 0 ? ZERO : new DD(Double.POSITIVE_INFINITY, 0.0);
        }
        return log().mul(p).exp();
    }

    public DD powInt(int n) {
        if (n == 0) {
            return ONE;
        }
        int e = Math.abs(n);
        DD base = this;
        DD result = ONE;
        while (e > 0) {
            if ((e & 1) != 0) {
                result = result.mul(base);
            }
            e >>= 1;
            if (e > 0) {
                base = base.sqr();
            }
        }
        return n < 0 ? ONE.div(result) : result;
    }

    /** sin of |r| <= pi/4 by its Taylor series. */
    private static DD sinTaylor(DD r) {
        if (r.hi == 0.0) {
            return ZERO;
        }
        final DD r2 = r.sqr().neg();
        DD s = r;
        DD p = r;
        int i = 1;
        final double threshold = 0.5 * Math.abs(r.hi) * EPS;
        DD t;
        do {
            p = p.mul(r2);
            i += 2;
            t = p.mul(INV_FACT[i]);
            s = s.add(t);
        } while (Math.abs(t.hi) > threshold && i < 39);
        return s;
    }

    /** sin and cos together, as {sin, cos}. */
    public DD[] sincos() {
        if (hi == 0.0 && lo == 0.0) {
            return new DD[]{ZERO, ONE};
        }
        // Reduce modulo 2pi, then modulo pi/2.
        final DD z = div(TWO_PI).nint();
        DD r = sub(TWO_PI.mul(z));
        final double q = Math.floor(r.hi / HALF_PI.hi + 0.5);
        r = r.sub(HALF_PI.mul(q));
        final int j = (int) q;
        final DD s = sinTaylor(r);
        final DD c = ONE.sub(s.sqr()).sqrt();
        return switch (j) {
            case 0 -> new DD[]{s, c};
            case 1 -> new DD[]{c, s.neg()};
            case -1 -> new DD[]{c.neg(), s};
            default -> new DD[]{s.neg(), c.neg()};
        };
    }

    public DD sin() {
        return sincos()[0];
    }

    public DD cos() {
        return sincos()[1];
    }

    /** The angle of (x, y), in (-pi, pi]. */
    public static DD atan2(DD y, DD x) {
        if (x.hi == 0.0 && x.lo == 0.0) {
            if (y.hi == 0.0 && y.lo == 0.0) {
                return ZERO;
            }
            return y.signum() > 0 ? HALF_PI : HALF_PI.neg();
        }
        if (y.hi == 0.0 && y.lo == 0.0) {
            return x.signum() > 0 ? ZERO : PI;
        }
        if (x.equals(y)) {
            return y.signum() > 0 ? QUARTER_PI : THREE_QUARTER_PI.neg();
        }
        if (x.equals(y.neg())) {
            return y.signum() > 0 ? THREE_QUARTER_PI : QUARTER_PI.neg();
        }
        final DD r = x.sqr().add(y.sqr()).sqrt();
        final DD xx = x.div(r);
        final DD yy = y.div(r);
        DD z = new DD(Math.atan2(y.hi + y.lo, x.hi + x.lo));
        final DD[] sc = z.sincos();
        if (Math.abs(xx.hi) > Math.abs(yy.hi)) {
            z = z.add(yy.sub(sc[0]).div(sc[1]));
        } else {
            z = z.sub(xx.sub(sc[1]).div(sc[0]));
        }
        return z;
    }

    public static DD atan2(double y, double x) {
        return atan2(new DD(y), new DD(x));
    }

    public DD atan() {
        return atan2(this, ONE);
    }

    public DD tan() {
        final DD[] sc = sincos();
        return sc[0].div(sc[1]);
    }

    public DD acos() {
        if (Math.abs(hi) > 1.0 || (Math.abs(hi) == 1.0 && lo * hi > 0)) {
            return new DD(Double.NaN, Double.NaN);
        }
        return atan2(ONE.sub(this).mul(ONE.add(this)).sqrt(), this);
    }

    public DD asin() {
        if (Math.abs(hi) > 1.0 || (Math.abs(hi) == 1.0 && lo * hi > 0)) {
            return new DD(Double.NaN, Double.NaN);
        }
        return atan2(this, ONE.sub(this).mul(ONE.add(this)).sqrt());
    }

    public DD sinh() {
        if (Math.abs(hi) < 0.5) {
            // e - 1/e would cancel: the odd Taylor series instead
            final DD x2 = sqr();
            DD term = this;
            DD sum = this;
            for (int k = 1; k < 40; k++) {
                term = term.mul(x2).div((2.0 * k) * (2.0 * k + 1.0));
                sum = sum.add(term);
                if (Math.abs(term.hi) < Math.abs(sum.hi) * 1e-34) break;
            }
            return sum;
        }
        final DD e = exp();
        return e.sub(e.reciprocal()).mulPow2(0.5);
    }

    public DD cosh() {
        final DD e = exp();
        return e.add(e.reciprocal()).mulPow2(0.5);
    }

    /* ------------------------------------------------------------------ */
    /* Text                                                                */
    /* ------------------------------------------------------------------ */

    /** The exact value of the pair. */
    public BigDecimal toBigDecimal() {
        if (Double.isNaN(hi) || Double.isInfinite(hi)) {
            throw new ArithmeticException("not a finite number: " + hi);
        }
        return new BigDecimal(hi).add(new BigDecimal(lo));
    }

    /** The nearest pair to a decimal written out. */
    public static DD ofDecimal(String text) {
        final BigDecimal exact = new BigDecimal(text.trim());
        final double h = exact.doubleValue();
        final double l = exact.subtract(new BigDecimal(h)).doubleValue();
        return normalised(h, l);
    }

    /** A decimal read to 106 bits, or the usual double words for the specials. */
    public static DD parse(String text) {
        final String bare = text.trim();
        final String lower = bare.toLowerCase(Locale.ROOT);
        if (lower.equals("nan")) return new DD(Double.NaN, Double.NaN);
        if (lower.equals("inf") || lower.equals("+inf") || lower.equals("infinity")) {
            return new DD(Double.POSITIVE_INFINITY, 0.0);
        }
        if (lower.equals("-inf") || lower.equals("-infinity")) {
            return new DD(Double.NEGATIVE_INFINITY, 0.0);
        }
        return ofDecimal(bare.replace('D', 'E').replace('d', 'e'));
    }

    /** The value to the given number of significant digits (32 is the pair's worth). */
    public String toString(int digits) {
        if (Double.isNaN(hi) || Double.isInfinite(hi)) {
            return Double.toString(hi);
        }
        if (hi == 0.0) {
            return "0";
        }
        return toBigDecimal().round(new MathContext(digits)).stripTrailingZeros().toString();
    }

    /** Fixed notation with a given number of decimals. */
    public String toFixed(int decimals) {
        if (Double.isNaN(hi) || Double.isInfinite(hi)) {
            return Double.toString(hi);
        }
        return toBigDecimal().setScale(decimals, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }

    @Override
    public String toString() {
        return toString(32);
    }
}
