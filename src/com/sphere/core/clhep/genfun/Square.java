package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Square (Square.hh, Square.cc): x². */
public class Square extends AbsFunction {

    public Square() {
    }

    public Square(Square right) {
    }

    @Override
    public Square copy() {
        return new Square(this);
    }

    @Override
    public double call(double x) {
        return x * x;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final Variable x = new Variable();
        final AbsFunction fPrime = AbsFunction.times(2, x);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
