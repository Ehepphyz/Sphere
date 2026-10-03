package com.sphere.core.fjcontrib.disgenkt;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.EEDirection;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.internal.StdSort;

import java.util.List;

/**
 * The generalised-kt algorithm for deep-inelastic scattering in the Breit
 * frame, fastjet::contrib::DISGenktPlugin (DISGenkt, fastjet-contrib 1.104;
 * M. van Beekveld, S. Ferrario Ravasio, A. Karlberg and D. Peake):
 * e+e--like distances
 * d_ij = min(E_i^2p, E_j^2p) (1 - cos theta_ij) / (1 - cos R) and a beam
 * distance d_iB = E_i^2p (1 - s cos theta_i) towards the incoming beam of
 * sign s; the jet along the beam is the "macrojet".
 *
 * <p>With {@link Precision#DOUBLE} the clustering is the C++ one bit for bit.
 * In double-double 1 - cos theta_ij comes from the squared chord and
 * 1 - s cos theta_i from kt^2 / (|p| (|p| + s pz)) when it would cancel, so
 * the particles close to the beam or to each other keep full precision.
 */
public class DISGenktPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("disgenkt");
    }

    private final double p;
    private final double r;
    private int beamSign;
    private double q2 = -1;

    public DISGenktPlugin(double p, int beamSign, double r) {
        this.p = p;
        this.r = r;
        this.beamSign = beamSign;
        if (p < -1) throw new FastJetException("p should be larger or equal than 0");
        if (Math.abs(beamSign) != 1) throw new FastJetException("Expected a beam sign of +/- 1, got" + beamSign);
    }

    /** R = pi/2. */
    public DISGenktPlugin(double p, int beamSign) {
        this(p, beamSign, Math.PI / 2.);
    }

    @Override
    public String description() {
        return "DISCambridge with p = " + Fmt.g(p) + ", R = " + Fmt.g(r) + ", beam_sign = " + beamSign;
    }

    @Override public double R() { return r; }
    @Override public boolean exclusiveSequenceMeaningful() { return p >= 0; }
    @Override public boolean isSpherical() { return true; }
    public double p() { return p; }
    public int beamSign() { return beamSign; }
    public void resetBeamSign(int s) { beamSign = s; }
    public void resetQ2(double v) { q2 = v; }
    public double Q2() { return q2; }

    /** The index of the jet with the largest E - s pz (the macrojet), -1 if none is positive. */
    public int findIdxMacrojet(List<PseudoJet> jets) {
        double best = 0.0;
        int idx = -1;
        for (int i = 0; i < jets.size(); i++) {
            final double proj = jets.get(i).E() - beamSign * jets.get(i).pz();
            if (proj > best) {
                best = proj;
                idx = i;
            }
        }
        return idx;
    }

    /** The jets by decreasing E - s pz, FastJet's objects_sorted_by_values. */
    public List<PseudoJet> sortedByZjet(List<PseudoJet> jets) {
        final double[] zproj = new double[jets.size()];
        for (int i = 0; i < zproj.length; i++) zproj[i] = -(jets.get(i).E() - beamSign * jets.get(i).pz());
        return StdSort.objectsSortedByValues(jets, zproj);
    }

    /** DISBriefJet. */
    static final class BriefJet implements NNBriefJet<BriefJet> {
        private final boolean dd;
        private final EEDirection n;
        private final double nz;
        private final double e2p;
        private final double dijNorm;
        private final int beamSign;
        private DD e2pDD;
        private DD dijNormDD;
        private DD beamDD;
        private double low;

        BriefJet(PseudoJet jet, DISGenktPlugin pl, boolean dd) {
            this.dd = dd;
            n = new EEDirection(jet, dd);
            beamSign = pl.beamSign;
            if (!dd) {
                nz = jet.pz() * (1.0 / Math.sqrt(jet.modp2()));
                e2p = pl.p == 0 ? 1 : CRMath.pow(jet.E(), 2 * pl.p);
                dijNorm = pl.r < Math.PI ? 1 - CRMath.cos(pl.r) : 3 + CRMath.cos(pl.r);
            } else {
                nz = 0;
                e2pDD = pl.p == 0 ? DD.ONE : jet.eDD().pow(2 * pl.p);
                // 1 - cos R = 2 sin^2(R/2), 3 + cos R = 4 - 2 sin^2(R/2)
                final DD s = new DD(pl.r).mulPow2(0.5).sin();
                final DD twoS2 = s.sqr().mulPow2(2.0);
                dijNormDD = pl.r < Math.PI ? twoS2 : new DD(4.0).sub(twoS2);
                final DD kt2 = jet.kt2DD();
                final DD pz = jet.pzDD();
                final DD modp = kt2.add(pz.sqr()).sqrt();
                final DD spz = beamSign > 0 ? pz : pz.neg();
                final DD oneMinusCos = spz.gt(0.0) ? kt2.div(modp.mul(modp.add(spz))) : modp.sub(spz).div(modp);
                beamDD = oneMinusCos.mul(e2pDD);
                e2p = e2pDD.hi;
                dijNorm = dijNormDD.hi;
            }
        }

        @Override
        public double distance(BriefJet o) {
            double dij = n.oneMinusCos(o.n);
            if (!dd) {
                low = 0.0;
                dij = dij * Math.min(e2p, o.e2p) / dijNorm;
                return dij;
            }
            final DD d = new DD(dij, n.lastLow()).mul(DD.min(e2pDD, o.e2pDD)).div(dijNormDD);
            low = d.lo;
            return d.hi;
        }

        @Override
        public double beamDistance() {
            if (!dd) {
                low = 0.0;
                return (1 - beamSign * nz) * e2p;
            }
            low = beamDD.lo;
            return beamDD.hi;
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
