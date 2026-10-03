package com.sphere.core.fjcontrib.cmpplugin;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.ifnplugin.FlavHistory;
import com.sphere.core.fjcontrib.ifnplugin.FlavInfo;

import java.util.List;

/**
 * The flavoured anti-kt algorithm of M. Czakon, A. Mitov and R. Poncelet,
 * fastjet::CMPPlugin (CMPPlugin 1.0.0; "Infrared-safe flavoured anti-kT
 * jets", JHEP 04 (2023) 138, arXiv:2205.11879), with the corrections of
 * arXiv:2306.07314 that make it infrared and collinear safe.
 *
 * <p>Anti-kt distances, except between a flavour and an anti-flavour, whose
 * distance is multiplied by
 * <pre>
 *   S_ij = 1 - Theta(1 - kappa) cos(pi kappa / 2),
 *   kappa = (kt_i^2 + kt_j^2) / (2 a kt_max^2)
 * </pre>
 * (kappa rescaled by sqrt(M) with the default correction), so that a soft
 * flavour pair recombines with itself before either can flavour a hard jet.
 * The nearest neighbours are anti-kt's; the opposite-flavour pairs are
 * rescanned by hand at each step, as in the C++.
 */
public class CMPPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("cmp");
    }

    /** How the published S_ij is corrected. */
    public enum CorrectionType {
        /** Eq. 2.9 of 2205.11879v1 as written. */
        NO_CORRECTION,
        /** kappa times sqrt(M), M = 2(cosh dy - cos dphi)/dR^2: fixes a joint IR/collinear issue. */
        SQRT_COSHY_COSPHI_ARGUMENT,
        /** The same with damping a = 2: the default. */
        SQRT_COSHY_COSPHI_ARGUMENT_A2,
        /** Not recommended: S = 1 - Theta cos(...) M. */
        COSHY_COSPHI,
        /** S = (1 - Theta cos(...)) M. */
        OVERALL_COSHY_COSPHI,
        /** The same with damping a = 2. */
        OVERALL_COSHY_COSPHI_A2
    }

    /** kt_max: the hardest pseudojet still there, or the hardest anti-kt jet. */
    public enum ClusteringType { DYNAMIC_KTMAX, FIXED_KTMAX }

    static final double DELTA_R2_HANDOVER = Math.sqrt(Math.ulp(1.0));
    private static final double RAP_TRANSITION = 0.1;
    private static final double PHI_TRANSITION = 0.1;

    private final double r;
    private final double a;
    private final CorrectionType correctionType;
    private final ClusteringType clusteringType;
    private final boolean spherical;
    private boolean flavourFromUserIndex;

    public CMPPlugin(double r, double a, CorrectionType correctionType, ClusteringType clusteringType, boolean spherical) {
        this.r = r;
        this.a = a;
        this.correctionType = correctionType;
        this.clusteringType = clusteringType;
        this.spherical = spherical;
    }

    public CMPPlugin(double r, double a, CorrectionType correctionType, ClusteringType clusteringType) {
        this(r, a, correctionType, clusteringType, false);
    }

    public CMPPlugin(double r, double a) {
        this(r, a, CorrectionType.SQRT_COSHY_COSPHI_ARGUMENT_A2, ClusteringType.DYNAMIC_KTMAX, false);
    }

    public double a() { return a; }
    @Override public double R() { return r; }
    @Override public boolean isSpherical() { return spherical; }
    public CorrectionType correctionType() { return correctionType; }
    public ClusteringType clusteringType() { return clusteringType; }

    /** Particles without flavour information take it from the PDG code of their user index (Sphere's addition). */
    public CMPPlugin setFlavourFromUserIndex(boolean value) {
        flavourFromUserIndex = value;
        return this;
    }

    @Override
    public String description() {
        final StringBuilder d = new StringBuilder("CMP plugin with R = ").append(Fmt.g(r)).append(" and a = ").append(Fmt.g(a));
        d.append(switch (clusteringType) {
            case DYNAMIC_KTMAX -> ", reference scale is dynamic";
            case FIXED_KTMAX -> ", reference scale is largest anti-kt jet pt (for spherical, Eref about in flux)";
        });
        d.append(switch (correctionType) {
            case NO_CORRECTION -> ", original algorithm";
            case COSHY_COSPHI -> ", with coshy-cosphi correction to the cos term";
            case OVERALL_COSHY_COSPHI -> ", with overall coshy-cosphi correction";
            case OVERALL_COSHY_COSPHI_A2 -> ", with overall coshy-cosphi correction (a = 2)";
            case SQRT_COSHY_COSPHI_ARGUMENT -> ", with a sqrt coshy-cosphi correction to the cos argument";
            case SQRT_COSHY_COSPHI_ARGUMENT_A2 -> ", with a sqrt coshy-cosphi (a = 2) correction to the cos argument";
        });
        return d.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Geometry                                                            */
    /* ------------------------------------------------------------------ */

    private static double preciseRap(double rap, double pz, double e) {
        return Math.abs(rap) < RAP_TRANSITION ? 0.5 * CRMath.log1p(2 * pz / (e - pz)) : rap;
    }

    /** dy^2 + dphi^2 with the accurate rapidity and small-angle azimuth. */
    public double preciseSquaredDistance(PseudoJet j1, PseudoJet j2) {
        final double rap1 = preciseRap(j1.rap(), j1.pz(), j1.E());
        final double rap2 = preciseRap(j2.rap(), j2.pz(), j2.E());
        final double drap = rap1 - rap2;
        double dphi = Math.abs(j1.phi() - j2.phi());
        if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
        if (dphi < PHI_TRANSITION) {
            final double inv1 = 1.0 / j1.pt();
            final double inv2 = 1.0 / j2.pt();
            final double cross = (j1.px() * inv1) * (j2.py() * inv2) - (j2.px() * inv2) * (j1.py() * inv1);
            dphi = CRMath.asin(cross);
        }
        return drap * drap + dphi * dphi;
    }

    /** 1 - cos(theta) between two momenta, accurate for small angles. */
    public static double oneMinusCostheta(double e1, double px1, double py1, double pz1, double m21, double modp21,
                                          double e2, double px2, double py2, double pz2, double m22, double modp22) {
        if (m21 == 0 && m22 == 0) {
            final double dot = e1 * e2 - (px1 * px2 + py1 * py2 + pz1 * pz2);
            return dot / (e1 * e2);
        }
        final double p1mod = Math.sqrt(modp21);
        final double p2mod = Math.sqrt(modp22);
        final double p1p2mod = p1mod * p2mod;
        final double dot = px1 * px2 + py1 * py2 + pz1 * pz2;
        if (dot > (1 - Math.ulp(1.0)) * p1p2mod) {
            final double cx = py1 * pz2 - py2 * pz1;
            final double cy = pz1 * px2 - pz2 * px1;
            final double cz = px1 * py2 - px2 * py1;
            // the cross product as a PseudoJet of zero energy has m2 = -|c|^2
            final double m2 = (0.0 - cz) * (0.0 + cz) - (cx * cx + cy * cy);
            return -m2 / (p1p2mod * (p1p2mod + dot));
        }
        return 1.0 - dot / p1p2mod;
    }

    public static double oneMinusCostheta(PseudoJet p1, PseudoJet p2) {
        return oneMinusCostheta(p1.E(), p1.px(), p1.py(), p1.pz(), p1.m2(), p1.modp2(),
            p2.E(), p2.px(), p2.py(), p2.pz(), p2.m2(), p2.modp2());
    }

    /** The distance between an opposite-flavour pair, given the reference scale ktmax. */
    public double distanceOppositeFlavour(PseudoJet j1, PseudoJet j2, double ktmax) {
        final double deltaR2 = preciseSquaredDistance(j1, j2);
        double dFij;
        if (!spherical) {
            dFij = (1 / (j1.pt() > j2.pt() ? j1.pt() * j1.pt() : j2.pt() * j2.pt())) * deltaR2 / CRMath.pow(r, 2);
        } else {
            dFij = oneMinusCostheta(j1, j2) / (1 - CRMath.cos(r))
                / (j1.E() > j2.E() ? j1.E() * j1.E() : j2.E() * j2.E());
        }
        final double x = !spherical
            ? (j1.pt() * j1.pt() + j2.pt() * j2.pt()) / (2 * a * ktmax * ktmax)
            : (j1.E() * j1.E() + j2.E() * j2.E()) / (2 * a * ktmax * ktmax);
        double dphi = Math.abs(j1.phi() - j2.phi());
        if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
        final double drap = Math.abs(j1.rap() - j2.rap());

        final double halfPi = Math.PI / 2;
        double sij;
        if (Double.isInfinite(x)) {
            sij = 1;
        } else if (x * halfPi > 1e-4) {
            sij = 1 - (1 - x > 0 ? 1 : 0) * CRMath.cos(halfPi * x);
            switch (correctionType) {
                case COSHY_COSPHI -> {
                    final double cf = deltaR2 > DELTA_R2_HANDOVER ? 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) / deltaR2 : 1;
                    sij = 1 - (1 - x > 0 ? 1 : 0) * CRMath.cos(halfPi * x) * cf;
                }
                case OVERALL_COSHY_COSPHI -> sij *= deltaR2 > DELTA_R2_HANDOVER
                    ? 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) / deltaR2 : 1;
                case OVERALL_COSHY_COSPHI_A2 -> sij *= deltaR2 > DELTA_R2_HANDOVER
                    ? 2 * ((CRMath.cosh(2 * drap) - 1) / 4 + (1 - CRMath.cos(dphi))) / deltaR2 : 1;
                case SQRT_COSHY_COSPHI_ARGUMENT -> {
                    final double cf = deltaR2 > DELTA_R2_HANDOVER ? 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) / deltaR2 : 1;
                    final double y = Math.sqrt(cf) * x;
                    sij = 1 - (1 - y > 0 ? 1 : 0) * CRMath.cos(halfPi * y);
                }
                case SQRT_COSHY_COSPHI_ARGUMENT_A2 -> {
                    final double cf = deltaR2 > DELTA_R2_HANDOVER
                        ? 2 * ((CRMath.cosh(2 * drap) - 1) / 4 + (1 - CRMath.cos(dphi))) / deltaR2 : 1;
                    final double y = Math.sqrt(cf) * x;
                    sij = 1 - (1 - y > 0 ? 1 : 0) * CRMath.cos(halfPi * y);
                }
                case NO_CORRECTION -> { }
            }
        } else {
            // small-angle expansion; 1 - x > 0 for certain here
            sij = CRMath.pow(Math.PI * x / 2, 2) / 2;
            switch (correctionType) {
                case COSHY_COSPHI -> {
                    final double cf = deltaR2 > DELTA_R2_HANDOVER ? 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) / deltaR2 : 1;
                    sij = 1 - (1 - (Math.PI * x / 2) * (Math.PI * x / 2) / 2) * cf;
                }
                case OVERALL_COSHY_COSPHI -> sij *= deltaR2 > DELTA_R2_HANDOVER
                    ? 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) / deltaR2 : 1;
                case OVERALL_COSHY_COSPHI_A2 -> sij *= deltaR2 > DELTA_R2_HANDOVER
                    ? 2 * ((CRMath.cosh(2 * drap) - 1) / 4 + (1 - CRMath.cos(dphi))) / deltaR2 : 1;
                case SQRT_COSHY_COSPHI_ARGUMENT -> {
                    final double cf = deltaR2 > DELTA_R2_HANDOVER ? 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) / deltaR2 : 1;
                    final double y = Math.sqrt(cf) * x;
                    sij = y * Math.PI / 2 > 1e-4 ? 1 - (1 - y > 0 ? 1 : 0) * CRMath.cos(halfPi * y)
                        : (Math.PI * y / 2) * (Math.PI * y / 2) / 2;
                }
                case SQRT_COSHY_COSPHI_ARGUMENT_A2 -> {
                    final double cf = deltaR2 > DELTA_R2_HANDOVER
                        ? 2 * ((CRMath.cosh(2 * drap) - 1) / 4 + (1 - CRMath.cos(dphi))) / deltaR2 : 1;
                    final double y = Math.sqrt(cf) * x;
                    sij = y * Math.PI / 2 > 1e-4 ? 1 - (1 - y > 0 ? 1 : 0) * CRMath.cos(halfPi * y)
                        : (Math.PI * y / 2) * (Math.PI * y / 2) / 2;
                }
                case NO_CORRECTION -> { }
            }
        }
        return dFij * sij;
    }

    /* ------------------------------------------------------------------ */
    /* The brief jet: anti-kt, with opposite flavours out of reach         */
    /* ------------------------------------------------------------------ */

    private final class BriefJet implements NNBriefJet<BriefJet> {
        private final double e, px, py, pz, modp2, m2, kt, phi, nx, ny;
        private final double rap;
        private final boolean flavoured;
        private final FlavInfo flavour;

        BriefJet(PseudoJet jet) {
            e = jet.E();
            px = jet.px();
            py = jet.py();
            pz = jet.pz();
            modp2 = jet.modp2();
            m2 = jet.m2();
            kt = jet.pt();
            phi = jet.phi();
            final double pt = Math.sqrt(jet.pt2());
            nx = jet.px() / pt;
            ny = jet.py() / pt;
            rap = preciseRap(jet.rap(), pz, e);
            flavour = jet.userInfo(FlavHistory.class).currentFlavour();
            flavoured = !flavour.isFlavourless();
        }

        private double geometricalDistance(BriefJet o) {
            double dphi = Math.abs(phi - o.phi);
            final double deta = rap - o.rap;
            if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
            if (dphi < PHI_TRANSITION) {
                final double cross = nx * o.ny - o.nx * ny;
                dphi = CRMath.asin(cross);
            }
            return dphi * dphi + deta * deta;
        }

        @Override
        public double distance(BriefJet o) {
            // opposite flavours are recomputed by hand at each step
            if (flavoured && o.flavoured && flavour.plus(o.flavour).isFlavourless()) return Double.MAX_VALUE;
            if (!spherical) {
                final double deltaR2 = geometricalDistance(o);
                return (1 / (kt > o.kt ? kt * kt : o.kt * o.kt)) * deltaR2 / CRMath.pow(r, 2);
            }
            return oneMinusCostheta(e, px, py, pz, m2, modp2, o.e, o.px, o.py, o.pz, o.m2, o.modp2)
                / (1 - CRMath.cos(r)) / (e > o.e ? e * e : o.e * o.e);
        }

        @Override
        public double beamDistance() {
            return !spherical ? 1 / CRMath.pow(kt, 2) : Double.MAX_VALUE;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Clustering                                                          */
    /* ------------------------------------------------------------------ */

    private static boolean recombined(ClusterSequence cs, PseudoJet j) {
        return cs.history().get(j.clusterHistIndex()).child() > 0;
    }

    private static FlavInfo flavourOf(PseudoJet j) {
        return j.userInfo(FlavHistory.class).currentFlavour();
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        if (cs.nJets() == 0) return;
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet jet = cs.pluginNonConstJet(i);
            final int histIndex = jet.clusterHistIndex();
            final FlavInfo start;
            if (jet.hasUserInfo(FlavInfo.class)) {
                start = jet.userInfo(FlavInfo.class);
            } else if (jet.hasUserInfo(FlavHistory.class)) {
                start = jet.userInfo(FlavHistory.class).currentFlavour();
            } else if (flavourFromUserIndex) {
                start = FlavInfo.fromUserIndex(jet);
            } else {
                throw new FastJetException("A PseudoJet being clustered with CMPPlugin had neither FlavInfo nor FlavHistory user_info.");
            }
            jet.setUserInfo(new FlavHistory(start, histIndex));
        }
        final NNH<BriefJet> nnh = new NNH<>(cs.jets(), BriefJet::new);
        final List<PseudoJet> jets = cs.jets();
        int njets = jets.size();

        double currentKtmax;
        if (clusteringType == ClusteringType.FIXED_KTMAX) {
            if (!spherical) {
                final ClusterSequence akt = new ClusterSequence(jets, new JetDefinition(JetAlgorithm.ANTIKT, r));
                currentKtmax = PseudoJet.sortedByPt(akt.inclusiveJets()).get(0).pt();
            } else {
                currentKtmax = PseudoJet.sortedByE(jets).get(0).E();
            }
        } else {
            currentKtmax = !spherical ? PseudoJet.sortedByPt(jets).get(0).pt() : PseudoJet.sortedByE(jets).get(0).E();
        }

        final int[] ab = new int[2];
        while (njets > 0) {
            double dij = nnh.dijMin(ab);
            int i = ab[0];
            int j = ab[1];

            // the opposite-flavour pairs, whose distances the NNH does not know
            final int n = cs.nJets();
            for (int iA = 0; iA < n; iA++) {
                final PseudoJet jA = cs.pluginNonConstJet(iA);
                final FlavInfo flavA = flavourOf(jA);
                if (flavA.isFlavourless() || recombined(cs, jA)) continue;
                for (int iB = iA + 1; iB < n; iB++) {
                    final PseudoJet jB = cs.pluginNonConstJet(iB);
                    final FlavInfo flavB = flavourOf(jB);
                    if (flavB.isFlavourless() || !flavA.plus(flavB).isFlavourless()) continue;
                    if (recombined(cs, jB)) continue;
                    final double flavDij = distanceOppositeFlavour(jA, jB, currentKtmax);
                    if (flavDij < dij) {
                        dij = flavDij;
                        i = iA;
                        j = iB;
                    }
                }
            }

            if (j >= 0) {
                final int k = cs.pluginRecordIJRecombination(i, j, dij);
                nnh.mergeJets(i, j, cs.jet(k), k);
                if (clusteringType == ClusteringType.DYNAMIC_KTMAX) {
                    currentKtmax = updatedKtmax(cs, i, j, k, currentKtmax);
                }
            } else {
                final double diB = 1 / CRMath.pow(cs.pluginNonConstJet(i).pt(), 2);
                cs.pluginRecordIBRecombination(i, diB);
                nnh.removeJet(i);
            }
            njets--;
        }
    }

    /** kt_max after i and j made k: k's if harder, rescanned if i or j held it. */
    private double updatedKtmax(ClusterSequence cs, int i, int j, int k, double current) {
        final boolean sph = spherical;
        final double hk = sph ? cs.pluginNonConstJet(k).E() : cs.pluginNonConstJet(k).pt();
        if (hk > current) return hk;
        final double hi = sph ? cs.pluginNonConstJet(i).E() : cs.pluginNonConstJet(i).pt();
        final double hj = sph ? cs.pluginNonConstJet(j).E() : cs.pluginNonConstJet(j).pt();
        if (Math.abs(hi - current) < 1e-4 || Math.abs(hj - current) < 1e-4) {
            double best = 0.0;
            for (int iA = 0; iA < cs.nJets(); iA++) {
                final PseudoJet jA = cs.pluginNonConstJet(iA);
                if (recombined(cs, jA)) continue;
                final double h = sph ? jA.E() : jA.pt();
                if (h > best) best = h;
            }
            return best;
        }
        return current;
    }
}
