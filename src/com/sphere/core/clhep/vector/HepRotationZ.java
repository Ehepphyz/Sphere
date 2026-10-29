package com.sphere.core.clhep.vector;

import com.sphere.core.clhep.units.SystemOfUnits;

import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepRotationZ (Vector/RotationZ.h, .icc, RotationZ.cc): a rotation about the z axis: x turns towards y.
 * Products of two such rotations stay of this kind; with others they are HepRotations.
 */
public final class HepRotationZ extends HepAxialRotation implements Comparable<HepRotationZ> {

    /** The identity. */
    public HepRotationZ() {
        super(0.0, 0.0, 1.0);
    }

    /** A rotation by delta: its angle brought into (-pi, pi], sine and cosine of delta as given. */
    public HepRotationZ(double ddelta) {
        super(proper(ddelta), sin(ddelta), cos(ddelta));
    }

    public HepRotationZ(HepRotationZ orig) {
        super(orig.its_d, orig.its_s, orig.its_c);
    }

    HepRotationZ(double dd, double ss, double cc) {
        super(dd, ss, cc);
    }

    public HepRotationZ copy() {
        return new HepRotationZ(this);
    }

    public HepRotationZ assign(HepRotationZ orig) {
        its_d = orig.its_d;
        its_s = orig.its_s;
        its_c = orig.its_c;
        return this;
    }

    public HepRotationZ set(double ddelta) {
        its_d = proper(ddelta);
        its_s = sin(its_d);
        its_c = cos(its_d);
        return this;
    }

    public void setDelta(double ddelta) {
        set(ddelta);
    }

    @Override
    public double xx() {
        return its_c;
    }

    @Override
    public double xy() {
        return -its_s;
    }

    @Override
    public double xz() {
        return 0.0;
    }

    @Override
    public double yx() {
        return its_s;
    }

    @Override
    public double yy() {
        return its_c;
    }

    @Override
    public double yz() {
        return 0.0;
    }

    @Override
    public double zx() {
        return 0.0;
    }

    @Override
    public double zy() {
        return 0.0;
    }

    @Override
    public double zz() {
        return 1.0;
    }

    @Override
    public Hep3Vector colX() {
        return new Hep3Vector(its_c, its_s, 0.0);
    }

    @Override
    public Hep3Vector colY() {
        return new Hep3Vector(-its_s, its_c, 0.0);
    }

    @Override
    public Hep3Vector colZ() {
        return new Hep3Vector(0.0, 0.0, 1.0);
    }

    @Override
    public Hep3Vector rowX() {
        return new Hep3Vector(its_c, -its_s, 0.0);
    }

    @Override
    public Hep3Vector rowY() {
        return new Hep3Vector(its_s, its_c, 0.0);
    }

    @Override
    public Hep3Vector rowZ() {
        return new Hep3Vector(0.0, 0.0, 1.0);
    }

    @Override
    public Hep3Vector axis() {
        return new Hep3Vector(0, 0, 1);
    }

    @Override
    public double phi() {
        return -its_d / 2.0;
    }

    @Override
    public double theta() {
        return 0.0;
    }

    @Override
    public double psi() {
        return -its_d / 2.0;
    }

    @Override
    public Hep3Vector apply(Hep3Vector p) {
        final double x = p.x();
        final double y = p.y();
        final double z = p.z();
        return new Hep3Vector(x * its_c - y * its_s, x * its_s + y * its_c, z);
    }

    @Override
    public double distance2(HepRotation r) {
        final double sum = xx() * r.xx() + xy() * r.xy() + yx() * r.yx() + yy() * r.yy() + r.zz();
        final double answer = 3.0 - sum;
        return (answer >= 0) ? answer : 0;
    }

    public double distance2(HepRotationZ r) {
        return distance2Same(r);
    }

    public double howNear(HepRotationZ r) {
        return sqrt(distance2(r));
    }

    public boolean isNear(HepRotationZ r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotationZ r, double epsilon) {
        return distance2(r) <= epsilon * epsilon;
    }

    /** this * r: still a rotation about the same axis. */
    public HepRotationZ times(HepRotationZ r) {
        return new HepRotationZ(proper(its_d + r.its_d), its_s * r.its_c + its_c * r.its_s,
            its_c * r.its_c - its_s * r.its_s);
    }

    /** this * r as a HepRotation. */
    public HepRotation times(HepRotation r) {
        return HepRotation.times(this, r);
    }

    public HepRotationZ timesAssign(HepRotationZ m1) {
        return assign(times(m1));
    }

    public HepRotationZ transform(HepRotationZ m1) {
        return assign(m1.times(this));
    }

    public HepRotationZ inverse() {
        return new HepRotationZ(proper(-its_d), -its_s, its_c);
    }

    public static HepRotationZ inverseOf(HepRotationZ r) {
        return r.inverse();
    }

    public HepRotationZ invert() {
        return assign(inverse());
    }

    public int compare(HepRotationZ r) {
        if (its_d > r.its_d) return 1;
        else if (its_d < r.its_d) return -1;
        else return 0;
    }

    @Override
    public int compareTo(HepRotationZ r) {
        return compare(r);
    }

    /** operator==: the same angle. */
    public boolean isEqual(HepRotationZ r) {
        return its_d == r.its_d;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepRotationZ r && isEqual(r);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(its_d);
    }

    @Override
    protected char axisName() {
        return 'Z';
    }
}
