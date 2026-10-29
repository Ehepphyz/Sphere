package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Bessel functions (Bessel.hh, .icc), GSL's: Genfun::FractionalOrder::Bessel, of a real order
 * that is a parameter, and Genfun::IntegralOrder::Bessel, of a fixed integer order.
 */
public final class Bessel {

    /** J, of the first kind, or Y, of the second. */
    public enum Type {
        J, Y
    }

    private Bessel() {
    }

    /** Genfun::FractionalOrder::Bessel: J_nu or Y_nu, nu the parameter Order. */
    public static class FractionalOrder extends AbsFunction {

        private final Type _type;
        private final Parameter _order;

        public FractionalOrder(Type type) {
            _type = type;
            _order = new Parameter("Order", 0.0, -10, 10);
        }

        public FractionalOrder(FractionalOrder right) {
            _type = right._type;
            _order = new Parameter(right._order);
        }

        @Override
        public FractionalOrder copy() {
            return new FractionalOrder(this);
        }

        public Parameter order() {
            return _order;
        }

        @Override
        public double call(double x) {
            if (_type == Type.J) return Gsl.checked(Gsl.besselJnu(_order.getValue(), x), "gsl_sf_bessel_Jnu_impl");
            return Gsl.checked(Gsl.besselYnu(_order.getValue(), x), "gsl_sf_bessel_Ynu_impl");
        }

        @Override
        public double call(Argument a) {
            return call(a.get(0));
        }
    }

    /** Genfun::IntegralOrder::Bessel: J_n or Y_n. */
    public static class IntegralOrder extends AbsFunction {

        private final Type _type;
        private final int _order;

        public IntegralOrder(Type type, int order) {
            _type = type;
            _order = order;
        }

        public IntegralOrder(IntegralOrder right) {
            _type = right._type;
            _order = right._order;
        }

        @Override
        public IntegralOrder copy() {
            return new IntegralOrder(this);
        }

        @Override
        public double call(double x) {
            if (_type == Type.J) return Gsl.checked(Gsl.besselJn(_order, x), "gsl_sf_bessel_Jn_impl");
            return Gsl.checked(Gsl.besselYn(_order, x), "gsl_sf_bessel_Yn_impl");
        }

        @Override
        public double call(Argument a) {
            return call(a.get(0));
        }
    }
}
