package com.sphere.core.fjcontrib.ifnplugin;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.internal.StdSort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Interleaved flavour neutralisation, fastjet::contrib::FlavNeutraliser
 * (IFNPlugin 1.0.4): the history of a clustering is replayed step by step,
 * and before the softer of two merging objects brings its flavour into the
 * harder one, it may first neutralise it against a flavoured object closer
 * to it in the measure
 * <pre>
 *   u_ik = max(pt_i^2, pt_k^2)^p min(pt_i^2, pt_k^2)^q
 *          x 2 [ (cosh(a dy) - 1)/a^2 - (cos dphi - 1) ]
 * </pre>
 * The kinematics of the jets stay those of the base algorithm; only the
 * flavours move. Recursion lets the partner k look for its own preferred
 * neutraliser first, so that flavour is not stolen from where it belongs.
 *
 * <p>The rapidity is taken from log1p for |y| &lt; 0.1 and small azimuthal
 * differences from a transverse cross product, as the C++ does; with the
 * correctly rounded {@link CRMath} functions the answers are the C++'s to
 * the bit.
 */
public class FlavNeutraliser {

    /** The measures u_ij; {@link #GENERAL} is the published one, the others are kept for studies. */
    public enum Measure {
        SINH_DELTA_R, DELTA_R, JADE_DELTA_R, MAXSCALE_DELTA_R, PHI2_COSHY, COSPHI_COSHY,
        AKTLIKE_PAIR_REFRATIO, AKTLIKE_PAIR_DYNREFRATIO, JADE, JADEA2, MAXSCALE, GENERAL
    }

    private static final LimitedWarning WARN_OLD_MEASURE = new LimitedWarning(100);
    private static final LimitedWarning WARN_AKTLIKE_MEASURE = new LimitedWarning(100);

    /** Below this deltaR^2, the measures use deltaR^2 itself: sqrt(epsilon) = 2^-26. */
    static final double DELTA_R2_HANDOVER = Math.sqrt(Math.ulp(1.0));

    private double p = 1.0;
    private double q = 0.0;
    private double a = 1.0;
    private final boolean modulo2;
    private final Measure measure;
    private final boolean useMassFlav;
    private final boolean sphericalAlgo;
    private final double pp;
    private boolean recursive;

    /** The general measure of parameters p, q and a. */
    public FlavNeutraliser(double p, double q, double a, boolean modulo2, Measure measure,
                           boolean useMassFlav, boolean sphericalAlgo, double pp, boolean recursive) {
        this.p = p;
        this.q = q;
        this.a = a;
        this.modulo2 = modulo2;
        this.measure = measure;
        this.useMassFlav = useMassFlav;
        this.sphericalAlgo = sphericalAlgo;
        this.pp = pp;
        this.recursive = recursive;
    }

    public FlavNeutraliser(double p, double q, double a, boolean modulo2) {
        this(p, q, a, modulo2, Measure.GENERAL, false, false, 1.0, true);
    }

    /** One of the older measures, which do not need p, q or a. */
    public FlavNeutraliser(boolean modulo2, Measure measure, boolean useMassFlav, boolean sphericalAlgo,
                           double pp, boolean recursive) {
        this.modulo2 = modulo2;
        this.measure = measure;
        this.useMassFlav = useMassFlav;
        this.sphericalAlgo = sphericalAlgo;
        this.pp = pp;
        this.recursive = recursive;
    }

    public void setRecursive(boolean value) {
        recursive = value;
    }

    public boolean recursive() {
        return recursive;
    }

    /* ------------------------------------------------------------------ */
    /* Kinematics                                                          */
    /* ------------------------------------------------------------------ */

    /** The rapidity, from log1p below |y| = 0.1 so that it stays accurate down to the smallest values. */
    public static double accurateRap(PseudoJet p) {
        double rap = p.rap();
        if (Math.abs(rap) < 0.1) rap = 0.5 * CRMath.log1p(2 * p.pz() / (p.E() - p.pz()));
        return rap;
    }

