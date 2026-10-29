package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::ScaleY3D: a scaling along y. */
public class ScaleY3D extends Scale3D {

    public ScaleY3D() {
        super();
    }

    public ScaleY3D(double y) {
        super(1, y, 1);
    }
}
