package com.sphere.core.fjcontrib.jetffmoments;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.ClusterSequenceAreaBase;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.List;

/**
 * Moments of the jet fragmentation function, fastjet::contrib::JetFFMoments
 * (JetFFMoments 1.0.0; M. Cacciari, P. Quiroga-Arias, G.P. Salam and
 * G. Soyez, Eur. Phys. J. C 73 (2013) 2319): M_N = sum_i pt_i^N / S^N, with
 * S the jet pt or the scalar pt sum, optionally background-subtracted
 * (rho_N from the pt^N density of the estimator's jets) and further
 * corrected for the fluctuations correlated with the jet pt ("improved"
 * subtraction, with the parameter mu).
 */
public class JetFFMoments implements FunctionOfPseudoJet<double[]> {

    static {
        ContribCitations.use("jetffmoments");
    }

    private static final LimitedWarning WARNINGS_NEGATIVE_PT = new LimitedWarning();

    private double[] ns;
    private JetMedianBackgroundEstimator bge;
    private boolean returnNumerator;
    private double norm;
    private boolean useScalarSum;
    private double mu;
    private List<PseudoJet> jetsForImprovedSub = new ArrayList<>();
    private Selector rhoRangeForImprovedSub = new Selector();

    /** What the calculation found besides the moments, JetFFMoments::Info. */
    public static final class Info {
        double[] rhoNs = new double[0];
        double[] sigmaNs = new double[0];
        double[] rNs = new double[0];
        double[] ptshiftTerm = new double[0];
        double[] correlTerm = new double[0];
        double rho;
        double sigma;

        void resize(int n) {
            rhoNs = new double[n];
            sigmaNs = new double[n];
            rNs = new double[n];
            ptshiftTerm = new double[n];
            correlTerm = new double[n];
        }

        public double[] rhoNs() { return rhoNs.clone(); }
        public double[] sigmaNs() { return sigmaNs.clone(); }
        public double[] rNs() { return rNs.clone(); }
        public double[] ptshiftTerm() { return ptshiftTerm.clone(); }
        public double[] correlTerm() { return correlTerm.clone(); }
        public double rho() { return rho; }
        public double sigma() { return sigma; }
    }

    public JetFFMoments(double[] ns, JetMedianBackgroundEstimator bge) {
        this.ns = ns.clone();
        this.bge = bge;
        initialise();
    }

    public JetFFMoments(double[] ns) {
        this(ns, null);
    }

    /** nn values of N evenly spaced from nmin to nmax. */
    public JetFFMoments(double nmin, double nmax, int nn, JetMedianBackgroundEstimator bge) {
        if (nn == 0) throw new FastJetException("JetFFMoments should be constructed with at least one element");
        ns = new double[nn];
        if (nn == 1) {
            ns[0] = nmin;
        } else {
            for (int in = 0; in < nn; in++) ns[in] = nmin + in * (nmax - nmin) / (nn - 1);
        }
        this.bge = bge;
        initialise();
    }

    public JetFFMoments(double nmin, double nmax, int nn) {
        this(nmin, nmax, nn, null);
    }

    private void initialise() {
        useScalarSum(true);
        setReturnNumerator(false);
        setDenominator(-1.0);
        mu = -1.0;
        jetsForImprovedSub = new ArrayList<>();
    }

    public void setReturnNumerator(boolean v) { returnNumerator = v; }
    public void setDenominator(double v) { norm = v; }
    public void useScalarSum(boolean v) { useScalarSum = v; }

    /** Improved subtraction with the estimator's own jets. */
    public void setImprovedSubtraction(double muIn) {
        mu = muIn;
        jetsForImprovedSub = new ArrayList<>();
    }

    public void setImprovedSubtraction(double muIn, Selector rhoRange, ClusterSequenceAreaBase csa) {
        mu = muIn;
        jetsForImprovedSub = csa.inclusiveJets();
        rhoRangeForImprovedSub = rhoRange;
    }

