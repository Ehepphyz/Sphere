package com.sphere.core.clhep.genfun;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Classical::RungeKuttaSolver (RungeKuttaClassicalSolver.hh, .cc): Hamilton's equations
 * dq/dt = ∂H/∂p, dp/dt = -∂H/∂q integrated by an {@link RKIntegrator}. The Hamiltonian is held, not copied.
 */
public class RungeKuttaSolver extends ClassicalSolver {

    private final AbsFunction H;
    private final PhaseSpace phaseSpace;
    private final RKIntegrator integrator;
    private final List<Parameter> startingQ = new ArrayList<>();
    private final List<Parameter> startingP = new ArrayList<>();
    private EnergyFunction energy;

    public RungeKuttaSolver(AbsFunction H, PhaseSpace phaseSpace) {
        this(H, phaseSpace, null);
    }

    public RungeKuttaSolver(AbsFunction H, PhaseSpace phaseSpace, RKIntegrator.RKStepper stepper) {
        this.H = H;
        this.phaseSpace = phaseSpace;
        integrator = new RKIntegrator(stepper);
        final int DIM = phaseSpace.dim();
        final PhaseSpace.Component X = phaseSpace.coordinates();
        final PhaseSpace.Component P = phaseSpace.momenta();
        for (int i = 0; i < DIM; i++) {
            final AbsFunction DXDT = H.partial(P.get(i).index());
            startingQ.add(integrator.addDiffEquation(DXDT, "X", phaseSpace.startValue(X.get(i))));
        }
        for (int i = 0; i < DIM; i++) {
            final AbsFunction DPDT = H.partial(X.get(i).index()).negate();
            startingP.add(integrator.addDiffEquation(DPDT, "P", phaseSpace.startValue(P.get(i))));
        }
        energy = null;
    }

    @Override
    public AbsFunction equationOf(Variable v) {
        return integrator.getFunction(v.index());
    }

    @Override
    public AbsFunction hamiltonian() {
        return H;
    }

    @Override
    public PhaseSpace phaseSpace() {
        return phaseSpace;
    }

    @Override
    public AbsFunction energy() {
        if (energy == null) energy = new EnergyFunction(this);
        return energy;
    }

    @Override
    public Parameter createControlParameter(String variableName, double defStartingValue, double startingValueMin,
                                            double startingValueMax) {
        return integrator.createControlParameter(variableName, defStartingValue, startingValueMin, startingValueMax);
    }

    @Override
    public Parameter takeQ0(int index) {
        return startingQ.get(index);
    }

    @Override
    public Parameter takeP0(int index) {
        return startingP.get(index);
    }
}
