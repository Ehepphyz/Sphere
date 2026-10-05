package com.sphere.core.minuit2;

/**
 * Makes an error matrix positive-definite: a negative diagonal shifted up,
 * then, looking at the eigenvalues of the correlation matrix, the diagonal
 * enlarged until the smallest is safely positive.
 */
public final class MnPosDef {

    public MinimumState apply(MinimumState st, MnMachinePrecision prec) {
        final MinimumError err = apply(st.error(), prec);
        return new MinimumState(st.parameters(), err, st.gradient(), st.edm(), st.nfcn());
    }

    public MinimumError apply(MinimumError e, MnMachinePrecision prec) {
        final MnPrint print = new MnPrint("MnPosDef");
        final LASymMatrix err = e.invHessian().copy();
        if (err.size() == 1 && err.get(0, 0) < prec.eps()) {
            err.set(0, 0, 1.);
            return new MinimumError(err, MinimumError.Status.MnMadePosDef);
        }
        if (err.size() == 1 && err.get(0, 0) > prec.eps()) {
            return e;
        }
        final double epspdf = Cxx.max(1.e-12, prec.eps2());
        double dgmin = err.get(0, 0);
        for (int i = 0; i < err.nrow(); i++) {
            if (err.get(i, i) <= 0) print.warn("non-positive diagonal element in covariance matrix[", i, "] =", err.get(i, i));
            if (err.get(i, i) < dgmin) dgmin = err.get(i, i);
        }
        double dg = 0.;
        if (dgmin <= 0) {
            dg = 0.5 + epspdf - dgmin;
            print.warn("Added to diagonal of Error matrix a value", dg);
        }
        final LAVector s = new LAVector(err.nrow());
        final LASymMatrix p = new LASymMatrix(err.nrow());
        for (int i = 0; i < err.nrow(); i++) {
            err.set(i, i, err.get(i, i) + dg);
            if (err.get(i, i) < 0.) err.set(i, i, 1.);
            s.set(i, 1. / Math.sqrt(err.get(i, i)));
            for (int j = 0; j <= i; j++) {
                p.set(i, j, err.get(i, j) * s.get(i) * s.get(j));
            }
        }
        final LAVector eval = MnMatrix.eigenvalues(p);
        final double pmin = eval.get(0);
        double pmax = eval.get(eval.size() - 1);
        pmax = Cxx.max(Math.abs(pmax), 1.);
        if (pmin > epspdf * pmax) return new MinimumError(err, e.dcovar());
        final double pAdd = 0.001 * pmax - pmin;
        for (int i = 0; i < err.nrow(); i++) err.set(i, i, err.get(i, i) * (1. + pAdd));
        if (print.shows(MnPrint.Verbosity.DEBUG)) {
            print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                os.put("Eigenvalues:");
                for (int i = 0; i < err.nrow(); ++i) os.put("\n  ").put(eval.get(i));
            });
        }
        print.warn("Matrix forced pos-def by adding to diagonal", pAdd);
        return new MinimumError(err, MinimumError.Status.MnMadePosDef);
    }
}
