package com.sphere.core.fjcontrib.genericsubtractor;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.BackgroundEstimatorBase;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.List;

/**
 * Pileup subtraction of any jet shape, fastjet::contrib::GenericSubtractor
 * (GenericSubtractor 1.3.1; G. Soyez, G.P. Salam, J. Kim, S. Dutta and
 * M. Cacciari, Phys. Rev. Lett. 110 (2013) 162001): the shape is computed
 * with the jet's ghosts given pt (and m_delta) scales h, its derivatives with
 * respect to the ghost scale extrapolated from a ladder of 28 steps (the one
 * where they are most stable being kept), and the shape's value at the
 * background density rho subtracted to second order.
 */
public class GenericSubtractor {

    static {
        ContribCitations.use("genericsubtractor");
    }

    private static final double INVALID_RHO = Double.NEGATIVE_INFINITY;
    private static final LimitedWarning WARNING_DEPRECATED = new LimitedWarning();
    private static final LimitedWarning WARNING_UNUSED_RHOM = new LimitedWarning();

    protected BackgroundEstimatorBase bgeRho;
    protected BackgroundEstimatorBase bgeRhom;
    protected double jetPtFraction = 0.01;
    protected boolean commonBge;
    protected boolean rhomFromBgeRhom;
    protected double rho = INVALID_RHO;
    protected double rhom;
    protected boolean externallySuppliedRhoRhom;

    public GenericSubtractor() {
    }

    public GenericSubtractor(BackgroundEstimatorBase bgeRho) {
        this(bgeRho, null);
    }

    public GenericSubtractor(BackgroundEstimatorBase bgeRho, BackgroundEstimatorBase bgeRhom) {
        this.bgeRho = bgeRho;
        this.bgeRhom = bgeRhom;
    }

    public GenericSubtractor(double rho, double rhom) {
        if (!(rho >= 0) || !(rhom >= 0)) throw new FastJetException("GenericSubtractor: rho and rho_m must be >= 0");
        this.rho = rho;
        this.rhom = rhom;
        this.externallySuppliedRhoRhom = true;
    }

    public GenericSubtractor(double rho) {
        this(rho, 0);
    }

    public String description() {
        if (externallySuppliedRhoRhom) {
            return "GenericSubtractor using externally supplied rho = " + Fmt.g(rho) + " and rho_m = " + Fmt.g(rhom)
                + " to describe the background";
        }
        if (bgeRhom != null) {
            return "GenericSubtractor using [" + bgeRho.description() + "] and [" + bgeRhom.description() + "] to estimate the background";
        }
        return "GenericSubtractor using [" + bgeRho.description() + "] to estimate the background";
    }

    /** The subtracted shape (second order). */
    public double apply(FunctionOfPseudoJet<Double> shape, PseudoJet jet) {
        return apply(shape, jet, new GenericSubtractorInfo());
    }

    /** The subtracted shape, the details of the subtraction going into info. */
    public double apply(FunctionOfPseudoJet<Double> shape, PseudoJet jet, GenericSubtractorInfo info) {
        if (bgeRho == null && !externallySuppliedRhoRhom) {
            throw new FastJetException("GenericSubtractor::operator(): generic subtraction needs a JetMedianBackgroundEstimator or a value for rho");
        }
        final ShapeWithPartition partitioned = shape instanceof ShapeWithPartition s ? s : null;
        final PseudoJet workingJet = partitioned != null ? partitioned.partition(jet) : jet;
        if (shape instanceof ShapeWithComponents components) {
            return componentSubtraction(components, workingJet, info);
        }
        final List<PseudoJet> ghosts = Selector.isPureGhost().apply(workingJet.constituents());
        if (ghosts.isEmpty()) {
            info.unsubtracted = partitioned != null ? partitioned.resultFromPartition(workingJet) : shape.result(jet);
            info.firstOrderSubtracted = info.unsubtracted;
            info.secondOrderSubtracted = info.unsubtracted;
            info.thirdOrderSubtracted = info.unsubtracted;
            info.firstDerivative = info.secondDerivative = info.thirdDerivative = 0.0;
            info.ghostScaleUsed = 0;
            return info.unsubtracted;
        }
        double ghostScale = 0.0;
        for (PseudoJet g : ghosts) ghostScale += g.perp();
        ghostScale /= ghosts.size();
        final double f0 = shapeWithRescaledGhosts(shape, workingJet, ghostScale, ghostScale, 0);
        info.unsubtracted = f0;
        final double ghostArea = ghosts.get(0).area();
        double rhoHere;
        double rhomHere;
        if (externallySuppliedRhoRhom) {
            rhoHere = rho;
            rhomHere = rhom;
        } else {
            rhoHere = bgeRho.rho(jet);
            if (bgeRhom != null) {
                rhomHere = rhomFromBgeRhom ? bgeRhom.rhoM(jet) : bgeRhom.rho(jet);
            } else if (commonBge) {
                if (bgeRho.hasRhoM()) {
                    rhomHere = bgeRho.rhoM(jet);
                } else {
                    final JetMedianBackgroundEstimator jmbge = (JetMedianBackgroundEstimator) bgeRho;
                    final FunctionOfPseudoJet<Double> original = jmbge.jetDensityClass();
                    jmbge.setJetDensityClass(com.sphere.core.fjcontrib.constituentsubtractor.ConstituentSubtractor.ptmDensity());
                    rhomHere = jmbge.rho(jet);
                    jmbge.setJetDensityClass(original);
                }
            } else {
                final double threshold = 1e-5;
                if (bgeRho.hasRhoM() && bgeRho.rhoM(jet) > threshold * rhoHere) {
                    WARNING_UNUSED_RHOM.warn("GenericSubtractor::operator(): Background estimator indicates non-zero rho_m, but the generic subtractor does not use rho_m information; consider calling set_common_bge_for_rho_and_rhom(true) to include the rho_m information");
                }
                rhomHere = 0.0;
            }
        }
        info.rho = rhoHere;
        info.rhom = rhomHere;
        final double rhoSum = rhoHere + rhomHere;
        final double rhoPtFraction = rhoSum == 0 ? 0.0 : rhoHere / rhoSum;
        computeDerivatives(shape, workingJet, ghostScale, ghostArea, f0, rhoPtFraction, info);
        info.firstOrderSubtracted = f0 - rhoSum * info.firstDerivative;
        info.secondOrderSubtracted = info.firstOrderSubtracted + 0.5 * CRMath.pow(rhoSum, 2) * info.secondDerivative;
        info.thirdOrderSubtracted = info.secondOrderSubtracted - CRMath.pow(rhoSum, 3) / 6.0 * info.thirdDerivative;
        return info.secondOrderSubtracted;
    }

