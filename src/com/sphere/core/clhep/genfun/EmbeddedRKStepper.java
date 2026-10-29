package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::EmbeddedRKStepper (EmbeddedRKStepper.hh, .cc): a step of an embedded Runge-Kutta pair,
 * its error the difference of the two estimates (Cash-Karp by default).
 */
public class EmbeddedRKStepper extends AdaptiveRKStepper.EEStepper {

    private final ExtendedButcherTableau tableau;

    public EmbeddedRKStepper() {
        this(new ExtendedButcherTableau.CashKarpXtTableau());
    }

    public EmbeddedRKStepper(ExtendedButcherTableau tableau) {
        this.tableau = new ExtendedButcherTableau(tableau);
    }

    @Override
    public double[] step(RKIntegrator.RKData data, RKIntegrator.RKData.Data s, RKIntegrator.RKData.Data d) {
        final double h = d.time - s.time;
        if (h <= 0) throw new RuntimeException("Runtime error in RKIntegrator (zero or negative stepsize)");
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
        final double[] errors = new double[nvar];
        for (int i = 0; i < tableau.nSteps(); i++) {
            for (int v = 0; v < nvar; v++) errors[v] += (h * (tableau.bHat(i) - tableau.b(i)) * k[i][v]);
        }
        return errors;
    }

    @Override
    public EmbeddedRKStepper copy() {
        return new EmbeddedRKStepper(tableau);
    }

    @Override
    public int order() {
        return tableau.order();
    }
}
