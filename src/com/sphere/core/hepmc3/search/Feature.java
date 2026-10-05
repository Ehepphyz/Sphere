package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.GenParticle;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Something measured on a particle, from which filters are made by
 * comparison: HepMC3's Feature, with its two specialisations.
 *
 * <p>An integral feature compared with an integer compares exactly; with a
 * double it compares as a double, equality meaning |a - b| &lt; DBL_EPSILON.
 * A floating feature's equality is |a - b| &lt;= DBL_EPSILON. A feature of
 * another type compares with compareTo and equals.
 */
public final class Feature {

    private Feature() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** std::numeric_limits&lt;double&gt;::epsilon(). */
    public static final double DBL_EPSILON = Math.ulp(1.0);

    /** An integral feature (int, long). */
    public static final class Integral {
        private final ToLongFunction<GenParticle> functor;

        public Integral(ToLongFunction<GenParticle> functor) {
            this.functor = functor;
        }

        public long apply(GenParticle p) {
            return functor.applyAsLong(p);
        }

        public Filter gt(long value) {
            return p -> functor.applyAsLong(p) > value;
        }

        public Filter lt(long value) {
            return p -> functor.applyAsLong(p) < value;
        }

        public Filter ge(long value) {
            return p -> functor.applyAsLong(p) >= value;
        }

        public Filter le(long value) {
            return p -> functor.applyAsLong(p) <= value;
        }

        public Filter eq(long value) {
            return p -> functor.applyAsLong(p) == value;
        }

        public Filter ne(long value) {
            return p -> functor.applyAsLong(p) != value;
        }

        public Filter gt(double value) {
            return p -> functor.applyAsLong(p) > value;
        }

        public Filter lt(double value) {
            return p -> functor.applyAsLong(p) < value;
        }

        /** !(this &lt; value), as HepMC3 writes it. */
        public Filter ge(double value) {
            return lt(value).not();
        }

        /** !(this &gt; value). */
        public Filter le(double value) {
            return gt(value).not();
        }

        public Filter eq(double value) {
            return p -> Math.abs(functor.applyAsLong(p) - value) < DBL_EPSILON;
        }

        public Filter ne(double value) {
            return eq(value).not();
        }

        public Integral abs() {
            return new Integral(p -> Math.abs(functor.applyAsLong(p)));
        }
    }

    /** A floating-point feature. */
    public static final class Floating {
        private final ToDoubleFunction<GenParticle> functor;

        public Floating(ToDoubleFunction<GenParticle> functor) {
            this.functor = functor;
        }

        public double apply(GenParticle p) {
            return functor.applyAsDouble(p);
        }

        public Filter gt(double value) {
            return p -> functor.applyAsDouble(p) > value;
        }

        public Filter lt(double value) {
            return p -> functor.applyAsDouble(p) < value;
        }

        public Filter ge(double value) {
            return p -> functor.applyAsDouble(p) >= value;
        }

        public Filter le(double value) {
            return p -> functor.applyAsDouble(p) <= value;
        }

        public Filter eq(double value) {
            return p -> Math.abs(functor.applyAsDouble(p) - value) <= DBL_EPSILON;
        }

        public Filter ne(double value) {
            return eq(value).not();
        }

        public Floating abs() {
            return new Floating(p -> Math.abs(functor.applyAsDouble(p)));
        }
    }

    /** A feature of any comparable type. */
    public static final class Generic<T extends Comparable<? super T>> {
        private final Function<GenParticle, T> functor;

        public Generic(Function<GenParticle, T> functor) {
            this.functor = functor;
        }

        public T apply(GenParticle p) {
            return functor.apply(p);
        }

        public Filter gt(T value) {
            return p -> functor.apply(p).compareTo(value) > 0;
        }

        public Filter lt(T value) {
            return p -> functor.apply(p).compareTo(value) < 0;
        }

        public Filter ge(T value) {
            return p -> functor.apply(p).compareTo(value) >= 0;
        }

        public Filter le(T value) {
            return p -> functor.apply(p).compareTo(value) <= 0;
        }

        public Filter eq(T value) {
            return p -> functor.apply(p).equals(value);
        }

        public Filter ne(T value) {
            return eq(value).not();
        }
    }
}
