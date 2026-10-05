package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenVertex;

import java.util.ArrayList;
import java.util.List;

/**
 * The relatives of a particle or a vertex: PARENTS and CHILDREN (one step
 * through the production or end vertex) and ANCESTORS and DESCENDANTS (all
 * the way), as HepMC3's Relatives on gcc and clang: the recursion goes
 * depth first and visits each vertex once, a vertex being known by its id.
 *
 * <p>The free functions of Relatives.cc, which walk the graph breadth first
 * and return other orders, are in {@link RelativesFunctions}.
 */
public abstract class Relatives {

    public abstract List<GenParticle> apply(GenParticle input);

    public abstract List<GenParticle> apply(GenVertex input);

    /** The incoming particles of the vertex, or of the particle's production vertex. */
    public static final Relatives PARENTS = new Relatives() {
        @Override
        public List<GenParticle> apply(GenParticle input) {
            return apply(input.productionVertex());
        }

        @Override
        public List<GenParticle> apply(GenVertex input) {
            return input == null ? new ArrayList<>() : new ArrayList<>(input.particlesIn());
        }
    };

    /** The outgoing particles of the vertex, or of the particle's end vertex. */
    public static final Relatives CHILDREN = new Relatives() {
        @Override
        public List<GenParticle> apply(GenParticle input) {
            return apply(input.endVertex());
        }

        @Override
        public List<GenParticle> apply(GenVertex input) {
            return input == null ? new ArrayList<>() : new ArrayList<>(input.particlesOut());
        }
    };

    /** Every particle up the graph, depth first. */
    public static final Relatives ANCESTORS = new Recursive(true);

    /** Every particle down the graph, depth first. */
    public static final Relatives DESCENDANTS = new Recursive(false);

    /** Recursive&lt;_parents&gt; and Recursive&lt;_children&gt;. */
    private static final class Recursive extends Relatives {
        private final boolean up;

        Recursive(boolean up) {
            this.up = up;
        }

        @Override
        public List<GenParticle> apply(GenParticle input) {
            return recursive(vertex(input), new ArrayList<>());
        }

        @Override
        public List<GenParticle> apply(GenVertex input) {
            return recursive(input, new ArrayList<>());
        }

        private GenVertex vertex(GenParticle p) {
            return up ? p.productionVertex() : p.endVertex();
        }

        private List<GenParticle> recursive(GenVertex input, List<Integer> checked) {
            final List<GenParticle> results = new ArrayList<>();
            if (input == null) return results;
            for (int v : checked) if (v == input.id()) return results;
            checked.add(input.id());
            for (GenParticle p : up ? input.particlesIn() : input.particlesOut()) {
                results.add(p);
                results.addAll(recursive(vertex(p), checked));
            }
            return results;
        }
    }
}
