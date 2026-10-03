package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;

/** The recombiners Nsubjettiness defines for its axes (ExtraRecombiners.hh). */
public final class ExtraRecombiners {

    private ExtraRecombiners() {
    }

    /**
     * GeneralEtSchemeRecombiner: pt added, rapidity and azimuth averaged
     * with weights pt^delta (delta = 1: the Et scheme; infinity: winner
     * take all).
     */
    public static final class GeneralEtSchemeRecombiner implements Recombiner {
        private final double delta;

        public GeneralEtSchemeRecombiner(double delta) {
            this.delta = delta;
        }

        @Override
        public String description() {
            return "General Et-scheme recombination";
        }

        @Override
        public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
            final double ratio = Math.abs(delta - 1.0) < Math.ulp(1.0)
                ? pb.perp() / pa.perp() : CRMath.pow(pb.perp() / pa.perp(), delta);
            final double weighta = 1.0 / (1.0 + ratio);
            final double weightb = 1.0 / (1.0 + 1.0 / ratio);
            final double perpAb = pa.perp() + pb.perp();
            if (perpAb != 0.0) {
                final double yAb = weighta * pa.rap() + weightb * pb.rap();
                final double phiA = pa.phi();
                double phiB = pb.phi();
                if (phiA - phiB > Math.PI) phiB += 2 * Math.PI;
                if (phiA - phiB < -Math.PI) phiB -= 2 * Math.PI;
                final double phiAb = weighta * phiA + weightb * phiB;
                pab.resetPtYPhiM(perpAb, yAb, phiAb, 0.0);
            } else {
                pab.reset(0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * WinnerTakeAllRecombiner: the summed momentum along the harder
     * particle, "harder" measured by E (pt/E)^alpha (alpha = 1: pt).
     */
    public static final class WinnerTakeAllRecombiner implements Recombiner {
        private final double alpha;

        public WinnerTakeAllRecombiner() {
            this(1.0);
        }

        public WinnerTakeAllRecombiner(double alpha) {
            this.alpha = alpha;
        }

        @Override
        public String description() {
            return "Winner-Take-All recombination";
        }

        @Override
        public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
            final double aPt = pa.perp();
            final double bPt = pb.perp();
            final double aRap = pa.rap();
            final double bRap = pb.rap();
            if (alpha == 1.0) {
                if (aPt >= bPt) {
                    pab.resetPtYPhiM(aPt + bPt, aRap, pa.phi(), 0.0);
                } else if (bPt > aPt) {
                    pab.resetPtYPhiM(aPt + bPt, bRap, pb.phi(), 0.0);
                }
            } else {
                final double aMetric = aPt * CRMath.pow(CRMath.cosh(aRap), 1.0 - alpha);
                final double bMetric = bPt * CRMath.pow(CRMath.cosh(bRap), 1.0 - alpha);
                if (aMetric >= bMetric) {
                    final double newPt = aPt + bPt * CRMath.pow(CRMath.cosh(bRap) / CRMath.cosh(aRap), 1.0 - alpha);
                    pab.resetPtYPhiM(newPt, aRap, pa.phi(), 0.0);
                }
                if (bMetric > aMetric) {
                    final double newPt = bPt + aPt * CRMath.pow(CRMath.cosh(aRap) / CRMath.cosh(bRap), 1.0 - alpha);
                    pab.resetPtYPhiM(newPt, bRap, pb.phi(), 0.0);
                }
            }
        }
    }
}
