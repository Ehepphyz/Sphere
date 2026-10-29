package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/**
 * CLHEP's Genfun::AdaptiveRKStepper (AdaptiveRKStepper.hh, .cc): steps whose size follows the error an
 * error-estimating stepper (embedded pair or step doubling) reports, kept below the tolerance; the size
 * carried from step to step, reset at t = 0.
 */
public class AdaptiveRKStepper extends RKIntegrator.RKStepper {

    /** A step of the size d.time - s.time that also estimates its error, per variable. */
    public abstract static class EEStepper {

        public abstract double[] step(RKIntegrator.RKData data, RKIntegrator.RKData.Data sdata,
                                      RKIntegrator.RKData.Data ddata);

        public abstract EEStepper copy();

        public abstract int order();
    }

    private final EEStepper eeStepper;
    private double T;
    private double sStepsize;
    private double S;
    private double Rmin;
    private double Rmax;
    private double stepsize;

    /** With a Cash-Karp embedded stepper. */
    public AdaptiveRKStepper() {
        this((EEStepper) null);
    }

    public AdaptiveRKStepper(EEStepper stepper) {
        eeStepper = stepper != null ? stepper.copy() : new EmbeddedRKStepper();
        T = 1.0E-6;
        sStepsize = 0.01;
        S = 0.9;
        Rmin = 0.0;
        Rmax = 5.0;
        stepsize = sStepsize;
    }

    public AdaptiveRKStepper(AdaptiveRKStepper right) {
        eeStepper = right.eeStepper.copy();
        T = right.T;
        sStepsize = right.sStepsize;
        S = right.S;
        Rmin = right.Rmin;
        Rmax = right.Rmax;
        stepsize = right.sStepsize;
    }

    @Override
    public void step(RKIntegrator.RKData data, RKIntegrator.RKData.Data s, RKIntegrator.RKData.Data d, double timeLimit) {
        if (s.time == 0.0) stepsize = sStepsize;
        final int p = eeStepper.order();
        final double deltaMax = T * CMath.pow(S / Rmax, p + 1);
        final double TINY = 1.0E-30;
        double hnext;

        d.time = timeLimit == 0 ? s.time + stepsize : timeLimit;
        double h = d.time - s.time;
        while (true) {
            final double[] errors = eeStepper.step(data, s, d);
            if (timeLimit != 0.0) return;

            for (int e = 0; e < errors.length; e++) errors[e] = CMath.fabs(errors[e]);
            double delta = errors[0];
            for (double e : errors) {
                if (delta < e) delta = e;
            }
            if (delta > T) {
                h = CMath.max(S * h * CMath.pow(T / (delta + TINY), 1.0 / (p + 1)), Rmin * h);
                if (!(((s.time + h) - s.time) > 0)) throw new RuntimeException("Warning, RK Integrator step underflow");
                d.time = s.time + h;
                continue;
            } else {
                if (delta < deltaMax) {
                    hnext = S * h * CMath.pow(T / (delta + TINY), 1.0 / (p + 1));
                    if (hnext < h) hnext = h;
                } else {
                    hnext = Rmax * h;
                }
            }
            break;
        }
        stepsize = hnext;
    }

    @Override
    public AdaptiveRKStepper copy() {
        return new AdaptiveRKStepper(this);
    }

    public double tolerance() {
        return T;
    }

    public void setTolerance(double t) {
        T = t;
    }

    public double startingStepsize() {
        return sStepsize;
    }

    public void setStartingStepsize(double s) {
        sStepsize = s;
    }

    public double safetyFactor() {
        return S;
    }

    public void setSafetyFactor(double s) {
        S = s;
    }

    public double rmin() {
        return Rmin;
    }

    public void setRmin(double r) {
        Rmin = r;
    }

    public double rmax() {
        return Rmax;
    }

    public void setRmax(double r) {
        Rmax = r;
    }
}
