package com.sphere.core.geant4;

import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Puts new volumes where they belong. A volume drawn in the world goes into
 * the deepest volume that holds it entirely, the way a detector is built
 * from the inside of its envelopes, and its placement is written in that
 * mother's frame whatever the mother's own placement.
 */
public final class Placer {

    private Placer() {
    }

    /** The deepest instance whose solid holds every corner and the centre of a world box. */
    public static Geometry.Instance smartMother(Geometry g, BBox worldBox, LogicalVolume exclude) {
        final List<Geometry.Instance> path = g.locate(worldBox.center());
        for (int i = path.size() - 1; i >= 0; i--) {
            final Geometry.Instance in = path.get(i);
            if (exclude != null && in.lv().contains(exclude)) {
                continue;
            }
            if (holds(in, worldBox)) {
                return in;
            }
        }
        return g.instances().isEmpty() ? null : g.instances().get(0);
    }

    static boolean holds(Geometry.Instance in, BBox b) {
        final Solid s = in.lv().solid();
        if (s == null || s.error() != null) {
            return false;
        }
        for (int k = 0; k < 9; k++) {
            final Vec3 c = k == 8 ? b.center() : new Vec3(
                (k & 1) == 0 ? b.minX() : b.maxX(), (k & 2) == 0 ? b.minY() : b.maxY(),
                (k & 4) == 0 ? b.minZ() : b.maxZ());
            final Vec3 l = in.world().applyInverse(c);
            if (s.inside(l) == Solid.OUTSIDE) {
                return false;
            }
        }
        return true;
    }

    /** The instance of a logical volume that holds a world point, or its first instance. */
    public static Geometry.Instance instanceOf(Geometry g, LogicalVolume lv, Vec3 near) {
        Geometry.Instance first = null;
        for (Geometry.Instance i : g.instances()) {
            if (i.lv() != lv) {
                continue;
            }
            if (first == null) {
                first = i;
            }
            if (near != null && lv.solid() != null && lv.solid().inside(i.world().applyInverse(near)) != Solid.OUTSIDE) {
                return i;
            }
        }
        return first;
    }

    /**
     * Writes a placement's position and rotation so the volume lands at a
     * world transform, given the mother instance it sits in.
     */
    public static void setWorldTransform(PhysicalVolume pv, Geometry.Instance mother, Xform world,
                                         DoubleFunction<String> length) {
        setLocalTransform(pv, mother == null ? world : mother.world().inverse().compose(world), length);
    }

    /**
     * Writes a placement's position and rotation from its transform in the
     * mother. A mirrored placement's transform carries the mirror, which its
     * flag keeps: the rotation written is what is left once it is taken out.
     */
    public static void setLocalTransform(PhysicalVolume pv, Xform transform, DoubleFunction<String> length) {
        final Xform local = pv.reflected ? transform.compose(Xform.REFLECT_Z) : transform;
        final Vec3 t = local.translation();
        pv.setPosition(length.apply(t.x()), length.apply(t.y()), length.apply(t.z()));
        if (local.isIdentityRotation()) {
            pv.setRotation("0*deg", "0*deg", "0*deg");
        } else {
            // p_mother = R_gdml^-1 p_local: R_gdml is the transpose of the local rotation
            final double[] m = local.rotation();
            final double[] rt = {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
            final double[] a = Xform.gdmlAngles(rt);
            pv.setRotation(deg(a[0]), deg(a[1]), deg(a[2]));
        }
    }

    static String deg(double rad) {
        final double d = Math.toDegrees(rad);
        final double r = Math.rint(d * 1e9) / 1e9;
        return Units.num(Math.abs(r) < 1e-12 ? 0 : r) + "*deg";
    }

    /**
     * Creates a solid's logical volume and places it at a world transform,
     * in the given mother or in the deepest volume that holds it.
     */
    public static PhysicalVolume create(Geometry g, String baseName, Solid s, String material, Xform world,
                                        LogicalVolume mother, DoubleFunction<String> length) {
        final String name = g.uniqueName(baseName);
        s.rename(g.uniqueName(name + "_s"));
        g.addSolid(s);
        try {
            s.evaluate(g.scope());
        } catch (GeometryException ignored) {
            // reported by rebuild
        }
        final LogicalVolume lv = new LogicalVolume(name, s, material);
        lv.setColor(MaterialColors.colorFor(material));
        g.addVolume(lv);
        final BBox wb = s.error() == null ? world.apply(s.extent()) : new BBox(0, 0, 0, 0, 0, 0);
        Geometry.Instance mi;
        if (mother != null) {
            mi = instanceOf(g, mother, wb.center());
        } else {
            mi = smartMother(g, wb, null);
        }
        final LogicalVolume m = mi == null ? g.world() : mi.lv();
        final PhysicalVolume pv = g.place(lv, m, g.uniqueName(name + "_p"));
        setWorldTransform(pv, mi, world, length);
        return pv;
    }
}
