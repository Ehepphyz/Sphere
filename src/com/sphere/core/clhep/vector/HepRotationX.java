package com.sphere.core.clhep.vector;

import com.sphere.core.clhep.units.SystemOfUnits;

import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepRotationX (Vector/RotationX.h, .icc, RotationX.cc): a rotation about the x axis: y turns towards z.
 * Products of two such rotations stay of this kind; with others they are HepRotations.
 */
public final class HepRotationX extends HepAxialRotation implements Comparable<HepRotationX> {

    /** The identity. */
    public HepRotationX() {
        super(0.0, 0.0, 1.0);
    }

    /** A rotation by delta: its angle brought into (-pi, pi], sine and cosine of delta as given. */
    public HepRotationX(double ddelta) {
        super(proper(ddelta), sin(ddelta), cos(ddelta));
    }

    public HepRotationX(HepRotationX orig) {
        super(orig.its_d, orig.its_s, orig.its_c);
    }

    HepRotationX(double dd, double ss, double cc) {
        super(dd, ss, cc);
    }

    public HepRotationX copy() {
        return new HepRotationX(this);
    }

    public HepRotationX assign(HepRotationX orig) {
        its_d = orig.its_d;
        its_s = orig.its_s;
        its_c = orig.its_c;
        return this;
    }

    public HepRotationX set(double ddelta) {
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
        return 1.0;
    }

    @Override
    public double xy() {
        return 0.0;
    }

    @Override
    public double xz() {
        return 0.0;
    }

    @Override
    public double yx() {
        return 0.0;
    }

    @Override
    public double yy() {
        return its_c;
    }

    @Override
    public double yz() {
        return -its_s;
    }

    @Override
    public double zx() {
        return 0.0;
    }

    @Override
    public double zy() {
        return its_s;
    }

    @Override
    public double zz() {
        return its_c;
    }

    @Override
    public Hep3Vector colX() {
        return new Hep3Vector(1.0, 0.0, 0.0);
    }

    @Override
    public Hep3Vector colY() {
        return new Hep3Vector(0.0, its_c, its_s);
    }

    @Override
    public Hep3Vector colZ() {
        return new Hep3Vector(0.0, -its_s, its_c);
    }

    @Override
    public Hep3Vector rowX() {
        return new Hep3Vector(1.0, 0.0, 0.0);
    }

    @Override
    public Hep3Vector rowY() {
        return new Hep3Vector(0.0, its_c, -its_s);
    }

    @Override
    public Hep3Vector rowZ() {
        return new Hep3Vector(0.0, its_s, its_c);
    }

    @Override
    public Hep3Vector axis() {
        return new Hep3Vector(1, 0, 0);
    }

    @Override
    public double phi() {
        return (its_d > 0) && (its_d < SystemOfUnits.pi) ? SystemOfUnits.pi : 0.0;
    }

    @Override
    public double theta() {
        return fabs(its_d);
    }

    @Override
    public double psi() {
        return (its_d > 0) && (its_d < SystemOfUnits.pi) ? SystemOfUnits.pi : 0.0;
    }

    @Override
    public Hep3Vector apply(Hep3Vector p) {
        final double x = p.x();
        final double y = p.y();
        final double z = p.z();
        return new Hep3Vector(x, y * its_c - z * its_s, z * its_c + y * its_s);
    }

    @Override
    public double distance2(HepRotation r) {
        final double sum = r.xx() + yy() * r.yy() + yz() * r.yz() + zy() * r.zy() + zz() * r.zz();
        final double answer = 3.0 - sum;
        return (answer >= 0) ? answer : 0;
    }

    public double distance2(HepRotationX r) {
        return distance2Same(r);
    }

    public double howNear(HepRotationX r) {
        return sqrt(distance2(r));
    }

    public boolean isNear(HepRotationX r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotationX r, double epsilon) {
        return distance2(r) <= epsilon * epsilon;
    }

    /** this * r: still a rotation about the same axis. */
    public HepRotationX times(HepRotationX r) {
        return new HepRotationX(proper(its_d + r.its_d), its_s * r.its_c + its_c * r.its_s,
            its_c * r.its_c - its_s * r.its_s);
    }

    /** this * r as a HepRotation. */
    public HepRotation times(HepRotation r) {
        return HepRotation.times(this, r);
    }

    public HepRotationX timesAssign(HepRotationX m1) {
        return assign(times(m1));
    }

    public HepRotationX transform(HepRotationX m1) {
        return assign(m1.times(this));
    }

    public HepRotationX inverse() {
        return new HepRotationX(proper(-its_d), -its_s, its_c);
    }

    public static HepRotationX inverseOf(HepRotationX r) {
        return r.inverse();
    }

    public HepRotationX invert() {
        return assign(inverse());
    }

    public int compare(HepRotationX r) {
        if (its_d > r.its_d) return 1;
        else if (its_d < r.its_d) return -1;
        else return 0;
    }

    @Override
    public int compareTo(HepRotationX r) {
        return compare(r);
    }

    /** operator==: the same angle. */
    public boolean isEqual(HepRotationX r) {
        return its_d == r.its_d;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepRotationX r && isEqual(r);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(its_d);
    }

    @Override
    protected char axisName() {
        return 'X';
    }
}
