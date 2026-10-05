package com.sphere.core.minuit2;

/**
 * The trade between speed and reliability: low (0), medium (1, the
 * default), high (2), very high (3). It sets how many cycles and how tight
 * the tolerances of the numerical gradient and of Hesse are, whether Migrad
 * starts from a full Hessian (2) and when it recomputes it at the end, and
 * whether Hesse may return a matrix that is not positive-definite (3).
 */
public final class MnStrategy {

    private int strategy;
    private int gradNCyc;
    private double gradTlrStp;
    private double gradTlr;
    private int hessNCyc;
    private double hessTlrStp;
    private double hessTlrG2;
    private int hessGradNCyc;
    private int hessCFDG2 = 0;
    private int hessForcePosDef = 1;
    private int storeLevel = 1;

    public MnStrategy() {
        setMediumStrategy();
    }

    public MnStrategy(int stra) {
        if (stra == 0) setLowStrategy();
        else if (stra == 1) setMediumStrategy();
        else if (stra == 2) setHighStrategy();
        else setVeryHighStrategy();
    }

    public MnStrategy(MnStrategy s) {
        strategy = s.strategy;
        gradNCyc = s.gradNCyc;
        gradTlrStp = s.gradTlrStp;
        gradTlr = s.gradTlr;
        hessNCyc = s.hessNCyc;
        hessTlrStp = s.hessTlrStp;
        hessTlrG2 = s.hessTlrG2;
        hessGradNCyc = s.hessGradNCyc;
        hessCFDG2 = s.hessCFDG2;
        hessForcePosDef = s.hessForcePosDef;
        storeLevel = s.storeLevel;
    }

    /** The level: 0 to 3. */
    public int strategy() {
        return strategy;
    }

    public int gradientNCycles() {
        return gradNCyc;
    }

    public double gradientStepTolerance() {
        return gradTlrStp;
    }

    public double gradientTolerance() {
        return gradTlr;
    }

    public int hessianNCycles() {
        return hessNCyc;
    }

    public double hessianStepTolerance() {
        return hessTlrStp;
    }

    public double hessianG2Tolerance() {
        return hessTlrG2;
    }

    public int hessianGradientNCycles() {
        return hessGradNCyc;
    }

    public int hessianCentralFDMixedDerivatives() {
        return hessCFDG2;
    }

    public int hessianForcePosDef() {
        return hessForcePosDef;
    }

    public int storageLevel() {
        return storeLevel;
    }

    public boolean refineGradientInHessian() {
        return strategy > 0;
    }

    public boolean computeInitialHessian() {
        return strategy == 2;
    }

    /** Above this relative change of the covariance in its last step, Migrad runs Hesse at the end. */
    public double hessianRecomputeThreshold() {
        if (strategy == 0) return Double.POSITIVE_INFINITY;
        if (strategy == 1) return 0.05;
        return Double.NEGATIVE_INFINITY;
    }

    public void setGradientNCycles(int n) {
        gradNCyc = n;
    }

    public void setGradientStepTolerance(double stp) {
        gradTlrStp = stp;
    }

    public void setGradientTolerance(double toler) {
        gradTlr = toler;
    }

    public void setHessianNCycles(int n) {
        hessNCyc = n;
    }

    public void setHessianStepTolerance(double stp) {
        hessTlrStp = stp;
    }

    public void setHessianG2Tolerance(double toler) {
        hessTlrG2 = toler;
    }

    public void setHessianGradientNCycles(int n) {
        hessGradNCyc = n;
    }

    public void setHessianCentralFDMixedDerivatives(int flag) {
        hessCFDG2 = flag;
    }

    public void setHessianForcePosDef(int flag) {
        hessForcePosDef = flag;
    }

    public void setStorageLevel(int level) {
        storeLevel = level;
    }

    /** The level below (used by Minos and contours for their own minimizations). */
    MnStrategy nextLower() {
        return new MnStrategy(Math.max(0, strategy - 1));
    }

    private void setLowStrategy() {
        strategy = 0;
        setGradientNCycles(2);
        setGradientStepTolerance(0.5);
        setGradientTolerance(0.1);
        setHessianNCycles(3);
        setHessianStepTolerance(0.5);
        setHessianG2Tolerance(0.1);
        setHessianGradientNCycles(1);
        setHessianCentralFDMixedDerivatives(0);
    }

    private void setMediumStrategy() {
        strategy = 1;
        setGradientNCycles(3);
        setGradientStepTolerance(0.3);
        setGradientTolerance(0.05);
        setHessianNCycles(5);
        setHessianStepTolerance(0.3);
        setHessianG2Tolerance(0.05);
        setHessianGradientNCycles(2);
        setHessianCentralFDMixedDerivatives(0);
    }

    private void setHighStrategy() {
        strategy = 2;
        setGradientNCycles(5);
        setGradientStepTolerance(0.1);
        setGradientTolerance(0.02);
        setHessianNCycles(7);
        setHessianStepTolerance(0.1);
        setHessianG2Tolerance(0.02);
        setHessianGradientNCycles(6);
        setHessianCentralFDMixedDerivatives(0);
    }

    private void setVeryHighStrategy() {
        strategy = 3;
        setGradientNCycles(5);
        setGradientStepTolerance(0.1);
        setGradientTolerance(0.02);
        setHessianNCycles(7);
        setHessianStepTolerance(0.);
        setHessianG2Tolerance(0.);
        setHessianGradientNCycles(6);
        setHessianCentralFDMixedDerivatives(1);
        setHessianForcePosDef(0);
    }
}
