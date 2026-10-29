package com.sphere.core.clhep.geometry;

import com.sphere.core.hepmc3.cxx.StdStreams;

/** CLHEP's HepGeom::Reflect3D: the reflection in a plane. */
public class Reflect3D extends Transform3D {

    protected Reflect3D(double XX, double XY, double XZ, double DX,
                        double YX, double YY, double YZ, double DY,
                        double ZX, double ZY, double ZZ, double DZ) {
        super(XX, XY, XZ, DX, YX, YY, YZ, DY, ZX, ZY, ZZ, DZ);
    }

    public Reflect3D() {
        super();
    }

    /** In the plane a x + b y + c z + d = 0 (the identity, said on cerr, for a zero normal). */
    public Reflect3D(double a, double b, double c, double d) {
        double ll = a * a + b * b + c * c;
        if (ll == 0) {
            StdStreams.cerr().print("Reflect3D: zero normal\n");
            setIdentity();
        } else {
            ll = 1 / ll;
            final double aa = a * a * ll, ab = a * b * ll, ac = a * c * ll, ad = a * d * ll,
                bb = b * b * ll, bc = b * c * ll, bd = b * d * ll,
                cc = c * c * ll, cd = c * d * ll;
            setTransform(-aa + bb + cc, -ab - ab, -ac - ac, -ad - ad,
                -ab - ab, aa - bb + cc, -bc - bc, -bd - bd,
                -ac - ac, -bc - bc, aa + bb - cc, -cd - cd);
        }
    }

    /** In the plane of normal n through p. */
    public Reflect3D(Normal3D normal, Point3D point) {
        this(normal.x(), normal.y(), normal.z(), normal.negate().dot(point));
    }
}
