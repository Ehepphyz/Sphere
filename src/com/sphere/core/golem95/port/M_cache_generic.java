package com.sphere.core.golem95.port;

import java.util.Arrays;
import java.util.HashMap;

/**
 * The cache of generic form factors (Golem95's module cache_generic), written for Sphere.
 *
 * <p>Golem95 keeps the integrals its recursion has already computed in a table of two million entries
 * per program, found through a 64-bit hash of the key and recognised by that hash alone: two integrals
 * whose hashes collide would be taken for one another. Here the table is keyed by the whole key
 * (number of legs, extra dimensions, pinched propagators, Feynman parameters), holds only what was
 * computed, and is cleared, like the Fortran's, each time the kinematics changes.
 */
public final class M_cache_generic {

    final GolemCore core;
    private final HashMap<Key, M_form_factor_type.T_form_factor> table = new HashMap<>();
    /** The Fortran's count of entries, which its cache module sets back to 0 when it resets. */
    int cache_generic_count;
    private long hits;
    private long misses;

    M_cache_generic(GolemCore core) {
        this.core = core;
    }

    /** One integral: what fnp_generic was asked. */
    private static final class Key {
        private final int legs;
        private final int dim;
        private final int pinched;
        private final int[] params;
        private final int hash;

        Key(int legs, int dim, int pinched, int count, int[] l) {
            this.legs = legs;
            this.dim = dim;
            this.pinched = pinched;
            this.params = Arrays.copyOf(l, Math.min(count, l.length));
            this.hash = ((legs * 31 + dim) * 31 + pinched) * 31 + Arrays.hashCode(params);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.legs == legs && k.dim == dim && k.pinched == pinched
                && Arrays.equals(k.params, params);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    void allocate_cache_generic() {
        reset_cache_generic();
    }

    void reset_cache_generic() {
        table.clear();
    }

    void clear_cache_generic() {
        reset_cache_generic();
    }

    /** True, and the value in val, when this integral was computed since the kinematics was set. */
    boolean cache_generic_get_value(int leg_count, int dim_nplus, int b_pin, int l_count, int[] l,
                                    M_form_factor_type.T_form_factor val) {
        final M_form_factor_type.T_form_factor v = table.get(new Key(leg_count, dim_nplus, b_pin, l_count, l));
        if (v == null) {
            misses++;
            return false;
        }
        hits++;
        val.assign(v);
        return true;
    }

    void cache_generic_put_value(int leg_count, int dim_nplus, int b_pin, int l_count, int[] l,
                                 M_form_factor_type.T_form_factor val) {
        table.put(new Key(leg_count, dim_nplus, b_pin, l_count, l), val.copy());
    }

    /** How many integrals are held, and how often the recursion found one already computed. */
    public long[] statistics() {
        return new long[]{table.size(), hits, misses};
    }
}
