package com.sphere.core.clhep.vector;

import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.acos;
import static com.sphere.core.clhep.CMath.atan2;
import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.floor;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * What CLHEP's HepRotationX, HepRotationY and HepRotationZ share (Vector/RotationX.h and its two
 * siblings): a rotation about a fixed axis, kept as its angle in (-pi, pi] with its sine and cosine.
 * Each class gives its matrix elements and Euler angles; the rest, written once in C++ three times,
 * is here.
 */
public abstract class HepAxialRotation {

    protected double its_d;
    protected double its_s;
    protected double its_c;

    protected HepAxialRotation(double d, double s, double c) {
        its_d = d;
        its_s = s;
        its_c = c;
    }

    /** The angle brought into (-pi, pi]. */
    protected static double proper(double ddelta) {
        if (fabs(ddelta) < SystemOfUnits.pi) {
            return ddelta;
        } else {
            final double x = ddelta / (SystemOfUnits.twopi);
            return (SystemOfUnits.twopi) * (x + floor(.5 - x));
        }
    }

    static double safe_acos(double x) {
        if (Math.abs(x) <= 1.0) return acos(x);
        return (x > 0) ? 0 : SystemOfUnits.pi;
    }

    public abstract double xx();

    public abstract double xy();

    public abstract double xz();

    public abstract double yx();

    public abstract double yy();

    public abstract double yz();

    public abstract double zx();

    public abstract double zy();

    public abstract double zz();

    public abstract Hep3Vector colX();

    public abstract Hep3Vector colY();

    public abstract Hep3Vector colZ();

    public abstract Hep3Vector rowX();

    public abstract Hep3Vector rowY();

    public abstract Hep3Vector rowZ();

    public abstract double phi();

    public abstract double theta();

    public abstract double psi();

    public abstract Hep3Vector axis();

    /** operator(): the vector rotated. */
    public abstract Hep3Vector apply(Hep3Vector p);

    public abstract double distance2(HepRotation r);

    /** The axis letter of print(). */
    protected abstract char axisName();

    public HepRep3x3 rep3x3() {
        return new HepRep3x3(xx(), xy(), xz(), yx(), yy(), yz(), zx(), zy(), zz());
    }

    public HepRep4x4 rep4x4() {
        return new HepRep4x4(xx(), xy(), xz(), 0.0, yx(), yy(), yz(), 0.0, zx(), zy(), zz(), 0.0, 0.0, 0.0, 0.0, 1.0);
    }

    public double getPhi() {
        return phi();
    }

    public double getTheta() {
        return theta();
    }

    public double getPsi() {
        return psi();
    }

    public HepEulerAngles eulerAngles() {
        return new HepEulerAngles(phi(), theta(), psi());
    }

    public double getDelta() {
        return its_d;
    }

    public double delta() {
        return its_d;
    }

    public Hep3Vector getAxis() {
        return axis();
    }

    public HepAxisAngle axisAngle() {
        return new HepAxisAngle(axis(), delta());
    }

    /** getAngleAxis(delta, axis): the axis into the vector given, the angle returned. */
    public double getAngleAxis(Hep3Vector aaxis) {
        aaxis.assign(getAxis());
        return its_d;
    }

    public double phiX() {
        return (yx() == 0.0 && xx() == 0.0) ? 0.0 : atan2(yx(), xx());
    }

    public double phiY() {
        return (yy() == 0.0 && xy() == 0.0) ? 0.0 : atan2(yy(), xy());
    }

    public double phiZ() {
        return (yz() == 0.0 && xz() == 0.0) ? 0.0 : atan2(yz(), xz());
    }

    public double thetaX() {
        return safe_acos(zx());
    }

    public double thetaY() {
        return safe_acos(zy());
    }

    public double thetaZ() {
        return safe_acos(zz());
    }

    public HepLorentzVector col1() {
        return new HepLorentzVector(colX(), 0);
    }

    public HepLorentzVector col2() {
        return new HepLorentzVector(colY(), 0);
    }

    public HepLorentzVector col3() {
        return new HepLorentzVector(colZ(), 0);
    }

