package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.GenParticle;

import java.util.ArrayList;
import java.util.List;

/**
 * A test on a particle, combined with and, or and not into larger ones:
 * HepMC3's Filter (std::function&lt;bool(ConstGenParticlePtr)&gt;).
 */
@FunctionalInterface
public interface Filter {

    boolean test(GenParticle p);

    /** operator&amp;&amp;. */
    default Filter and(Filter rhs) {
        final Filter lhs = this;
        return p -> lhs.test(p) && rhs.test(p);
    }

    /** operator||. */
    default Filter or(Filter rhs) {
        final Filter lhs = this;
        return p -> lhs.test(p) || rhs.test(p);
    }

    /** operator!. */
    default Filter not() {
        final Filter rhs = this;
        return p -> !rhs.test(p);
    }

    static Filter not(Filter rhs) {
        return rhs.not();
    }

    /** The filter that accepts every particle. */
    Filter ACCEPT_ALL = p -> true;

    /** The particles the filter accepts, in order. */
    static List<GenParticle> applyFilter(Filter filter, List<GenParticle> particles) {
        final List<GenParticle> result = new ArrayList<>();
        for (GenParticle p : particles) if (filter.test(p)) result.add(p);
        return result;
    }
}
