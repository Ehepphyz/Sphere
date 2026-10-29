package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::AbsFunctional (AbsFunctional.hh): a number from a function, its operator[]. */
public abstract class AbsFunctional {

    /** The value of the functional on the function. */
    public abstract double apply(AbsFunction function);
}
