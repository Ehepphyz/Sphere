package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Cut&lt;Type&gt; (CutBase.hh, .icc): a predicate on a Type, combined with or, and and not (CLHEP's
 * operators ||, &amp;&amp; and !), each combination holding copies of its operands.
 */
public abstract class Cut<T> {

    public abstract Cut<T> copy();

    /** Whether t passes the cut, operator(). */
    public abstract boolean test(T t);

    public OR<T> or(Cut<T> a) {
        return new OR<>(this, a);
    }

    public AND<T> and(Cut<T> a) {
        return new AND<>(this, a);
    }

    public NOT<T> not() {
        return new NOT<>(this);
    }

    public static class AND<T> extends Cut<T> {

        private final Cut<T> _pA;
        private final Cut<T> _pB;

        public AND(AND<T> right) {
            _pA = right._pA.copy();
            _pB = right._pB.copy();
        }

        public AND(Cut<T> a, Cut<T> b) {
            _pA = a.copy();
            _pB = b.copy();
        }

        @Override
        public AND<T> copy() {
            return new AND<>(this);
        }

        @Override
        public boolean test(T t) {
            return _pA.test(t) && _pB.test(t);
        }
    }

    public static class OR<T> extends Cut<T> {

        private final Cut<T> _pA;
        private final Cut<T> _pB;

        public OR(OR<T> right) {
            _pA = right._pA.copy();
            _pB = right._pB.copy();
        }

        public OR(Cut<T> a, Cut<T> b) {
            _pA = a.copy();
            _pB = b.copy();
        }

        @Override
        public OR<T> copy() {
            return new OR<>(this);
        }

        @Override
        public boolean test(T t) {
            return _pA.test(t) || _pB.test(t);
        }
    }

    public static class NOT<T> extends Cut<T> {

        private final Cut<T> _pA;

        public NOT(NOT<T> right) {
            _pA = right._pA.copy();
        }

        public NOT(Cut<T> a) {
            _pA = a.copy();
        }

        @Override
        public NOT<T> copy() {
            return new NOT<>(this);
        }

        @Override
        public boolean test(T t) {
            return !_pA.test(t);
        }
    }

    /** A copy of a cut, Cut&lt;Type&gt;::Predicate. */
    public static class Predicate<T> extends Cut<T> {

        private final Cut<T> _pA;

        public Predicate(Predicate<T> right) {
            _pA = right._pA.copy();
        }

        public Predicate(Cut<T> a) {
            _pA = a.copy();
        }

        @Override
        public Predicate<T> copy() {
            return new Predicate<>(this);
        }

        @Override
        public boolean test(T t) {
            return _pA.test(t);
        }
    }
}
