package com.sphere.core.fastjet;

import java.util.List;

/**
 * Passive areas by the fastest method each algorithm allows,
 * fastjet::ClusterSequencePassiveArea: Voronoi for kt, ghosts that only
 * cluster with the hard particles for Cambridge and anti-kt, one ghost at a
 * time otherwise.
 */
public class ClusterSequencePassiveArea extends ClusterSequence1GhostPassiveArea {

    private boolean useVoronoiEmptyArea;

    public ClusterSequencePassiveArea(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                      GhostedAreaSpec areaSpec) {
        transferInputJets(particles, jetDef.precision());
        initialiseAndRunPA(jetDef, areaSpec);
    }

    private void initialiseAndRunPA(JetDefinition jetDefIn, GhostedAreaSpec areaSpec) {
        final JetAlgorithm alg = jetDefIn.jetAlgorithm();
        if (alg == JetAlgorithm.KT) {
            final ClusterSequenceVoronoiArea csva = new ClusterSequenceVoronoiArea(jets, jetDefIn,
                new AreaDefinition.VoronoiAreaSpec(1.0));
            transferFromSequence(csva);
            resizeAndZeroAA();
            for (int i = 0; i < history.size(); i++) {
                final int ijetp = history.get(i).jetpIndex;
                if (ijetp != INVALID) {
                    averageArea[i] = csva.area(jets.get(ijetp));
                    averageArea4vector[i] = csva.area4vector(jets.get(ijetp));
                }
            }
            useVoronoiEmptyArea = true;
        } else if (alg == JetAlgorithm.CAMBRIDGE) {
            final JetDefinition tmp = new JetDefinition(jetDefIn);
            tmp.setJetAlgorithm(JetAlgorithm.CAMBRIDGE_FOR_PASSIVE);
            tmp.setExtraParam(Math.sqrt(areaSpec.meanGhostPt()));
            initialiseAndRunAA(tmp, areaSpec, false);
            jetDef = new JetDefinition(jetDefIn);
        } else if (alg == JetAlgorithm.ANTIKT) {
            initialiseAndRunAA(jetDefIn, areaSpec, false);
        } else if (alg == JetAlgorithm.PLUGIN && jetDefIn.plugin().supportsGhostedPassiveAreas()) {
            final double store = jetDefIn.plugin().ghostSeparationScale();
            jetDefIn.plugin().setGhostSeparationScale(Math.sqrt(areaSpec.meanGhostPt()));
            initialiseAndRunAA(jetDefIn, areaSpec, false);
            jetDefIn.plugin().setGhostSeparationScale(store);
        } else {
            initialiseAndRun1GPA(jetDefIn, areaSpec, false);
        }
    }

    @Override
    public double emptyArea(Selector selector) {
        if (useVoronoiEmptyArea) {
            if (hasExplicitGhosts()) {
                return 0.0;
            }
            return emptyAreaFromJets(inclusiveJets(0.0), selector);
        }
        return super.emptyArea(selector);
    }
}
