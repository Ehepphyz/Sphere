package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::EllipticIntegral (EllipticIntegral.hh, .icc): the incomplete elliptic integrals of the
 * first, second and third kinds as functions of the amplitude phi, GSL's (Legendre form) to double precision.
 */
public final class EllipticIntegral {

    private EllipticIntegral() {
    }

    /** F(phi, k). */
    public static class FirstKind extends AbsFunction {

        private final Parameter _k;

        public FirstKind() {
            _k = new Parameter("K", 1.0, 0.0, 1.0);
        }

        public FirstKind(FirstKind right) {
            _k = new Parameter(right._k);
        }

        @Override
        public FirstKind copy() {
            return new FirstKind(this);
        }

        public Parameter k() {
            return _k;
        }

        @Override
        public double call(double x) {
            return Gsl.checked(Gsl.ellintF(x, _k.getValue()), "gsl_sf_ellint_F_impl");
        }

        @Override
        public double call(Argument a) {
            return call(a.get(0));
        }
    }

    /** E(phi, k). */
    public static class SecondKind extends AbsFunction {

        private final Parameter _k;

        public SecondKind() {
            _k = new Parameter("K", 1.0, 0.0, 1.0);
        }

        public SecondKind(SecondKind right) {
            _k = new Parameter(right._k);
        }

        @Override
        public SecondKind copy() {
            return new SecondKind(this);
        }

        public Parameter k() {
            return _k;
        }

        @Override
        public double call(double x) {
            return Gsl.checked(Gsl.ellintE(x, _k.getValue()), "gsl_sf_ellint_E_impl");
        }

        @Override
        public double call(Argument a) {
            return call(a.get(0));
        }
    }

    /** P(phi, k, n). */
    public static class ThirdKind extends AbsFunction {

        private final Parameter _k;
        private final Parameter _n;

        public ThirdKind() {
            _k = new Parameter("K", 1.0, 0.0, 1.0);
            _n = new Parameter("N", 1.0, 0.0, 10.0);
        }

        public ThirdKind(ThirdKind right) {
            _k = new Parameter(right._k);
            _n = new Parameter(right._n);
        }

        @Override
        public ThirdKind copy() {
            return new ThirdKind(this);
        }

        public Parameter k() {
            return _k;
        }

        public Parameter n() {
            return _n;
        }

        @Override
        public double call(double x) {
            return Gsl.checked(Gsl.ellintP(x, _k.getValue(), _n.getValue()), "gsl_ellint_P_impl");
        }

        @Override
        public double call(Argument a) {
            return call(a.get(0));
        }
    }
}
