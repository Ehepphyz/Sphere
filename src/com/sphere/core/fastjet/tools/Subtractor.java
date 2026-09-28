package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;

import java.util.ArrayList;
import java.util.List;

/**
 * Background subtraction, fastjet::Subtractor: jet - rho A (and, with rho_m,
 * the extra mass term), where A is the jet's four-vector area and rho comes
 * from an estimator or is given.
 */
public class Subtractor implements Transformer {

    static {
        Citations.use("rho"); // listed in the console's Citations menu once used
    }

    static final double INVALID_RHO = Double.NEGATIVE_INFINITY;
    private static final LimitedWarning UNUSED_RHO_M_WARNING = new LimitedWarning();

    private BackgroundEstimatorBase bge;
    private double rho;
    private double rhoM;
    private boolean useRhoM;
    private boolean safeMass;
    private Selector selKnownVertex = new Selector();
    private Selector selLeadingVertex = new Selector();

    public Subtractor(BackgroundEstimatorBase bge) {
        this.bge = bge;
        this.rho = -1.0;
        setDefaults();
    }

    public Subtractor(double rho) {
        if (rho < 0.0) {
            throw new FastJetException("Subtractor(rho) was passed a negative rho value; rho should be >= 0");
        }
        this.rho = rho;
        setDefaults();
    }

    public Subtractor(double rho, double rhoM) {
        if (rho < 0.0) {
            throw new FastJetException("Subtractor(rho, rho_m) was passed a negative rho value; rho should be >= 0");
        }
        if (rhoM < 0.0) {
            throw new FastJetException("Subtractor(rho, rho_m) was passed a negative rho_m value; rho_m should be >= 0");
        }
        this.rho = rho;
        setDefaults();
        this.rhoM = rhoM;
        setUseRhoM(true);
    }

    public Subtractor() {
        this.rho = INVALID_RHO;
        setDefaults();
    }

    public final void setDefaults() {
        rhoM = INVALID_RHO;
        useRhoM = false;
        safeMass = false;
        selKnownVertex = new Selector();
        selLeadingVertex = new Selector();
    }

    public void setBackgroundEstimator(BackgroundEstimatorBase b) {
        bge = b;
        rho = INVALID_RHO;
    }

    public final void setUseRhoM(boolean use) {
        if (bge == null && rhoM < 0) {
            throw new FastJetException("Subtractor: rho_m support works only for Subtractors constructed with a background estimator or an explicit rho_m value");
        }
        useRhoM = use;
    }

    public boolean useRhoM() { return useRhoM; }
    public void setSafeMass(boolean s) { safeMass = s; }
    public boolean safeMass() { return safeMass; }

    public void setKnownSelectors(Selector knownVertex, Selector leadingVertex) {
        selKnownVertex = knownVertex;
        selLeadingVertex = leadingVertex;
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasArea()) {
            throw new FastJetException("Subtractor::result(...): Trying to subtract a jet without area support");
        }
        PseudoJet knownLv;
        PseudoJet knownPu;
        PseudoJet unknown = jet;
        if (selKnownVertex.worker() != null) {
            final List<PseudoJet> known = new ArrayList<>();
            final List<PseudoJet> unknownConstits = new ArrayList<>();
            selKnownVertex.sift(jet.constituents(), known, unknownConstits);
            final List<PseudoJet> lv = new ArrayList<>();
            final List<PseudoJet> pu = new ArrayList<>();
            selLeadingVertex.sift(known, lv, pu);
            knownLv = !lv.isEmpty() ? Selector.identity().sum(lv) : jet.times(0.0);
            knownPu = !pu.isEmpty() ? Selector.identity().sum(pu) : jet.times(0.0);
            if (unknownConstits.isEmpty()) {
                final PseudoJet sub = jet.copy();
                sub.resetMomentum(knownLv);
                return sub;
            }
            unknown = jet.copy();
            unknown.resetMomentum(Selector.identity().sum(unknownConstits));
        } else {
            knownLv = jet.copy();
            knownLv.timesEqual(0.0);
            knownPu = knownLv;
        }
        final PseudoJet subtracted = jet.copy();
        final PseudoJet toSubtract = knownPu.plus(amountToSubtract(unknown));
        if (toSubtract.pt2() < jet.pt2()) {
            subtracted.minusEqual(toSubtract);
        } else {
            subtracted.resetMomentum(knownLv);
            return subtracted;
        }
        if (subtracted.pt2() < knownLv.pt2()) {
            subtracted.resetMomentum(knownLv);
            return subtracted;
        }
        if (safeMass && subtracted.m2() < knownLv.m2()) {
            subtracted.resetMomentum(PseudoJet.ptYPhiM(subtracted.pt(), knownLv.rap(), subtracted.phi(), knownLv.m()));
        }
        return subtracted;
    }

    private PseudoJet amountToSubtract(PseudoJet jet) {
        BackgroundEstimate est = null;
        double r;
        if (bge != null) {
            est = bge.estimate(jet);
            r = est.rho();
        } else if (rho != INVALID_RHO) {
            r = rho;
        } else {
            throw new FastJetException("Subtractor::_amount_to_subtract(...): default Subtractor does not have any information about the background, needed to perform the subtraction");
        }
        final PseudoJet area = jet.area4vector();
        final PseudoJet toSubtract = area.times(r);
        final double rhoMWarningThreshold = 1e-5;
        if (useRhoM) {
            double rm;
            if (bge != null) {
                if (!est.hasRhoM()) {
                    throw new FastJetException("Subtractor::_amount_to_subtract(...): requested subtraction with rho_m from a background estimator, but the estimator does not have rho_m support");
                }
                rm = est.rhoM();
            } else if (rhoM != INVALID_RHO) {
                rm = rhoM;
            } else {
                throw new FastJetException("Subtractor::_amount_to_subtract(...): default Subtractor does not have any information about the background rho_m, needed to perform the rho_m subtraction");
            }
            toSubtract.plusEqual(new PseudoJet(0.0, 0.0, area.pz(), area.E()).times(rm));
        } else if (bge != null && est.hasRhoM() && est.rhoM() > rhoMWarningThreshold * r) {
            UNUSED_RHO_M_WARNING.warn("Subtractor::_amount_to_subtract(...): Background estimator indicates non-zero rho_m, but use_rho_m()==false in subtractor; consider calling set_use_rho_m(true) to include the rho_m information");
        }
        return toSubtract;
    }

    @Override
    public String description() {
        if (bge != null) {
            String d = "Subtractor that uses the following background estimator to determine rho: " + bge.description();
            if (useRhoM) d += "; including the rho_m correction";
            if (safeMass) d += "; including mass safety tests";
            if (selKnownVertex.worker() != null) {
                d += "; using known vertex selection: " + selKnownVertex.description()
                    + " and leading vertex selection: " + selLeadingVertex.description();
            }
            return d;
        } else if (rho != INVALID_RHO) {
            String d = "Subtractor that uses a fixed value of rho = " + Fmt.g(rho);
            if (useRhoM) d += " and rho_m = " + Fmt.g(rhoM);
            return d;
        }
        return "Uninitialised subtractor";
    }
}
