package com.sphere.core.minuit2;

import java.util.List;

/** Minimization by scanning each parameter in turn and keeping the best point. */
public final class ScanBuilder extends MinimumBuilder {

    @Override
    public FunctionMinimum minimum(MnFcn mfcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy, int maxfcn,
                                   double edmval) {
        final LAVector x = seed.parameters().vec().copy();
        final MnUserParameterState upst = new MnUserParameterState(seed.state(), mfcn.up(), seed.trafo());
        final MnParameterScan scan = new MnParameterScan(mfcn.fcn(), upst.parameters(), seed.fval());
        double amin = scan.fval();
        final int n = seed.trafo().variableParameters();
        final LAVector dirin = new LAVector(n);
        for (int i = 0; i < n; i++) {
            final int ext = seed.trafo().extOfInt(i);
            scan.scan(ext);
            if (scan.fval() < amin) {
                amin = scan.fval();
                x.set(i, seed.trafo().ext2int(ext, scan.parameters().value(ext)));
            }
            dirin.set(i, Math.sqrt(2. * mfcn.up() * seed.error().invHessian().get(i, i)));
        }
        final MinimumParameters mp = new MinimumParameters(x, dirin, amin);
        final MinimumState st = new MinimumState(mp, 0., mfcn.numOfCalls());
        return new FunctionMinimum(seed, List.of(st), mfcn.up());
    }
}
