package com.sphere.core.golem95.port;

/**
 * Form factors of a rank higher than the number of legs (Golem95's module form_factor_higher_ranks).
 *
 * <p>Golem95 ships 16,820 lines of Fortran for them, written by tool/highrank/gen_form_factors.py:
 * 1,312 functions, each the same formula with other numbers. That formula is here, once:
 *
 * <pre>
 *   F^{N,r}_g(l1..lk) = (-1/2)^g (-1)^r  I_N^{n+2g}(l1..lk),   k = r - 2g,
 * </pre>
 *
 * A for g = 0, B for g = 1, C for g = 2..., where I is the generic integral with Feynman parameters
 * l1..lk in the numerator (fnp_generic), computed in n + 2g dimensions. As the script writes it, an
 * even rank with g = 0 is the integral itself, and every other is the real coefficient times it.
 */
public final class M_form_factor_higher_ranks {

    final GolemCore core;

    M_form_factor_higher_ranks(GolemCore core) {
        this.core = core;
    }

    /**
     * The form factor with g pairs of metric tensors, of an N-point integral of rank r.
     *
     * @param g    0 for A, 1 for B, 2 for C, ...
     * @param n    the number of legs N
     * @param r    the rank r
     * @param bPin the propagators pinched, as bits
     * @param l    the Feynman parameters in the numerator, r - 2g of them
     */
    public M_form_factor_type.T_form_factor ff(int g, int n, int r, int bPin, int[] l) {
        final int dim = 2 * g;
        final M_form_factor_type.T_form_factor v =
            core.m_generic_function_np.fnp_generic(n, dim, bPin, l.length, l, null);
        if (r % 2 == 0 && dim == 0) {
            return v;
        }
        final double coefficient = Math.pow(-0.5, g) * (r % 2 == 0 ? 1.0 : -1.0);
        return core.m_form_factor_type.mul_real_ff(coefficient, v);
    }

    /** The same with the pinched propagators as a set of labels. */
    public M_form_factor_type.T_form_factor ffSet(int g, int n, int r, int[] set, int[] l) {
        return ff(g, n, r, core.m_array.packb(set), l);
    }
}