    public HepLorentzVector col4() {
        return new HepLorentzVector(0, 0, 0, 1);
    }

    public HepLorentzVector row1() {
        return new HepLorentzVector(rowX(), 0);
    }

    public HepLorentzVector row2() {
        return new HepLorentzVector(rowY(), 0);
    }

    public HepLorentzVector row3() {
        return new HepLorentzVector(rowZ(), 0);
    }

    public HepLorentzVector row4() {
        return new HepLorentzVector(0, 0, 0, 1);
    }

    public double xt() {
        return 0.0;
    }

    public double yt() {
        return 0.0;
    }

    public double zt() {
        return 0.0;
    }

    public double tx() {
        return 0.0;
    }

    public double ty() {
        return 0.0;
    }

    public double tz() {
        return 0.0;
    }

    public double tt() {
        return 1.0;
    }

    public void decompose(HepAxisAngle rotation, Hep3Vector boost) {
        boost.set(0, 0, 0);
        rotation.assign(axisAngle());
    }

    public void decompose(Hep3Vector boost, HepAxisAngle rotation) {
        boost.set(0, 0, 0);
        rotation.assign(axisAngle());
    }

    public void decompose(HepRotation rotation, HepBoost boost) {
        boost.set(0, 0, 0);
        rotation.assign(new HepRotation(this));
    }

    public void decompose(HepBoost boost, HepRotation rotation) {
        boost.set(0, 0, 0);
        rotation.assign(new HepRotation(this));
    }

    public boolean isIdentity() {
        return its_d == 0;
    }

    public void rectify() {
        its_d = proper(its_d);
        its_s = sin(its_d);
        its_c = cos(its_d);
    }

    /** The distance to a rotation about the same axis: 2 (1 - cos of the angle between them). */
    protected double distance2Same(HepAxialRotation r) {
        final double answer = 2.0 * (1.0 - (its_s * r.its_s + its_c * r.its_c));
        return (answer >= 0) ? answer : 0;
    }

    public double distance2(HepLorentzRotation lt) {
        final HepAxisAngle a = new HepAxisAngle();
        final Hep3Vector b = new Hep3Vector();
        lt.decompose(b, a);
        final double bet = b.beta();
        final double bet2 = bet * bet;
        final HepRotation r = new HepRotation(a);
        return bet2 / (1 - bet2) + distance2(r);
    }

    public double distance2(HepBoost lt) {
        return distance2(new HepLorentzRotation(lt));
    }

    public double howNear(HepRotation r) {
        return sqrt(distance2(r));
    }

    public double howNear(HepBoost b) {
        return sqrt(distance2(b));
    }

    public double howNear(HepLorentzRotation lt) {
        return sqrt(distance2(lt));
    }

    public boolean isNear(HepRotation r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotation r, double epsilon) {
        return distance2(r) <= epsilon * epsilon;
    }

    public boolean isNear(HepBoost lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoost lt, double epsilon) {
        return distance2(lt) <= epsilon * epsilon;
    }

    public boolean isNear(HepLorentzRotation lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepLorentzRotation lt, double epsilon) {
        return distance2(lt) <= epsilon * epsilon;
    }

    public double norm2() {
        return 2.0 - 2.0 * its_c;
    }

    public Hep3Vector times(Hep3Vector p) {
        return apply(p);
    }

    /** operator() on a four-vector: its space part rotated, its time kept. */
    public HepLorentzVector apply(HepLorentzVector w) {
        return new HepLorentzVector(apply(w.vect()), w.t());
    }

    public HepLorentzVector times(HepLorentzVector p) {
        return apply(p);
    }

    public static double getTolerance() {
        return Hep4RotationInterface.tolerance;
    }

    public static double setTolerance(double tol) {
        return Hep4RotationInterface.setTolerance(tol);
    }

    /** "\nRotation about X (d) [cos d = c sin d = s]\n". */
    public COStream print(COStream os) {
        return os.put("\nRotation about ").put(axisName()).put(" (").put(its_d).put(") [cos d = ").put(its_c)
            .put(" sin d = ").put(its_s).put("]\n");
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
