package com.sphere.core.fjcontrib.valencia;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.EEDirection;
import com.sphere.core.fjcontrib.ContribCitations;

/**
 * The Valencia jet algorithm, fastjet::contrib::ValenciaPlugin
 * (ValenciaPlugin 2.0.2; M. Boronat, J. Fuster, I. Garcia, E. Ros and M. Vos,
 * Phys. Lett. B 750 (2015) 95, arXiv:1404.4294): an e+e- algorithm
 * for colliders with beam-induced background,
 * d_ij = 2 min(E_i^2beta, E_j^2beta) (1 - cos theta_ij) / R^2 and
 * d_iB = E_i^2beta sin^2gamma theta_i.
 *
 * <p>With {@link Precision#DOUBLE} the clustering is the C++ one bit for bit.
 * In double-double 1 - cos theta is taken from the squared chord between the
 * directions, which keeps full precision for nearly collinear particles
 * where the double 1 - n_i.n_j cancels, and the energy powers are made to
 * 106 bits.
 */
public class ValenciaPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("valencia");
    }

    private final double r;
    private final double beta;
    private final double gamma;

    public ValenciaPlugin(double r, double beta, double gamma) {
        this.r = r;
        this.beta = beta;
        this.gamma = gamma;
    }

    /** gamma = beta. */
    public ValenciaPlugin(double r, double beta) {
        this(r, beta, beta);
    }

    @Override
    public String description() {
        return "Valencia plugin with R = " + Fmt.g(r) + ", beta = " + Fmt.g(beta) + " and gamma = " + Fmt.g(gamma);
    }

    @Override public double R() { return r; }
    public double beta() { return beta; }
    public double gamma() { return gamma; }
    @Override public boolean exclusiveSequenceMeaningful() { return true; }

    /** ValenciaBriefJet. */
    static final class BriefJet implements NNBriefJet<BriefJet> {
        private final boolean dd;
        private final EEDirection n;
        private final double eBeta;
        private final double r2;
        private final double diB;
        private DD eBetaDD;
        private DD r2DD;
        private DD diBDD;
        private double low;

        BriefJet(PseudoJet jet, ValenciaPlugin p, boolean dd) {
            this.dd = dd;
            n = new EEDirection(jet, dd);
            if (!dd) {
                eBeta = CRMath.pow(jet.E(), 2 * p.beta);
                r2 = CRMath.pow(p.r, 2);
                diB = eBeta * CRMath.pow(jet.perp() / Math.sqrt(jet.perp2() + jet.pz() * jet.pz()), 2 * p.gamma);
            } else {
                eBetaDD = jet.eDD().pow(2 * p.beta);
                r2DD = DD.square(p.r);
                final DD kt2 = jet.kt2DD();
                final DD sin2 = kt2.div(kt2.add(jet.pzDD().sqr()));
                diBDD = eBetaDD.mul(sin2.pow(p.gamma));
                eBeta = eBetaDD.hi;
                r2 = r2DD.hi;
                diB = diBDD.hi;
            }
        }

        @Override
        public double distance(BriefJet o) {
            double dij = n.oneMinusCos(o.n);
            if (!dd) {
                low = 0.0;
                if (o.eBeta < eBeta) dij *= 2 * o.eBeta;
                else dij *= 2 * eBeta;
                dij /= r2;
                return dij;
            }
            final DD d = new DD(dij, n.lastLow()).mul(DD.min(eBetaDD, o.eBetaDD)).mulPow2(2.0).div(r2DD);
            low = d.lo;
            return d.hi;
        }

        @Override
        public double beamDistance() {
            low = dd ? diBDD.lo : 0.0;
            return diB;
        }

        @Override public double lowWord() { return low; }
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final boolean dd = cs.precision() == Precision.DD;
        int njets = cs.jets().size();
        final NNH<BriefJet> nnh = new NNH<>(cs.jets(), j -> new BriefJet(j, this, dd));
        final int[] ab = new int[2];
        while (njets > 0) {
            final DD dij = nnh.dijMinDD(ab);
            final int i = ab[0];
            final int j = ab[1];
            if (j >= 0) {
                final int k = dd ? cs.pluginRecordIJRecombination(i, j, dij) : cs.pluginRecordIJRecombination(i, j, dij.hi);
                nnh.mergeJets(i, j, cs.jet(k), k);
            } else {
                if (dd) cs.pluginRecordIBRecombination(i, dij);
                else cs.pluginRecordIBRecombination(i, dij.hi);
                nnh.removeJet(i);
            }
            njets--;
        }
    }
}