    public void setImprovedSubtraction(double muIn, Selector rhoRange, List<PseudoJet> particles, JetDefinition rhoJetDef,
                                       AreaDefinition rhoAreaDef) {
        mu = muIn;
        jetsForImprovedSub = new ClusterSequenceArea(particles, rhoJetDef, rhoAreaDef).inclusiveJets();
        rhoRangeForImprovedSub = rhoRange;
    }

    public double N(int i) {
        if (i >= ns.length) throw new FastJetException("JetFFMoments: out-of-range value of n requested");
        return ns[i];
    }

    public double[] Ns() { return ns.clone(); }

    @Override
    public String description() {
        final StringBuilder o = new StringBuilder();
        if (returnNumerator) o.append("Numerator of the ");
        o.append("Jet fragmentation function moments calculated");
        if (!returnNumerator) {
            if (norm > 0) o.append(" with a fixed denominator");
            else if (useScalarSum) o.append(" using the scalar pt sum as denominator");
            else o.append(" using the pt of the jet as denominator");
        }
        if (bge != null) o.append(", with background subtracted using the estimator ").append(bge.description());
        if (mu > 0) {
            if (jetsForImprovedSub.isEmpty()) {
                o.append(", subtraction improved using jets from the background estimator and mu = ").append(Fmt.g(mu));
            } else {
                o.append(", subtraction improved using jets in the range ").append(rhoRangeForImprovedSub.description())
                    .append(" and mu = ").append(Fmt.g(mu));
            }
        }
        return o.append('.').toString();
    }

    @Override
    public double[] result(PseudoJet jet) {
        return result(jet, new Info());
    }

    /** sum pt^n over the non-ghost constituents, per unit area: the density rho_N is measured with. */
    static FunctionOfPseudoJet<Double> scalarDensity(double n) {
        return FunctionOfPseudoJet.of("BackgroundScalarJetPtDensity", j -> {
            double s = 0;
            for (PseudoJet c : Selector.isPureGhost().negate().apply(j.constituents())) s += CRMath.pow(c.perp(), n);
            return s / j.area();
        });
    }

