package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Masses as G4LogicalVolume::GetMass computes them: the volume's own material
 * over its solid minus its daughters' solids, plus the daughters' masses.
 */
public final class Mass {

    /** One line of the report. */
    public record Line(LogicalVolume lv, double ownVolume, double ownMass, double totalMass, int placements) {
    }

    private Mass() {
    }

    /** Total mass of a logical volume with everything inside it, in internal units. */
    public static double of(Geometry g, LogicalVolume lv) {
        return of(g, lv, new HashMap<>());
    }

    private static double of(Geometry g, LogicalVolume lv, Map<LogicalVolume, Double> cache) {
        final Double c = cache.get(lv);
        if (c != null) {
            return c;
        }
        double mass = 0;
        final Material m = g.materials.material(lv.material);
        if (lv.solid != null && lv.solid.error() == null && m != null) {
            double v = lv.solid.cubicVolume();
            for (PhysicalVolume pv : lv.daughters) {
                if (pv.logical.solid != null && pv.logical.solid.error() == null) {
                    v -= pv.copies() * pv.logical.solid.cubicVolume();
                }
            }
            mass = m.density() * Math.max(0, v);
        }
        for (PhysicalVolume pv : lv.daughters) {
            mass += pv.copies() * of(g, pv.logical, cache);
        }
        cache.put(lv, mass);
        return mass;
    }

    /** Every volume of the tree with its own and total mass. */
    public static List<Line> report(Geometry g) {
        final Map<LogicalVolume, Integer> count = new LinkedHashMap<>();
        for (Geometry.Instance i : g.instances()) {
            count.merge(i.lv(), 1, Integer::sum);
        }
        final Map<LogicalVolume, Double> cache = new HashMap<>();
        final List<Line> out = new ArrayList<>();
        for (Map.Entry<LogicalVolume, Integer> e : count.entrySet()) {
            final LogicalVolume lv = e.getKey();
            final Material m = g.materials.material(lv.material);
            double v = lv.solid == null || lv.solid.error() != null ? 0 : lv.solid.cubicVolume();
            for (PhysicalVolume pv : lv.daughters) {
                if (pv.logical.solid != null && pv.logical.solid.error() == null) {
                    v -= pv.copies() * pv.logical.solid.cubicVolume();
                }
            }
            v = Math.max(0, v);
            out.add(new Line(lv, v, m == null ? 0 : m.density() * v, of(g, lv, cache), e.getValue()));
        }
        return out;
    }
}
