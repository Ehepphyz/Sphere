package com.sphere.core.fjcontrib.variabler;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNFJN2Plain;
import com.sphere.core.fastjet.NNFJN2Tiled;
import com.sphere.core.fastjet.NNFJTiledBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.internal.StdPriorityQueue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Variable-R jets, fastjet::contrib::VariableRPlugin (VariableR 1.2.1;
 * D. Krohn, J. Thaler and L.-T. Wang, JHEP 0906 (2009) 059,
 * arXiv:0903.0392): a generalised-kt clustering whose jet radius
 * R_eff = rho / pt shrinks as the jet hardens, clamped to [min_r, max_r],
 * with d_ij = min(pt_i^2p, pt_j^2p) DeltaR_ij^2 and d_iB = pt_i^2p R_eff^2.
 *
 * <p>The five strategies of the C++ plugin are here (Best, N2Tiled, N2Plain,
 * NNH, and the original Native priority-queue clustering with its optional
 * kt pre-clustering). They cluster at the precision of the ClusterSequence:
 * with {@link Precision#DOUBLE} the history is the C++ one bit for bit, with
 * double-double every distance, and so every decision, is made to 106 bits.
 */
public class VariableRPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("variabler");
    }

    public static final double CALIKE = 0.0;
    public static final double KTLIKE = 1.0;
    public static final double AKTLIKE = -1.0;

    public enum Strategy { Best, N2Tiled, N2Plain, NNH, Native }

    private static final LimitedWarning PRECLUSTERING_DEPRECATED = new LimitedWarning();

    private final double rho2;
    private final double minR2;
    private final double maxR;
    private final double maxR2;
    private final double clustType;
    private final Strategy requestedStrategy;
    private final boolean precluster;
    private final JetDefinition preJetDef;

    public VariableRPlugin(double rho, double minR, double maxR, double clustType, boolean precluster, Strategy requestedStrategy) {
        this.rho2 = rho * rho;
        this.minR2 = minR * minR;
        this.maxR = maxR;
        this.maxR2 = maxR * maxR;
        this.clustType = clustType;
        this.requestedStrategy = requestedStrategy;
        this.precluster = precluster;
        if (minR < 0.0) throw new FastJetException("VariableRPlugin: Minimum radius must be positive.");
        if (precluster && minR == 0.0) throw new FastJetException("VariableRPlugin: To apply preclustering, minimum radius must be non-zero.");
        if (maxR < 0.0) throw new FastJetException("VariableRPlugin: Maximum radius must be positive.");
        if (minR > maxR) throw new FastJetException("VariableRPlugin: Minimum radius must be bigger than or equal to maximum radius.");
        this.preJetDef = minR > 0 ? new JetDefinition(JetAlgorithm.KT, minR) : null;
        if (precluster) {
            if (requestedStrategy != Strategy.Best && requestedStrategy != Strategy.Native) {
                throw new FastJetException("VariableRPlugin: pre-clustering is only supported for the Native and Best strategies");
            }
            PRECLUSTERING_DEPRECATED.warn("VariableRPlugin: internal pre-clustering is deprecated; use the NestedDefs FastJet plugin instead.");
        }
    }

    public VariableRPlugin(double rho, double minR, double maxR, double clustType, boolean precluster) {
        this(rho, minR, maxR, clustType, precluster, Strategy.Best);
    }

    public VariableRPlugin(double rho, double minR, double maxR, double clustType) {
        this(rho, minR, maxR, clustType, false, Strategy.Best);
    }

    /** Anti-kt-like variable R, contrib::AKTVR. */
    public static VariableRPlugin aktvr(double rho, double maxR) { return new VariableRPlugin(rho, 0.0, maxR, AKTLIKE); }

    /** C/A-like variable R, contrib::CAVR. */
    public static VariableRPlugin cavr(double rho, double maxR) { return new VariableRPlugin(rho, 0.0, maxR, CALIKE); }

    /** kt-like variable R, contrib::KTVR. */
    public static VariableRPlugin ktvr(double rho, double maxR) { return new VariableRPlugin(rho, 0.0, maxR, KTLIKE); }

    @Override
    public String description() {
        final StringBuilder s = new StringBuilder("Variable R (0903.0392), ");
        if (clustType == AKTLIKE) s.append("AKT");
        else if (clustType == CALIKE) s.append("CA");
        else if (clustType == KTLIKE) s.append("KT");
        else s.append("GenKT(p=").append(Fmt.g(clustType)).append(")");
        s.append(", rho=").append(Fmt.f(Math.sqrt(rho2), 0, 1));
        s.append(", min_r=").append(Fmt.f(Math.sqrt(minR2), 0, 1));
        s.append(", max_r=").append(Fmt.f(Math.sqrt(maxR2), 0, 1));
        s.append(precluster ? ", with precluster" : "");
        s.append(", strategy=").append(requestedStrategy.name());
        return s.toString();
    }

    @Override
    public double R() {
        return maxR;
    }

    /** The effective radius of a jet of transverse momentum pt: rho / pt clamped to [min_r, max_r]. */
    public double effectiveRadius(double pt) {
        final double r2 = rho2 / (pt * pt);
        return Math.sqrt(r2 > maxR2 ? maxR2 : (r2 < minR2 ? minR2 : r2));
    }

    Strategy bestStrategy(int n) {
        if (precluster) return Strategy.Native;
        if (n <= 30 || n <= 39.0 / (Math.max(maxR, 0.1) + 0.6)) return Strategy.N2Plain;
        return Strategy.N2Tiled;
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Strategy strategy = requestedStrategy;
        if (strategy == Strategy.Best) strategy = bestStrategy(cs.jets().size());
        final Precision precision = cs.precision();
        final boolean dd = precision == Precision.DD;
        if (strategy == Strategy.Native) {
            nativeClustering(cs, dd);
            return;
        }
        switch (strategy) {
            case N2Tiled -> {
                final NNFJN2Tiled<BriefJet> nn = new NNFJN2Tiled<>(cs.jets(), maxR, j -> new BriefJet(j, this, dd), precision);
                nnClustering(cs, dd, nn::dijMinDD, nn::mergeJets, nn::removeJet);
            }
            case N2Plain -> {
                final NNFJN2Plain<BriefJet> nn = new NNFJN2Plain<>(cs.jets(), j -> new BriefJet(j, this, dd), precision);
                nnClustering(cs, dd, nn::dijMinDD, nn::mergeJets, nn::removeJet);
            }
            default -> {
                final NNH<BriefJet> nn = new NNH<>(cs.jets(), j -> new BriefJet(j, this, dd));
                nnClustering(cs, dd, nn::dijMinDD, nn::mergeJets, nn::removeJet);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Nearest-neighbour strategies                                        */
    /* ------------------------------------------------------------------ */

    /**
     * The brief jet of all three nearest-neighbour helpers,
     * VariableRBriefJet: g_ij = DeltaR^2, g_iB = R_eff^2 and m_i = pt^2p.
     */
    static final class BriefJet implements NNFJTiledBriefJet<BriefJet>, NNBriefJet<BriefJet> {
        private final double rap;
        private final double phi;
        private final double beamR2;
        private final double momFactor2;
        private final boolean dd;
        private DD rapDD;
        private DD phiDD;
        private DD beamR2DD;
        private DD momFactor2DD;
        private double low;

        BriefJet(PseudoJet jet, VariableRPlugin p, boolean dd) {
            this.dd = dd;
            rap = jet.rap();
            phi = jet.phi();
            if (!dd) {
                final double pt2 = jet.pt2();
                double b = p.rho2 / pt2;
                if (b > p.maxR2) b = p.maxR2;
                else if (b < p.minR2) b = p.minR2;
                beamR2 = b;
                momFactor2 = CRMath.pow(pt2, p.clustType);
            } else {
                rapDD = jet.rapDD();
                phiDD = jet.phiDD();
                final DD pt2 = jet.kt2DD();
                DD b = new DD(p.rho2).div(pt2);
                if (b.gt(p.maxR2)) b = new DD(p.maxR2);
                else if (b.lt(p.minR2)) b = new DD(p.minR2);
                beamR2DD = b;
                momFactor2DD = pt2.pow(p.clustType);
                beamR2 = b.hi;
                momFactor2 = momFactor2DD.hi;
            }
        }

        private DD geometricalDistanceDD(BriefJet o) {
            DD dphi = phiDD.sub(o.phiDD).abs();
            final DD deta = rapDD.sub(o.rapDD);
            if (dphi.gt(DD.PI)) dphi = DD.TWO_PI.sub(dphi);
            return dphi.sqr().add(deta.sqr());
        }

        @Override
        public double geometricalDistance(BriefJet o) {
            if (!dd) {
                low = 0.0;
                double dphi = Math.abs(phi - o.phi);
                final double deta = rap - o.rap;
                if (dphi > Math.PI) dphi = 2.0 * Math.PI - dphi;
                return dphi * dphi + deta * deta;
            }
            final DD d = geometricalDistanceDD(o);
            low = d.lo;
            return d.hi;
        }

        @Override
        public double geometricalBeamDistance() {
            low = dd ? beamR2DD.lo : 0.0;
            return beamR2;
        }

        @Override
        public double momentumFactor() {
            return momFactor2;
        }

        @Override
        public double momentumFactorLow() {
            return dd ? momFactor2DD.lo : 0.0;
        }

        @Override
        public double distance(BriefJet o) {
            if (!dd) {
                low = 0.0;
                final double mom1 = momFactor2;
                final double mom2 = o.momFactor2;
                return (mom1 < mom2 ? mom1 : mom2) * geometricalDistance(o);
            }
            final DD d = DD.min(momFactor2DD, o.momFactor2DD).mul(geometricalDistanceDD(o));
            low = d.lo;
            return d.hi;
        }

        @Override
        public double beamDistance() {
            if (!dd) {
                low = 0.0;
                return momFactor2 * beamR2;
            }
            final DD d = momFactor2DD.mul(beamR2DD);
            low = d.lo;
            return d.hi;
        }

        @Override public double rap() { return rap; }
        @Override public double phi() { return phi; }
        @Override public double lowWord() { return low; }
    }

    private interface DijMin {
        DD apply(int[] ab);
    }

    private interface Merge {
        void apply(int iA, int iB, PseudoJet jet, int index);
    }

    private interface Remove {
        void apply(int iA);
    }

    private static void nnClustering(ClusterSequence cs, boolean dd, DijMin dijMin, Merge merge, Remove remove) {
        int njets = cs.jets().size();
        final int[] ab = new int[2];
        while (njets > 0) {
            final DD dij = dijMin.apply(ab);
            final int i = ab[0];
            final int j = ab[1];
            if (j >= 0) {
                final int k = dd ? cs.pluginRecordIJRecombination(i, j, dij) : cs.pluginRecordIJRecombination(i, j, dij.hi);
                merge.apply(i, j, cs.jet(k), k);
            } else {
                if (dd) cs.pluginRecordIBRecombination(i, dij);
                else cs.pluginRecordIBRecombination(i, dij.hi);
                remove.apply(i);
            }
            njets--;
        }
    }

    /* ------------------------------------------------------------------ */
    /* The native strategy                                                  */
    /* ------------------------------------------------------------------ */

    /** A candidate merging, JetDistancePair; j2 = -1 for the beam. */
    private record JetDistancePair(int j1, int j2, double distance, double distanceLow) {
    }

    /** CompareJetDistancePair: lhs.distance &gt; rhs.distance, so that the queue pops the smallest. */
    private static final Comparator<JetDistancePair> COMPARE = (l, r) ->
        (l.distance > r.distance || (l.distance == r.distance && l.distanceLow > r.distanceLow)) ? -1 : 0;

    private DD jjDistance(PseudoJet j1, PseudoJet j2, boolean dd) {
        if (!dd) {
            double ret;
            if (clustType == AKTLIKE) ret = Math.min(1.0 / j1.perp2(), 1.0 / j2.perp2());
            else if (clustType == CALIKE) ret = 1.0;
            else if (clustType == KTLIKE) ret = Math.min(j1.perp2(), j2.perp2());
            else if (clustType >= 0) ret = CRMath.pow(Math.min(j1.perp2(), j2.perp2()), clustType);
            else ret = CRMath.pow(Math.min(1.0 / j1.perp2(), 1.0 / j2.perp2()), -clustType);
            ret *= j1.squaredDistance(j2);
            return new DD(ret, 0.0);
        }
        final DD a = j1.kt2DD();
        final DD b = j2.kt2DD();
        final DD ret;
        if (clustType == CALIKE) ret = DD.ONE;
        else if (clustType >= 0) ret = DD.min(a, b).pow(clustType);
        else ret = DD.min(DD.ONE.div(a), DD.ONE.div(b)).pow(-clustType);
        return ret.mul(j1.squaredDistanceDD(j2));
    }

    private DD jbDistance(PseudoJet jet, boolean dd) {
        if (!dd) {
            final double preFactor = CRMath.pow(jet.perp2(), clustType);
            final double geomFactor = rho2 / jet.perp2();
            if (geomFactor < minR2) return new DD(minR2 * preFactor, 0.0);
            if (geomFactor > maxR2) return new DD(maxR2 * preFactor, 0.0);
            return new DD(geomFactor * preFactor, 0.0);
        }
        final DD pt2 = jet.kt2DD();
        final DD preFactor = pt2.pow(clustType);
        final DD geomFactor = new DD(rho2).div(pt2);
        if (geomFactor.lt(minR2)) return preFactor.mul(minR2);
        if (geomFactor.gt(maxR2)) return preFactor.mul(maxR2);
        return geomFactor.mul(preFactor);
    }

    private JetDistancePair pair(int j1, int j2, DD d) {
        return new JetDistancePair(j1, j2, d.hi, d.lo);
    }

    private void preclustering(ClusterSequence cs, TreeSet<Integer> unmerged) {
        for (int i = 0; i < cs.jets().size(); i++) unmerged.add(i);
        final ClusterSequence preCs = new ClusterSequence(cs.jets(), preJetDef);
        final List<PseudoJet> preclustered = preCs.inclusiveJets();
        final int[] particleJetIndices = preCs.particleJetIndices(preclustered);
        for (int i = 0; i < preclustered.size(); i++) {
            final ArrayDeque<Integer> constitIndices = new ArrayDeque<>();
            for (int j = 0; j < particleJetIndices.length; j++) if (particleJetIndices[j] == i) constitIndices.add(j);
            while (constitIndices.size() > 1) {
                final int indx1 = constitIndices.poll();
                unmerged.remove(indx1);
                final int indx2 = constitIndices.poll();
                unmerged.remove(indx2);
                final int finalJet = cs.pluginRecordIJRecombination(indx1, indx2, 0.0);
                constitIndices.add(finalJet);
                unmerged.add(finalJet);
            }
        }
    }

    private void setupDistanceMeasures(ClusterSequence cs, List<JetDistancePair> jetVec, TreeSet<Integer> unmerged, boolean dd) {
        final Integer[] ids = unmerged.toArray(new Integer[0]);
        for (int a = 0; a < ids.length; a++) {
            final int i1 = ids[a];
            for (int b = a + 1; b < ids.length; b++) {
                final int i2 = ids[b];
                jetVec.add(pair(i1, i2, jjDistance(cs.jet(i1), cs.jet(i2), dd)));
            }
            jetVec.add(pair(i1, -1, jbDistance(cs.jet(i1), dd)));
        }
    }

    private void nativeClustering(ClusterSequence cs, boolean dd) {
        final TreeSet<Integer> unmerged = new TreeSet<>();
        if (precluster) {
            preclustering(cs, unmerged);
        } else {
            for (int i = 0; i < cs.jets().size(); i++) unmerged.add(i);
        }
        final List<JetDistancePair> jetVec = new ArrayList<>();
        setupDistanceMeasures(cs, jetVec, unmerged, dd);
        StdPriorityQueue<JetDistancePair> queue = new StdPriorityQueue<>(COMPARE, jetVec);
        while (!queue.isEmpty()) {
            final JetDistancePair jd = queue.pop();
            if (queue.size() > 50 && queue.size() > 1.5 * unmerged.size() * unmerged.size()) {
                jetVec.clear();
                setupDistanceMeasures(cs, jetVec, unmerged, dd);
                queue = new StdPriorityQueue<>(COMPARE, jetVec);
            }
            if (!unmerged.contains(jd.j1) || (jd.j2 != -1 && !unmerged.contains(jd.j2))) continue;
            final DD d = new DD(jd.distance, jd.distanceLow);
            if (jd.j2 == -1) {
                if (dd) cs.pluginRecordIBRecombination(jd.j1, d);
                else cs.pluginRecordIBRecombination(jd.j1, jd.distance);
                unmerged.remove(jd.j1);
            } else {
                final int newJet = dd ? cs.pluginRecordIJRecombination(jd.j1, jd.j2, d)
                    : cs.pluginRecordIJRecombination(jd.j1, jd.j2, jd.distance);
                unmerged.remove(jd.j1);
                unmerged.remove(jd.j2);
                for (int it : unmerged) queue.push(pair(newJet, it, jjDistance(cs.jet(it), cs.jet(newJet), dd)));
                unmerged.add(newJet);
                queue.push(pair(newJet, -1, jbDistance(cs.jet(newJet), dd)));
            }
        }
    }
}
