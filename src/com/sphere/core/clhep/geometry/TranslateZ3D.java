package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::TranslateZ3D: a translation along z. */
public class TranslateZ3D extends Translate3D {

    public TranslateZ3D() {
        super();
    }

    public TranslateZ3D(double z) {
        super(0, 0, z);
    }
}
