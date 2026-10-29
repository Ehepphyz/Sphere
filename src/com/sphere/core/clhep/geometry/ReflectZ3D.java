package com.sphere.core.clhep.geometry;

/** CLHEP's HepGeom::ReflectZ3D: the reflection in the plane z = z0 (0 by default). */
public class ReflectZ3D extends Reflect3D {

    public ReflectZ3D() {
        this(0);
    }

    public ReflectZ3D(double z) {
        super(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, z + z);
    }
}
