package com.sphere.core.minuit2.validation;

import com.sphere.core.hepmc3.cxx.CRand;
import com.sphere.core.minuit2.Cxx;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.ParametricFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The helpers of Minuit2's test/MnSim directory: random numbers through the
 * C library's rand(), a Gaussian, the data generator and the chi-square
 * functions, written as the C++ writes them (same operations, same order,
 * the C library's exp, log and cos) so that the data and the fits are bit
 * for bit those of the C++ test programs.
 */
public final class MnSim {

    private MnSim() {
    }

    static final double TWO_PI = 2 * 3.14159265358979323846;

    /** A flat random number in [mean - delta, mean + delta]. */
    record FlatRandomGen(double mean, double delta) {
        FlatRandomGen() {
            this(0.5, 0.5);
        }

        double next() {
            return 2. * delta() * (CRand.rand() / (double) CRand.randMax() - 0.5) + mean();
        }
    }

    /** A Gaussian random number by the Box-Muller formula. */
    record GaussRandomGen(double mean, double sigma) {
        GaussRandomGen() {
            this(0., 1.);
        }

        double next() {
            final double r1 = CRand.rand() / (double) CRand.randMax();
            final double r2 = CRand.rand() / (double) CRand.randMax();
            final double s = Math.sqrt(-2. * Cxx.log(1. - r1)) * Cxx.cos(TWO_PI * r2);
            return sigma() * s + mean();
        }
    }

    /** c exp(-(x-m)^2 / 2s^2) / (sqrt(2 pi) s). */
    record GaussFunction(double m, double s, double c) {
        double value(double x) {
            return c() * Cxx.exp(-0.5 * (x - m()) * (x - m()) / (s() * s())) / (Math.sqrt(TWO_PI) * s());
        }
    }

    /** n measurements of a Gaussian of random mean and variance, with a little noise. */
    static final class GaussDataGen {
        final double simMean;
        final double simVar;
        final List<Double> positions = new ArrayList<>();
        final List<Double> measurements = new ArrayList<>();
        final List<Double> variances = new ArrayList<>();

        GaussDataGen(int n) {
            final FlatRandomGen randMean = new FlatRandomGen(0., 50.);
            final FlatRandomGen randVar = new FlatRandomGen(6., 5.);
            final double mvariance = 0.01 * 0.01;
            final GaussRandomGen randMvar = new GaussRandomGen(0., 0.01);
            simMean = randMean.next();
            simVar = randVar.next();
            final double simSig = Math.sqrt(simVar);
            final double simConst = 1.;
            final GaussFunction gaussSim = new GaussFunction(simMean, simSig, simConst);
            for (int i = 0; i < n; i++) {
                final double position = simMean - 5. * simSig + (double) i * 10. * simSig / (double) n;
                positions.add(position);
                final double epsilon = randMvar.next();
                measurements.add(gaussSim.value(position) + epsilon);
                variances.add(mvariance);
            }
        }

        double[] positions() {
            return toArray(positions);
        }

        double[] measurements() {
            return toArray(measurements);
        }

        double[] variances() {
            return toArray(variances);
        }
    }

    static double[] toArray(List<Double> l) {
        final double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** The chi-square of a Gaussian (mean, sigma, area) to the measurements. */
    static final class GaussFcn implements FCNBase {
        private final double[] measurements;
        private final double[] positions;
        private final double[] mVariances;
        private double errorDef = 1.;

        GaussFcn(double[] meas, double[] pos, double[] mvar) {
            measurements = meas.clone();
            positions = pos.clone();
            mVariances = mvar.clone();
        }

        @Override
        public double up() {
            return errorDef;
        }

        @Override
        public double value(double[] par) {
            final GaussFunction gauss = new GaussFunction(par[0], par[1], par[2]);
            double chi2 = 0.;
            for (int n = 0; n < measurements.length; n++) {
                chi2 += ((gauss.value(positions[n]) - measurements[n]) * (gauss.value(positions[n]) - measurements[n])
                    / mVariances[n]);
            }
            return chi2;
        }

        double[] measurements() {
            return measurements.clone();
        }

        double[] positions() {
            return positions.clone();
        }

        @Override
        public void setErrorDef(double def) {
            errorDef = def;
        }
    }

    /** The chi-square of two Gaussians. */
    static final class GaussFcn2 implements FCNBase {
        private final double[] measurements;
        private final double[] positions;
        private final double[] mVariances;
        @SuppressWarnings("unused")
        private double min;

        GaussFcn2(double[] meas, double[] pos, double[] mvar) {
            measurements = meas.clone();
            positions = pos.clone();
            mVariances = mvar.clone();
            init();
        }

        private void init() {
            final int nmeas = measurements.length;
            double x = 0.;
            double x2 = 0.;
            double norm = 0.;
            final double dx = positions[1] - positions[0];
            double c = 0.;
            for (int i = 0; i < nmeas; i++) {
                norm += measurements[i];
                x += (measurements[i] * positions[i]);
                x2 += (measurements[i] * positions[i] * positions[i]);
                c += dx * measurements[i];
            }
            final double mean = x / norm;
            final double rms2 = x2 / norm - mean * mean;
            min = value(new double[] {mean, Math.sqrt(rms2), c, mean, Math.sqrt(rms2), c});
        }

        @Override
        public double up() {
            return 1.;
        }

        @Override
        public double value(double[] par) {
            final GaussFunction gauss1 = new GaussFunction(par[0], par[1], par[2]);
            final GaussFunction gauss2 = new GaussFunction(par[3], par[4], par[5]);
            double chi2 = 0.;
            final int nmeas = measurements.length;
            for (int n = 0; n < nmeas; n++) {
                chi2 += ((gauss1.value(positions[n]) + gauss2.value(positions[n]) - measurements[n])
                    * (gauss1.value(positions[n]) + gauss2.value(positions[n]) - measurements[n]) / mVariances[n]);
            }
            return chi2;
        }

        double[] measurements() {
            return measurements.clone();
        }

        double[] positions() {
            return positions.clone();
        }
    }

    /**
     * The model of DemoFumili: a normalized Gaussian whose "parameter" is the
     * position and whose "coordinates" are the fit's mean, sigma and area.
     */
    static final class GaussianModelFunction extends ParametricFunction {
        GaussianModelFunction() {
            super(1);
            setParameters(new double[] {0.0});
        }

        @Override
        public double value(double[] x) {
            return x[2] * Cxx.exp(-0.5 * (par[0] - x[0]) * (par[0] - x[0]) / (x[1] * x[1]))
                / (Math.sqrt(TWO_PI) * Math.abs(x[1]));
        }

        @Override
        public double value(double[] x, double[] param) {
            return x[2] * Cxx.exp(-0.5 * (param[0] - x[0]) * (param[0] - x[0]) / (x[1] * x[1]))
                / (Math.sqrt(TWO_PI) * Math.abs(x[1]));
        }

        @Override
        public double up() {
            return 1.0;
        }

        @Override
        public double[] getGradient(double[] x) {
            final double[] param = getParameters();
            final double[] grad = new double[x.length];
            final double y = (param[0] - x[0]) / x[1];
            final double gaus = Cxx.exp(-0.5 * y * y) / (Math.sqrt(TWO_PI) * Math.abs(x[1]));
            grad[0] = y / (x[1]) * gaus * x[2];
            grad[1] = x[2] * gaus * (y * y - 1.0) / x[1];
            grad[2] = gaus;
            return grad;
        }
    }
}
