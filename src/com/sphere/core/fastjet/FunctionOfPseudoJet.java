package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Anything computed from a jet, fastjet::FunctionOfPseudoJet: a jet shape, a
 * transformer returning a new jet, a rescaling of the background.
 */
@FunctionalInterface
public interface FunctionOfPseudoJet<T> extends Function<PseudoJet, T> {

    T result(PseudoJet jet);

    default String description() {
        return "";
    }

    @Override
    default T apply(PseudoJet jet) {
        return result(jet);
    }

    /** The function applied to each jet of a list. */
    default List<T> apply(List<PseudoJet> jets) {
        final List<T> out = new ArrayList<>(jets.size());
        for (PseudoJet j : jets) {
            out.add(result(j));
        }
        return out;
    }

    /** A named function from a lambda. */
    static <T> FunctionOfPseudoJet<T> of(String description, Function<PseudoJet, T> f) {
        return new FunctionOfPseudoJet<T>() {
            @Override
            public T result(PseudoJet jet) {
                return f.apply(jet);
            }

            @Override
            public String description() {
                return description;
            }
        };
    }
}
