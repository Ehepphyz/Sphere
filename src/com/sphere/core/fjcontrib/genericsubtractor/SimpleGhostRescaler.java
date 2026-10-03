package com.sphere.core.fjcontrib.genericsubtractor;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;

import java.util.ArrayList;
import java.util.List;

/**
 * The jet with its explicit ghosts given pt pt_scale (relative to their
 * mean ghost_scale) and a mass such that mt - pt = mdelta_scale,
 * SimpleGhostRescaler. The result is a composite of the rescaled
 * constituents.
 */
public class SimpleGhostRescaler implements FunctionOfPseudoJet<PseudoJet> {

    private final double ptScale;
    private final double mdeltaScale;
    private final double ghostScale;

    public SimpleGhostRescaler(double ptScale, double mdeltaScale, double ghostScale) {
        this.ptScale = ptScale;
        this.mdeltaScale = mdeltaScale;
        this.ghostScale = ghostScale;
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasAssociatedClusterSequence()) {
            if (!jet.hasPieces()) {
                throw new FastJetException("Ghost rescaling can only be performed on jets with an associated ClusterSequence or composite jets (with pieces associated with a Clustersequence)");
            }
            final List<PseudoJet> pieces = new ArrayList<>();
            for (PseudoJet p : jet.pieces()) pieces.add(result(p));
            return PseudoJet.join(pieces);
        }
        if (!jet.hasArea()) throw new FastJetException("Ghost rescaling can only be applied on jets with an area");
        if (!jet.validatedCsab().hasExplicitGhosts()) throw new FastJetException("Ghost rescaling can only be applied on jets with explicit ghosts");
        final Selector ghostSelector = Selector.isPureGhost();
        final List<PseudoJet> rescaled = new ArrayList<>(jet.constituents());
        for (int i = 0; i < rescaled.size(); i++) {
            final PseudoJet c = rescaled.get(i);
            if (ghostSelector.pass(c)) {
                final double pt = ptScale * (c.perp() / ghostScale);
                final double m = Math.sqrt(mdeltaScale * (mdeltaScale + 2 * pt));
                rescaled.set(i, PseudoJet.ptYPhiM(pt, c.rap(), c.phi(), m));
            }
        }
        return PseudoJet.join(rescaled);
    }

    @Override
    public String description() {
        return "ghosts rescaled to pt scale " + ptScale;
    }
}
