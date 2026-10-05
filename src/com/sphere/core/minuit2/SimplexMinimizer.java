package com.sphere.core.minuit2;

/** Simplex: the seed without derivatives and the Nelder-Mead builder. */
public final class SimplexMinimizer extends ModularFunctionMinimizer {

    private final SimplexSeedGenerator seedGenerator = new SimplexSeedGenerator();
    private final SimplexBuilder builder = new SimplexBuilder();

    @Override
    public MinimumSeedGenerator seedGenerator() {
        return seedGenerator;
    }

    @Override
    public SimplexBuilder builder() {
        return builder;
    }
}
