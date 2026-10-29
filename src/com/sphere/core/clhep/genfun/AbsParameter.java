package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::AbsParameter (GenericFunctions/AbsParameter.hh, AbsParameter.cc): a value a function
 * depends on, and the algebra of parameters (sums, products... of parameters and numbers, each a
 * parameter whose value follows its operands').
 */
public abstract class AbsParameter {

    protected AbsParameter() {
    }

    public abstract double getValue();

    /** clone(): a copy of the same kind. */
    public abstract AbsParameter copy();

    /** This as a Parameter, null for the others. */
    public Parameter parameter() {
        return null;
    }

    /* ---- the operators ---- */

    public ParameterSum plus(AbsParameter b) {
        return new ParameterSum(this, b);
    }

    public ParameterDifference minus(AbsParameter b) {
        return new ParameterDifference(this, b);
    }

    public ParameterProduct times(AbsParameter b) {
        return new ParameterProduct(this, b);
    }

    public ParameterQuotient div(AbsParameter b) {
        return new ParameterQuotient(this, b);
    }

    public ParameterNegation negate() {
        return new ParameterNegation(this);
    }

    /** p * c. */
    public ConstTimesParameter times(double c) {
        return new ConstTimesParameter(c, this);
    }

    /** p + c. */
    public ConstPlusParameter plus(double c) {
        return new ConstPlusParameter(c, this);
    }

    /** p - c: c's opposite plus p. */
    public ConstPlusParameter minus(double c) {
        return new ConstPlusParameter(-c, this);
    }

    /** p / c: 1/c times p. */
    public ConstTimesParameter div(double c) {
        return new ConstTimesParameter(1 / c, this);
    }

    /** c * p. */
    public static ConstTimesParameter times(double c, AbsParameter op2) {
        return new ConstTimesParameter(c, op2);
    }

    /** c + p. */
    public static ConstPlusParameter plus(double c, AbsParameter op2) {
        return new ConstPlusParameter(c, op2);
    }

    /** c - p. */
    public static ConstMinusParameter minus(double c, AbsParameter op2) {
        return new ConstMinusParameter(c, op2);
    }

    /** c / p. */
    public static ConstOverParameter div(double c, AbsParameter op2) {
        return new ConstOverParameter(c, op2);
    }

    /** p * f. */
    public FunctionTimesParameter times(AbsFunction f) {
        return new FunctionTimesParameter(this, f);
    }

    /** p + f. */
    public FunctionPlusParameter plus(AbsFunction f) {
        return new FunctionPlusParameter(this, f);
    }

    /** p - f: p plus -f. */
    public FunctionPlusParameter minus(AbsFunction f) {
        final AbsFunction minusF = f.negate();
        return new FunctionPlusParameter(this, minusF);
    }

    /** p / f: p times 1/f. */
    public FunctionTimesParameter div(AbsFunction f) {
        final AbsFunction oneOverF = AbsFunction.div(1.0, f);
        return new FunctionTimesParameter(this, oneOverF);
    }

    /** The clone of a parameter, connected to it when both are Parameters (what the composites do). */
    static AbsParameter connectedCopy(AbsParameter p) {
        final AbsParameter c = p.copy();
        if (p.parameter() != null && c.parameter() != null) c.parameter().connectFrom(p.parameter());
        return c;
    }
}
