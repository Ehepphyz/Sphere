package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::ScaleX3D: a scaling along x. */
public class ScaleX3D extends Scale3D {

    public ScaleX3D() {
        super();
    }

    public ScaleX3D(double x) {
        super(x, 1, 1);
    }
}
