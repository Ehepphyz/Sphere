package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The global correlation coefficient of each parameter: how much of it the
 * others can explain, sqrt(1 - 1/(V_ii (V^-1)_ii)).
 */
public final class MnGlobalCorrelationCoeff implements MnPrint.Printable {

    private final List<Double> globalCC = new ArrayList<>();
    private boolean valid;

    /** Invalid. */
    public MnGlobalCorrelationCoeff() {
        valid = false;
    }

    public MnGlobalCorrelationCoeff(LASymMatrix cov) {
        valid = true;
        final MnPrint print = new MnPrint("MnGlobalCorrelationCoeff");
        final LASymMatrix inv = cov.copy();
        final int ifail = MnMatrix.invert(inv);
        if (ifail != 0) {
            print.warn("inversion of matrix fails");
            valid = false;
        } else {
            final int n = cov.nrow();
            for (int i = 0; i < n; i++) {
                final double denom = inv.get(i, i) * cov.get(i, i);
                if (denom < 1. && denom > 0.) globalCC.add(0.);
                else globalCC.add(Math.sqrt(1. - 1. / denom));
            }
        }
    }

    public List<Double> globalCC() {
        return Collections.unmodifiableList(globalCC);
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public void print(COStream os) {
        final int pr = os.precision();
        os.precision(6);
        for (double x : globalCC) {
            os.put('\n');
            os.width(6 + 7);
            os.put(x);
        }
        os.precision(pr);
    }
}
