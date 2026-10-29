package com.sphere.core.clhep.genfun;

import java.util.HashMap;
import java.util.Map;

/**
 * CLHEP's Genfun::ClebschGordanCoefficientSet (ClebschGordanCoefficientSet.hh, .cc): the Clebsch-Gordan
 * coefficients &lt;l1 m1 l2 m2 | L M&gt; by Racah's formula, each computed once and kept.
 */
public class ClebschGordanCoefficientSet {

    private record Key(int l1, int l2, int m1, int m2, int L) {
    }

    private final Map<Key, Double> coeff = new HashMap<>();

    /** &lt;l1 m1 l2 m2 | L M&gt;, 0 unless m1 + m2 = M. */
    public double get(int l1, int l2, int m1, int m2, int L, int M) {
        if ((m1 + m2) != M) return 0;
        final Key key = new Key(l1, l2, m1, m2, L);
        final Double i = coeff.get(key);
        if (i == null) {
            final double c = calcCoefficient(l1, l2, L, m1, m2, M);
            coeff.put(key, c);
            return c;
        }
        return i;
    }

    private static double factorial(int N) {
        double retVal = 1.0;
        for (int i = 2; i <= N; i++) retVal *= i;
        return retVal;
    }

    private static double calcCoefficient(int l1, int l2, int L, int xm1, int xm2, int M) {
        if (xm1 + xm2 != M) return 0;
        final double F1 = Math.sqrt((2 * L + 1) * factorial(L + l1 - l2) * factorial(L - l1 + l2) * factorial(l1 + l2 - L)
            / factorial(l1 + l2 + L + 1));
        final double F2 = Math.sqrt(factorial(L + M) * factorial(L - M) * factorial(l1 - xm1) * factorial(l1 + xm1)
            * factorial(l2 - xm2) * factorial(l2 + xm2));
        double F3 = 0.0;
        int max = 0;
        max = Math.max(max, l2 + xm2);
        max = Math.max(max, l1 - xm1);
        max = Math.max(max, l1 + l2 - L);
        for (int k = 0; k <= max; k++) {
            double term = factorial(k);
            boolean skipTerm = false;
            {
                final int T = l1 + l2 - L - k;
                if (T >= 0) term *= factorial(T); else skipTerm = true;
            }
            if (!skipTerm) {
                final int T = l1 - xm1 - k;
                if (T >= 0) term *= factorial(T); else skipTerm = true;
            }
            if (!skipTerm) {
                final int T = l2 + xm2 - k;
                if (T >= 0) term *= factorial(T); else skipTerm = true;
            }
            if (!skipTerm) {
                final int T = L - l2 + xm1 + k;
                if (T >= 0) term *= factorial(T); else skipTerm = true;
            }
            if (!skipTerm) {
                final int T = L - l1 - xm2 + k;
                if (T >= 0) term *= factorial(T); else skipTerm = true;
            }
            if (!skipTerm) F3 += ((k % 2) != 0 ? -1 : 1) / term;
        }
        return F1 * F2 * F3;
    }
}
