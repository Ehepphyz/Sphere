package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.GenParticle;

import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * A feature of particles without its type showing: comparisons with ints or
 * doubles give filters, as HepMC3's Selector and SelectorWrapper. The
 * standard ones are in {@link StandardSelector}; {@link #ATTRIBUTE} tests
 * an attribute.
 */
public interface Selector {

    Filter gt(int value);

    Filter gt(double value);

    Filter ge(int value);

    Filter ge(double value);

    Filter lt(int value);

    Filter lt(double value);

    Filter le(int value);

    Filter le(double value);

    Filter eq(int value);

    Filter eq(double value);

    Filter ne(int value);

    Filter ne(double value);

    Selector abs();

    /** The value for a particle, as a double. */
    double value(GenParticle p);

    static AttributeFeature ATTRIBUTE(String name) {
        return new AttributeFeature(name);
    }

    /** A selector of an integral feature. */
    static Selector ofInt(ToLongFunction<GenParticle> f) {
        return new IntSelector(new Feature.Integral(f));
    }

    /** A selector of a floating feature. */
    static Selector ofDouble(ToDoubleFunction<GenParticle> f) {
        return new DoubleSelector(new Feature.Floating(f));
    }

    /** SelectorWrapper&lt;int&gt;. */
    final class IntSelector implements Selector {
        private final Feature.Integral internal;

        IntSelector(Feature.Integral internal) {
            this.internal = internal;
        }

        @Override
        public Filter gt(int value) {
            return internal.gt((long) value);
        }

        @Override
        public Filter gt(double value) {
            return internal.gt(value);
        }

        @Override
        public Filter ge(int value) {
            return internal.ge((long) value);
        }

        @Override
        public Filter ge(double value) {
            return internal.ge(value);
        }

        @Override
        public Filter lt(int value) {
            return internal.lt((long) value);
        }

        @Override
        public Filter lt(double value) {
            return internal.lt(value);
        }

        @Override
        public Filter le(int value) {
            return internal.le((long) value);
        }

        @Override
        public Filter le(double value) {
            return internal.le(value);
        }

        @Override
        public Filter eq(int value) {
            return internal.eq((long) value);
        }

        @Override
        public Filter eq(double value) {
            return internal.eq(value);
        }

        @Override
        public Filter ne(int value) {
            return internal.ne((long) value);
        }

        @Override
        public Filter ne(double value) {
            return internal.ne(value);
        }

        @Override
        public Selector abs() {
            return new IntSelector(internal.abs());
        }

        @Override
        public double value(GenParticle p) {
            return internal.apply(p);
        }
    }

    /** SelectorWrapper&lt;double&gt;. */
    final class DoubleSelector implements Selector {
        private final Feature.Floating internal;

        DoubleSelector(Feature.Floating internal) {
            this.internal = internal;
        }

        @Override
        public Filter gt(int value) {
            return internal.gt(value);
        }

        @Override
        public Filter gt(double value) {
            return internal.gt(value);
        }

        @Override
        public Filter ge(int value) {
            return internal.ge(value);
        }

        @Override
        public Filter ge(double value) {
            return internal.ge(value);
        }

        @Override
        public Filter lt(int value) {
            return internal.lt(value);
        }

        @Override
        public Filter lt(double value) {
            return internal.lt(value);
        }

        @Override
        public Filter le(int value) {
            return internal.le(value);
        }

        @Override
        public Filter le(double value) {
            return internal.le(value);
        }

        @Override
        public Filter eq(int value) {
            return internal.eq(value);
        }

        @Override
        public Filter eq(double value) {
            return internal.eq(value);
        }

        @Override
        public Filter ne(int value) {
            return internal.ne(value);
        }

        @Override
        public Filter ne(double value) {
            return internal.ne(value);
        }

        @Override
        public Selector abs() {
            return new DoubleSelector(internal.abs());
        }

        @Override
        public double value(GenParticle p) {
            return internal.apply(p);
        }
    }

    /** abs(selector). */
    static Selector abs(Selector input) {
        return input.abs();
    }
}
