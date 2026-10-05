package com.sphere.core.minuit2;

/** Migrad with Simplex as the fallback (MnMinimize). */
public final class CombinedMinimizer extends ModularFunctionMinimizer {

    private final MnSeedGenerator seedGen = new MnSeedGenerator();
    private final CombinedMinimumBuilder builder = new CombinedMinimumBuilder();

    @Override
    public MinimumSeedGenerator seedGenerator() {
        return seedGen;
    }

    @Override
    public CombinedMinimumBuilder builder() {
        return builder;
    }
}
