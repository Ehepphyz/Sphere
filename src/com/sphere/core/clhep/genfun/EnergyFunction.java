package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::EnergyFunction (ClassicalSolver.hh, .cc): a solver's Hamiltonian evaluated on its
 * solution, a function of time (constant for an exact solution).
 */
public class EnergyFunction extends AbsFunction {

    private final ClassicalSolver solver;

    public EnergyFunction(ClassicalSolver solver) {
        this.solver = solver;
    }

    public EnergyFunction(EnergyFunction right) {
        solver = right.solver;
    }

    @Override
    public EnergyFunction copy() {
        return new EnergyFunction(this);
    }

    @Override
    public double call(double t) {
        final PhaseSpace phaseSpace = solver.phaseSpace();
        final int DIM = phaseSpace.dim();
        final Argument arg = new Argument(2 * DIM);
        for (int i = 0; i < phaseSpace.dim(); i++) {
            arg.set(i, solver.equationOf(phaseSpace.coordinates().get(i)).call(t));
            arg.set(i + DIM, solver.equationOf(phaseSpace.momenta().get(i)).call(t));
        }
        final AbsFunction H = solver.hamiltonian();
        return H.call(arg);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
