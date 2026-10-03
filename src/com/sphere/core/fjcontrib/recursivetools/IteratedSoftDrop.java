package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Iterated soft drop, fastjet::contrib::IteratedSoftDrop (C. Frye, A.J.
 * Larkoski, J. Thaler and K. Zhou, JHEP 09 (2017) 083): soft drop applied
 * again and again along the harder branch, down to an angular cut, giving
 * the list of (z_g, theta_g) that passed. Not a transformer: it returns an
 * {@link Info}.
 */
public class IteratedSoftDrop implements FunctionOfPseudoJet<IteratedSoftDrop.Info> {

    static {
        ContribCitations.use("isd");
    }

    /** What iterated soft drop found on a jet, IteratedSoftDropInfo. */
    public static final class Info {
        private final List<double[]> allZgThetag;

        public Info() {
            this(new ArrayList<>());
        }

        public Info(List<double[]> zgThetag) {
            allZgThetag = zgThetag;
        }

        /** The (z_g, theta_g) pairs, by decreasing angle. */
        public List<double[]> allZgThetag() {
            return Collections.unmodifiableList(allZgThetag);
        }

        public double[] get(int i) {
            return allZgThetag.get(i);
        }

        /** sum z^kappa theta^alpha over the splittings found; 0 when none. */
        public double angularity(double alpha, double kappa) {
            double sum = 0.0;
            for (double[] p : allZgThetag) sum += CRMath.pow(p[0], kappa) * CRMath.pow(p[1], alpha);
            return sum;
        }

        public double angularity(double alpha) {
            return angularity(alpha, 1.0);
        }

        /** The iterated soft drop multiplicity. */
        public int multiplicity() {
            return allZgThetag.size();
        }

        public int size() {
            return allZgThetag.size();
        }
    }

    protected final RecursiveSoftDrop rsd;

    public IteratedSoftDrop(double beta, double symmetryCut, double angularCut) {
        this(beta, symmetryCut, angularCut, 1.0, null);
    }

    public IteratedSoftDrop(double beta, double symmetryCut, double angularCut, double r0) {
        this(beta, symmetryCut, angularCut, r0, null);
    }

    public IteratedSoftDrop(double beta, double symmetryCut, double angularCut, double r0,
                            FunctionOfPseudoJet<PseudoJet> subtractor) {
        rsd = new RecursiveSoftDrop(beta, symmetryCut, -1, r0, subtractor);
        rsd.setHardestBranchOnly(true);
        if (angularCut > 0) rsd.setMinDeltaRSquared(angularCut * angularCut);
    }

    public IteratedSoftDrop(double beta, double symmetryCut, RecursiveSymmetryCutBase.SymmetryMeasure symmetryMeasure,
                            double angularCut, double r0, double muCut,
                            RecursiveSymmetryCutBase.RecursionChoice recursionChoice,
                            FunctionOfPseudoJet<PseudoJet> subtractor) {
        rsd = new RecursiveSoftDrop(beta, symmetryCut, symmetryMeasure, -1, r0, muCut, recursionChoice, subtractor);
        rsd.setHardestBranchOnly(true);
        if (angularCut > 0) rsd.setMinDeltaRSquared(angularCut * angularCut);
    }

    public void setDynamicalR0(boolean value) { rsd.setDynamicalR0(value); }
    public boolean useDynamicalR0() { return rsd.useDynamicalR0(); }
    public void setSubtractor(FunctionOfPseudoJet<PseudoJet> s) { rsd.setSubtractor(s); }
    public FunctionOfPseudoJet<PseudoJet> subtractor() { return rsd.subtractor(); }
    public void setInputJetIsSubtracted(boolean s) { rsd.setInputJetIsSubtracted(s); }
    public boolean inputJetIsSubtracted() { return rsd.inputJetIsSubtracted(); }

    public void setReclustering(boolean doReclustering, FunctionOfPseudoJet<PseudoJet> recluster) {
        rsd.setReclustering(doReclustering, recluster);
    }

    @Override
    public Info result(PseudoJet jet) {
        final PseudoJet rsdJet = rsd.result(jet);
        if (!(rsdJet.structure() instanceof RecursiveSymmetryCutBase.StructureType st)) return new Info();
        return new Info(st.sortedZgAndThetag());
    }

    public List<double[]> allZgThetag(PseudoJet jet) {
        return result(jet).allZgThetag();
    }

    public double angularity(PseudoJet jet, double alpha, double kappa) {
        return result(jet).angularity(alpha, kappa);
    }

    public double angularity(PseudoJet jet, double alpha) {
        return result(jet).angularity(alpha, 1.0);
    }

    public double multiplicity(PseudoJet jet) {
        return result(jet).multiplicity();
    }

    @Override
    public String description() {
        final StringBuilder o = new StringBuilder("IteratedSoftDrop with beta =").append(Fmt.g(rsd.beta()))
            .append(", symmetry_cut=").append(Fmt.g(rsd.symmetryCut()))
            .append(", R0=").append(Fmt.g(rsd.R0()));
        if (rsd.minDeltaRSquared() >= 0) {
            o.append(" and angular_cut=").append(Fmt.g(Math.sqrt(rsd.minDeltaRSquared())));
        } else {
            o.append(" and no angular_cut");
        }
        if (rsd.subtractor() != null) {
            o.append(", and with internal subtraction using [").append(rsd.subtractor().description()).append("]");
        }
        return o.toString();
    }
}
