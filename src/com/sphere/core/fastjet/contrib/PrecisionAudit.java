package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.util.List;
import java.util.Locale;

/**
 * What double precision costs on an event: the clustering is made in
 * {@link Precision#DOUBLE} (FastJet's arithmetic) and in
 * {@link Precision#DD} (106 bits), and the two histories and jets compared.
 *
 * Besides the steps where the two clusterings take different decisions, the
 * audit counts the steps whose distance is within a few double ulps of the
 * next one in the 106-bit history: their order is beyond what double
 * precision can resolve, and a double clustering takes it from rounding.
 */
public final class PrecisionAudit {

    private PrecisionAudit() {
    }

    public record Report(int steps, int divergentSteps, int firstDivergentStep, int nearDegenerateSteps,
                         int jetsDouble, int jetsDD, double maxRelPtDiff, double maxRapDiff, double maxPhiDiff) {
        public boolean identicalDecisions() { return divergentSteps == 0; }

        @Override
        public String toString() {
            return "history steps: " + steps + ", decisions differing between double and double-double: "
                + divergentSteps + (divergentSteps > 0 ? " (first at step " + firstDivergentStep + ")" : "")
                + "\nsteps closer to the next than double can resolve (<4 ulp): " + nearDegenerateSteps
                + "\njets: " + jetsDouble + " (double) / " + jetsDD + " (double-double)"
                + String.format(Locale.ROOT, "%nlargest differences: |dpt|/pt = %.3g, |dy| = %.3g, |dphi| = %.3g",
                    maxRelPtDiff, maxRapDiff, maxPhiDiff);
        }
    }

    public static Report audit(List<PseudoJet> event, JetDefinition def, double ptmin) {
        final ClusterSequence csD = new ClusterSequence(event, def.withPrecision(Precision.DOUBLE));
        final ClusterSequence csQ = new ClusterSequence(event, def.withPrecision(Precision.DD));
        final List<ClusterSequence.HistoryElement> hD = csD.history();
        final List<ClusterSequence.HistoryElement> hQ = csQ.history();
        final int n = Math.min(hD.size(), hQ.size());
        int divergent = 0;
        int first = -1;
        for (int i = csD.nParticles(); i < n; i++) {
            final ClusterSequence.HistoryElement a = hD.get(i);
            final ClusterSequence.HistoryElement b = hQ.get(i);
            final boolean same = (a.parent1() == b.parent1() && a.parent2() == b.parent2())
                || (a.parent1() == b.parent2() && a.parent2() == b.parent1());
            if (!same) {
                divergent++;
                if (first < 0) first = i;
            }
        }
        divergent += Math.abs(hD.size() - hQ.size());
        int near = 0;
        for (int i = csQ.nParticles(); i + 1 < hQ.size(); i++) {
            final DD d0 = hQ.get(i).dijDD();
            final DD d1 = hQ.get(i + 1).dijDD();
            final double gap = Math.abs(d1.sub(d0).doubleValue());
            if (d0.doubleValue() != 0 && gap < 4 * Math.ulp(Math.abs(d0.doubleValue()))) near++;
        }
        final List<PseudoJet> jD = PseudoJet.sortedByPt(csD.inclusiveJets(ptmin));
        final List<PseudoJet> jQ = PseudoJet.sortedByPt(csQ.inclusiveJets(ptmin));
        double dpt = 0;
        double dy = 0;
        double dphi = 0;
        for (int i = 0; i < Math.min(jD.size(), jQ.size()); i++) {
            final PseudoJet a = jD.get(i);
            final PseudoJet b = jQ.get(i);
            dpt = Math.max(dpt, Math.abs(a.pt() - b.pt()) / Math.max(b.pt(), 1e-300));
            dy = Math.max(dy, Math.abs(a.rap() - b.rap()));
            double p = Math.abs(a.phi() - b.phi());
            if (p > Math.PI) p = 2 * Math.PI - p;
            dphi = Math.max(dphi, p);
        }
        return new Report(hQ.size() - csQ.nParticles(), divergent, first, near, jD.size(), jQ.size(), dpt, dy, dphi);
    }
}
