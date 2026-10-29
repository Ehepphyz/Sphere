package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::AbsFunction (GenericFunctions/AbsFunction.hh, AbsFunction.cc): a function of one or
 * several variables, and the algebra that combines functions — sums, products, quotients, compositions
 * (f.of(g) for f(g)), direct products (%, a function of the variables of both), convolutions — with
 * numbers and parameters; each combination a function that keeps copies of its operands.
 *
 * <p>Derivatives are functions too: analytic where the class knows its own (hasAnalyticDerivative), a
 * numerical one otherwise ({@link FunctionNumDeriv}). CLHEP's Derivative is a FunctionNoop.
 *
 * <p>The C++ operators are methods (plus, minus, times, div, negate; the statics for a number or a
 * parameter on the left); operator() is call.
 */
public abstract class AbsFunction {

    protected AbsFunction() {
    }

    /** The number of variables. */
    public int dimensionality() {
        return 1;
    }

    /** f(x). */
    public abstract double call(double argument);

    /** f(x0, x1...). */
    public abstract double call(Argument argument);

    /** clone(): a copy of the same kind. */
    public abstract AbsFunction copy();

    /** f(g): the composition. */
    public FunctionComposition of(AbsFunction f) {
        return new FunctionComposition(this, f);
    }

    /** f(p): a parameter whose value is f of p's. */
    public ParameterComposition of(AbsParameter p) {
        return new ParameterComposition(this, p);
    }

    /** The derivative with respect to the variable v. */
    public FunctionNoop derivative(Variable v) {
        return partial(v.index());
    }

    /** The derivative of a function of one variable. */
    public FunctionNoop prime() {
        return partial(0);
    }

    public boolean hasAnalyticDerivative() {
        return false;
    }

    /** The partial derivative with respect to the variable index: numerical unless the class knows it. */
    public FunctionNoop partial(int index) {
        final FunctionNumDeriv fPrime = new FunctionNumDeriv(this, index);
        return new FunctionNoop(fPrime);
    }

    /* ---- functions with functions ---- */

    public FunctionSum plus(AbsFunction b) {
        return new FunctionSum(this, b);
    }

    public FunctionDifference minus(AbsFunction b) {
        return new FunctionDifference(this, b);
    }

    public FunctionProduct times(AbsFunction b) {
        return new FunctionProduct(this, b);
    }

    public FunctionQuotient div(AbsFunction b) {
        return new FunctionQuotient(this, b);
    }

    public FunctionNegation negate() {
        return new FunctionNegation(this);
    }

    /** operator %: the direct product, a function of this one's variables then b's. */
    public FunctionDirectProduct directProduct(AbsFunction b) {
        return new FunctionDirectProduct(this, b);
    }

    /** The convolution of a and b, integrated from x0 to x1. */
    public static FunctionConvolution convolve(AbsFunction a, AbsFunction b, double x0, double x1) {
        return new FunctionConvolution(a, b, x0, x1);
    }

    /* ---- functions with numbers ---- */

    /** f * c. */
    public ConstTimesFunction times(double c) {
        return new ConstTimesFunction(c, this);
    }

    /** f + c. */
    public ConstPlusFunction plus(double c) {
        return new ConstPlusFunction(c, this);
    }

    /** f - c: -c plus f. */
    public ConstPlusFunction minus(double c) {
        return new ConstPlusFunction(-c, this);
    }

    /** f / c: 1/c times f. */
    public ConstTimesFunction div(double c) {
        return new ConstTimesFunction(1 / c, this);
    }

    /** c * f. */
    public static ConstTimesFunction times(double c, AbsFunction op2) {
        return new ConstTimesFunction(c, op2);
    }

    /** c + f. */
    public static ConstPlusFunction plus(double c, AbsFunction op2) {
        return new ConstPlusFunction(c, op2);
    }

    /** c - f. */
    public static ConstMinusFunction minus(double c, AbsFunction op2) {
        return new ConstMinusFunction(c, op2);
    }

    /** c / f. */
    public static ConstOverFunction div(double c, AbsFunction op2) {
        return new ConstOverFunction(c, op2);
    }

    /* ---- functions with parameters ---- */

    /** f * p. */
    public FunctionTimesParameter times(AbsParameter p) {
        return new FunctionTimesParameter(p, this);
    }

    /** f + p. */
    public FunctionPlusParameter plus(AbsParameter p) {
        return new FunctionPlusParameter(p, this);
    }

    /** f - p: -p plus f. */
    public FunctionPlusParameter minus(AbsParameter p) {
        final AbsParameter minusP = p.negate();
        return new FunctionPlusParameter(minusP, this);
    }

    /** f / p: 1/p times f. */
    public FunctionTimesParameter div(AbsParameter p) {
        final AbsParameter oneOverP = AbsParameter.div(1.0, p);
        return new FunctionTimesParameter(oneOverP, this);
    }

    /** The warning a combination of functions of different dimensionalities prints (CLHEP then asserts). */
    static void dimensionWarning(String what) {
        com.sphere.core.hepmc3.cxx.StdStreams.cout().print("Warning:  dimension mismatch in " + what + "\n");
    }
}
