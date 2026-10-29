package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::TranslateY3D: a translation along y. */
public class TranslateY3D extends Translate3D {

    public TranslateY3D() {
        super();
    }

    public TranslateY3D(double y) {
        super(0, y, 0);
    }
}
