package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A bounding-volume hierarchy over the daughters of one logical volume, in the
 * mother's frame. It is what G4SmartVoxelHeader is to G4Navigator: the way a
 * point or a ray tests a handful of daughters instead of all of them.
 */
final class DaughterIndex {

    /** One copy of one daughter, with its transform and box in the mother frame. */
    static final class Slot {
        final PhysicalVolume pv;
        final int copy;
        final Xform t;
        final BBox box;

        Slot(PhysicalVolume pv, int copy, Xform t, BBox box) {
            this.pv = pv;
            this.copy = copy;
            this.t = t;
            this.box = box;
        }
    }

    final Slot[] slots;
    // Flattened tree: for node i, bounds[6i..6i+5], left/right child or leaf range
    private double[] bounds = new double[0];
    private int[] child = new int[0];     // >=0: left child index (right = left+1); <0: leaf
    private int[] start = new int[0], count = new int[0];
    private int nodes;

    DaughterIndex(LogicalVolume lv) {
        final List<Slot> list = new ArrayList<>();
        for (PhysicalVolume pv : lv.daughters) {
            if (pv.logical.solid == null || pv.logical.solid.error() != null) {
                continue;
            }
            final BBox local = pv.logical.solid.extent();
            for (int c = 0; c < pv.copies(); c++) {
                final Xform t = pv.copyTransform(c);
                list.add(new Slot(pv, c, t, t.apply(local)));
            }
        }
        slots = list.toArray(new Slot[0]);
        if (slots.length > 0) {
            final int cap = slots.length * 2;
            bounds = new double[cap * 6];
            child = new int[cap];
            start = new int[cap];
            count = new int[cap];
            nodes = 1;
            build(0, 0, slots.length);
        }
    }

    private void build(int node, int from, int to) {
        BBox b = BBox.EMPTY;
        for (int i = from; i < to; i++) {
            b = b.union(slots[i].box);
        }
        bounds[node * 6] = b.minX();
        bounds[node * 6 + 1] = b.minY();
        bounds[node * 6 + 2] = b.minZ();
        bounds[node * 6 + 3] = b.maxX();
        bounds[node * 6 + 4] = b.maxY();
        bounds[node * 6 + 5] = b.maxZ();
        if (to - from <= 4) {
            child[node] = -1;
            start[node] = from;
            count[node] = to - from;
            return;
        }
        final Vec3 s = b.size();
        final int axis = s.x() >= s.y() && s.x() >= s.z() ? 0 : s.y() >= s.z() ? 1 : 2;
        Arrays.sort(slots, from, to, Comparator.comparingDouble(sl -> sl.box.center().get(axis)));
        final int mid = (from + to) >>> 1;
        final int left = nodes;
        nodes += 2;
        child[node] = left;
        build(left, from, mid);
        build(left + 1, mid, to);
    }

    /** Calls back every slot whose box holds the point. */
    void query(double x, double y, double z, java.util.function.Predicate<Slot> visitor) {
        if (nodes == 0) {
            return;
        }
        final int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            final int n = stack[--sp];
            final int o = n * 6;
            if (x < bounds[o] || y < bounds[o + 1] || z < bounds[o + 2]
                || x > bounds[o + 3] || y > bounds[o + 4] || z > bounds[o + 5]) {
                continue;
            }
            if (child[n] < 0) {
                for (int i = start[n]; i < start[n] + count[n]; i++) {
                    final BBox b = slots[i].box;
                    if (b.contains(x, y, z) && !visitor.test(slots[i])) {
                        return;
                    }
                }
            } else if (sp + 2 <= stack.length) {
                stack[sp++] = child[n];
                stack[sp++] = child[n] + 1;
            }
        }
    }

    /** Calls back every slot whose box a ray segment [0, tmax] crosses, with the box entry distance. */
    void ray(double ox, double oy, double oz, double dx, double dy, double dz, double tmax,
             java.util.function.ObjDoubleConsumer<Slot> visitor) {
        if (nodes == 0) {
            return;
        }
        final double ix = 1 / dx, iy = 1 / dy, iz = 1 / dz;
        final int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            final int n = stack[--sp];
            final double t = slab(n * 6, bounds, ox, oy, oz, ix, iy, iz, tmax);
            if (t < 0) {
                continue;
            }
            if (child[n] < 0) {
                for (int i = start[n]; i < start[n] + count[n]; i++) {
                    final BBox b = slots[i].box;
                    final double tb = slab(b, ox, oy, oz, ix, iy, iz, tmax);
                    if (tb >= 0) {
                        visitor.accept(slots[i], tb);
                    }
                }
            } else if (sp + 2 <= stack.length) {
                stack[sp++] = child[n];
                stack[sp++] = child[n] + 1;
            }
        }
    }

    private static double slab(int o, double[] b, double ox, double oy, double oz, double ix, double iy,
                               double iz, double tmax) {
        return slab(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], ox, oy, oz, ix, iy, iz, tmax);
    }

    private static double slab(BBox b, double ox, double oy, double oz, double ix, double iy, double iz,
                               double tmax) {
        return slab(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), ox, oy, oz, ix, iy, iz, tmax);
    }

    /** Entry distance of a ray into a box, clamped at 0, or -1 when it misses within tmax. */
    private static double slab(double x0, double y0, double z0, double x1, double y1, double z1, double ox,
                               double oy, double oz, double ix, double iy, double iz, double tmax) {
        double t0 = 0, t1 = tmax;
        double a = (x0 - ox) * ix, b = (x1 - ox) * ix;
        if (Double.isNaN(a) || Double.isNaN(b)) {
            if (ox < x0 || ox > x1) {
                return -1;
            }
        } else {
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        }
        a = (y0 - oy) * iy;
        b = (y1 - oy) * iy;
        if (Double.isNaN(a) || Double.isNaN(b)) {
            if (oy < y0 || oy > y1) {
                return -1;
            }
        } else {
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        }
        a = (z0 - oz) * iz;
        b = (z1 - oz) * iz;
        if (Double.isNaN(a) || Double.isNaN(b)) {
            if (oz < z0 || oz > z1) {
                return -1;
            }
        } else {
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        }
        return t0 <= t1 ? t0 : -1;
    }

    /** Slots whose boxes overlap a box. */
    List<Slot> overlapping(BBox q) {
        final List<Slot> out = new ArrayList<>();
        if (nodes == 0) {
            return out;
        }
        final int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            final int n = stack[--sp];
            final int o = n * 6;
            if (q.maxX() < bounds[o] || q.maxY() < bounds[o + 1] || q.maxZ() < bounds[o + 2]
                || q.minX() > bounds[o + 3] || q.minY() > bounds[o + 4] || q.minZ() > bounds[o + 5]) {
                continue;
            }
            if (child[n] < 0) {
                for (int i = start[n]; i < start[n] + count[n]; i++) {
                    if (slots[i].box.overlaps(q)) {
                        out.add(slots[i]);
                    }
                }
            } else if (sp + 2 <= stack.length) {
                stack[sp++] = child[n];
                stack[sp++] = child[n] + 1;
            }
        }
        return out;
    }
}
