package com.sphere.core.fjcontrib.jetswithoutjets;

import com.sphere.core.fastjet.PseudoJet;

import java.util.List;
import java.util.function.Function;

/** A function of a set of particles, fastjet::jwj::MyFunctionOfVectorOfPseudoJets. */
@FunctionalInterface
public interface FunctionOfVectorOfPseudoJets<T> {

    T result(List<PseudoJet> pjs);

    default String description() {
        return "";
    }

    default T apply(List<PseudoJet> pjs) {
        return result(pjs);
    }

    /** A function with a description, for measurements written as lambdas. */
    static <T> FunctionOfVectorOfPseudoJets<T> of(String description, Function<List<PseudoJet>, T> f) {
        return new FunctionOfVectorOfPseudoJets<>() {
            @Override public T result(List<PseudoJet> pjs) { return f.apply(pjs); }
            @Override public String description() { return description; }
        };
    }
}
