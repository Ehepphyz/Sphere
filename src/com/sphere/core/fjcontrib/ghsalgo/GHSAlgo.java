package com.sphere.core.fjcontrib.ghsalgo;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.ifnplugin.FlavHistory;
import com.sphere.core.fjcontrib.ifnplugin.FlavInfo;
import com.sphere.core.fjcontrib.ifnplugin.FlavRecombiner;

import java.util.ArrayList;
import java.util.List;

/**
 * The flavour dressing algorithm of R. Gauld, A. Huss and G. Stagnitto,
 * fastjet::contrib::run_GHS (GHSAlgo 1.0.0; "Flavor Identification of
 * Reconstructed Hadronic Jets", Phys. Rev. Lett. 130 (2023) 161901, erratum
 * 132 (2024) 159901, arXiv:2208.11138 v2, with the corrections of
 * arXiv:2306.07314).
 *
 * <p>Flavour is assigned after the jets are found, whatever algorithm found
 * them: the flavoured particles are clustered among themselves and with the
 * hard jets under a flavour-kt distance, opposite flavours annihilating, and
 * each hard jet takes the flavour of what reaches it. The particles and
 * their flavours are read from the cluster sequence of the jets, which must
 * have been clustered with a {@link FlavRecombiner}.
 */
public final class GHSAlgo {

    static {
        ContribCitations.use("ghs");
    }

    static final double DELTA_R2_HANDOVER = Math.sqrt(Math.ulp(1.0));

    private GHSAlgo() {
    }

    /** What every brief jet sees. */
    private static final class Info {
        double alpha;
        double omega;
        List<PseudoJet> jets;
        FlavRecombiner flavRecombiner;
    }

    private static double preciseRap(PseudoJet p) {
        final double rap = p.rap();
        return Math.abs(rap) < 0.1 ? 0.5 * CRMath.log1p(2 * p.pz() / (p.E() - p.pz())) : rap;
    }

    /** A hard jet (user index 1) or a flavour cluster (user index 0, or -1 - i when inside jet i). */
    private static final class BriefJet implements NNBriefJet<BriefJet> {
        private final PseudoJet jet;
        private final Info info;
        private final double pt2, nx, ny, rap, phi;
        private final double diB;

        BriefJet(PseudoJet jet, Info info) {
            this.jet = jet;
            this.info = info;
            pt2 = jet.pt2();
            final double pt = Math.sqrt(pt2);
            nx = jet.px() / pt;
            ny = jet.py() / pt;
            phi = jet.phi();
            rap = preciseRap(jet);
            if (isJet()) {
                diB = Double.MAX_VALUE;
            } else if (isParticle()) {
                if (associatedJet() == -1) {
                    double ptBp = 0;
                    double ptBm = 0;
                    for (PseudoJet j : info.jets) {
                        final double dyj = preciseRap(j) - preciseRap(jet);
                        ptBp += j.pt() * CRMath.exp(Math.min(0.0, -dyj));
                        ptBm += j.pt() * CRMath.exp(Math.min(0.0, dyj));
                    }
                    final double al = info.alpha;
                    final double diBp = Math.max(CRMath.pow(jet.pt(), al), CRMath.pow(ptBp, al))
                        * Math.min(CRMath.pow(jet.pt(), 2 - al), CRMath.pow(ptBp, 2 - al));
                    final double diBm = Math.max(CRMath.pow(jet.pt(), al), CRMath.pow(ptBm, al))
                        * Math.min(CRMath.pow(jet.pt(), 2 - al), CRMath.pow(ptBm, 2 - al));
                    diB = Math.min(diBp, diBm);
                } else {
                    diB = Double.MAX_VALUE;
                }
            } else {
                throw new FastJetException("the PseudoJet should either be a particle or a jet, but was neither");
            }
        }

        int associatedJet() {
            return Math.abs(jet.userIndex()) - 1;
        }

        boolean isJet() {
            return jet.userIndex() == 1;
        }

        boolean isParticle() {
            return jet.userIndex() <= 0;
        }

        static double geometricalDistanceSquared(BriefJet first, BriefJet other) {
            final double dy = first.rap - other.rap;
            double dphi = Math.abs(first.phi - other.phi);
            if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
            if (dphi < 0.1) {
                final double cross = first.nx * other.ny - other.nx * first.ny;
                dphi = CRMath.asin(cross);
            }
            final double omega = first.info.omega;
            final double deltaR2 = dy * dy + dphi * dphi;
            if (omega == 0.0) return deltaR2;
            if (deltaR2 > DELTA_R2_HANDOVER) {
                return 2 * ((CRMath.cosh(omega * dy) - 1) / (omega * omega) - (CRMath.cos(dphi) - 1));
            }
            return deltaR2;
        }

        static double dij(BriefJet first, BriefJet other) {
            final double al = first.info.alpha;
            final double ptf = Math.sqrt(first.pt2);
            final double pto = Math.sqrt(other.pt2);
            return geometricalDistanceSquared(first, other)
                * Math.max(CRMath.pow(ptf, al), CRMath.pow(pto, al))
                * Math.min(CRMath.pow(ptf, 2 - al), CRMath.pow(pto, 2 - al));
        }

