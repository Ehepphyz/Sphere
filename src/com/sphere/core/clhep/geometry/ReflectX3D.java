package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::ReflectX3D: the reflection in the plane x = x0 (0 by default). */
public class ReflectX3D extends Reflect3D {

    public ReflectX3D() {
        this(0);
    }

    public ReflectX3D(double x) {
        super(-1, 0, 0, x + x, 0, 1, 0, 0, 0, 0, 1, 0);
    }
}
