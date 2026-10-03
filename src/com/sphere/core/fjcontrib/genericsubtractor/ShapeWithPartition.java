package com.sphere.core.fjcontrib.genericsubtractor;

import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

/**
 * A shape computed from a partition of the jet (subjets, axes) that should
 * be found once, on the unrescaled jet, and kept while the ghosts are
 * rescaled, ShapeWithPartition.
 */
public abstract class ShapeWithPartition implements FunctionOfPseudoJet<Double> {

    @Override
    public abstract String description();

    /** The partition, a composite jet whose pieces are the parts. */
    public abstract PseudoJet partition(PseudoJet jet);

    /** The shape of a partition. */
    public abstract double resultFromPartition(PseudoJet partit);

    @Override
    public Double result(PseudoJet jet) {
        return resultFromPartition(partition(jet));
    }
}
