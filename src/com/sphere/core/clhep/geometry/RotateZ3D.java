package com.sphere.core.clhep.geometry;

import com.sphere.core.clhep.CMath;

/** CLHEP's HepGeom::RotateZ3D: a rotation about the z axis. */
public class RotateZ3D extends Rotate3D {

    public RotateZ3D() {
        super();
    }

    public RotateZ3D(double a) {
        final double cosa = CMath.cos(a), sina = CMath.sin(a);
        setTransform(cosa, -sina, 0, 0, sina, cosa, 0, 0, 0, 0, 1, 0);
    }
}