    /** |dphi|, from a transverse cross product below 0.1 (accurate near the x and y axes). */
    public static double accurateAbsdphi(PseudoJet p1, PseudoJet p2) {
        double dphi = Math.abs(p1.deltaPhiTo(p2));
        if (dphi < 0.1) {
            final double invp1pt = 1.0 / p1.pt();
            final double invp2pt = 1.0 / p2.pt();
            final double cross = (p1.px() * invp1pt) * (p2.py() * invp2pt) - (p2.px() * invp2pt) * (p1.py() * invp1pt);
            dphi = Math.abs(CRMath.asin(cross));
        }
        return dphi;
    }

    /** u_12, the neutralisation distance; refScale matters for the aktlike measures only. */
    public double neutralisationDistance(PseudoJet p1, PseudoJet p2, double refScale) {
        if (sphericalAlgo) {
            final double norm = 1.0 / (p1.modp() * p2.modp());
            double oneMinusCosTheta = 1.0 - (p1.px() * p2.px() + p1.py() * p2.py() + p1.pz() * p2.pz()) * norm;
            if (oneMinusCosTheta * oneMinusCosTheta < Math.ulp(1.0)) {
                final double cx = p1.py() * p2.pz() - p2.py() * p1.pz();
                final double cy = p1.pz() * p2.px() - p2.pz() * p1.px();
                final double cz = p1.px() * p2.py() - p2.px() * p1.py();
                final double sin2theta = (cx * cx + cy * cy + cz * cz) * norm * norm;
                oneMinusCosTheta = sin2theta / 2;
            }
            double u;
            final double eMax = Math.max(p1.E(), p2.E());
            final double eMin = Math.min(p1.E(), p2.E());
            switch (measure) {
                case AKTLIKE_PAIR_REFRATIO -> {
                    WARN_AKTLIKE_MEASURE.warn("FlavNeutraliser::neutralisation_distance: using aktlike_pair_refratio, which is not validated");
                    u = 2 * oneMinusCosTheta / CRMath.pow(eMax, 2);
                    if (!FlavHistory.currentFlavourOf(p1).isFlavourless()
                            && !FlavHistory.currentFlavourOf(p2).isFlavourless()) {
                        u *= CRMath.pow(eMax / refScale, 4);
                    }
                }
                case JADE, JADEA2 -> u = 2 * p1.E() * p2.E() * oneMinusCosTheta;
                case MAXSCALE -> u = 2 * CRMath.pow(eMax, 2) * oneMinusCosTheta;
                case GENERAL -> u = 2 * CRMath.pow(eMax, 2 * p) * CRMath.pow(eMin, 2 * q) * oneMinusCosTheta;
                default -> {
                    WARN_OLD_MEASURE.warn("FlavNeutraliser::neutralisation_distance: using deprecated old ratio measure");
                    u = 2 * CRMath.pow(eMax / eMin, 2 * pp) * oneMinusCosTheta;
                }
            }
            return u;
        }

        final double p1t2 = p1.pt2();
        final double p2t2 = p2.pt2();
        final double pt2ratio = p1t2 > p2t2 ? p1t2 / p2t2 : p2t2 / p1t2;
        final double drap = Math.abs(accurateRap(p1) - accurateRap(p2));
        final double dphi = accurateAbsdphi(p1, p2);
        final double deltaR2 = drap * drap + dphi * dphi;
        final boolean far = deltaR2 > DELTA_R2_HANDOVER;
        switch (measure) {
            case GENERAL -> {
                final double maxpt2p = CRMath.pow(Math.max(p1.pt2(), p2.pt2()), p);
                final double minpt2q = CRMath.pow(Math.min(p1.pt2(), p2.pt2()), q);
                if (far) return maxpt2p * minpt2q * 2 * ((CRMath.cosh(a * drap) - 1) / (a * a) - (CRMath.cos(dphi) - 1));
                return maxpt2p * minpt2q * deltaR2;
            }
            case SINH_DELTA_R -> {
                WARN_OLD_MEASURE.warn("FlavNeutraliser::neutralisation_distance: using deprecated old ratio measure");
                return far ? pt2ratio * CRMath.pow(CRMath.sinh(Math.sqrt(deltaR2)), 2) : pt2ratio * deltaR2;
            }
            case DELTA_R -> {
                WARN_OLD_MEASURE.warn("FlavNeutraliser::neutralisation_distance: using deprecated old ratio measure");
                return pt2ratio * deltaR2;
            }
            case JADE_DELTA_R -> {
                return Math.sqrt(p1t2 * p2t2) * deltaR2;
            }
            case MAXSCALE_DELTA_R -> {
                return Math.max(p1.pt2(), p2.pt2()) * deltaR2;
            }
            case PHI2_COSHY -> {
                return far ? pt2ratio * (CRMath.pow(dphi, 2) + 2 * (CRMath.cosh(drap) - 1)) : pt2ratio * deltaR2;
            }
            case COSPHI_COSHY -> {
                WARN_OLD_MEASURE.warn("FlavNeutraliser::neutralisation_distance: using deprecated old ratio measure");
                return far ? pt2ratio * 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) : pt2ratio * deltaR2;
            }
            case AKTLIKE_PAIR_REFRATIO, AKTLIKE_PAIR_DYNREFRATIO -> {
                WARN_AKTLIKE_MEASURE.warn("FlavNeutraliser::neutralisation_distance: using aktlike_pair_refratio, which is not validated");
                final double maxpt2 = Math.max(p1.pt2(), p2.pt2());
                double u = 1.0 / maxpt2;
                if (far) u *= 2 * (CRMath.cosh(drap) - CRMath.cos(dphi));
                else u *= deltaR2;
                if (!FlavHistory.currentFlavourOf(p1).isFlavourless()
                        && !FlavHistory.currentFlavourOf(p2).isFlavourless()) {
                    u *= CRMath.pow(maxpt2 / CRMath.pow(refScale, 2), 2);
                }
                return u;
            }
            case JADE -> {
                final double p1tp2t = Math.sqrt(p1t2 * p2t2);
                return far ? p1tp2t * 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) : p1tp2t * deltaR2;
            }
            case JADEA2 -> {
                final double p1tp2t = Math.sqrt(p1t2 * p2t2);
                return far ? p1tp2t * 2 * ((CRMath.cosh(2 * drap) - 1) / 4 + (1 - CRMath.cos(dphi))) : p1tp2t * deltaR2;
            }
            case MAXSCALE -> {
                final double maxpt2 = Math.max(p1.pt2(), p2.pt2());
                return far ? maxpt2 * 2 * (CRMath.cosh(drap) - CRMath.cos(dphi)) : maxpt2 * deltaR2;
            }
            default -> throw new FastJetException("Unrecognised neutralisation measure");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Flavour bookkeeping                                                 */
    /* ------------------------------------------------------------------ */

