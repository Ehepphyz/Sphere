package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Classical::Solver (ClassicalSolver.hh): the motion of a classical system from its Hamiltonian,
 * each coordinate and momentum a function of time.
 */
public abstract class ClassicalSolver {

    /** The solution for a coordinate or momentum of the phase space. */
    public abstract AbsFunction equationOf(Variable v);

    public abstract PhaseSpace phaseSpace();

    public abstract AbsFunction hamiltonian();

    /** The Hamiltonian along the solution, a function of time. */
    public abstract AbsFunction energy();

    /** The parameter of the starting value of coordinate index. */
    public abstract Parameter takeQ0(int index);

    /** The parameter of the starting value of momentum index. */
    public abstract Parameter takeP0(int index);

    public Parameter createControlParameter() {
        return createControlParameter("anon", 0.0, 0.0, 0.0);
    }

    public abstract Parameter createControlParameter(String variableName, double defStartingValue,
                                                     double startingValueMin, double startingValueMax);
}
