package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::TranslateX3D: a translation along x. */
public class TranslateX3D extends Translate3D {

    public TranslateX3D() {
        super();
    }

    public TranslateX3D(double x) {
        super(x, 0, 0);
    }
}
