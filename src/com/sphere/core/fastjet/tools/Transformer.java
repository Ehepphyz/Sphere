package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

/** A tool that turns a jet into another jet, fastjet::Transformer. */
public interface Transformer extends FunctionOfPseudoJet<PseudoJet> {

    @Override
    PseudoJet result(PseudoJet original);

    @Override
    String description();
}
