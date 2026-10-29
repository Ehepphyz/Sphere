package com.sphere.core.clhep.geometry;

import com.sphere.core.clhep.CMath;

/** CLHEP's HepGeom::RotateX3D: a rotation about the x axis. */
public class RotateX3D extends Rotate3D {

    public RotateX3D() {
        super();
    }

    public RotateX3D(double a) {
        final double cosa = CMath.cos(a), sina = CMath.sin(a);
        setTransform(1, 0, 0, 0, 0, cosa, -sina, 0, 0, sina, cosa, 0);
    }
}
