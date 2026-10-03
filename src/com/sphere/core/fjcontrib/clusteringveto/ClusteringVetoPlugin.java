package com.sphere.core.fjcontrib.clusteringveto;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A terminating clustering veto, fastjet::contrib::ClusteringVetoPlugin
 * (ClusteringVetoPlugin 1.0.0; M. Stoll, "Vetoed jet clustering: The
 * mass-jump algorithm", JHEP 04 (2015) 111, arXiv:1410.4637).
 *
 * <p>C/A-, kt- or anti-kt-like clustering in which a recombination j_a + j_b
 * -> j is vetoed when m(j) &gt; mu and theta m(j) &gt; max(m_a, m_b): a jump in
 * mass means two separate objects, and both then stop clustering (they turn
 * passive). When the jump is not large enough, the pair is also checked
 * against the nearest passive jets. Any other veto function can be given.
 */
public class ClusteringVetoPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("clusteringveto");
    }

    /** The distance measure. */
    public enum ClusterType { CALIKE, KTLIKE, AKTLIKE }

    /** What a veto function answers. */
    public enum VetoResult {
        /** Below threshold: recombine. */
        CLUSTER,
        /** Vetoed: both turn passive. */
        VETO,
        /** Not vetoed, but the active-passive veto is to be checked. */
        NOVETO
    }

    /** A veto of one's own, which makes mu and theta irrelevant. */
    @FunctionalInterface
    public interface VetoFunction {
        VetoResult check(PseudoJet j1, PseudoJet j2);
    }

    private final double maxR2;
    private final double mu;
    private final double theta;
    private final ClusterType clustType;
    private VetoFunction vetoFunction;

    /**
     * @param mu        veto condition 1, m(i+j) &gt; mu
     * @param theta     veto condition 2, theta m(i+j) &gt; max(m_i, m_j), in [0, 1]
     * @param maxR      the largest jet radius
     * @param clustType the distance measure
     */
    public ClusteringVetoPlugin(double mu, double theta, double maxR, ClusterType clustType) {
        if (mu < 0.0) throw new FastJetException("ClusteringVetoPlugin: mu must be positive.");
        if (theta > 1.0 || theta < 0.0) throw new FastJetException("ClusteringVetoPlugin: theta must be in [0.0,1.0].");
        if (maxR < 0.0) throw new FastJetException("ClusteringVetoPlugin: Maximum radius must be positive.");
        this.maxR2 = maxR * maxR;
        this.mu = mu;
        this.theta = theta;
        this.clustType = clustType;
    }

    public void setVetoFunction(VetoFunction f) {
        vetoFunction = f;
    }

    public double mu() { return mu; }
    public double theta() { return theta; }
    public ClusterType clusterType() { return clustType; }

    @Override
    public double R() {
        return Math.sqrt(maxR2);
    }

    @Override
    public String description() {
        final String type = switch (clustType) {
            case AKTLIKE -> "AKT";
            case CALIKE -> "CA";
            case KTLIKE -> "KT";
        };
        return "Clustering Veto (1410.4637), " + type + "-like" + ", theta=" + fixed1(theta) + ", mu=" + fixed1(mu)
            + ", max_r=" + fixed1(Math.sqrt(maxR2)) + (vetoFunction != null ? ", have user-defined veto function" : "");
    }

    /** std::fixed with precision 1: the exact binary value rounded half-even. */
    private static String fixed1(double x) {
        return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    /* ------------------------------------------------------------------ */
    /* Distances and vetoes                                                */
    /* ------------------------------------------------------------------ */

    private double perpFactor(PseudoJet jet) {
        return switch (clustType) {
            case AKTLIKE -> 1. / jet.perp2();
            case CALIKE -> 1.;
            case KTLIKE -> jet.perp2();
        };
    }

    double jjDistance(PseudoJet j1, PseudoJet j2) {
        double ret = switch (clustType) {
            case AKTLIKE -> Math.min(1. / j1.perp2(), 1. / j2.perp2());
            case CALIKE -> 1.;
            case KTLIKE -> Math.min(j1.perp2(), j2.perp2());
        };
        ret *= j1.squaredDistance(j2) / maxR2;
        return ret;
    }

    double jbDistance(PseudoJet jet) {
        return perpFactor(jet);
    }

    VetoResult checkVeto(PseudoJet j1, PseudoJet j2) {
        return vetoFunction == null ? checkVetoMassJump(j1, j2) : vetoFunction.check(j1, j2);
    }

    /** The mass-jump veto. */
    VetoResult checkVetoMassJump(PseudoJet j1, PseudoJet j2) {
        final PseudoJet comb = j1.plus(j2);
        final double m1 = Math.abs(j1.m());
        final double m2 = Math.abs(j2.m());
        final double m = Math.abs(comb.m());
        if (m < mu) return VetoResult.CLUSTER;
        if (theta * m > Math.max(m1, m2)) return VetoResult.VETO;
        return VetoResult.NOVETO;
    }

    private final class BriefJet implements NNBriefJet<BriefJet> {
        private final double ph;
        private final double rp;
        private final double perpfactor;

        BriefJet(PseudoJet jet) {
            ph = jet.phi();
            rp = jet.rap();
            perpfactor = perpFactor(jet);
        }

        @Override
        public double distance(BriefJet o) {
            double dij = Math.min(perpfactor, o.perpfactor);
            double dphi = Math.abs(ph - o.ph);
            if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
            dij *= (dphi * dphi + ((rp - o.rp) * (rp - o.rp))) / maxR2;
            return dij;
        }

        @Override
        public double beamDistance() {
            return perpfactor;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Clustering                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public void runClustering(ClusterSequence cs) {
        final List<Integer> passive = new ArrayList<>();
        final NNH<BriefJet> nnh = new NNH<>(cs.jets(), BriefJet::new);
        int njets = cs.nJets();
        final int[] ab = new int[2];
        while (njets > 0) {
            final double dij = nnh.dijMin(ab);
            final int i = ab[0];
            final int j = ab[1];
            if (j < 0) {
                passive.add(i);
                cs.pluginRecordIBRecombination(i, dij);
                nnh.removeJet(i);
                njets--;
                continue;
            }
            final PseudoJet ji = cs.pluginNonConstJet(i);
            final PseudoJet jj = cs.pluginNonConstJet(j);
            switch (checkVeto(ji, jj)) {
                case CLUSTER -> {
                    final int k = cs.pluginRecordIJRecombination(i, j, dij);
                    nnh.mergeJets(i, j, cs.jet(k), k);
                    njets--;
                }
                case VETO -> {
                    passive.add(i);
                    passive.add(j);
                    cs.pluginRecordIBRecombination(i, dij);
                    cs.pluginRecordIBRecombination(j, dij);
                    nnh.removeJet(i);
                    nnh.removeJet(j);
                    njets -= 2;
                }
                case NOVETO -> {
                    int pass1 = -1;
                    int pass2 = -1;
                    double d1 = dij;
                    double d2 = dij;
                    // the closest passive jets that could have recombined with i or j
                    for (int jjIndex = 0; jjIndex < passive.size(); jjIndex++) {
                        final PseudoJet pj = cs.pluginNonConstJet(passive.get(jjIndex));
                        final double dd1 = jjDistance(pj, ji);
                        final double dd2 = jjDistance(pj, jj);
                        final double db = jbDistance(pj);
                        if (dd1 < d1 && dd1 < db) {
                            d1 = dd1;
                            pass1 = passive.get(jjIndex);
                        }
                        if (dd2 < d2 && dd2 < db) {
                            d2 = dd2;
                            pass2 = passive.get(jjIndex);
                        }
                    }
                    boolean hadVeto = false;
                    if (pass1 >= 0 && checkVeto(ji, cs.pluginNonConstJet(pass1)) == VetoResult.VETO) {
                        passive.add(i);
                        cs.pluginRecordIBRecombination(i, jjDistance(ji, cs.pluginNonConstJet(pass1)));
                        nnh.removeJet(i);
                        hadVeto = true;
                        njets--;
                    }
                    if (pass2 >= 0 && checkVeto(jj, cs.pluginNonConstJet(pass2)) == VetoResult.VETO) {
                        passive.add(j);
                        cs.pluginRecordIBRecombination(j, jjDistance(jj, cs.pluginNonConstJet(pass2)));
                        nnh.removeJet(j);
                        hadVeto = true;
                        njets--;
                    }
                    if (!hadVeto) {
                        final int k = cs.pluginRecordIJRecombination(i, j, dij);
                        nnh.mergeJets(i, j, cs.jet(k), k);
                        njets--;
                    }
                }
            }
        }
    }
}
