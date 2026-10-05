package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenVertex;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The free functions of HepMC3's Relatives.cc: children, grandchildren,
 * parents, grandparents, descendants and ancestors, of particles and of
 * vertices. Descendants and ancestors are gathered generation by generation,
 * each object once, in the order first met.
 */
public final class RelativesFunctions {

    private RelativesFunctions() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static List<GenParticle> childrenParticles(GenVertex o) {
        return o != null ? new ArrayList<>(o.particlesOut()) : new ArrayList<>();
    }

    public static List<GenVertex> childrenVertices(GenParticle o) {
        final List<GenVertex> result = new ArrayList<>();
        if (o.endVertex() != null) result.add(o.endVertex());
        return result;
    }

    public static List<GenParticle> grandchildrenParticles(GenParticle o) {
        if (o != null && o.endVertex() != null) return new ArrayList<>(o.endVertex().particlesOut());
        return new ArrayList<>();
    }

    public static List<GenVertex> grandchildrenVertices(GenVertex o) {
        final List<GenVertex> result = new ArrayList<>();
        if (o != null) for (GenParticle p : o.particlesOut()) if (p.endVertex() != null) result.add(p.endVertex());
        return result;
    }

    public static List<GenParticle> parentParticles(GenVertex o) {
        return o != null ? new ArrayList<>(o.particlesIn()) : new ArrayList<>();
    }

    public static List<GenVertex> parentVertices(GenParticle o) {
        final List<GenVertex> result = new ArrayList<>();
        if (o.productionVertex() != null) result.add(o.productionVertex());
        return result;
    }

    public static List<GenParticle> grandparentParticles(GenParticle o) {
        if (o != null && o.productionVertex() != null) return new ArrayList<>(o.productionVertex().particlesIn());
        return new ArrayList<>();
    }

    public static List<GenVertex> grandparentVertices(GenVertex o) {
        final List<GenVertex> result = new ArrayList<>();
        if (o != null) for (GenParticle p : o.particlesIn()) if (p.productionVertex() != null) result.add(p.productionVertex());
        return result;
    }

    /** Generation after generation of the same type, each once. */
    private static <O> List<O> ofSameType(O obj, Function<O, List<O>> next) {
        final List<O> result = new ArrayList<>(next.apply(obj));
        int gc = 0;
        final List<O> temp = new ArrayList<>();
        while (true) {
            temp.clear();
            for (; gc < result.size(); gc++) temp.addAll(next.apply(result.get(gc)));
            for (O p2 : temp) if (!containsSame(result, p2)) result.add(p2);
            if (gc >= result.size()) break;
        }
        return result;
    }

    private static <O> boolean containsSame(List<O> l, O o) {
        for (O x : l) if (x == o) return true;
        return false;
    }

    public static List<GenParticle> descendantParticles(GenParticle obj) {
        return ofSameType(obj, RelativesFunctions::grandchildrenParticles);
    }

    public static List<GenVertex> descendantVertices(GenVertex obj) {
        return ofSameType(obj, RelativesFunctions::grandchildrenVertices);
    }

    public static List<GenParticle> ancestorParticles(GenParticle obj) {
        return ofSameType(obj, RelativesFunctions::grandparentParticles);
    }

    public static List<GenVertex> ancestorVertices(GenVertex obj) {
        return ofSameType(obj, RelativesFunctions::grandparentVertices);
    }

    /** The children of a vertex, then the descendants of each, each once. */
    public static List<GenParticle> descendantParticles(GenVertex obj) {
        final List<GenParticle> local = childrenParticles(obj);
        final List<GenParticle> result = new ArrayList<>(local);
        for (GenParticle c : local) for (GenParticle d : descendantParticles(c)) if (!containsSame(result, d)) result.add(d);
        return result;
    }

    public static List<GenVertex> descendantVertices(GenParticle obj) {
        final List<GenVertex> local = childrenVertices(obj);
        final List<GenVertex> result = new ArrayList<>(local);
        for (GenVertex c : local) for (GenVertex d : descendantVertices(c)) if (!containsSame(result, d)) result.add(d);
        return result;
    }

    public static List<GenParticle> ancestorParticles(GenVertex obj) {
        final List<GenParticle> local = parentParticles(obj);
        final List<GenParticle> result = new ArrayList<>(local);
        for (GenParticle c : local) for (GenParticle d : ancestorParticles(c)) if (!containsSame(result, d)) result.add(d);
        return result;
    }

    public static List<GenVertex> ancestorVertices(GenParticle obj) {
        final List<GenVertex> local = parentVertices(obj);
        final List<GenVertex> result = new ArrayList<>(local);
        for (GenVertex c : local) for (GenVertex d : ancestorVertices(c)) if (!containsSame(result, d)) result.add(d);
        return result;
    }
}
