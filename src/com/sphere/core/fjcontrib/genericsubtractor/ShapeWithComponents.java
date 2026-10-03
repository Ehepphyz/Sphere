package com.sphere.core.fjcontrib.genericsubtractor;

import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

/**
 * A shape made of components that are subtracted one by one before being
 * combined (a ratio, typically), ShapeWithComponents.
 */
public abstract class ShapeWithComponents implements FunctionOfPseudoJet<Double> {

    @Override
    public Double result(PseudoJet jet) {
        return resultFromComponents(components(jet));
    }

    @Override
    public abstract String description();

    public abstract int nComponents();

    public abstract double[] components(PseudoJet jet);

    public double component(int i, PseudoJet jet) {
        if (i >= nComponents()) throw new IllegalArgumentException("component " + i + " of " + nComponents());
        return components(jet)[i];
    }

    public abstract double resultFromComponents(double[] components);

    /** The shape of one component. */
    public FunctionOfPseudoJet<Double> componentShape(int index) {
        return FunctionOfPseudoJet.of(description() + " [component " + index + "]", jet -> component(index, jet));
    }
}
