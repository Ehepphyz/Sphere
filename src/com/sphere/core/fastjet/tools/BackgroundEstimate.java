package com.sphere.core.fastjet.tools;

/** The result of a background estimation, fastjet::BackgroundEstimate. */
public class BackgroundEstimate {

    double rho, sigma, rhoM, sigmaM, meanArea;
    boolean hasSigma, hasRhoM;
    Object extras;

    public BackgroundEstimate() {
    }

    public BackgroundEstimate(BackgroundEstimate o) {
        rho = o.rho;
        sigma = o.sigma;
        rhoM = o.rhoM;
        sigmaM = o.sigmaM;
        meanArea = o.meanArea;
        hasSigma = o.hasSigma;
        hasRhoM = o.hasRhoM;
        extras = o.extras;
    }

    public double rho() { return rho; }
    public double sigma() { return sigma; }
    public boolean hasSigma() { return true; }
    public double rhoM() { return rhoM; }
    public double sigmaM() { return sigmaM; }
    public boolean hasRhoM() { return hasRhoM; }
    public double meanArea() { return meanArea; }
    public boolean hasExtras() { return extras != null; }

    public <T> T extras(Class<T> type) {
        return type.cast(extras);
    }

    public void reset() {
        rho = sigma = rhoM = sigmaM = meanArea = 0.0;
        hasSigma = hasRhoM = false;
        extras = null;
    }

    public void setRho(double v) { rho = v; }
    public void setSigma(double v) { sigma = v; }
    public void setHasSigma(boolean v) { hasSigma = v; }
    public void setRhoM(double v) { rhoM = v; }
    public void setSigmaM(double v) { sigmaM = v; }
    public void setHasRhoM(boolean v) { hasRhoM = v; }
    public void setMeanArea(double v) { meanArea = v; }
    public void setExtras(Object e) { extras = e; }

    public void applyRescalingFactor(double f) {
        rho *= f;
        sigma *= f;
        rhoM *= f;
        sigmaM *= f;
    }
}