    /** Whether j and k carry flavour that can cancel. */
    static boolean haveFlavourToNeutralise(PseudoJet j, PseudoJet k, boolean modulo2) {
        final FlavInfo f1 = FlavHistory.currentFlavourOf(j);
        final FlavInfo f2 = FlavHistory.currentFlavourOf(k);
        for (int i = 1; i <= 6; i++) {
            if (!modulo2 ? f2.get(i) * f1.get(i) < 0 : f2.get(i) == 1 && f1.get(i) == 1) return true;
        }
        return false;
    }

    /** Cancels what flavour j and k can cancel, recording it in both histories at histStep. */
    static void neutraliseFlavour(PseudoJet j, PseudoJet k, int histStep, boolean modulo2) {
        final int[] f1 = FlavHistory.currentFlavourOf(j).words();
        final int[] f2 = FlavHistory.currentFlavourOf(k).words();
        for (int i = 1; i <= 6; i++) {
            if (!modulo2) {
                if (f2[i] * f1[i] < 0 && Math.abs(f2[i]) <= Math.abs(f1[i])) {
                    f1[i] = f2[i] + f1[i];
                    f2[i] = 0;
                }
                if (f2[i] * f1[i] < 0 && Math.abs(f2[i]) > Math.abs(f1[i])) {
                    f2[i] = f2[i] + f1[i];
                    f1[i] = 0;
                }
            } else if (f2[i] == 1 && f1[i] == 1) {
                f1[i] = 0;
                f2[i] = 0;
            }
        }
        history(j).updateFlavourHistory(new FlavInfo(f1, 0), histStep);
        history(k).updateFlavourHistory(new FlavInfo(f2, 0), histStep);
    }