    public void setCommonBgeForRhoAndRhom(boolean value) {
        if (value) {
            if (bgeRhom != null) throw new FastJetException("GenericSubtractor::use_common_bge_for_rho_and_rhom() is not allowed in the presence of an existing background estimator for rho_m.");
            if (externallySuppliedRhoRhom) throw new FastJetException("GenericSubtractor::use_common_bge_for_rho_and_rhom() is not allowed when supplying externally the values for rho and rho_m.");
            if (!bgeRho.hasRhoM() && !(bgeRho instanceof JetMedianBackgroundEstimator)) {
                throw new FastJetException("GenericSubtractor::use_common_bge_for_rho_and_rhom() is currently only allowed for background estimators of JetMedianBackgroundEstimator type.");
            }
        }
        commonBge = value;
    }

    public void setCommonBgeForRhoAndRhom() {
        setCommonBgeForRhoAndRhom(true);
    }

    @Deprecated
    public void useCommonBgeForRhoAndRhom(boolean value) {
        WARNING_DEPRECATED.warn("GenericSubtractor::use_common_bge_for_rho_and_rhom(bool value) is deprecated (as of version 1.3.0 of GenericSubtractor) and should be replaced by set_common_bge_for_rho_and_rhom(value). It may be removed in a future release.");
        setCommonBgeForRhoAndRhom(value);
    }

    public boolean commonBgeForRhoAndRhom() { return commonBge; }

    public void setUseBgeRhomRhom(boolean value) {
        if (!value) {
            rhomFromBgeRhom = false;
            return;
        }
        if (bgeRhom == null) throw new FastJetException("GenericSubtractor::use_rhom_from_bge_rhom() requires a background estimator for rho_m.");
        if (!bgeRhom.hasRhoM()) throw new FastJetException("GenericSubtractor::use_rhom_from_bge_rhom() requires rho_m support for the background estimator for rho_m.");
        rhomFromBgeRhom = true;
    }

    public boolean useBgeRhomRhom() { return rhomFromBgeRhom; }

    /** The fraction of the jet pt that sets the stability requirement of the step. */
    public void setJetPtFractionForStability(double f) { jetPtFraction = f; }

    /* ------------------------------------------------------------------ */
    /* The numerical derivatives                                           */
    /* ------------------------------------------------------------------ */

    protected void computeDerivatives(FunctionOfPseudoJet<Double> shape, PseudoJet jet, double originalGhostScale,
                                      double ghostArea, double f0, double rhoPtFraction, GenericSubtractorInfo info) {
        final double[] cached = new double[4];
        final double stepMax = jet.pt() / (jet.area() / ghostArea);
        final double h = optimizeStep(shape, jet, originalGhostScale, ghostArea, rhoPtFraction, f0, cached, stepMax);
        final double f1 = cached[0], f2 = cached[1], f3 = cached[2], f4 = cached[3];
        info.ghostScaleUsed = h;
        final double d1 = (f1 - f0) * 8;
        final double d2 = (f2 - f0) * 4;
        final double d3 = (f3 - f0) * 2;
        final double d4 = f4 - f0;
        info.firstDerivative = (64.0 / 21.0 * d1 - 8.0 / 3.0 * d2 + 2.0 / 3.0 * d3 - 1.0 / 21.0 * d4) / h * ghostArea;
        final double s1 = 8 * (d2 / h - d1 / h);
        final double s2 = 4 * (d3 / h - d2 / h);
        final double s3 = 2 * (d4 / h - d3 / h);
        info.secondDerivative = (8.0 / 3.0 * s1 - 2.0 * s2 + 1 / 3.0 * s3) / (h / 2) * ghostArea * ghostArea;
        final double t1 = (s2 - s1) / h;
        final double t2 = (s3 - s2) / h;
        info.thirdDerivative = (4 * t1 - t2) / (h / 8) * ghostArea * ghostArea * ghostArea;
    }

