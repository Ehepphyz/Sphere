package com.sphere.core.clhep.vector;

/**
 * What CLHEP's Hep4RotationInterface (Vector/RotationInterfaces.h) holds of its own: the tolerance
 * the rotations and Lorentz transformations compare with (100 ticks of 1e-8). Its methods, which C++
 * only declares there, are those of the concrete classes.
 */
public final class Hep4RotationInterface {

    public static final int ToleranceTicks = 100;
    public static double tolerance = ToleranceTicks * 1.0e-08;

    private Hep4RotationInterface() {
    }

    public static double getTolerance() {
        return tolerance;
    }

    public static double setTolerance(double tol) {
        final double t = tolerance;
        tolerance = tol;
        return t;
    }
}
