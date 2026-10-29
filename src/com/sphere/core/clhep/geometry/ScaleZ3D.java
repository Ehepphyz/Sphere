package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::ScaleZ3D: a scaling along z. */
public class ScaleZ3D extends Scale3D {

    public ScaleZ3D() {
        super();
    }

    public ScaleZ3D(double z) {
        super(1, 1, z);
    }
}