    /** Scans h over 28 powers of two and keeps the most stable plateau of the derivatives. */
    protected double optimizeStep(FunctionOfPseudoJet<Double> shape, PseudoJet jet, double originalGhostScale,
                                  double ghostArea, double xFraction, double f0, double[] cached, double maxStep) {
        final int nh = 28;
        final double h0 = maxStep;
        final double refPt = jetPtFraction * jet.perp();
        final double[] d1 = new double[nh + 1];
        final double[] d2 = new double[nh + 1];
        final double[] stab = new double[nh + 1];
        final double[] fcts = new double[nh + 4];
        double h = h0 * CRMath.pow(2.0, -nh);
        double f1, f2, f3, f4;
        fcts[0] = f1 = shapeWithRescaledGhosts(shape, jet, originalGhostScale, xFraction * h / 8, (1 - xFraction) * h / 8);
        fcts[1] = f2 = shapeWithRescaledGhosts(shape, jet, originalGhostScale, xFraction * h / 4, (1 - xFraction) * h / 4);
        fcts[2] = f3 = shapeWithRescaledGhosts(shape, jet, originalGhostScale, xFraction * h / 2, (1 - xFraction) * h / 2);
        for (int i = 0; i <= nh; i++) {
            fcts[i + 3] = f4 = shapeWithRescaledGhosts(shape, jet, originalGhostScale, xFraction * h, (1 - xFraction) * h);
            final double dd1 = (f1 - f0) / (h / 8);
            final double dd2 = (f2 - f0) / (h / 4);
            final double dd3 = (f3 - f0) / (h / 2);
            final double dd4 = (f4 - f0) / h;
            d1[nh - i] = (64.0 / 21.0 * dd1 - 8.0 / 3.0 * dd2 + 2.0 / 3.0 * dd3 - 1.0 / 21.0 * dd4) * ghostArea;
            final double ss1 = (dd2 - dd1) / (h / 8);
            final double ss2 = (dd3 - dd2) / (h / 4);
            final double ss3 = (dd4 - dd3) / (h / 2);
            d2[nh - i] = (2 * (8.0 / 3.0 * ss1 - 2.0 * ss2 + 1 / 3.0 * ss3)) * ghostArea * ghostArea;
            stab[nh - i] = refPt * (Math.abs(d1[nh - i]) + refPt * Math.abs(d2[nh - i]));
            h = h0 * CRMath.pow(2.0, -nh + i + 1);
            f1 = f2;
            f2 = f3;
            f3 = f4;
        }
        final int nPlateau = 4;
        double mindiff = Double.MAX_VALUE;
        int nMindiff = 0;
        for (int iscale = nPlateau / 2; iscale <= nh - nPlateau / 2 + 1; iscale++) {
            double diff = 0;
            for (int i = -nPlateau / 2 + 1; i <= nPlateau / 2 - 1; i++) diff += Math.abs(stab[iscale + i] - stab[iscale + i - 1]);
            if (diff > 0 && diff < mindiff) {
                mindiff = diff;
                nMindiff = iscale;
            }
        }
        for (int i = 0; i < 4; i++) cached[i] = fcts[nh - nMindiff + i];
        return h0 * CRMath.pow(2.0, -nMindiff);
    }

    protected double shapeWithRescaledGhosts(FunctionOfPseudoJet<Double> shape, PseudoJet jet, double originalGhostScale,
                                             double newGhostScale, double newDmassScale) {
        final PseudoJet rescaled = new SimpleGhostRescaler(newGhostScale, newDmassScale, originalGhostScale).result(jet);
        if (shape instanceof ShapeWithPartition s) return s.resultFromPartition(rescaled);
        return shape.result(rescaled);
    }

    protected double componentSubtraction(ShapeWithComponents shape, PseudoJet jet, GenericSubtractorInfo info) {
        final int n = shape.nComponents();
        final double[] sub1 = new double[n];
        final double[] sub2 = new double[n];
        final double[] sub3 = new double[n];
        final double[] unsub = new double[n];
        final GenericSubtractorInfo ci = new GenericSubtractorInfo();
        for (int i = 0; i < n; i++) {
            sub2[i] = apply(shape.componentShape(i), jet, ci);
            sub1[i] = ci.firstOrderSubtracted();
            sub3[i] = ci.thirdOrderSubtracted();
            unsub[i] = ci.unsubtracted();
        }
        info.unsubtracted = shape.resultFromComponents(unsub);
        info.firstOrderSubtracted = shape.resultFromComponents(sub1);
        info.secondOrderSubtracted = shape.resultFromComponents(sub2);
        info.thirdOrderSubtracted = shape.resultFromComponents(sub3);
        info.firstDerivative = info.secondDerivative = info.thirdDerivative = 0.0;
        return info.secondOrderSubtracted;
    }
}
