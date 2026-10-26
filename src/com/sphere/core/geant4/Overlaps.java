package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * G4PVPlacement::CheckOverlaps for the whole document: points sampled on each
 * daughter's surface must lie inside its mother, and outside every sister.
 * Every logical volume is checked once, whatever the number of its
 * placements, since an overlap is a property of the daughters inside it.
 */
public final class Overlaps {

    public enum Kind { PROTRUSION, OVERLAP }

    /** One fault: the volumes involved, how deep, and where (in the mother's frame and in the world). */
    public record Fault(Kind kind, LogicalVolume mother, PhysicalVolume daughter, int daughterCopy,
                        PhysicalVolume sister, int sisterCopy, double depth, Vec3 localPoint, Vec3 worldPoint,
                        int hits) {
        public String describe() {
            final String d = daughter.name() + (daughter.isReplica() ? "[" + daughterCopy + "]" : "");
            if (kind == Kind.PROTRUSION) {
                return d + " protrudes from its mother " + mother.name() + " by "
                    + Units.best(depth, Units.Category.LENGTH) + " (" + hits + " points)";
            }
            final String s = sister.name() + (sister.isReplica() ? "[" + sisterCopy + "]" : "");
            return d + " overlaps " + s + " in " + mother.name() + " by "
                + Units.best(depth, Units.Category.LENGTH) + " (" + hits + " points)";
        }
    }

    private Overlaps() {
    }

    /**
     * @param resolution points per daughter surface (Geant4's default is 1000)
     * @param tolerance  depths up to this are accepted (Geant4's default is 0)
     */
    public static List<Fault> check(Geometry g, int resolution, double tolerance, IntConsumer progress) {
        final List<LogicalVolume> mothers = new ArrayList<>();
        final Set<LogicalVolume> seen = new HashSet<>();
        collect(g.world, mothers, seen);
        final ConcurrentLinkedQueue<Fault> out = new ConcurrentLinkedQueue<>();
        final AtomicInteger done = new AtomicInteger();
        // World placement of each mother, to report a world point (first placement found)
        mothers.parallelStream().forEach(m -> {
            checkMother(g, m, resolution, tolerance, out);
            if (progress != null) {
                progress.accept(100 * done.incrementAndGet() / Math.max(1, mothers.size()));
            }
        });
        // A pair seen from both sides is one overlap: keep the deeper report, count all points
        final java.util.Map<String, Fault> merged = new java.util.LinkedHashMap<>();
        for (Fault f : out) {
            String key;
            if (f.kind() == Kind.OVERLAP) {
                final String a = f.daughter().name() + "#" + f.daughterCopy();
                final String b = f.sister().name() + "#" + f.sisterCopy();
                key = f.mother().name() + "|" + (a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
            } else {
                key = f.mother().name() + "|" + f.daughter().name() + "#" + f.daughterCopy() + "|out";
            }
            final Fault prev = merged.get(key);
            if (prev == null) {
                merged.put(key, f);
            } else {
                final Fault deeper = f.depth() > prev.depth() ? f : prev;
                merged.put(key, new Fault(deeper.kind(), deeper.mother(), deeper.daughter(), deeper.daughterCopy(),
                    deeper.sister(), deeper.sisterCopy(), deeper.depth(), deeper.localPoint(), deeper.worldPoint(),
                    f.hits() + prev.hits()));
            }
        }
        final List<Fault> list = new ArrayList<>(merged.values());
        list.sort((a, b) -> Double.compare(b.depth(), a.depth()));
        return Collections.unmodifiableList(list);
    }

    private static void collect(LogicalVolume lv, List<LogicalVolume> out, Set<LogicalVolume> seen) {
        if (lv == null || !seen.add(lv)) {
            return;
        }
        if (!lv.daughters.isEmpty()) {
            out.add(lv);
        }
        for (PhysicalVolume pv : lv.daughters) {
            collect(pv.logical, out, seen);
        }
    }

    private static void checkMother(Geometry g, LogicalVolume m, int res, double tol, ConcurrentLinkedQueue<Fault> out) {
        if (m.solid == null || m.solid.error() != null) {
            return;
        }
        final DaughterIndex idx = m.index();
        final Xform motherToWorld = worldOf(g, m);
        for (DaughterIndex.Slot d : idx.slots) {
            checkDaughter(m, idx, d, res, tol, motherToWorld, out);
        }
    }

    /** One copy of a daughter: points on its surface against its mother and the sisters near it. */
    static void checkDaughter(LogicalVolume m, DaughterIndex idx, DaughterIndex.Slot d, int res, double tol,
                              Xform motherToWorld, java.util.Collection<Fault> out) {
        final Solid ds = d.pv.logical.solid;
        if (ds == null || ds.error() != null) {
            return;
        }
        final SplittableRandom rng = new SplittableRandom(0x0E1A9L ^ d.pv.name.hashCode() * 31L + d.copy);
        double worstOut = 0;
        Vec3 worstOutAt = null;
        int outHits = 0;
        final List<DaughterIndex.Slot> sisters = idx.overlapping(d.box.grow(tol));
        final double[] worstIn = new double[sisters.size()];
        final Vec3[] worstInAt = new Vec3[sisters.size()];
        final int[] inHits = new int[sisters.size()];
        for (int i = 0; i < res; i++) {
            final Vec3 pd = ds.pointOnSurface(rng);
            final Vec3 pm = d.t.apply(pd);
            final double sm = m.solid.sdf(pm.x(), pm.y(), pm.z());
            if (sm > tol + Solid.HALF_TOLERANCE * 2) {
                outHits++;
                if (sm > worstOut) {
                    worstOut = sm;
                    worstOutAt = pm;
                }
            }
            for (int k = 0; k < sisters.size(); k++) {
                final DaughterIndex.Slot s = sisters.get(k);
                if (s == d || (s.pv == d.pv && s.copy == d.copy)) {
                    continue;
                }
                if (!s.box.contains(pm.x(), pm.y(), pm.z())) {
                    continue;
                }
                final Vec3 ps = s.t.applyInverse(pm);
                final double ss = s.pv.logical.solid.sdf(ps.x(), ps.y(), ps.z());
                if (ss < -(tol + Solid.HALF_TOLERANCE * 2)) {
                    inHits[k]++;
                    if (-ss > worstIn[k]) {
                        worstIn[k] = -ss;
                        worstInAt[k] = pm;
                    }
                }
            }
        }
        if (outHits > 0) {
            out.add(new Fault(Kind.PROTRUSION, m, d.pv, d.copy, null, 0, worstOut, worstOutAt,
                motherToWorld == null ? null : motherToWorld.apply(worstOutAt), outHits));
        }
        for (int k = 0; k < sisters.size(); k++) {
            if (inHits[k] > 0) {
                final DaughterIndex.Slot s = sisters.get(k);
                // Report a pair once: from the daughter that comes first
                out.add(new Fault(Kind.OVERLAP, m, d.pv, d.copy, s.pv, s.copy, worstIn[k], worstInAt[k],
                    motherToWorld == null ? null : motherToWorld.apply(worstInAt[k]), inHits[k]));
            }
        }
    }

    /** The world transform of the first placement of a volume, or null when it is not placed. */
    static Xform worldOf(Geometry g, LogicalVolume lv) {
        for (Geometry.Instance i : g.instances()) {
            if (i.lv() == lv) {
                return i.world();
            }
        }
        return null;
    }
}
