package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;

/**
 * A geantino: a straight line through the geometry, cut into the volumes it
 * crosses, the way G4Navigator::ComputeStep walks it. Each step ends at the
 * nearer of the current volume's exit and the entry into a daughter, found by
 * sphere tracing on the solids' signed distances through the daughters'
 * bounding-volume hierarchy.
 */
public final class Tracer {

    /** One frame of the location stack: a volume and its local-to-world transform. */
    public record Frame(LogicalVolume lv, PhysicalVolume pv, int copy, Xform toWorld) {
    }

    /** A piece of the line inside one volume. */
    public record Segment(LogicalVolume lv, PhysicalVolume pv, int copy, double from, double to, int depth) {
        public double length() {
            return to - from;
        }
    }

    private final Geometry g;
    private static final double PUSH = 1e-6;

    public Tracer(Geometry g) {
        this.g = g;
    }

    /** The location stack of a world point, deepest last; empty outside the world. */
    public List<Frame> locate(double x, double y, double z) {
        final List<Frame> out = new ArrayList<>();
        final LogicalVolume w = g.world;
        if (w == null || w.solid == null || w.solid.error() != null || w.solid.inside(x, y, z) == Solid.OUTSIDE) {
            return out;
        }
        Frame cur = new Frame(w, null, 0, Xform.IDENTITY);
        out.add(cur);
        final double[] local = new double[3];
        final double[] d = new double[3];
        for (int depth = 0; depth < 64; depth++) {
            cur.toWorld().applyInverse(x, y, z, local);
            final DaughterIndex.Slot[] hit = new DaughterIndex.Slot[1];
            cur.lv().index().query(local[0], local[1], local[2], slot -> {
                slot.t.applyInverse(local[0], local[1], local[2], d);
                if (slot.pv.logical.solid.inside(d[0], d[1], d[2]) != Solid.OUTSIDE) {
                    hit[0] = slot;
                    return false;
                }
                return true;
            });
            if (hit[0] == null) {
                break;
            }
            cur = new Frame(hit[0].pv.logical, hit[0].pv, hit[0].copy, cur.toWorld().compose(hit[0].t));
            out.add(cur);
        }
        return out;
    }

    /** Walks a ray of unit direction from an origin over a length, calling back each segment. */
    public void trace(Vec3 origin, Vec3 direction, double maxLength, java.util.function.Consumer<Segment> sink) {
        final Vec3 dir = direction.unit();
        final double ox = origin.x(), oy = origin.y(), oz = origin.z();
        final double dx = dir.x(), dy = dir.y(), dz = dir.z();
        double t = 0;
        List<Frame> frames = locate(ox, oy, oz);
        if (frames.isEmpty()) {
            final LogicalVolume w = g.world;
            if (w == null || w.solid == null || w.solid.error() != null) {
                return;
            }
            final double tin = w.solid.distanceAlong(ox, oy, oz, dx, dy, dz, maxLength);
            if (!(tin < maxLength)) {
                return;
            }
            t = tin + PUSH;
            frames = locate(ox + dx * t, oy + dy * t, oz + dz * t);
        }
        double push = PUSH;
        int guard = 0;
        while (!frames.isEmpty() && t < maxLength && guard++ < 100_000) {
            final Frame top = frames.get(frames.size() - 1);
            final Vec3 lp = top.toWorld().applyInverse(new Vec3(ox + dx * t, oy + dy * t, oz + dz * t));
            final Vec3 ld = top.toWorld().applyInverseDir(dir);
            final double remaining = maxLength - t;
            final double exit = top.lv().solid.distanceAlong(lp.x(), lp.y(), lp.z(), ld.x(), ld.y(), ld.z(), remaining);
            final double[] best = {Math.min(exit, remaining)};
            top.lv().index().ray(lp.x(), lp.y(), lp.z(), ld.x(), ld.y(), ld.z(), best[0], (slot, tb) -> {
                if (tb >= best[0]) {
                    return;
                }
                final Vec3 dp = slot.t.applyInverse(lp);
                final Vec3 dd = slot.t.applyInverseDir(ld);
                final Solid s = slot.pv.logical.solid;
                final double start = Math.max(0, tb - 1e-6);
                final double sx = dp.x() + dd.x() * start, sy = dp.y() + dd.y() * start, sz = dp.z() + dd.z() * start;
                if (s.sdf(sx, sy, sz) <= 0) {
                    best[0] = Math.min(best[0], start);
                    return;
                }
                final double din = s.distanceAlong(sx, sy, sz, dd.x(), dd.y(), dd.z(), best[0] - start);
                if (start + din < best[0]) {
                    best[0] = start + din;
                }
            });
            final double step = best[0];
            if (step > 0) {
                sink.accept(new Segment(top.lv(), top.pv(), top.copy(), t, t + step, frames.size() - 1));
                push = PUSH;
            } else {
                push *= 10; // stuck on a boundary: push harder
            }
            t += step + push;
            if (t >= maxLength) {
                break;
            }
            frames = locate(ox + dx * t, oy + dy * t, oz + dz * t);
        }
    }

    /** The segments of a ray, collected. */
    public List<Segment> segments(Vec3 origin, Vec3 direction, double maxLength) {
        final List<Segment> out = new ArrayList<>();
        trace(origin, direction, maxLength, out::add);
        return out;
    }
}