    public double[] result(PseudoJet jet, Info info) {
        if (!jet.hasConstituents()) throw new FastJetException("JetFFMoments can only be applied to jets having constituents");
        if (bge != null && !jet.hasArea()) throw new FastJetException("JetFFMoments with background subtraction can only be applied to jets having an area");
        final double[] ffm = new double[ns.length];
        info.resize(ns.length);
        final List<PseudoJet> constituents = Selector.isPureGhost().negate().apply(jet.constituents());
        final double[] rhoSigma = new double[2];
        final double s1 = computeNormalisation(jet, constituents, rhoSigma);
        final double rho = rhoSigma[0];
        final double sigma = rhoSigma[1];
        info.rho = rho;
        info.sigma = sigma;
        if (s1 <= 0) {
            WARNINGS_NEGATIVE_PT.warn("JetFFMoments: Negative or zero (subtracted) denominator. Returning 1 for all moments.");
            java.util.Arrays.fill(ffm, 1.0);
            return ffm;
        }
        for (PseudoJet c : constituents) {
            final double pti = c.pt();
            for (int in = 0; in < ns.length; in++) ffm[in] += CRMath.pow(pti, ns[in]);
        }
        FunctionOfPseudoJet<Double> oldDensity = null;
        if (bge != null) {
            oldDensity = bge.jetDensityClass();
            for (int in = 0; in < ns.length; in++) {
                bge.setJetDensityClass(scalarDensity(ns[in]));
                info.rhoNs[in] = bge.rho(jet);
                info.sigmaNs[in] = bge.sigma(jet);
                ffm[in] -= info.rhoNs[in] * jet.area();
            }
        }
        for (int in = 0; in < ns.length; in++) ffm[in] /= CRMath.pow(s1, ns[in]);
        if (mu > 0) {
            if (norm > 0 || returnNumerator) throw new FastJetException("JetFFMoments: improved subtraction (mu>0) is not available for a fixed denominator");
            final List<PseudoJet> jetsUsed;
            if (jetsForImprovedSub.isEmpty()) {
                jetsUsed = bge.jetsUsed();
            } else {
                if (rhoRangeForImprovedSub.takesReference()) rhoRangeForImprovedSub.setReference(jet);
                jetsUsed = rhoRangeForImprovedSub.apply(jetsForImprovedSub);
            }
            double sumQt = 0.0;
            double sumQt2 = 0.0;
            final double[] sumQn = new double[ns.length];
            final double[] sumQnQn = new double[ns.length];
            final double[] sumQq = new double[ns.length];
            for (PseudoJet current : jetsUsed) {
                double qt = 0.0;
                final List<PseudoJet> constits = Selector.isPureGhost().negate().apply(current.constituents());
                if (!useScalarSum) {
                    qt = current.minus(current.area4vector().times(rho)).pt();
                    if (current.pt2() < rho * rho * current.area4vector().pt2()) qt *= -1.0;
                } else {
                    for (PseudoJet c : constits) qt += c.pt();
                    qt -= rho * current.area();
                }
                sumQt += qt;
                sumQt2 += qt * qt;
                for (int in = 0; in < ns.length; in++) {
                    double qn = 0.0;
                    for (PseudoJet c : constits) qn += CRMath.pow(c.pt(), ns[in]);
                    qn -= info.rhoNs[in] * current.area();
                    sumQn[in] += qn;
                    sumQnQn[in] += qn * qn;
                    sumQq[in] += qn * qt;
                }
            }
            final double expectedJetQt = sigma * sigma * jet.area() / mu;
            final double avgQt = sumQt / jetsUsed.size();
            for (int in = 0; in < ns.length; in++) {
                info.rNs[in] = (sumQq[in] - avgQt * sumQn[in])
                    / Math.sqrt((sumQt2 - avgQt * sumQt) * (sumQnQn[in] - CRMath.pow(sumQn[in], 2) / jetsUsed.size()));
                info.ptshiftTerm[in] = ffm[in] * ns[in] / s1 * expectedJetQt;
                info.correlTerm[in] = info.rNs[in] * sigma * info.sigmaNs[in] * jet.area() / (mu * CRMath.pow(s1, ns[in]));
                ffm[in] *= 1 + ns[in] / s1 * expectedJetQt;
                ffm[in] -= info.correlTerm[in];
            }
        }
        if (bge != null) bge.setJetDensityClass(oldDensity);
        return ffm;
    }

    /** The denominator S; rho and sigma of the estimator go into rhoSigma. */
    private double computeNormalisation(PseudoJet jet, List<PseudoJet> constituents, double[] rhoSigma) {
        rhoSigma[0] = 0.0;
        rhoSigma[1] = 0.0;
        if (returnNumerator) return 1.0;
        if (norm > 0) return norm;
        if (!useScalarSum) {
            if (bge != null) {
                rhoSigma[0] = bge.rho(jet);
                rhoSigma[1] = bge.sigma(jet);
                final PseudoJet toSubtract = jet.area4vector().times(rhoSigma[0]);
                if (toSubtract.pt2() >= jet.pt2()) return -1.0;
                return jet.minus(toSubtract).pt();
            }
            return jet.pt();
        }
        double scalarSum = 0.0;
        for (PseudoJet c : constituents) scalarSum += c.pt();
        if (bge != null) {
            final FunctionOfPseudoJet<Double> old = bge.jetDensityClass();
            bge.setJetDensityClass(scalarDensity(1.0));
            rhoSigma[0] = bge.rho(jet);
            rhoSigma[1] = bge.sigma(jet);
            scalarSum -= rhoSigma[0] * jet.area();
            bge.setJetDensityClass(old);
        }
        return scalarSum;
    }
}
