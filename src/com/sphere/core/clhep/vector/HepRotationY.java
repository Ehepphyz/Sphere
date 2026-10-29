package com.sphere.core.clhep.vector;

import com.sphere.core.clhep.units.SystemOfUnits;

import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepRotationY (Vector/RotationY.h, .icc, RotationY.cc): a rotation about the y axis: z turns towards x.
 * Products of two such rotations stay of this kind; with others they are HepRotations.
 */
public final class HepRotationY extends HepAxialRotation implements Comparable<HepRotationY> {

    /** The identity. */
    public HepRotationY() {
        super(0.0, 0.0, 1.0);
    }

    /** A rotation by delta: its angle brought into (-pi, pi], sine and cosine of delta as given. */
    public HepRotationY(double ddelta) {
        super(proper(ddelta), sin(ddelta), cos(ddelta));
    }

    public HepRotationY(HepRotationY orig) {
        super(orig.its_d, orig.its_s, orig.its_c);
    }

    HepRotationY(double dd, double ss, double cc) {
        super(dd, ss, cc);
    }

    public HepRotationY copy() {
        return new HepRotationY(this);
    }

    public HepRotationY assign(HepRotationY orig) {
        its_d = orig.its_d;
        its_s = orig.its_s;
        its_c = orig.its_c;
        return this;
    }

    public HepRotationY set(double ddelta) {
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
        return 0.0;
    }

    @Override
    public double xz() {
        return its_s;
    }

    @Override
    public double yx() {
        return 0.0;
    }

    @Override
    public double yy() {
        return 1.0;
    }

    @Override
    public double yz() {
        return 0.0;
    }

    @Override
    public double zx() {
        return -its_s;
    }

    @Override
    public double zy() {
        return 0.0;
    }

    @Override
    public double zz() {
        return its_c;
    }

    @Override
    public Hep3Vector colX() {
        return new Hep3Vector(its_c, 0.0, -its_s);
    }

    @Override
    public Hep3Vector colY() {
        return new Hep3Vector(0.0, 1.0, 0.0);
    }

    @Override
    public Hep3Vector colZ() {
        return new Hep3Vector(its_s, 0.0, its_c);
    }

    @Override
    public Hep3Vector rowX() {
        return new Hep3Vector(its_c, 0.0, its_s);
    }

    @Override
    public Hep3Vector rowY() {
        return new Hep3Vector(0.0, 1.0, 0.0);
    }

    @Override
    public Hep3Vector rowZ() {
        return new Hep3Vector(-its_s, 0.0, its_c);
    }

    @Override
    public Hep3Vector axis() {
        return new Hep3Vector(0, 1, 0);
    }

    @Override
    public double phi() {
        return its_d == 0 ? 0 : (its_d < 0) || (its_d == SystemOfUnits.pi) ? +SystemOfUnits.halfpi : -SystemOfUnits.halfpi;
    }

    @Override
    public double theta() {
        return fabs(its_d);
    }

    @Override
    public double psi() {
        return its_d == 0 ? 0 : (its_d < 0) || (its_d == SystemOfUnits.pi) ? -SystemOfUnits.halfpi : +SystemOfUnits.halfpi;
    }

    @Override
    public Hep3Vector apply(Hep3Vector p) {
        final double x = p.x();
        final double y = p.y();
        final double z = p.z();
        return new Hep3Vector(x * its_c + z * its_s, y, z * its_c - x * its_s);
    }

    @Override
    public double distance2(HepRotation r) {
        final double sum = xx() * r.xx() + xz() * r.xz() + r.yy() + zx() * r.zx() + zz() * r.zz();
        final double answer = 3.0 - sum;
        return (answer >= 0) ? answer : 0;
    }

    public double distance2(HepRotationY r) {
        return distance2Same(r);
    }

    public double howNear(HepRotationY r) {
        return sqrt(distance2(r));
    }

    public boolean isNear(HepRotationY r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotationY r, double epsilon) {
        return distance2(r) <= epsilon * epsilon;
    }

    /** this * r: still a rotation about the same axis. */
    public HepRotationY times(HepRotationY r) {
        return new HepRotationY(proper(its_d + r.its_d), its_s * r.its_c + its_c * r.its_s,
            its_c * r.its_c - its_s * r.its_s);
    }

    /** this * r as a HepRotation. */
    public HepRotation times(HepRotation r) {
        return HepRotation.times(this, r);
    }

    public HepRotationY timesAssign(HepRotationY m1) {
        return assign(times(m1));
    }

    public HepRotationY transform(HepRotationY m1) {
        return assign(m1.times(this));
    }

    public HepRotationY inverse() {
        return new HepRotationY(proper(-its_d), -its_s, its_c);
    }

    public static HepRotationY inverseOf(HepRotationY r) {
        return r.inverse();
    }

    public HepRotationY invert() {
        return assign(inverse());
    }

    public int compare(HepRotationY r) {
        if (its_d > r.its_d) return 1;
        else if (its_d < r.its_d) return -1;
        else return 0;
    }

    @Override
    public int compareTo(HepRotationY r) {
        return compare(r);
    }

    /** operator==: the same angle. */
    public boolean isEqual(HepRotationY r) {
        return its_d == r.its_d;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepRotationY r && isEqual(r);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(its_d);
    }

    @Override
    protected char axisName() {
        return 'Y';
    }
}