        @Override
        public double distance(BriefJet otherIn) {
            // if one of them is a particle, first is a particle
            BriefJet first = this;
            BriefJet other = otherIn;
            if (other.isParticle()) {
                final BriefJet t = first;
                first = other;
                other = t;
            }
            if (first.isJet()) return Double.MAX_VALUE;
            if (other.isParticle()) {
                // only opposite flavours annihilate
                final FlavInfo flavA = jet.userInfo(FlavHistory.class).currentFlavour();
                final FlavInfo flavB = otherIn.jet.userInfo(FlavHistory.class).currentFlavour();
                if ((flavA.isFlavourless() || flavB.isFlavourless()) && associatedJet() != otherIn.associatedJet()) {
                    return Double.MAX_VALUE;
                }
                final FlavInfo sum = info.flavRecombiner.applySummationChoice(flavA.plus(flavB));
                if (!flavA.isFlavourless() && !flavB.isFlavourless() && !sum.isFlavourless()) return Double.MAX_VALUE;
                return dij(first, other);
            }
            // a particle and a jet: only the jet it is associated with
            if (first.associatedJet() < 0) return Double.MAX_VALUE;
            if (info.jets.get(first.associatedJet()).clusterHistIndex() == other.jet.clusterHistIndex()) {
                return dij(first, other);
            }
            return Double.MAX_VALUE;
        }

        @Override
        public double beamDistance() {
            return diB;
        }
    }

    /** With alpha = 1, omega = 2 and net flavour, the published defaults. */
    public static List<PseudoJet> runGHS(List<PseudoJet> jetsBase, double ptcut) {
        return runGHS(jetsBase, ptcut, 1.0, 2.0, new FlavRecombiner());
    }

    /**
     * The jets of jetsBase above ptcut, dressed with their flavour.
     *
     * @param jetsBase the jets before any hardness cut; the particles and their
     *                 flavours come from their cluster sequence
     * @param ptcut    the hardness cut, to match the fiducial jet definition
     * @param alpha    the power of kt_max/kt_min in the flavour-kt distance
     * @param omega    the weight of rapidity separation (0: deltaR^2)
     */
    public static List<PseudoJet> runGHS(List<PseudoJet> jetsBase, double ptcut, double alpha, double omega,
                                         FlavRecombiner flavRecombiner) {
        if (jetsBase.isEmpty()) return jetsBase;
        final ClusterSequence cs = jetsBase.get(0).associatedClusterSequence();
        if (cs == null) throw new FastJetException("run_GHS: the jets have no cluster sequence to take the particles from");
        final List<PseudoJet> all0 = cs.jets();
        final List<PseudoJet> inputs = new ArrayList<>(all0.subList(0, cs.nParticles()));

        final List<PseudoJet> jetsHard = Selector.ptMin(ptcut).apply(jetsBase);
        if (jetsHard.isEmpty()) return jetsHard;

        final List<PseudoJet> all = new ArrayList<>();
        final List<PseudoJet> finalJets = new ArrayList<>(jetsHard.size());
        for (PseudoJet j : jetsHard) finalJets.add(j.copy());
        final FlavInfo[] jetFlavs = new FlavInfo[finalJets.size()];
        java.util.Arrays.fill(jetFlavs, new FlavInfo(0));
        final int njets = finalJets.size();

        final Info info = new Info();
        info.jets = new ArrayList<>();
        for (PseudoJet j : finalJets) info.jets.add(j.copy());
        info.alpha = alpha;
        info.omega = omega;
        info.flavRecombiner = flavRecombiner;

        for (PseudoJet j : finalJets) {
            j.setUserInfo(new FlavHistory(new FlavInfo(0)));
            j.setUserIndex(1);
            all.add(j);
        }
        for (PseudoJet c : inputs) {
            c.setUserIndex(0);
            for (int i = 0; i < finalJets.size(); i++) {
                if (c.isInside(finalJets.get(i))) {
                    c.setUserIndex(-1 - i);
                    break;
                }
            }
            all.add(c);
        }

        final NNH<BriefJet> nnh = new NNH<>(all, p -> new BriefJet(p, info));
        final int[] ab = new int[2];
        while (njets > 0) {
            final double dij = nnh.dijMin(ab);
            int iA = ab[0];
            int iB = ab[1];
            if (dij == Double.MAX_VALUE) break;
            if (iB >= 0) {
                if (iA > iB) {
                    final int t = iA;
                    iA = iB;
                    iB = t;
                }
                if (iB < njets) throw new FastJetException("run_GHS: two jets were paired, which cannot be");
                if (iA < njets) {
                    // a jet: it takes B's flavour, and B goes
                    final FlavInfo flavB = all.get(iB).userInfo(FlavHistory.class).currentFlavour();
                    jetFlavs[iA] = flavRecombiner.applySummationChoice(jetFlavs[iA].plus(flavB));
                    nnh.removeJet(iB);
                } else {
                    // two flavour clusters merge
                    final PseudoJet merged = all.get(iA).copy();
                    merged.resetMomentum(all.get(iA).plus(all.get(iB)));
                    merged.setUserIndex(all.get(iA).userIndex() == all.get(iB).userIndex() ? all.get(iA).userIndex() : 0);
                    final FlavInfo flav = flavRecombiner.applySummationChoice(
                        FlavHistory.currentFlavourOf(all.get(iA)).plus(FlavHistory.currentFlavourOf(all.get(iB))));
                    merged.setUserInfo(new FlavHistory(flav));
                    all.add(merged);
                    nnh.mergeJets(iA, iB, merged, all.size() - 1);
                }
            } else {
                nnh.removeJet(iA);
            }
        }
        for (int i = 0; i < finalJets.size(); i++) {
            finalJets.get(i).setUserInfo(new FlavHistory(jetFlavs[i]));
            finalJets.get(i).setUserIndex(jetsHard.get(i).userIndex());
        }
        return finalJets;
    }
}
