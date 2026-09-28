package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Precision;

/**
 * The e+e- Cambridge algorithm, fastjet::EECambridgePlugin (Dokshitzer,
 * Leder, Moretti, Webber, JHEP 08 (1997) 001): pairs are ordered in
 * v_ij = 1 - cos(theta_ij); the closest pair is merged if
 * y_ij = 2 min(E_i^2, E_j^2) v_ij / Q^2 is below ycut, otherwise its softer
 * member is frozen as a jet (soft freezing).
 *
 * Under {@link Precision#DD} the angular ordering is made on 1 - cos(theta)
 * computed without cancellation, which FastJet's double 1 - n_i.n_j cannot
 * resolve below theta ~ 1e-8, and the y_ij recorded carry 106 bits.
 */
public final class EECambridgePlugin implements JetDefinition.Plugin {

    private final double ycut;

    public EECambridgePlugin(double ycut) {
        this.ycut = ycut;
    }

    public double ycut() {
        return ycut;
    }

    @Override
    public String description() {
        return "EECambridge plugin with ycut = " + Fmt.g(ycut);
    }

    @Override
    public double R() {
        return 1.0;
    }

    @Override
    public boolean exclusiveSequenceMeaningful() {
        return true;
    }

    @Override
    public boolean isSpherical() {
        return true;
    }

    /** A jet reduced to its direction. */
    private static final class BriefJet implements NNBriefJet<BriefJet> {
        private final EEDirection n;

        BriefJet(PseudoJet jet, boolean dd) {
            n = new EEDirection(jet, dd);
        }

        @Override
        public double distance(BriefJet other) {
            return n.oneMinusCos(other.n);
        }

        @Override
        public double beamDistance() {
            n.lastLow = 0.0;
            return Double.MAX_VALUE;
        }

        @Override
        public double lowWord() {
            return n.lastLow;
        }
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final boolean dd = cs.precision() == Precision.DD;
        int njets = cs.nJets();
        final NNH<BriefJet> nnh = new NNH<>(cs.jets(), j -> new BriefJet(j, dd));
        final int[] ab = new int[2];
        if (!dd) {
            final double q2 = cs.Q2();
            while (njets > 0) {
                final double vij = nnh.dijMin(ab);
                int i = ab[0];
                int j = ab[1];
                double dij;
                if (j >= 0) {
                    final double ei = cs.jet(i).E();
                    final double ej = cs.jet(j).E();
                    final double scale = Math.min(ei, ej);
                    dij = 2 * vij * scale * scale;
                    if (dij > q2 * ycut) {
                        // the softer partner is called a "beam" jet
                        if (ei > ej) i = j;
                        j = -1;
                    }
                } else {
                    dij = q2;
                }
                if (j >= 0) {
                    final int k = cs.pluginRecordIJRecombination(i, j, dij);
                    nnh.mergeJets(i, j, cs.jet(k), k);
                } else {
                    cs.pluginRecordIBRecombination(i, dij);
                    nnh.removeJet(i);
                }
                njets--;
            }
            return;
        }
        final DD q2 = cs.q2DD();
        final DD q2ycut = q2.mul(ycut);
        while (njets > 0) {
            final DD vij = nnh.dijMinDD(ab);
            int i = ab[0];
            int j = ab[1];
            DD dij;
            if (j >= 0) {
                final DD ei = cs.jet(i).eDD();
                final DD ej = cs.jet(j).eDD();
                final DD scale = DD.min(ei, ej);
                dij = vij.mulPow2(2.0).mul(scale.sqr());
                if (dij.gt(q2ycut)) {
                    if (ei.gt(ej)) i = j;
                    j = -1;
                }
            } else {
                dij = q2;
            }
            if (j >= 0) {
                final int k = cs.pluginRecordIJRecombination(i, j, dij);
                nnh.mergeJets(i, j, cs.jet(k), k);
            } else {
                cs.pluginRecordIBRecombination(i, dij);
                nnh.removeJet(i);
            }
            njets--;
        }
    }
}