    private static FlavHistory history(PseudoJet p) {
        if (!(p.userInfo() instanceof FlavHistory h)) {
            throw new FastJetException("FlavNeutraliser: a flavoured object has no FlavHistory");
        }
        return h;
    }

    /* ------------------------------------------------------------------ */
    /* The replay                                                          */
    /* ------------------------------------------------------------------ */

    /** A flavoured object and its distance u to the one looking for a neutraliser. */
    private static final class Candidate {
        final PseudoJet jet;
        double u;

        Candidate(PseudoJet jet) {
            this.jet = jet;
        }
    }

    private static final Comparator<Candidate> BY_U = (x, y) -> x.u < y.u ? -1 : (y.u < x.u ? 1 : 0);
    private static final Comparator<Candidate> BY_U_DECREASING = (x, y) -> x.u > y.u ? -1 : (y.u > x.u ? 1 : 0);

    /**
     * Replays the clustering of cs with neutralisation, and answers what is to
     * replace cs.jets(): the same momenta, with the flavour histories this
     * gave. The C++ copies of the jets share their user information with the
     * sequence's, and so do these copies.
     */
    public List<PseudoJet> neutralise(ClusterSequence cs) {
        final FlavRecombiner flavRecombiner = useMassFlav ? new MassFlavRecombiner() : new FlavRecombiner();
        final List<PseudoJet> jets = new ArrayList<>(cs.jets().size());
        for (PseudoJet j : cs.jets()) jets.add(j.copy());
        final List<ClusterSequence.HistoryElement> hist = cs.history();
        final ToDoubleFunction<PseudoJet> hardness = sphericalAlgo ? PseudoJet::E : PseudoJet::pt;

        double refScale = 0;
        if (measure == Measure.AKTLIKE_PAIR_REFRATIO) {
            for (int i = 0; i < cs.nParticles(); i++) refScale += hardness.applyAsDouble(jets.get(i));
        } else if (measure == Measure.AKTLIKE_PAIR_DYNREFRATIO) {
            for (int i = 0; i < cs.nParticles(); i++) refScale = Math.max(refScale, hardness.applyAsDouble(jets.get(i)));
        }

        for (int ihStep = 0; ihStep < hist.size(); ihStep++) {
            final ClusterSequence.HistoryElement step = hist.get(ihStep);
            int index1 = step.parent1();
            int index2 = step.parent2();
            if (index1 < 0 || index2 < 0) continue;
            // jet_i is the softer of the two
            if (hardness.applyAsDouble(jets.get(hist.get(index1).jetpIndex()))
                    > hardness.applyAsDouble(jets.get(hist.get(index2).jetpIndex()))) {
                final int t = index1;
                index1 = index2;
                index2 = t;
            }
            final int ji = hist.get(index1).jetpIndex();
            final int jj = hist.get(index2).jetpIndex();
            final PseudoJet jetI = jets.get(ji);
            final PseudoJet jetJ = jets.get(jj);

            if (!FlavHistory.currentFlavourOf(jetI).isFlavourless()) {
                final double uij = neutralisationDistance(jetI, jetJ, refScale);
                final List<Candidate> candidates = new ArrayList<>();
                for (int k = 0; k < jets.size(); k++) {
                    if (k == ji || k == jj) continue;
                    final PseudoJet jetK = jets.get(k);
                    final int chi = jetK.clusterHistIndex();
                    // not created yet, or recombined meanwhile
                    if (chi >= ihStep) continue;
                    final int child = hist.get(chi).child();
                    if (hist.get(child).parent2() != -1 && child < ihStep) continue;
                    if (!FlavHistory.currentFlavourOf(jetK).isFlavourless()) candidates.add(new Candidate(jetK));
                }
                if (recursive) {
                    candidates.add(new Candidate(jetJ));
                    useCandidatesRecursive(jetI, uij, ihStep, candidates, refScale, jetJ);
                } else {
                    useCandidates(jetI, uij, ihStep, candidates, refScale);
                }
            }

            // the recombination itself, into the jet the step made
            final PseudoJet child = jets.get(step.jetpIndex());
            final int keep = child.clusterHistIndex();
            flavRecombiner.recombine(jetI, jetJ, child);
            child.setClusterHistIndex(keep);
            final FlavHistory h = history(child);
            h.amendLastHistoryIndex(ihStep);
            if (modulo2) h.applyModulo2();
            if (measure == Measure.AKTLIKE_PAIR_DYNREFRATIO) {
                refScale = Math.max(refScale, hardness.applyAsDouble(child));
            }
        }
        return jets;
    }

