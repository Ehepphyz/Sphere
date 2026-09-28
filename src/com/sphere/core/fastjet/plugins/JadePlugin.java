package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNFJBriefJet;
import com.sphere.core.fastjet.NNFJN2Plain;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Precision;

/**
 * The e+e- JADE algorithm, fastjet::JadePlugin: d_ij = 2 E_i E_j
 * (1 - cos theta_ij), always merging, so that its content is in the
 * exclusive jets (exclusive_jets_ycut with y_ij = d_ij / Q^2).
 *
 * The NNFJN2Plain strategy factorises d_ij = min(m_i, m_j) g_ij with
 * m = sqrt2 E and g_ij = max(m_i, m_j)(1 - cos theta_ij); NNH uses d_ij
 * directly. Both give the same clustering.
 */
public final class JadePlugin implements JetDefinition.Plugin {

    public enum Strategy {
        NNH, NNFJN2PLAIN
    }

    private final Strategy strategy;

    public JadePlugin() {
        this(Strategy.NNFJN2PLAIN);
    }

    public JadePlugin(Strategy strategy) {
        this.strategy = strategy;
    }

    /** FastJet's integer codes: 0 for NNH, 1 for NNFJN2Plain. */
    public static Strategy strategyOf(int code) {
        return switch (code) {
            case 0 -> Strategy.NNH;
            case 1 -> Strategy.NNFJN2PLAIN;
            default -> throw new FastJetException("Unrecognized strategy in JadePlugin");
        };
    }

    public Strategy strategy() {
        return strategy;
    }

    @Override
    public String description() {
        return "e+e- JADE algorithm plugin"
            + (strategy == Strategy.NNH ? ", using NNH strategy" : ", using NNFJN2Plain strategy");
    }

    @Override
    public double R() {
        return 1.0;
    }

    @Override
    public boolean exclusiveSequenceMeaningful() {
        return true;
    }

    private static final double SQRT2_H = Math.sqrt(2.0);
    private static final DD SQRT2 = DD.TWO.sqrt();
    private static final double ALMOST_MAX = Double.MAX_VALUE * (1 - 1e-13);

    static final class BriefJet implements NNBriefJet<BriefJet>, NNFJBriefJet<BriefJet> {
        private final boolean dd;
        private final EEDirection n;
        private final double rt2E;
        private final double rt2EL;
        private double low;

        BriefJet(PseudoJet jet, boolean dd) {
            this.dd = dd;
            n = new EEDirection(jet, dd);
            if (dd) {
                final DD r = SQRT2.mul(jet.eDD());
                rt2E = r.hi;
                rt2EL = r.lo;
            } else {
                rt2E = SQRT2_H * jet.E();
                rt2EL = 0.0;
            }
        }

        private DD rt2EDD() {
            return new DD(rt2E, rt2EL);
        }

        @Override
        public double distance(BriefJet other) {
            final double dij = n.oneMinusCos(other.n);
            if (!dd) {
                low = 0.0;
                return dij * (rt2E * other.rt2E);
            }
            final DD d = new DD(dij, n.lastLow).mul(rt2EDD().mul(other.rt2EDD()));
            low = d.lo;
            return d.hi;
        }

        @Override
        public double geometricalDistance(BriefJet other) {
            final double dij = n.oneMinusCos(other.n);
            if (!dd) {
                low = 0.0;
                return dij * Math.max(rt2E, other.rt2E);
            }
            final DD d = new DD(dij, n.lastLow).mul(DD.max(rt2EDD(), other.rt2EDD()));
            low = d.lo;
            return d.hi;
        }

        @Override
        public double momentumFactor() {
            return rt2E;
        }

        @Override
        public double momentumFactorLow() {
            return rt2EL;
        }

        @Override
        public double beamDistance() {
            low = 0.0;
            return Double.MAX_VALUE;
        }

        @Override
        public double geometricalBeamDistance() {
            low = 0.0;
            // slightly below the largest double, so that times the momentum
            // factor it cannot overflow
            return rt2E > 1.0 ? ALMOST_MAX / rt2E : ALMOST_MAX;
        }

        @Override
        public double lowWord() {
            return low;
        }
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final Precision precision = cs.precision();
        final boolean dd = precision == Precision.DD;
        switch (strategy) {
            case NNH -> {
                final NNH<BriefJet> nn = new NNH<>(cs.jets(), j -> new BriefJet(j, dd));
                run(cs, dd, nn::dijMinDD, nn::mergeJets, nn::removeJet);
            }
            case NNFJN2PLAIN -> {
                final NNFJN2Plain<BriefJet> nn = new NNFJN2Plain<>(cs.jets(), j -> new BriefJet(j, dd), precision);
                run(cs, dd, nn::dijMinDD, nn::mergeJets, nn::removeJet);
            }
            default -> throw new FastJetException("Unrecognized strategy in JadePlugin");
        }
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

    private static void run(ClusterSequence cs, boolean dd, DijMin dijMin, Merge merge, Remove remove) {
        int njets = cs.nJets();
        final int[] ab = new int[2];
        while (njets > 0) {
            final DD dij = dijMin.apply(ab);
            final int i = ab[0];
            final int j = ab[1];
            if (j >= 0) {
                final int k = cs.pluginRecordIJRecombination(i, j, dij);
                merge.apply(i, j, cs.jet(k), k);
            } else {
                final PseudoJet ji = cs.jet(i);
                if (dd) {
                    cs.pluginRecordIBRecombination(i, ji.eDD().sqr());
                } else {
                    cs.pluginRecordIBRecombination(i, ji.E() * ji.E());
                }
                remove.apply(i);
            }
            njets--;
        }
    }
}
