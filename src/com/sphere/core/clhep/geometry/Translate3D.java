package com.sphere.core.clhep.geometry;

import com.sphere.core.clhep.vector.Hep3Vector;

/** CLHEP's HepGeom::Translate3D: a translation. */
public class Translate3D extends Transform3D {

    public Translate3D() {
        super();
    }

    public Translate3D(Hep3Vector v) {
        super(1, 0, 0, v.x(), 0, 1, 0, v.y(), 0, 0, 1, v.z());
    }

    public Translate3D(double x, double y, double z) {
        super(1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z);
    }
}
