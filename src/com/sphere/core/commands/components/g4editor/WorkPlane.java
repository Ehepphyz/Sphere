package com.sphere.components.g4editor;

import com.sphere.core.geant4.Vec3;
import com.sphere.core.geant4.Xform;

/**
 * The plane the mouse draws on, with its own (u, v) axes and normal n: the
 * sketch plane of FreeCAD's Sketcher, or a face of a volume clicked on.
 */
final class WorkPlane {

    final String name;
    final Vec3 origin, u, v, n;

    WorkPlane(String name, Vec3 origin, Vec3 u, Vec3 v) {
        this.name = name;
        this.origin = origin;
        this.u = u.unit();
        this.v = v.unit();
        this.n = this.u.cross(this.v).unit();
    }

    static WorkPlane xy(double z) {
        return new WorkPlane("XY", new Vec3(0, 0, z), Vec3.X, Vec3.Y);
    }

    static WorkPlane xz(double y) {
        return new WorkPlane("XZ", new Vec3(0, y, 0), Vec3.X, Vec3.Z);
    }

    static WorkPlane yz(double x) {
        return new WorkPlane("YZ", new Vec3(x, 0, 0), Vec3.Y, Vec3.Z);
    }

    /** The axis-aligned plane closest to a face normal, through a point. */
    static WorkPlane facing(Vec3 point, Vec3 normal) {
        final double ax = Math.abs(normal.x()), ay = Math.abs(normal.y()), az = Math.abs(normal.z());
        if (az >= ax && az >= ay) {
            return normal.z() >= 0 ? new WorkPlane("XY", point, Vec3.X, Vec3.Y)
                : new WorkPlane("XY", point, Vec3.Y, Vec3.X);
        }
        if (ay >= ax) {
            return normal.y() >= 0 ? new WorkPlane("XZ", point, Vec3.Z, Vec3.X)
                : new WorkPlane("XZ", point, Vec3.X, Vec3.Z);
        }
        return normal.x() >= 0 ? new WorkPlane("YZ", point, Vec3.Y, Vec3.Z)
            : new WorkPlane("YZ", point, Vec3.Z, Vec3.Y);
    }

    WorkPlane through(Vec3 p) {
        return new WorkPlane(name, p, u, v);
    }

    WorkPlane offset(double d) {
        return new WorkPlane(name, origin.add(n.mul(d)), u, v);
    }

    /** (u, v) of the ray's hit with the plane, or null when parallel or behind. */
    double[] hit(Vec3 o, Vec3 d) {
        final double den = d.dot(n);
        if (Math.abs(den) < 1e-9) {
            return null;
        }
        final double t = origin.sub(o).dot(n) / den;
        if (t < 0) {
            return null;
        }
        final Vec3 p = o.add(d.mul(t));
        return uv(p);
    }

    double[] uv(Vec3 p) {
        final Vec3 r = p.sub(origin);
        return new double[] {r.dot(u), r.dot(v)};
    }

    Vec3 at(double a, double b) {
        return origin.add(u.mul(a)).add(v.mul(b));
    }

    Vec3 at(double a, double b, double h) {
        return origin.add(u.mul(a)).add(v.mul(b)).add(n.mul(h));
    }

    /** The transform taking a solid's local (x, y, z) onto (u, v, n), centred at a point. */
    Xform frame(Vec3 center) {
        final double[] m = {u.x(), v.x(), n.x(), u.y(), v.y(), n.y(), u.z(), v.z(), n.z()};
        return new Xform(m, center.x(), center.y(), center.z());
    }

    /**
     * Distance along the normal of the point on the normal line through base
     * closest to a ray: how high the mouse pulls a shape off the plane.
     */
    double height(Vec3 base, Vec3 o, Vec3 d) {
        // Closest points between lines base + s n and o + t d
        final Vec3 w0 = base.sub(o);
        final double a = 1, b = n.dot(d), c = d.dot(d), dd = n.dot(w0), e = d.dot(w0);
        final double den = a * c - b * b;
        if (Math.abs(den) < 1e-12) {
            return 0;
        }
        return (b * e - c * dd) / den;
    }

    String axisName(boolean first) {
        final Vec3 a = first ? u : v;
        if (Math.abs(a.x()) > 0.99) {
            return a.x() > 0 ? "X" : "-X";
        }
        if (Math.abs(a.y()) > 0.99) {
            return a.y() > 0 ? "Y" : "-Y";
        }
        if (Math.abs(a.z()) > 0.99) {
            return a.z() > 0 ? "Z" : "-Z";
        }
        return first ? "U" : "V";
    }
}
