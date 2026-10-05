package com.sphere.core.minuit2;

/** Migrad: the seed of MnSeedGenerator and the variable metric builder (Davidon, or BFGS). */
public final class VariableMetricMinimizer extends ModularFunctionMinimizer {

    private final MnSeedGenerator seedGen = new MnSeedGenerator();
    private final VariableMetricBuilder builder;

    public VariableMetricMinimizer() {
        builder = new VariableMetricBuilder();
    }

    /** With the BFGS update of the inverse Hessian. */
    public static VariableMetricMinimizer bfgs() {
        return new VariableMetricMinimizer(VariableMetricBuilder.ErrorUpdatorType.kBFGS);
    }

    public VariableMetricMinimizer(VariableMetricBuilder.ErrorUpdatorType type) {
        builder = new VariableMetricBuilder(type);
    }

    @Override
    public MinimumSeedGenerator seedGenerator() {
        return seedGen;
    }

    @Override
    public VariableMetricBuilder builder() {
        return builder;
    }
}
