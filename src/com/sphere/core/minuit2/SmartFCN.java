package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The function as the autopilot sees it: every value Minuit2 asks for passes
 * here. A value already computed at the same 64 bits of every parameter is
 * given back from a cache: Minuit2's numbers are unchanged (a function gives
 * the same value at the same point) but an expensive function is not called
 * again when Hesse, Minos or a second Migrad come back to a point already
 * seen. The values that are not finite are counted with where they happened,
 * so that the domain of the function (a parameter that must stay positive...)
 * can be inferred. Up can be changed, for Minos and contours at another
 * confidence level, even when the wrapped function keeps its own fixed.
 */
public final class SmartFCN implements FCNBase {

    /** Calls asked, calls made, values not finite, gradients asked. */
    public record Stats(long requests, long evaluations, long nonFinite, long gradients) {
        public long cached() {
            return requests - evaluations;
        }

        public String text() {
            return String.format(Locale.ROOT, "%d values asked, %d computed (%.0f%% from the cache)%s%s", requests, evaluations,
                requests == 0 ? 0. : 100. * cached() / requests, gradients > 0 ? ", " + gradients + " gradients" : "",
                nonFinite > 0 ? ", " + nonFinite + " not finite" : "");
        }
    }

    /** A limit the function seems to need: parameter par below (lower) or above (!lower) bound is outside its domain. */
    public record DomainHint(int par, String name, boolean lower, double bound) {
        public String text() {
            return String.format(Locale.ROOT, "%s %s %.6g", name, lower ? ">=" : "<=", bound);
        }
    }

    private static final int CACHE_SIZE = 1 << 16;

    private final FCNBase fcn;
    private final boolean caching;
    private volatile double up = Double.NaN;
    private final Map<Key, Double> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Double> e) {
            return size() > CACHE_SIZE;
        }
    };
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong nonFinite = new AtomicLong();
    private final AtomicLong gradients = new AtomicLong();
    /** Per parameter, the range where the function was finite and where it was not. */
    private double[] finiteLo, finiteHi, badLo, badHi;
    private double lowest = Double.POSITIVE_INFINITY;

    public SmartFCN(FCNBase fcn) {
        this(fcn, true);
    }

    public SmartFCN(FCNBase fcn, boolean caching) {
        this.fcn = fcn;
        this.caching = caching;
    }

    /** The function wrapped, or the function itself when it is already a SmartFCN. */
    public static SmartFCN of(FCNBase fcn) {
        return fcn instanceof SmartFCN s ? s : new SmartFCN(fcn);
    }

    public FCNBase wrapped() {
        return fcn;
    }

    public Stats stats() {
        return new Stats(requests.get(), evaluations.get(), nonFinite.get(), gradients.get());
    }

    /** The parameters as 64-bit words, hashed once. */
    private static final class Key {
        private final long[] bits;
        private final int hash;

        Key(double[] v) {
            bits = new long[v.length];
            for (int i = 0; i < v.length; i++) bits[i] = Double.doubleToRawLongBits(v[i]);
            hash = Arrays.hashCode(bits);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.hash == hash && Arrays.equals(k.bits, bits);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    @Override
    public double value(double[] v) {
        requests.incrementAndGet();
        final Key key = caching ? new Key(v) : null;
        if (caching) {
            final Double hit;
            synchronized (cache) {
                hit = cache.get(key);
            }
            if (hit != null) return hit;
        }
        final double f = fcn.value(v);
        evaluations.incrementAndGet();
        record(v, f);
        if (caching) {
            synchronized (cache) {
                cache.put(key, f);
            }
        }
        return f;
    }

    /** The lowest finite value computed so far (infinite before any). */
    public synchronized double lowest() {
        return lowest;
    }

    private synchronized void record(double[] v, double f) {
        final boolean finite = Double.isFinite(f);
        if (finite && f < lowest) lowest = f;
        if (finiteLo == null || finiteLo.length != v.length) {
            finiteLo = new double[v.length];
            finiteHi = new double[v.length];
            badLo = new double[v.length];
            badHi = new double[v.length];
            Arrays.fill(finiteLo, Double.POSITIVE_INFINITY);
            Arrays.fill(badLo, Double.POSITIVE_INFINITY);
            Arrays.fill(finiteHi, Double.NEGATIVE_INFINITY);
            Arrays.fill(badHi, Double.NEGATIVE_INFINITY);
        }
        if (!finite) nonFinite.incrementAndGet();
        final double[] lo = finite ? finiteLo : badLo;
        final double[] hi = finite ? finiteHi : badHi;
        for (int i = 0; i < v.length; i++) {
            if (v[i] < lo[i]) lo[i] = v[i];
            if (v[i] > hi[i]) hi[i] = v[i];
        }
    }

    /**
     * The limits the function seems to need: for each variable parameter of st
     * whose values where the function was not finite all lie on one side of
     * those where it was, a limit at the edge (at zero when zero separates
     * them: a logarithm, a square root, a width...). Empty when the bad values
     * do not separate along any single parameter.
     */
    public synchronized List<DomainHint> domainHints(MnUserParameterState st) {
        final List<DomainHint> out = new ArrayList<>();
        if (nonFinite.get() == 0 || finiteLo == null) return out;
        for (int i = 0; i < Math.min(finiteLo.length, st.minuitParameters().size()); i++) {
            final MinuitParameter p = st.parameter(i);
            if (p.isFixed() || p.isConst() || badLo[i] > badHi[i] || finiteLo[i] > finiteHi[i]) continue;
            if (badHi[i] < finiteLo[i] && !(p.hasLowerLimit() && p.lowerLimit() >= finiteLo[i])) {
                out.add(new DomainHint(i, p.name(), true, badHi[i] < 0 && finiteLo[i] >= 0 ? 0 : finiteLo[i]));
            } else if (badLo[i] > finiteHi[i] && !(p.hasUpperLimit() && p.upperLimit() <= finiteHi[i])) {
                out.add(new DomainHint(i, p.name(), false, badLo[i] > 0 && finiteHi[i] <= 0 ? 0 : finiteHi[i]));
            }
        }
        return out;
    }

    /* ---- the rest of the function, as it is ---------------------------------------- */

    @Override
    public double up() {
        final double u = up;
        return Double.isNaN(u) ? fcn.up() : u;
    }

    @Override
    public void setErrorDef(double u) {
        up = u;
    }

    @Override
    public boolean hasGradient() {
        return fcn.hasGradient();
    }

    @Override
    public double[] gradient(double[] v) {
        gradients.incrementAndGet();
        return fcn.gradient(v);
    }

    @Override
    public double[] gradientWithPrevResult(double[] parameters, double[] previousGrad, double[] previousG2,
                                           double[] previousGstep, double fValAtParameters) {
        gradients.incrementAndGet();
        return fcn.gradientWithPrevResult(parameters, previousGrad, previousG2, previousGstep, fValAtParameters);
    }

    @Override
    public GradientParameterSpace gradParameterSpace() {
        return fcn.gradParameterSpace();
    }

    @Override
    public double[] g2(double[] v) {
        return fcn.g2(v);
    }

    @Override
    public double[] hessian(double[] v) {
        return fcn.hessian(v);
    }

    @Override
    public boolean hasHessian() {
        return fcn.hasHessian();
    }

    @Override
    public boolean hasG2() {
        return fcn.hasG2();
    }

    @Override
    public boolean secondDerivativeAlwaysVanishes(int i, int j) {
        return fcn.secondDerivativeAlwaysVanishes(i, j);
    }
}
