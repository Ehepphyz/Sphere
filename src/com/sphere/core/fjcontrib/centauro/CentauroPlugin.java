package com.sphere.core.fjcontrib.centauro;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

/**
 * The Centauro jet algorithm for deep-inelastic scattering,
 * fastjet::contrib::CentauroPlugin (Centauro 1.0.0; M. Arratia, Y. Makris,
 * D. Neill, F. Ringer and N. Sato, Phys. Rev. D 104 (2021) 034005,
 * arXiv:2006.10751): in the Breit frame, with etabar = 2 pT / (E - pz),
 * d_ij = [(etabar_i - etabar_j)^2 + 2 etabar_i etabar_j (1 - cos dphi_ij)] / R^2
 * and d_iB = 1; given gamma_E and gamma_pz, the boost to the Breit frame is
 * folded into etabar.
 *
 * <p>With {@link Precision#DOUBLE} the clustering is the C++ one bit for bit.
 * In double-double, E - pz is formed without cancellation for the forward
 * particles and 1 - cos dphi as 2 sin^2(dphi/2), so that nearly collinear
 * pairs keep their full relative precision.
 */
public class CentauroPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("centauro");
    }

    private final double r;
    private final double gammaE;
    private final double gammaPz;

    public CentauroPlugin(double r, double gammaE, double gammaPz) {
        this.r = r;
        this.gammaE = gammaE;
        this.gammaPz = gammaPz;
    }

    /** Particles given in the Breit frame. */
    public CentauroPlugin(double r) {
        this(r, 0, 0);
    }

    @Override
    public String description() {
        String s = "Centauro plugin with R = " + Fmt.g(r);
        if (gammaE == 0 && gammaPz == 0) {
            s += " gamma E and gamma Pz parameters were not given --> assume you are giving particles momenta in Breit frame";
        }
        return s;
    }

    @Override public double R() { return r; }
    public double gammaE() { return gammaE; }
    public double gammaPz() { return gammaPz; }
    @Override public boolean exclusiveSequenceMeaningful() { return true; }

    /** CentauroBriefJet. */
    static final class BriefJet implements NNBriefJet<BriefJet> {
        private final boolean dd;
        private final double phi;
        private final double etabar;
        private final double r;
        private DD phiDD;
        private DD etabarDD;
        private double low;

        BriefJet(PseudoJet jet, CentauroPlugin p, boolean dd) {
            this.dd = dd;
            r = p.r;
            final double pT = jet.perp();
            phi = jet.phi();
            final boolean boosted = p.gammaE != 0 && p.gammaPz != 0;
            if (!dd) {
                if (boosted) {
                    final double q = Math.sqrt(-1.0 * (p.gammaE * p.gammaE - p.gammaPz * p.gammaPz));
                    etabar = -2.0 * (q / (p.gammaE + p.gammaPz)) * (pT / (jet.E() - jet.pz()));
                } else {
                    etabar = +2.0 * pT / (jet.E() - jet.pz());
                }
            } else {
                phiDD = jet.phiDD();
                final DD ptDD = jet.kt2DD().sqrt();
                final DD eMinusPz = jet.eDD().sub(jet.pzDD());
                DD eb = ptDD.div(eMinusPz).mulPow2(2.0);
                if (boosted) {
                    final DD q = DD.square(p.gammaPz).sub(DD.square(p.gammaE)).sqrt();
                    eb = eb.mul(q.div(DD.sum(p.gammaE, p.gammaPz))).neg();
                }
                etabarDD = eb;
                etabar = eb.hi;
            }
        }

        @Override
        public double distance(BriefJet o) {
            if (!dd) {
                low = 0.0;
                double dij = CRMath.pow(etabar - o.etabar, 2.0) + 2 * etabar * o.etabar * (1 - CRMath.cos(phi - o.phi));
                dij = dij / CRMath.pow(r, 2.0);
                return dij;
            }
            final DD s = phiDD.sub(o.phiDD).mulPow2(0.5).sin();
            final DD oneMinusCos = s.sqr().mulPow2(2.0);
            final DD d = etabarDD.sub(o.etabarDD).sqr().add(etabarDD.mul(o.etabarDD).mulPow2(2.0).mul(oneMinusCos))
                .div(DD.square(r));
            low = d.lo;
            return d.hi;
        }

        @Override
        public double beamDistance() {
            low = 0.0;
            return 1.0;
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
