package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The overlaps of the placements being edited, found while they are edited: what a CAD program does when it
 * flags interferences as a part is dragged, rather than waiting for a check of the whole document (Overlaps).
 * Each copy is sampled against its mother and the sisters near it, a few hundred points, a few milliseconds.
 *
 * Each fault comes with its remedy: the shortest move of the daughter, along its mother's axes or out along
 * the other volume's surface normal, after which a fresh sampling finds it inside its mother and clear of
 * every sister, both ways (its points in theirs, theirs in it). It is offered as the console command that
 * makes it, so it is echoed and undone like any edit.
 */
public final class Clashes {

    /** A fault, and the move of its daughter (world frame) that clears it, or null when none was found. */
    public record Clash(Overlaps.Fault fault, Vec3 move) {

        /** The console command that applies the remedy, or null. */
        public String command() {
            if (move == null) {
                return null;
            }
            return "move " + fault.daughter().name() + " " + mm(move.x()) + " " + mm(move.y()) + " " + mm(move.z());
        }

        private static String mm(double v) {
            return Units.num(Math.abs(v) < 1e-9 ? 0 : v) + "mm";
        }
    }

    private Clashes() {
    }

    /** Past this many copies of a placement (a replica's), the first ones stand for the rest. */
    private static final int MAX_COPIES = 64;
    /** What a remedy leaves between the surfaces, so the next check (and Geant4's) finds them apart. */
    private static final double CLEARANCE = 1e-3;

    /** The overlaps and protrusions of these placements, deepest first, each with its remedy when one exists. */
    public static List<Clash> of(Geometry g, Collection<PhysicalVolume> pvs, int points) {
        final Map<String, Overlaps.Fault> faults = new LinkedHashMap<>();
        final Map<Overlaps.Fault, DaughterIndex.Slot> slotOf = new java.util.IdentityHashMap<>();
        for (PhysicalVolume pv : pvs) {
            final LogicalVolume m = pv.mother();
            if (m == null || m.solid == null || m.solid.error() != null || pv.logical.solid == null
                || pv.logical.solid.error() != null) {
                continue;
            }
            final DaughterIndex idx = m.index();
            final Xform toWorld = Overlaps.worldOf(g, m);
            int copies = 0;
            for (DaughterIndex.Slot d : idx.slots) {
                if (d.pv != pv || copies++ >= MAX_COPIES) {
                    continue;
                }
                final List<Overlaps.Fault> found = new ArrayList<>();
                Overlaps.checkDaughter(m, idx, d, points, 0, toWorld, found);
                for (Overlaps.Fault f : found) {
                    // a pair of edited placements is seen from both: kept once
                    final String key = f.kind() == Overlaps.Kind.PROTRUSION
                        ? m.name + "|" + f.daughter().name + "#" + f.daughterCopy()
                        : m.name + "|" + pair(f.daughter().name + "#" + f.daughterCopy(), f.sister().name + "#" + f.sisterCopy());
                    if (!faults.containsKey(key)) {
                        faults.put(key, f);
                        slotOf.put(f, d);
                    }
                }
            }
        }
        final List<Clash> out = new ArrayList<>();
        for (Overlaps.Fault f : faults.values()) {
            final DaughterIndex.Slot d = slotOf.get(f);
            final boolean movable = !d.pv.isReplica() && d.pv.copies() == 1;
            out.add(new Clash(f, movable ? remedy(g, f, d, points) : null));
        }
        out.sort((a, b) -> Double.compare(b.fault().depth(), a.fault().depth()));
        return out;
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * The shortest move of a daughter, in its mother's frame then the world's, that clears a fault and makes
     * no other: candidates along the six axes and the surface normal where it is deepest, each as long as its
     * deepest point needs, kept only if the moved daughter checks clean.
     */
    private static Vec3 remedy(Geometry g, Overlaps.Fault f, DaughterIndex.Slot d, int points) {
        final LogicalVolume m = f.mother();
        final Solid ms = m.solid, ds = d.pv.logical.solid;
        final DaughterIndex idx = m.index();
        final double reach = ms.box().diagonal();
        // The daughter's surface, sampled once in the mother's frame
        final Vec3[] pts = surface(ds, d.t, points, 0x5EB0L ^ d.pv.name.hashCode());
        DaughterIndex.Slot other = null;
        if (f.kind() == Overlaps.Kind.OVERLAP) {
            for (DaughterIndex.Slot s : idx.slots) {
                if (s.pv == f.sister() && s.copy == f.sisterCopy()) {
                    other = s;
                }
            }
            if (other == null) {
                return null;
            }
        }
        final List<Vec3> dirs = new ArrayList<>(List.of(Vec3.X, Vec3.X.mul(-1), Vec3.Y, Vec3.Y.mul(-1), Vec3.Z, Vec3.Z.mul(-1)));
        final Vec3 at = f.localPoint();
        if (at != null) {
            // Out of the sister along its normal there; back into the mother against its own
            final Vec3 n = other != null
                ? other.t.applyDir(normalAt(other.pv.logical.solid, other.t.applyInverse(at)))
                : normalAt(ms, at).mul(-1);
            if (n != null && n.length() > 0.5) {
                dirs.add(0, n.unit());
            }
        }
        // A move far longer than the fault is no remedy: a volume wedged between two others, or sunk whole
        // into a sister, wants resizing or another mother, not a jump across the detector
        final double longest = Math.min(reach, Math.max(3 * f.depth(), 0.25 * ds.box().diagonal()));
        // Checked on other, denser points than the move was measured on: those all end up just clear by
        // construction, while the surface between them may still touch (a box's face against a sphere)
        final Vec3[] check = surface(ds, d.t, 4 * points, 0xC4EC4L ^ d.pv.name.hashCode());
        Vec3 best = null;
        double bestLen = Double.MAX_VALUE;
        for (Vec3 u : dirs) {
            double t = needed(pts, u, other, ms, reach);
            for (int iter = 0; iter < 6 && t > 0 && t < bestLen && t <= longest; iter++) {
                final double left = residual(m, idx, d, ds, check, u.mul(t + CLEARANCE), points);
                if (left == 0) {
                    best = u.mul(t + CLEARANCE);
                    bestLen = t;
                    break;
                }
                // what is left is a distance to the surface, not along u: exact for a move square to the
                // surface, a few steps more for an oblique one
                t += left + CLEARANCE;
            }
        }
        if (best == null) {
            return null;
        }
        final Xform toWorld = Overlaps.worldOf(g, m);
        return toWorld == null ? best : toWorld.applyDir(best);
    }

    /** Past this many mesh vertices, every k-th: a fine tessellation's vertices add little to the corners. */
    private static final int MAX_VERTICES = 1500;

    /**
     * Points on a solid's surface placed by t: random ones by area, as Overlaps samples, and its mesh's
     * vertices, which hold its corners. A box's corner is where it goes deepest into a sphere, and random
     * points miss it: the remedy then fell short by a fraction of a millimetre.
     */
    private static Vec3[] surface(Solid s, Xform t, int random, long seed) {
        final float[] v = s.mesh(2).pos;
        final int nv = v.length / 3, step = Math.max(1, nv / MAX_VERTICES);
        final List<Vec3> out = new ArrayList<>(random + nv / step);
        final SplittableRandom rng = new SplittableRandom(seed);
        for (int i = 0; i < random; i++) {
            out.add(t.apply(s.pointOnSurface(rng)));
        }
        for (int i = 0; i < nv; i += step) {
            out.add(t.apply(new Vec3(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])));
        }
        return out.toArray(new Vec3[0]);
    }