    /** The inclusive jets after neutralisation, above ptmin, in the order of the C++. */
    public List<PseudoJet> inclusiveJets(ClusterSequence cs, double ptmin) {
        final List<ClusterSequence.HistoryElement> hist = cs.history();
        final List<PseudoJet> jets = neutralise(cs);
        final List<PseudoJet> out = new ArrayList<>();
        for (int u = hist.size() - 1; u >= 0; u--) {
            if (hist.get(u).parent2() == ClusterSequence.BEAM_JET) {
                final PseudoJet jet = jets.get(hist.get(hist.get(u).parent1()).jetpIndex());
                if (jet.pt() >= ptmin) out.add(jet);
            }
        }
        return out;
    }

    /** The first, non-recursive variant: the closest candidates in turn. */
    void useCandidates(PseudoJet jetI, double uij, int ihStep, List<Candidate> candidates, double refScale) {
        for (Candidate c : candidates) c.u = neutralisationDistance(jetI, c.jet, refScale);
        StdSort.sort(candidates, BY_U_DECREASING);
        while (!candidates.isEmpty()) {
            final Candidate current = candidates.get(candidates.size() - 1);
            if (current.u >= uij) break;
            neutraliseFlavour(jetI, current.jet, ihStep, modulo2);
            if (FlavHistory.currentFlavourOf(jetI).isFlavourless()) break;
            candidates.remove(candidates.size() - 1);
        }
    }

    /**
     * The recursive variant: before i takes flavour from k, k first looks
     * among the other candidates (j included, i excluded) for a neutraliser
     * it is closer to, so that i does not steal what belongs elsewhere.
     */
    void useCandidatesRecursive(PseudoJet jetI, double uij, int ihStep, List<Candidate> candidates,
                                double refScale, PseudoJet exclude) {
        for (Candidate c : candidates) c.u = neutralisationDistance(jetI, c.jet, refScale);
        StdSort.sort(candidates, BY_U);
        final double[] us = new double[candidates.size()];
        final PseudoJet[] ks = new PseudoJet[candidates.size()];
        for (int n = 0; n < candidates.size(); n++) {
            us[n] = candidates.get(n).u;
            ks[n] = candidates.get(n).jet;
        }
        for (int n = 0; n < ks.length; n++) {
            final PseudoJet k = ks[n];
            if (k == exclude) continue;
            if (us[n] >= uij) break;
            if (haveFlavourToNeutralise(jetI, k, modulo2)) {
                final List<Candidate> others = new ArrayList<>(candidates.size() - 1);
                for (Candidate c : candidates) {
                    if (c.jet != k) {
                        final Candidate copy = new Candidate(c.jet);
                        copy.u = c.u;
                        others.add(copy);
                    }
                }
                useCandidatesRecursive(k, us[n], ihStep, others, refScale, null);
                neutraliseFlavour(jetI, k, ihStep, modulo2);
            }
            if (FlavHistory.currentFlavourOf(jetI).isFlavourless()) break;
        }
    }
}
