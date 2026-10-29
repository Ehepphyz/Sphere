package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepAxisAngle (Vector/AxisAngle.h, .icc, AxisAngle.cc): a rotation as a unit axis and an
 * angle about it. The axis given is made a unit vector; nearness compares the rotations' matrices.
 */
public final class HepAxisAngle implements Comparable<HepAxisAngle> {

    private static double tolerance = Hep3Vector.ToleranceTicks * 1.0e-08;

    private Hep3Vector axis_;   // of magnitude 1 after construction
    private double delta_;

    public HepAxisAngle() {
        axis_ = new Hep3Vector(0, 0, 1);
        delta_ = 0.0;
    }

    public HepAxisAngle(Hep3Vector aaxis, double ddelta) {
        axis_ = aaxis.unit();
        delta_ = ddelta;
    }

    public HepAxisAngle(HepAxisAngle o) {
        axis_ = new Hep3Vector(o.axis_);
        delta_ = o.delta_;
    }

    public Hep3Vector getAxis() {
        return new Hep3Vector(axis_);
    }

    public Hep3Vector axis() {
        return new Hep3Vector(axis_);
    }

    public HepAxisAngle setAxis(Hep3Vector aaxis) {
        axis_ = aaxis.unit();
        return this;
    }

    public double getDelta() {
        return delta_;
    }

    public double delta() {
        return delta_;
    }

    public HepAxisAngle setDelta(double ddelta) {
        delta_ = ddelta;
        return this;
    }

    /** operator=: the axis and angle of another. */
    public HepAxisAngle assign(HepAxisAngle o) {
        axis_ = new Hep3Vector(o.axis_);
        delta_ = o.delta_;
        return this;
    }

    public HepAxisAngle set(Hep3Vector aaxis, double ddelta) {
        axis_ = aaxis.unit();
        delta_ = ddelta;
        return this;
    }

    /** The angle first, then the axis. */
    public int compare(HepAxisAngle aa) {
        return delta_ < aa.delta_ ? -1
            : delta_ > aa.delta_ ? +1
            : axis_.lessThan(aa.axis_) ? -1
            : axis_.greaterThan(aa.axis_) ? +1
            : 0;
    }

    @Override
    public int compareTo(HepAxisAngle o) {
        return compare(o);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepAxisAngle a && compare(a) == 0;
    }

    @Override
    public int hashCode() {
        return axis_.hashCode() * 31 + Double.hashCode(delta_);
    }

    public static double getTolerance() {
        return tolerance;
    }

    public static double setTolerance(double tol) {
        final double old = tolerance;
        tolerance = tol;
        return old;
    }

    /** The rotation matrix of an axis and angle, row by row. */
    static void rep(HepAxisAngle aa, double[] array) {
        final double sinDelta = sin(aa.delta());
        final double cosDelta = cos(aa.delta());
        final double oneMinusCosDelta = 1.0 - cosDelta;
        final double uX = aa.getAxis().getX();
        final double uY = aa.getAxis().getY();
        final double uZ = aa.getAxis().getZ();
        array[0] = oneMinusCosDelta * uX * uX + cosDelta;
        array[1] = oneMinusCosDelta * uX * uY - sinDelta * uZ;
        array[2] = oneMinusCosDelta * uX * uZ + sinDelta * uY;
        array[3] = oneMinusCosDelta * uY * uX + sinDelta * uZ;
        array[4] = oneMinusCosDelta * uY * uY + cosDelta;
        array[5] = oneMinusCosDelta * uY * uZ - sinDelta * uX;
        array[6] = oneMinusCosDelta * uZ * uX - sinDelta * uY;
        array[7] = oneMinusCosDelta * uZ * uY + sinDelta * uX;
        array[8] = oneMinusCosDelta * uZ * uZ + cosDelta;
    }

    /** 3 minus the sum of the products of the two matrices' elements, never negative. */
    double distance(HepAxisAngle aa) {
        final double[] thisRep = new double[9];
        final double[] aaRep = new double[9];
        rep(this, thisRep);
        rep(aa, aaRep);
        double sum = 0.0;
        for (int i = 0; i < 9; i++) {
            sum += thisRep[i] * aaRep[i];
        }
        final double d = 3.0 - sum;
        return (d >= 0) ? d : 0;
    }

    public boolean isNear(HepAxisAngle aa) {
        return isNear(aa, tolerance);
    }

    public boolean isNear(HepAxisAngle aa, double epsilon) {
        return distance(aa) <= epsilon * epsilon;
    }

    public double howNear(HepAxisAngle aa) {
        return sqrt(distance(aa));
    }

    /** operator&lt;&lt;: "((x,y,z), delta)". */
    public COStream print(COStream os) {
        os.put('(');
        axis().print(os);
        return os.put(", ").put(delta()).put(')');
    }

    public static CIStream read(CIStream is, HepAxisAngle aa) {
        final double[] v = new double[4];
        ZMinput.inputAxisAngle(is, v);
        aa.set(new Hep3Vector(v[0], v[1], v[2]), v[3]);
        return is;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
