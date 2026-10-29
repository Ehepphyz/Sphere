package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::Scale3D: a scaling along the axes. */
public class Scale3D extends Transform3D {

    public Scale3D() {
        super();
    }

    public Scale3D(double x, double y, double z) {
        super(x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0);
    }

    public Scale3D(double sc) {
        super(sc, 0, 0, 0, 0, sc, 0, 0, 0, 0, sc, 0);
    }
}
