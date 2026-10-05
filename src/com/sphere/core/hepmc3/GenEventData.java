package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.List;

/**
 * An event flattened for storage, as every format writes it: particles,
 * vertices, weights, the position, the links and the attributes as strings.
 *
 * <p>A link (links1[i], links2[i]) is (particle id, vertex id) when the
 * particle enters the vertex, (vertex id, particle id) when it leaves it;
 * particle ids are positive, vertex ids negative.
 */
public final class GenEventData {

    public int eventNumber;
    public Units.MomentumUnit momentumUnit = Units.MomentumUnit.GEV;
    public Units.LengthUnit lengthUnit = Units.LengthUnit.MM;
    public final List<GenParticleData> particles = new ArrayList<>();
    public final List<GenVertexData> vertices = new ArrayList<>();
    public final List<Double> weights = new ArrayList<>();
    public FourVector eventPos = new FourVector();
    public final IntList links1 = new IntList();
    public final IntList links2 = new IntList();
    public final IntList attributeId = new IntList();
    public final List<String> attributeName = new ArrayList<>();
    public final List<String> attributeString = new ArrayList<>();

    /** A growable int array, so that the links of large events are not boxed. */
    public static final class IntList {
        private int[] a = new int[16];
        private int n;

        public void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        public int get(int i) {
            if (i >= n) throw new IndexOutOfBoundsException(i + " of " + n);
            return a[i];
        }

        public int size() {
            return n;
        }

        public void clear() {
            n = 0;
        }

        public void reserve(int capacity) {
            if (capacity > a.length) a = java.util.Arrays.copyOf(a, capacity);
        }

        public int[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }

    /** Empties every list, keeping the units and the number. */
    public void clearContent() {
        particles.clear();
        vertices.clear();
        weights.clear();
        links1.clear();
        links2.clear();
        attributeId.clear();
        attributeName.clear();
        attributeString.clear();
        eventPos = new FourVector();
    }
}
