package com.sphere.core.fjcontrib.qcdaware;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.internal.StdPriorityQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * QCD-aware partonic clustering, fastjet::contrib::QCDAwarePlugin
 * (QCDAwarePlugin 2.0.0; A. Buckley and C. Pollard, "QCD-aware partonic jet
 * clustering for truth-jet flavour labelling", Eur. Phys. J. C 76 (2016) 71,
 * arXiv:1507.00508).
 *
 * <p>Partons, photons and leptons carrying their PDG code in the user index
 * (1000000 x object id + pdg) cluster only through a Standard-Model 2 -&gt; 1
 * vertex: q g -&gt; q, g g -&gt; g, q qbar -&gt; g, q gamma -&gt; q, l gamma -&gt;
 * l, l+ l- -&gt; gamma. Other pairs are infinitely far apart. The result
 * carries the flavour of its history in its user index, a physically
 * consistent label. Couplings and colour factors can weight the distances.
 *
 * <p>As in the C++, the pairs are kept in a binary heap popped in
 * libstdc++'s order, so ties go the same way; and the one-loop QED running
 * uses the C++'s z_f = 60/9, which integer division makes 6.
 */
public class QCDAwarePlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("qcdaware");
    }

    private static final double C_F = 4.0 / 3.0;
    private static final double C_A = 3.0;
    private static final double T_R = 1.0 / 2.0;
    private static final double M_Z = 91.1876;
    private static final double[] QUARK_MASSES = {0.00216, 0.00470, 0.0935, 1.273, 4.183, 172.5};
    private static final LimitedWarning FORBIDDEN_MERGE = new LimitedWarning();

    /** A pair and its distance, PJDist; pj2 = -1 for the beam. */
    private record PJDist(double dist, int pj1, int pj2) {
    }

    private final DistanceMeasure dm;
    private boolean useCouplings;
    private double couplingPower;
    private int runningOrderAlphaS;
    private double alphaS;
    private int runningOrderAlphaEM;
    private double alphaEM;
    private boolean enableQCD = true;
    private boolean enableQED = true;

    public QCDAwarePlugin(DistanceMeasure dm, boolean useCouplings, double couplingPower, int runningOrderAlphaS,
                          double alphaS, int runningOrderAlphaEM, double alphaEM) {
        this.dm = dm;
        this.useCouplings = useCouplings;
        this.couplingPower = couplingPower;
        this.runningOrderAlphaS = runningOrderAlphaS;
        this.alphaS = alphaS;
        this.runningOrderAlphaEM = runningOrderAlphaEM;
        this.alphaEM = alphaEM;
    }

    /** Without coupling factors, the historical behaviour. */
    public QCDAwarePlugin(DistanceMeasure dm) {
        this(dm, false, -2.0, 0, 0.1181, 0, 1.0 / 128.92);
    }

    public QCDAwarePlugin setUseCouplings(boolean u) { useCouplings = u; return this; }
    public QCDAwarePlugin setCouplingPower(double p) { couplingPower = p; return this; }
    public QCDAwarePlugin setAlphaS(double as) { alphaS = as; return this; }
    public QCDAwarePlugin setAlphaEM(double a) { alphaEM = a; return this; }
    public QCDAwarePlugin setRunningCouplingOrderAlphaS(int o) { runningOrderAlphaS = o; return this; }
    public QCDAwarePlugin setRunningCouplingOrderAlphaEM(int o) { runningOrderAlphaEM = o; return this; }
    public QCDAwarePlugin setEnableQCD(boolean b) { enableQCD = b; return this; }
    public QCDAwarePlugin setEnableQED(boolean b) { enableQED = b; return this; }

    @Override
    public double R() {
        return dm.R();
    }

    @Override
    public String description() {
        return "QCDAwarePlugin jet algorithm with R = " + Fmt.g(R()) + " and " + dm.algname() + " distance measure";
    }

    /* ------------------------------------------------------------------ */
    /* Flavour labels                                                      */
    /* ------------------------------------------------------------------ */

    /** The PDG code of a user index 1000000 x id + pdg. */
    public static int pid(PseudoJet p) {
        final int ui = p.userIndex();
        return ui > 0 ? ui % 1000000 : ui % (-1000000);
    }

    private static boolean nonZero(PseudoJet p) {
        return !(p.px() == 0 && p.py() == 0 && p.pz() == 0 && p.E() == 0);
    }

    static boolean isQuark(PseudoJet p) {
        return nonZero(p) && Math.abs(pid(p)) <= 6;
    }

    static boolean isGluon(PseudoJet p) {
        return pid(p) == 21;
    }

    static boolean isPhoton(PseudoJet p) {
        return pid(p) == 22;
    }

    static boolean isLepton(PseudoJet p) {
        final int a = Math.abs(pid(p));
        return a == 11 || a == 13 || a == 15;
    }

    /* ------------------------------------------------------------------ */
    /* Couplings                                                           */
    /* ------------------------------------------------------------------ */

    private static int numFlavours(double q2) {
        int nf = 0;
        for (double m : QUARK_MASSES) {
            if (q2 > m * m) nf++;
        }
        return nf;
    }

    private static double lambda5(double as) {
        final double beta0 = (33.0 - 2.0 * 5) / (12.0 * Math.PI);
        return M_Z * CRMath.exp(-1.0 / (2.0 * beta0 * as));
    }

    private static double lambda4(double lambda5) {
        final double mb = QUARK_MASSES[4];
        final double beta05 = (33.0 - 2.0 * 5) / (12.0 * Math.PI);
        final double beta04 = (33.0 - 2.0 * 4) / (12.0 * Math.PI);
        final double l5 = CRMath.log(mb * mb / (lambda5 * lambda5));
        final double l4 = (beta05 / beta04) * l5;
        return mb * CRMath.exp(-l4 / 2.0);
    }

    /** alpha_s at Q^2, fixed (order 0) or one loop with flavour thresholds (order 1). */
    public static double alphaS(double q2, int order, double alphaS) {
        if (order == 0) return alphaS;
        if (order == 1) {
            final int nf = numFlavours(q2);
            final double l5 = lambda5(alphaS);
            final double lambda = nf > 4 ? l5 : lambda4(l5);
            final double beta0 = (33.0 - 2.0 * nf) / (12.0 * Math.PI);
            final double l = CRMath.log(q2 / (lambda * lambda));
            return 1.0 / (beta0 * l);
        }
        throw new FastJetException("alphaS: only orders 0 (fixed) and 1 (one-loop) are supported");
    }

    /** alpha_EM at Q^2, fixed or with the C++'s one-loop running. */
    public static double alphaEM(double q2, int order, double alphaEM) {
        if (order == 0) return alphaEM;
        if (order == 1) {
            final double zf = 60 / 9; // integer division in the C++: 6
            final double mz2 = M_Z * M_Z;
            return alphaEM / (1 - (alphaEM * zf / (3 * Math.PI)) * CRMath.log(q2 / mz2));
        }
        throw new FastJetException("alphaEM: only orders 0 (fixed) and 1 (one-loop) are supported");
    }

    /** The label two objects combine into (0 when they cannot) and the coupling-colour factor. */
    public double[] flavourSum(PseudoJet p, PseudoJet q) {
        final double q2 = p.perp() * q.perp();
        final double as = alphaS(q2, runningOrderAlphaS, alphaS);
        final double a = alphaEM(q2, runningOrderAlphaEM, alphaEM);
        if (enableQCD) {
            if (isQuark(p) && isGluon(q)) return new double[]{p.userIndex(), C_F * as};
            if (isGluon(p) && isQuark(q)) return new double[]{q.userIndex(), C_F * as};
            if (isGluon(p) && isGluon(q)) return new double[]{21, C_A * as};
            if (isQuark(p) && isQuark(q) && pid(p) + pid(q) == 0) return new double[]{21, T_R * as};
        }
        if (enableQED) {
            if (isQuark(p) && isPhoton(q)) return new double[]{p.userIndex(), a};
            if (isPhoton(p) && isQuark(q)) return new double[]{q.userIndex(), a};
            if (isLepton(p) && isPhoton(q)) return new double[]{p.userIndex(), a};
            if (isPhoton(p) && isLepton(q)) return new double[]{q.userIndex(), a};
            if (isLepton(p) && isLepton(q) && pid(p) + pid(q) == 0) return new double[]{22, a};
        }
        return new double[]{0, 0.0};
    }

    /* ------------------------------------------------------------------ */
    /* Clustering                                                          */
    /* ------------------------------------------------------------------ */

    private void insert(ClusterSequence cs, StdPriorityQueue<PJDist> pjds, int iJet, List<Boolean> merged) {
        final PseudoJet ijet = cs.pluginNonConstJet(iJet);
        for (int jJet = 0; jJet < iJet; jJet++) {
            if (merged.get(jJet)) continue;
            final PseudoJet jjet = cs.pluginNonConstJet(jJet);
            final double[] res = flavourSum(ijet, jjet);
            final double dist;
            if ((int) res[0] == 0) {
                dist = Double.MAX_VALUE;
            } else if (ijet.deltaR(jjet) > dm.R()) {
                dist = Double.MAX_VALUE;
            } else {
                final double couplings = useCouplings ? CRMath.pow(res[1], couplingPower) : 1.0;
                dist = couplings * dm.dij(ijet, jjet);
            }
            pjds.push(new PJDist(dist, iJet, jJet));
        }
        final double diB0 = dm.diB(ijet);
        double bf = 1.0;
        if (useCouplings) {
            final double as = alphaS(ijet.perp2(), runningOrderAlphaS, alphaS);
            final double a = alphaEM(ijet.perp2(), runningOrderAlphaEM, alphaEM);
            if (isQuark(ijet) || isGluon(ijet)) bf = CRMath.pow(as, couplingPower);
            else if (isLepton(ijet) || isPhoton(ijet)) bf = CRMath.pow(a, couplingPower);
        }
        pjds.push(new PJDist(bf * diB0, iJet, -1));
        merged.add(false);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final List<Boolean> merged = new ArrayList<>();
        // std::priority_queue<..., std::greater<PJDist>>: the smallest distance on top
        final StdPriorityQueue<PJDist> pjds = new StdPriorityQueue<>((x, y) -> y.dist() < x.dist() ? -1
            : (x.dist() < y.dist() ? 1 : 0));
        final int n = cs.nJets();
        for (int i = 0; i < n; i++) insert(cs, pjds, i, merged);

        while (!pjds.isEmpty()) {
            final PJDist d = pjds.pop();
            if (merged.get(d.pj1())) continue;
            if (d.pj2() < 0) {
                cs.pluginRecordIBRecombination(d.pj1(), d.dist());
                merged.set(d.pj1(), true);
                continue;
            }
            if (merged.get(d.pj2())) continue;
            merged.set(d.pj1(), true);
            merged.set(d.pj2(), true);
            final PseudoJet pj1 = cs.pluginNonConstJet(d.pj1());
            final PseudoJet pj2 = cs.pluginNonConstJet(d.pj2());
            final PseudoJet pj3 = pj1.plus(pj2);
            final int c = (int) flavourSum(pj1, pj2)[0];
            if (c == 0) {
                FORBIDDEN_MERGE.warn("QCDAwarePlugin: attempting to merge pseudojets with pdgids " + pid(pj1) + " and "
                    + pid(pj2) + ", which is not allowed.");
                pj3.setUserIndex(-999);
            } else {
                pj3.setUserIndex(c);
            }
            final int newIdx = cs.pluginRecordIJRecombination(d.pj1(), d.pj2(), d.dist(), pj3);
            insert(cs, pjds, newIdx, merged);
        }
    }
}
