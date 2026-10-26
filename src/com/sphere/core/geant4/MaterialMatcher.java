package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Recognises, among a document's own materials, those that are a NIST
 * material under another name: same elements in the same proportions by mass,
 * same density. A ROOT geometry defines every material it uses ("Lead",
 * "SILICON", "Air"), a Geant4 application would rather name G4_Pb, G4_Si and
 * G4_AIR, whose ionisation potentials and density effect parameters Geant4
 * knows precisely; this is what finds them.
 */
public final class MaterialMatcher {

    private MaterialMatcher() {
    }

    /** One recognition: the document's material, the NIST one, and how far apart they are. */
    public record Match(String material, String nist, double densityRatio, double compositionDistance) {
        public String describe() {
            return String.format(Locale.ROOT, "%s is %s (density within %.2g %%, composition within %.2g %% by mass)",
                material, nist, Math.abs(densityRatio - 1) * 100, compositionDistance * 100);
        }
    }

    /** Density within this fraction, mass fractions summing to within this distance. */
    private static final double DENSITY_TOLERANCE = 0.01;
    private static final double COMPOSITION_TOLERANCE = 0.02;

    private static Map<String, Map<Integer, Double>> nistCompositions;

    private static synchronized Map<String, Map<Integer, Double>> nist() {
        if (nistCompositions == null) {
            final Map<String, Map<Integer, Double>> out = new HashMap<>();
            for (Material m : NistDB.get().materials()) {
                out.put(m.name(), composition(m));
            }
            nistCompositions = out;
        }
        return nistCompositions;
    }

    /** Mass fraction per atomic number. */
    static Map<Integer, Double> composition(Material m) {
        final Map<Integer, Double> c = new TreeMap<>();
        final List<Element> els = m.elements();
        final double[] w = m.massFractions();
        for (int i = 0; i < els.size(); i++) {
            c.merge(els.get(i).z, w[i], Double::sum);
        }
        return c;
    }

    static double distance(Map<Integer, Double> a, Map<Integer, Double> b) {
        if (!a.keySet().equals(b.keySet())) {
            return Double.POSITIVE_INFINITY;
        }
        double d = 0;
        for (Map.Entry<Integer, Double> e : a.entrySet()) {
            d += Math.abs(e.getValue() - b.get(e.getKey()));
        }
        return d;
    }

    /** The document's materials that are NIST ones, the closest NIST material for each. */
    public static List<Match> matches(Geometry g) {
        final List<Match> out = new ArrayList<>();
        final NistDB db = NistDB.get();
        for (Material m : g.materials().defined()) {
            if (m.elements().isEmpty() || m.density() <= 0) {
                continue;
            }
            final Map<Integer, Double> mine = composition(m);
            Match best = null;
            double bestScore = Double.MAX_VALUE;
            for (Map.Entry<String, Map<Integer, Double>> e : nist().entrySet()) {
                final double dist = distance(mine, e.getValue());
                if (dist > COMPOSITION_TOLERANCE) {
                    continue;
                }
                final double ratio = m.density() / db.material(e.getKey()).density();
                if (Math.abs(ratio - 1) > DENSITY_TOLERANCE) {
                    continue;
                }
                final double score = Math.abs(ratio - 1) + dist;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Match(m.name(), e.getKey(), ratio, dist);
                }
            }
            if (best != null) {
                out.add(best);
            }
        }
        return out;
    }

    /**
     * Gives the volumes made of each matched material its NIST equivalent,
     * and drops the definitions nothing else uses. Returns the volumes changed.
     */
    public static int apply(Geometry g, List<Match> matches) {
        int changed = 0;
        for (Match m : matches) {
            for (LogicalVolume lv : g.volumes()) {
                if (m.material().equals(lv.material)) {
                    lv.material = m.nist();
                    changed++;
                }
            }
            boolean usedElsewhere = false;
            for (Material other : g.materials().defined()) {
                for (Material.Part p : other.parts()) {
                    usedElsewhere |= p.ref().equals(m.material());
                }
            }
            if (!usedElsewhere) {
                g.materials().remove(m.material());
            }
        }
        return changed;
    }
}
