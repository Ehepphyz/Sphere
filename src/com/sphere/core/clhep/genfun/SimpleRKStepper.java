package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::SimpleRKStepper (SimpleRKStepper.hh, .cc): fixed steps of an explicit Runge-Kutta method
 * given by its Butcher tableau.
 */
public class SimpleRKStepper extends RKIntegrator.RKStepper {

    private final ButcherTableau tableau;
    private final double stepsize;

    public SimpleRKStepper(ButcherTableau tableau, double stepsize) {
        this.tableau = new ButcherTableau(tableau);
        this.stepsize = stepsize;
    }

    @Override
    public void step(RKIntegrator.RKData data, RKIntegrator.RKData.Data s, RKIntegrator.RKData.Data d, double timeLimit) {
        final double h = timeLimit == 0 ? stepsize : timeLimit - s.time;
        if (h <= 0) throw new RuntimeException("SimpleRKStepper:  negative stepsize");
        rkStep(tableau, data, s, d, h);
        d.time = timeLimit == 0 ? s.time + h : timeLimit;
    }

    /** One step of h from s, d.variable and d.firstDerivative set: the loop CLHEP's steppers share. */
    static void rkStep(ButcherTableau tableau, RKIntegrator.RKData data, RKIntegrator.RKData.Data s,
                       RKIntegrator.RKData.Data d, double h) {
        final int nvar = s.variable.length;
        final double[][] k = new double[tableau.nSteps()][];
        for (int i = 0; i < tableau.nSteps(); i++) {
            k[i] = new double[nvar];
            final Argument arg = new Argument(nvar);
            for (int v = 0; v < nvar; v++) arg.set(v, s.variable[v]);
            for (int j = 0; j < i; j++) {
                for (int v = 0; v < nvar; v++) arg.set(v, arg.get(v) + h * tableau.A(i, j) * k[j][v]);
            }
            for (int v = 0; v < nvar; v++) k[i][v] = data.diffEqn(v).call(arg);
        }
        for (int v = 0; v < nvar; v++) d.firstDerivative[v] = 0;
        for (int i = 0; i < tableau.nSteps(); i++) {
            for (int v = 0; v < nvar; v++) d.firstDerivative[v] += tableau.b(i) * k[i][v];
        }
        for (int v = 0; v < nvar; v++) d.variable[v] = s.variable[v] + h * d.firstDerivative[v];
    }

    @Override
    public SimpleRKStepper copy() {
        return new SimpleRKStepper(tableau, stepsize);
    }
}
