package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;

import java.util.List;

/**
 * What top taggers share, fastjet::TopTaggerBase: selectors on the top and W
 * candidates, and the W helicity angle.
 */
public abstract class TopTaggerBase implements Transformer {

    /** A tagged top's W and the rest. */
    public interface TopTaggerBaseStructure {
        PseudoJet W();

        PseudoJet nonW();
    }

    protected Selector topSelector = Selector.identity();
    protected Selector wSelector = Selector.identity();
    protected boolean topSelectorSet;
    protected boolean wSelectorSet;

    public void setTopSelector(Selector sel) {
        topSelector = sel;
        topSelectorSet = true;
    }

    public void setWSelector(Selector sel) {
        wSelector = sel;
        wSelectorSet = true;
    }

    public String descriptionOfSelectors() {
        String d = "";
        if (topSelectorSet) d = ", top selector: " + topSelector.description();
        if (wSelectorSet) d += ", W selector: " + wSelector.description();
        return d;
    }

    /** The cosine of the helicity angle: the softer W piece against the top, in the W frame. */
    protected double cosThetaW(PseudoJet res) {
        if (!(res.structure() instanceof TopTaggerBaseStructure ts)) {
            throw new FastJetException("cos_theta_W needs a tagged top");
        }
        final PseudoJet w = ts.W();
        final List<PseudoJet> wPieces = w.pieces();
        if (wPieces.size() != 2) {
            throw new FastJetException("the W of a tagged top must have two pieces");
        }
        final PseudoJet w2 = (wPieces.get(0).perp2() < wPieces.get(1).perp2() ? wPieces.get(0) : wPieces.get(1)).copy();
        final PseudoJet top = res.copy();
        w2.unboost(w);
        top.unboost(w);
        return (w2.px() * top.px() + w2.py() * top.py() + w2.pz() * top.pz()) / Math.sqrt(w2.modp2() * top.modp2());
    }
}
