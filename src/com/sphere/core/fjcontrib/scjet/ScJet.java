package com.sphere.core.fjcontrib.scjet;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

/**
 * The semi-classical jet algorithm, fastjet::contrib::ScJet (ScJet 1.1.0;
 * J. Tseng and H. Evans, "Semi-classical approach to sequential recombination
 * algorithms for jet clustering", arXiv:1304.1025): d_ij = ((E_i + E_j)/4)^4 (DeltaR_ij^2 / R^2)^n and
 * d_iB = E_i^4, E the transverse mass (default), pt or Et; the beam
 * distance recorded is the square of the chosen E^2.
 *
 * <p>With {@link Precision#DOUBLE} the clustering is the C++ one bit for bit;
 * in double-double every distance is made to 106 bits.
 */
public class ScJet implements JetDefinition.Plugin {

    static {
        ContribCitations.use("scjet");
    }

    /** ScJet::energyModeType. */
    public enum EnergyMode {
        use_mt("Mt"), use_pt("Pt"), use_et("Et");

        private final String label;

        EnergyMode(String label) {
            this.label = label;
        }

        public String label() { return label; }
    }

    private final double r;
    private final int rexp;
    private final EnergyMode energyMode;

    public ScJet(double r, EnergyMode mode, int rexp) {
        this.r = r;
        this.rexp = rexp;
        this.energyMode = mode;
    }

    public ScJet(double r, EnergyMode mode) {
        this(r, mode, 3);
    }

    public ScJet(double r) {
        this(r, EnergyMode.use_mt, 3);
    }

    @Override public double R() { return r; }
    public int Rexp() { return rexp; }
    public EnergyMode energyMode() { return energyMode; }
    public String energyModeString() { return energyMode.label(); }
    @Override public boolean exclusiveSequenceMeaningful() { return true; }

    @Override
    public String description() {
        return "ScJet plugin using " + energyModeString() + " with R = " + Fmt.g(r) + " and exponent " + rexp;
    }

    static double energy2Value(PseudoJet jet, EnergyMode mode) {
        return switch (mode) {
            case use_et -> jet.Et2();
            case use_pt -> jet.pt2();
            default -> jet.mt2();
        };
    }

    static DD energy2ValueDD(PseudoJet jet, EnergyMode mode) {
        return switch (mode) {
            case use_et -> {
                final DD kt2 = jet.kt2DD();
                if (kt2.hi == 0) yield DD.ZERO;
                final DD e = jet.eDD();
                // E^2 kt^2 / (kt^2 + pz^2)
                yield e.sqr().mul(kt2).div(kt2.add(jet.pzDD().sqr()));
            }
            case use_pt -> jet.kt2DD();
            default -> jet.eDD().add(jet.pzDD()).mul(jet.eDD().sub(jet.pzDD()));
        };
    }

    /** ScBriefJet. */
    static final class BriefJet implements NNBriefJet<BriefJet> {
        private final boolean dd;
        private final double et;
        private final double rap;
        private final double phi;
        private final int rexp;
        private final double et4;
        private final double rr2;
        private DD etDD;
        private DD rapDD;
        private DD phiDD;
        private DD rr2DD;
        private double low;

        BriefJet(PseudoJet jet, ScJet plugin, boolean dd) {
            this.dd = dd;
            rexp = plugin.rexp;
            rap = jet.rap();
            phi = jet.phi();
            if (!dd) {
                et = Math.sqrt(energy2Value(jet, plugin.energyMode));
                rr2 = 1.0 / (plugin.r * plugin.r);
                et4 = et * et * et * et;
            } else {
                etDD = energy2ValueDD(jet, plugin.energyMode).sqrt();
                rapDD = jet.rapDD();
                phiDD = jet.phiDD();
                rr2DD = DD.ONE.div(DD.square(plugin.r));
                et = etDD.hi;
                rr2 = rr2DD.hi;
                et4 = etDD.sqr().sqr().hi;
            }
        }

        private double dr2(BriefJet o) {
            final double drap = rap - o.rap;
            double dphi = Math.abs(phi - o.phi);
            if (dphi > Math.PI) dphi = 2.0 * Math.PI - dphi;
            return drap * drap + dphi * dphi;
        }

        @Override
        public double distance(BriefJet o) {
            if (!dd) {
                low = 0.0;
                final double sumet = et + o.et;
                final double rho = dr2(o) * rr2;
                double dij = 0.25 * 0.25 * sumet * sumet * sumet * sumet;
                for (int n = 0; n < rexp; ++n) dij *= rho;
                return dij;
            }
            final DD sumet = etDD.add(o.etDD);
            final DD drap = rapDD.sub(o.rapDD);
            DD dphi = phiDD.sub(o.phiDD).abs();
            if (dphi.gt(DD.PI)) dphi = DD.TWO_PI.sub(dphi);
            final DD rho = drap.sqr().add(dphi.sqr()).mul(rr2DD);
            DD dij = sumet.sqr().sqr().mulPow2(0.0625);
            for (int n = 0; n < rexp; ++n) dij = dij.mul(rho);
            low = dij.lo;
            return dij.hi;
        }

        @Override
        public double beamDistance() {
            if (!dd) {
                low = 0.0;
                return et4;
            }
            final DD e4 = etDD.sqr().sqr();
            low = e4.lo;
            return e4.hi;
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
                if (dd) {
                    cs.pluginRecordIBRecombination(i, energy2ValueDD(cs.jet(i), energyMode).sqr());
                } else {
                    final double diB = energy2Value(cs.jet(i), energyMode);
                    cs.pluginRecordIBRecombination(i, diB * diB);
                }
                nnh.removeJet(i);
            }
            njets--;
        }
    }
}
