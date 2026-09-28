package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

/** Boosts from the rest frame of a jet to the lab, fastjet::Boost; {@link #unboost} the reverse. */
public final class Boost implements FunctionOfPseudoJet<PseudoJet> {

    private final PseudoJet jetRest;
    private final boolean inverse;

    public Boost(PseudoJet jetRest) {
        this(jetRest, false);
    }

    private Boost(PseudoJet jetRest, boolean inverse) {
        this.jetRest = jetRest.copy();
        this.inverse = inverse;
    }

    /** fastjet::Unboost: from the lab to the rest frame of the jet. */
    public static Boost unboost(PseudoJet jetRest) {
        return new Boost(jetRest, true);
    }

    @Override
    public PseudoJet result(PseudoJet original) {
        final PseudoJet res = original.copy();
        return inverse ? res.unboost(jetRest) : res.boost(jetRest);
    }

    @Override
    public String description() {
        return inverse ? "Unboost" : "Boost";
    }
}
