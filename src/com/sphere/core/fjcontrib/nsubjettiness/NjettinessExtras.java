package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.PseudoJet;

import java.util.List;

/**
 * The N-jettiness information a clustering by {@link NjettinessPlugin}
 * carries, NjettinessExtras: the TauComponents, and which jet of the
 * clustering is which region.
 */
public class NjettinessExtras extends TauComponents {

    private final int[] clusterHistIndices;

    public NjettinessExtras(TauComponents tauComponents, List<Integer> clusterHistIndices) {
        super(tauComponents);
        this.clusterHistIndices = clusterHistIndices.stream().mapToInt(Integer::intValue).toArray();
    }

    /** tau of the whole event. */
    public double tau(PseudoJet jet) {
        return tau;
    }

    /** tau of one jet's region (NaN for a jet that is not one). */
    public double tauPiece(PseudoJet jet) {
        final int l = labelOf(jet);
        return l == -1 ? Double.NaN : jetPieces[l];
    }

    /** The axis of a jet's region. */
    public PseudoJet axis(PseudoJet jet) {
        return axes.get(labelOf(jet));
    }

    public boolean hasNjettinessExtras(PseudoJet jet) {
        return labelOf(jet) >= 0;
    }

    private int labelOf(PseudoJet jet) {
        for (int i = 0; i < jets.size(); i++) {
            if (clusterHistIndices[i] == jet.clusterHistIndex()) return i;
        }
        return -1;
    }

    // the older names
    public double totalTau() { return tau; }
    public double[] subTaus() { return jetPieces.clone(); }
    public double totalTau(PseudoJet jet) { return tau; }
    public double subTau(PseudoJet jet) { return tauPiece(jet); }
    public double beamTau() { return beamPiece; }

    /** The extras of the clustering a jet came from, or null. */
    public static NjettinessExtras of(PseudoJet jet) {
        final ClusterSequence cs = jet.associatedClusterSequence();
        return cs == null ? null : of(cs);
    }

    public static NjettinessExtras of(ClusterSequence cs) {
        return cs.extras() instanceof NjettinessExtras e ? e : null;
    }
}
