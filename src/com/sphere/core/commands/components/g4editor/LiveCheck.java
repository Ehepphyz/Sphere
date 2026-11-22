package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Mesh;
import com.sphere.core.geant4.Placer;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geant4.Xform;

/**
 * What a volume being drawn would do once made, told while it is drawn: the mother it would go into (the
 * deepest volume that holds it, as Placer decides), and whether it would overlap a sister there, only
 * touch one, or stick out of its mother. Geant4 tracks particles wrongly through overlaps, and finding them
 * after the fact (F7) is a chore: the preview turns red before the click instead.
 *
 * The preview's surface points are tested in the sisters near it, and theirs in it, within a few
 * milliseconds; a point a hair inside (float precision, a face drawn on a face) counts as touching.
 */
final class LiveCheck {

    enum State {
        CLEAR(0x9046B37A), TOUCHING(0x902AA1C8), OVERLAP(0x90E0402A), OUTSIDE(0x90E8A33A);

        final int argb;

        State(int argb) {
            this.argb = argb;
        }
    }

    /** The verdict on a preview: its state, the mother it would go into, the volume concerned, how deep, where. */
    record Verdict(State state, Geometry.Instance mother, String other, double depth, Vec3 where) {

        /** One line for the dynamic input: what will happen. */
        String describe() {
            return switch (state) {
                case OVERLAP -> "overlaps " + other + " by " + Units.best(depth, Units.Category.LENGTH);
                case OUTSIDE -> "sticks out of " + other + " by " + Units.best(depth, Units.Category.LENGTH);
                case TOUCHING -> "touches " + other + ", no overlap";
                default -> "clear of its neighbors";
            };
        }

        String motherName() {
            return mother == null ? "the world" : mother.lv().name();
        }
    }

    private LiveCheck() {
    }

    /** A check takes at most this long; what it found by then is the verdict. */
    private static final long BUDGET_NANOS = 6_000_000L;

    /**
     * The verdict on a solid (evaluated) about to be placed at a world transform, in a container or (null)
     * where Placer would put it.
     */
    static Verdict of(Geometry g, LogicalVolume container, Solid s, Xform world) {
        if (s == null || s.error() != null) {
            return null;
        }
        final long t0 = System.nanoTime();
        final BBox wb = world.apply(s.extent());
        final Geometry.Instance mi = container != null ? Placer.instanceOf(g, container, wb.center())
            : Placer.smartMother(g, wb, null);
        if (mi == null) {
            return new Verdict(State.CLEAR, null, null, 0, null);
        }
        final Mesh m = s.mesh(1);
        final int n = m.vertexCount();
        final Vec3[] pts = new Vec3[n];
        for (int i = 0; i < n; i++) {
            pts[i] = world.apply(new Vec3(m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2]));
        }
        // A hair inside is touching: the meshes are floats, a face drawn on a face sits on it
        final double tol = Math.max(1e-5 * wb.diagonal(), 1e-7 * (world.translation().length() + wb.diagonal()));
        State state = State.CLEAR;
        String other = null;
        double depth = 0;
        Vec3 where = null;
        String touched = null;

        // Out of the mother
        final Solid ms = mi.lv().solid();
        if (ms != null && ms.error() == null) {
            for (Vec3 p : pts) {
                final Vec3 l = mi.world().applyInverse(p);
                final double d = ms.sdf(l.x(), l.y(), l.z());
                if (d > tol && d > depth) {
                    depth = d;
                    where = p;
                    state = State.OUTSIDE;
                    other = mi.lv().name();
                }
            }
        }
        // Into the sisters, both ways
        for (Geometry.Instance sis : g.childrenOf(mi)) {
            if (System.nanoTime() - t0 > BUDGET_NANOS) {
                break;
            }
            final Solid ss = sis.lv().solid();
            if (ss == null || ss.error() != null) {
                continue;
            }
            final BBox sb = Geometry.worldBox(sis);
            if (!sb.overlaps(wb.grow(tol * 2))) {
                continue;
            }
            for (Vec3 p : pts) {
                final Vec3 l = sis.world().applyInverse(p);
                final double d = ss.sdf(l.x(), l.y(), l.z());
                if (d < -tol) {
                    // an overlap outranks a protrusion: it is what Geant4 tracks wrongly
                    if (state != State.OVERLAP || -d > depth) {
                        depth = -d;
                        where = p;
                        other = sis.lv().name();
                        state = State.OVERLAP;
                    }
                } else if (d <= tol && touched == null) {
                    touched = sis.lv().name();
                }
            }
            final Mesh sm = ss.meshIfReady(1) != null ? ss.meshIfReady(1) : ss.meshIfReady(0);
            if (sm == null) {
                continue;
            }
            for (int i = 0; i < sm.vertexCount(); i++) {
                if ((i & 63) == 0 && System.nanoTime() - t0 > BUDGET_NANOS) {
                    break;
                }
                final Vec3 p = sis.world().apply(new Vec3(sm.pos[i * 3], sm.pos[i * 3 + 1], sm.pos[i * 3 + 2]));
                final Vec3 l = world.applyInverse(p);
                final double d = s.sdf(l.x(), l.y(), l.z());
                if (d < -tol && (state != State.OVERLAP || -d > depth)) {
                    depth = -d;
                    where = p;
                    other = sis.lv().name();
                    state = State.OVERLAP;
                } else if (d >= -tol && d <= tol && touched == null) {
                    touched = sis.lv().name();
                }
            }
        }
        if (state == State.CLEAR && touched != null) {
            return new Verdict(State.TOUCHING, mi, touched, 0, null);
        }
        return new Verdict(state, mi, other, depth, where);
    }

    /** What identifies a preview for its verdict: the solid's parameters and the transform. */
    static String key(Solid s, Xform w) {
        final StringBuilder b = new StringBuilder(s.tag());
        for (Solid.Param p : s.params()) {
            b.append('|').append(p.expr());
        }
        for (Solid.Table t : s.tables()) {
            for (String[] row : t.rows()) {
                b.append('|').append(String.join(",", row));
            }
        }
        final double[] r = w.rotation();
        for (double v : r) {
            b.append('|').append(v);
        }
        final Vec3 t = w.translation();
        return b.append('|').append(t.x()).append(',').append(t.y()).append(',').append(t.z()).toString();
    }
}