    private static Vec3 normalAt(Solid s, Vec3 p) {
        try {
            return s.normal(p.x(), p.y(), p.z());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * How far along u the deepest point must go: out of the sister for an overlap (DistanceToOut from the
     * points inside it), back into the mother for a protrusion (DistanceToIn from those outside); NaN when a
     * point never gets there along u.
     */
    private static double needed(Vec3[] pts, Vec3 u, DaughterIndex.Slot other, Solid mother, double reach) {
        double t = 0;
        for (Vec3 p : pts) {
            if (other != null) {
                if (!other.box.contains(p.x(), p.y(), p.z())) {
                    continue;
                }
                final Solid s = other.pv.logical.solid;
                final Vec3 q = other.t.applyInverse(p), v = other.t.applyInverseDir(u);
                if (s.sdf(q.x(), q.y(), q.z()) < -Solid.HALF_TOLERANCE * 2) {
                    final double out = s.distanceAlong(q.x(), q.y(), q.z(), v.x(), v.y(), v.z(), reach);
                    if (Double.isInfinite(out)) {
                        return Double.NaN;
                    }
                    t = Math.max(t, out);
                }
            } else if (mother.sdf(p.x(), p.y(), p.z()) > Solid.HALF_TOLERANCE * 2) {
                final double in = mother.distanceAlong(p.x(), p.y(), p.z(), u.x(), u.y(), u.z(), reach);
                if (Double.isInfinite(in)) {
                    return Double.NaN;
                }
                t = Math.max(t, in);
            }
        }
        return t;
    }

    /**
     * How deep the daughter moved by `move` (mother frame) still goes into anything: out of its mother, into a
     * sister, or a sister into it (so it has not swallowed a smaller one); 0 when it is clear of all.
     */
    private static double residual(LogicalVolume m, DaughterIndex idx, DaughterIndex.Slot d, Solid ds, Vec3[] pts,
                                   Vec3 move, int points) {
        final double tol = Solid.HALF_TOLERANCE * 2;
        final Xform moved = Xform.translation(move.x(), move.y(), move.z()).compose(d.t);
        final BBox box = moved.apply(ds.box());
        final List<DaughterIndex.Slot> near = new ArrayList<>();
        for (DaughterIndex.Slot s : idx.overlapping(box.grow(CLEARANCE))) {
            if (s.pv != d.pv) {
                near.add(s);
            }
        }
        double worst = 0;
        for (Vec3 p0 : pts) {
            final Vec3 p = p0.add(move);
            final double out = m.solid.sdf(p.x(), p.y(), p.z());
            if (out > tol) {
                worst = Math.max(worst, out);
            }
            for (DaughterIndex.Slot s : near) {
                if (s.box.contains(p.x(), p.y(), p.z())) {
                    final Vec3 q = s.t.applyInverse(p);
                    final double in = -s.pv.logical.solid.sdf(q.x(), q.y(), q.z());
                    if (in > tol) {
                        worst = Math.max(worst, in);
                    }
                }
            }
        }
        for (DaughterIndex.Slot s : near) {
            for (Vec3 p : surface(s.pv.logical.solid, s.t, Math.max(64, points / 2), 0x51573L ^ s.pv.name.hashCode() * 31L + s.copy)) {
                if (!box.contains(p.x(), p.y(), p.z())) {
                    continue;
                }
                final Vec3 q = moved.applyInverse(p);
                final double in = -ds.sdf(q.x(), q.y(), q.z());
                if (in > tol) {
                    worst = Math.max(worst, in);
                }
            }
        }
        return worst;
    }
}
