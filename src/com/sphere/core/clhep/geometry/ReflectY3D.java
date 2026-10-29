package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::ReflectY3D: the reflection in the plane y = y0 (0 by default). */
public class ReflectY3D extends Reflect3D {

    public ReflectY3D() {
        this(0);
    }

    public ReflectY3D(double y) {
        super(1, 0, 0, 0, 0, -1, 0, y + y, 0, 0, 1, 0);
    }
}
