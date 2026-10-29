package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/**
 * CLHEP's Genfun::StepDoublingRKStepper (StepDoublingRKStepper.hh, .cc): a step and two half steps of a
 * Runge-Kutta method, their difference the error and their Richardson extrapolation the result.
 */
public class StepDoublingRKStepper extends AdaptiveRKStepper.EEStepper {

    private final ButcherTableau tableau;

    public StepDoublingRKStepper(ButcherTableau tableau) {
        this.tableau = new ButcherTableau(tableau);
    }

    @Override
    public double[] step(RKIntegrator.RKData data, RKIntegrator.RKData.Data s, RKIntegrator.RKData.Data d) {
        final int nvar = s.variable.length;
        final RKIntegrator.RKData.Data d1 = new RKIntegrator.RKData.Data(nvar);
        final RKIntegrator.RKData.Data d2 = new RKIntegrator.RKData.Data(nvar);
        doStep(data, s, d);
        final double dt = (d.time - s.time);
        d1.time = s.time + dt / 2.0;
        d2.time = d.time;
        doStep(data, s, d1);
        doStep(data, d1, d2);
        final double[] errors = new double[nvar];
        for (int v = 0; v < nvar; v++) errors[v] = CMath.fabs(d2.variable[v] - d.variable[v]);
        for (int v = 0; v < nvar; v++) {
            d.variable[v] = d2.variable[v] + ((d2.variable[v] - d.variable[v]) / CMath.pow(2., tableau.order() - 1));
        }
        return errors;
    }

    /** A step from s to d.time. */
    public void doStep(RKIntegrator.RKData data, RKIntegrator.RKData.Data s, RKIntegrator.RKData.Data d) {
        final double h = (d.time - s.time);
        if (h <= 0) throw new RuntimeException("SimpleRKStepper:  negative stepsize");
        SimpleRKStepper.rkStep(tableau, data, s, d, h);
    }

    @Override
    public StepDoublingRKStepper copy() {
        return new StepDoublingRKStepper(tableau);
    }

    @Override
    public int order() {
        return tableau.order();
    }
}
