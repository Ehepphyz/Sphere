package com.sphere.core.clhep.geometry;

import com.sphere.core.clhep.CMath;

/** CLHEP's HepGeom::RotateY3D: a rotation about the y axis. */
public class RotateY3D extends Rotate3D {

    public RotateY3D() {
        super();
    }

    public RotateY3D(double a) {
        final double cosa = CMath.cos(a), sina = CMath.sin(a);
        setTransform(cosa, 0, sina, 0, 0, 1, 0, 0, -sina, 0, cosa, 0);
    }
}
