package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;

import java.util.Arrays;
import java.util.List;

/**
 * What background estimators share, fastjet::BackgroundEstimatorBase: the
 * median and its one-sigma quantile, allowing for empty jets, and an optional
 * rescaling with the jet's position.
 */
public abstract class BackgroundEstimatorBase {

    private static final LimitedWarning WARNINGS_EMPTY_AREA = new LimitedWarning();

    protected FunctionOfPseudoJet<Double> rescalingClass;
    protected boolean cacheAvailable;
    protected BackgroundEstimate cachedEstimate = new BackgroundEstimate();

    public abstract void setParticles(List<PseudoJet> particles);

    public void setParticlesWithSeed(List<PseudoJet> particles, int[] seed) {
        setParticles(particles);
    }

    public abstract BackgroundEstimate estimate();

    public abstract BackgroundEstimate estimate(PseudoJet jet);

    public abstract double rho();

    public double sigma() {
        throw new FastJetException("sigma() not supported for this Background Estimator");
    }

    public abstract double rho(PseudoJet jet);

    public double sigma(PseudoJet jet) {
        throw new FastJetException("sigma(jet) not supported for this Background Estimator");
    }

    public boolean hasSigma() {
        return false;
    }

    public double rhoM() {
        throw new FastJetException("rho_m() not supported for this Background Estimator");
    }

    public double sigmaM() {
        throw new FastJetException("sigma_m() not supported for this Background Estimator");
    }

    public double rhoM(PseudoJet jet) {
        throw new FastJetException("rho_m(jet) not supported for this Background Estimator");
    }

    public double sigmaM(PseudoJet jet) {
        throw new FastJetException("sigma_m(jet) not supported for this Background Estimator");
    }

    public boolean hasRhoM() {
        return false;
    }

    public void setRescalingClass(FunctionOfPseudoJet<Double> r) {
        rescalingClass = r;
    }

    public FunctionOfPseudoJet<Double> rescalingClass() {
        return rescalingClass;
    }

    public abstract String description();

    /** {median, standard deviation if gaussian}. */
    protected double[] medianAndStddev(double[] quantities, double nEmptyJets, boolean fj2) {
        if (quantities.length == 0) {
            return new double[]{0, 0};
        }
        final double[] sorted = quantities.clone();
        Arrays.sort(sorted);
        if (nEmptyJets < -sorted.length / 4.0) {
            WARNINGS_EMPTY_AREA.warn("BackgroundEstimatorBase::_median_and_stddev(...): the estimated empty area is suspiciously large and negative and may lead to an over-estimation of rho. This may be due to (i) a rare statistical fluctuation or (ii) too small a range used to estimate the background properties.");
        }
        final double r0 = percentile(sorted, 0.5, nEmptyJets, fj2);
        final double r1 = percentile(sorted, (1.0 - 0.6827) / 2.0, nEmptyJets, fj2);
        return new double[]{r0, r0 - r1};
    }

    /** A percentile of sorted values, counting nempty zeros below them. */
    protected static double percentile(double[] sorted, double percentile, double nempty, boolean fj2) {
        final int size = sorted.length;
        if (size == 0) return 0;
        final double totalNjets = size + nempty;
        double pos = fj2 ? (totalNjets - 1) * percentile - nempty : totalNjets * percentile - nempty - 0.5;
        if (pos >= 0 && size > 1) {
            int ipos = (int) pos;
            if (ipos + 1 > size - 1) {
                ipos = size - 2;
                pos = size - 1;
            }
            return sorted[ipos] * (ipos + 1 - pos) + sorted[ipos + 1] * (pos - ipos);
        } else if (pos > -0.5 && size >= 1 && !fj2) {
            return sorted[0];
        }
        return 0.0;
    }

    /** rho rescaled as a polynomial in rapidity, fastjet::BackgroundRescalingYPolynomial. */
    public static final class BackgroundRescalingYPolynomial implements FunctionOfPseudoJet<Double> {
        private final double a0, a1, a2, a3, a4;

        public BackgroundRescalingYPolynomial(double a0, double a1, double a2, double a3, double a4) {
            this.a0 = a0;
            this.a1 = a1;
            this.a2 = a2;
            this.a3 = a3;
            this.a4 = a4;
        }

        @Override
        public Double result(PseudoJet jet) {
            final double y = jet.rap();
            final double y2 = y * y;
            return a0 + a1 * y + a2 * y2 + a3 * y2 * y + a4 * y2 * y2;
        }
    }
}
