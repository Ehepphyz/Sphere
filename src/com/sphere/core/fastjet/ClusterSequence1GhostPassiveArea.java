package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * Passive areas measured one ghost at a time, fastjet::ClusterSequence1GhostPassiveArea:
 * each ghost is clustered alone with the event, and the jet it joins gains
 * its area. Slow (one clustering per ghost) but defined for any algorithm.
 */
public class ClusterSequence1GhostPassiveArea extends ClusterSequenceActiveArea {

    protected ClusterSequence1GhostPassiveArea() {
    }

    public ClusterSequence1GhostPassiveArea(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                            GhostedAreaSpec areaSpec) {
        transferInputJets(particles, jetDef.precision());
        initialiseAndRun1GPA(jetDef, areaSpec, false);
    }

    protected final void initialiseAndRun1GPA(JetDefinition jetDefIn, GhostedAreaSpec areaSpec, boolean writeout) {
        if (initialiseAA(jetDefIn, areaSpec, writeout)) {
            run1GPA(areaSpec);
            postprocessAA(areaSpec);
        }
    }

    private void run1GPA(GhostedAreaSpec areaSpec) {
        final List<PseudoJet> inputJets = new ArrayList<>(jets.size());
        for (PseudoJet j : jets) {
            inputJets.add(j.copy());
        }
        int[] uniqueTree = null;
        final double[] lclAverageArea2 = new double[averageArea.length];
        final double[] lastAverageArea = new double[averageArea.length];
        for (int irepeat = 0; irepeat < areaSpec.repeat(); irepeat++) {
            final List<PseudoJet> allGhosts = new ArrayList<>();
            areaSpec.addGhosts(allGhosts);
            for (int ig = 0; ig < allGhosts.size(); ig++) {
                final List<PseudoJet> someGhosts = List.of(allGhosts.get(ig));
                final ClusterSequenceActiveAreaExplicitGhosts cs = new ClusterSequenceActiveAreaExplicitGhosts(
                    inputJets, jetDef(), someGhosts, areaSpec.actualGhostArea());
                if (irepeat == 0 && ig == 0) {
                    transferGhostFreeHistory(cs);
                    uniqueTree = uniqueHistoryOrder();
                }
                transferAreas(uniqueTree, cs);
            }
            for (int i = 0; i < averageArea.length; i++) {
                final double d = averageArea[i] - lastAverageArea[i];
                lclAverageArea2[i] += d * d;
                lastAverageArea[i] = averageArea[i];
            }
        }
        System.arraycopy(lclAverageArea2, 0, averageArea2, 0, averageArea2.length);
    }

    @Override
    public double nEmptyJets(Selector selector) {
        final double R = jetDef().R();
        return emptyArea(selector) / (0.55 * Math.PI * R * R);
    }
}
