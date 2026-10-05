package com.sphere.core.minuit2;

/** Scan: the Simplex seed and the scanning builder. */
public final class ScanMinimizer extends ModularFunctionMinimizer {

    private final SimplexSeedGenerator seedGenerator = new SimplexSeedGenerator();
    private final ScanBuilder builder = new ScanBuilder();

    @Override
    public MinimumSeedGenerator seedGenerator() {
        return seedGenerator;
    }

    @Override
    public ScanBuilder builder() {
        return builder;
    }
}
