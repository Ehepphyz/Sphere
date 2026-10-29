package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::Variable (GenericFunctions/Variable.hh, Variable.cc): the variable selectionIndex of a
 * function of dimensionality variables — the building block of functions written as expressions.
 */
public class Variable extends AbsFunction {

    private final int _selectionIndex;
    private final int _dimensionality;

    public Variable() {
        this(0, 1);
    }

    public Variable(int selectionIndex) {
        this(selectionIndex, 1);
    }

    public Variable(int selectionIndex, int dimensionality) {
        _selectionIndex = selectionIndex;
        _dimensionality = dimensionality;
    }

    public Variable(Variable right) {
        _selectionIndex = right._selectionIndex;
        _dimensionality = right._dimensionality;
    }

    @Override
    public Variable copy() {
        return new Variable(this);
    }

    @Override
    public double call(double x) {
        if (_selectionIndex != 0) throw new RuntimeException("Genfun::Variable: selection index !=0");
        return x;
    }

    @Override
    public double call(Argument a) {
        if (!(_selectionIndex < a.dimension())) throw new RuntimeException("Genfun::Varaible selection index out of bounds");
        return a.get(_selectionIndex);
    }

    @Override
    public int dimensionality() {
        return _dimensionality;
    }

    public int index() {
        return _selectionIndex;
    }

    /** The Kronecker delta of the indices, as the direct product of dimensionality constants. */
    @Override
    public FunctionNoop partial(int mindex) {
        final int kroneckerDelta = mindex == _selectionIndex ? 1 : 0;
        AbsFunction f = new FixedConstant(kroneckerDelta);
        for (int i = 1; i < _dimensionality; i++) {
            final AbsFunction g = f.directProduct(new FixedConstant(kroneckerDelta));
            f = g.copy();
        }
        return new FunctionNoop(f);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
