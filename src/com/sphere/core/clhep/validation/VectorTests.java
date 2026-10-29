package com.sphere.core.clhep.validation;

import static com.sphere.core.clhep.validation.ClhepTests.check;
import static com.sphere.core.clhep.validation.ClhepTests.say;
import static com.sphere.core.clhep.units.SystemOfUnits.deg;
import static com.sphere.core.clhep.units.SystemOfUnits.halfpi;
import static com.sphere.core.clhep.units.SystemOfUnits.pi;
import static com.sphere.core.clhep.units.SystemOfUnits.twopi;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.validation.ClhepTests.Exit;
import com.sphere.core.clhep.validation.ClhepTests.Io;
import com.sphere.core.clhep.vector.Hep2Vector;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepLorentzRotation;
import com.sphere.core.clhep.vector.HepLorentzVector;
import com.sphere.core.clhep.vector.HepRep3x3;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.COStream;

/** CLHEP's Vector tests (Vector/test): ThreeVector, LorentzVector, Rotation, the rotation axis, subscripts. */
final class VectorTests {

    private VectorTests() {
    }

    private static final double DEPS = 1.0e-14;
    private static final double FEPS = 1.0e-6;

    private static boolean approx(double a, double b, double eps) {
        return Math.abs(a - b) < eps;
    }

    /** if (!ok) exit(code); */
    private static void exitUnless(boolean ok, int code) {
        if (!ok) throw new Exit(code);
    }

    private static double sqr(double x) {
        return x * x;
    }

    /* ---- testThreeVector ---- */

    private static boolean test(Hep3Vector p, double x, double y, double z, double eps) {
        return approx(p.x(), x, eps) && approx(p.y(), y, eps) && approx(p.z(), z, eps);
    }

    private static boolean test2(Hep2Vector p, double x, double y, double eps) {
        return approx(p.x(), x, eps) && approx(p.y(), y, eps);
    }

    static int testThreeVector(Io io) {
        // test constructors:
        final Hep3Vector d0 = new Hep3Vector();
        exitUnless(test(d0, 0.0, 0.0, 0.0, DEPS), 1);
        final Hep3Vector f0 = new Hep3Vector();
        exitUnless(test(f0, 0.0, 0.0, 0.0, FEPS), 1);
        final Hep3Vector d1 = new Hep3Vector(1.0);
        exitUnless(test(d1, 1.0, 0.0, 0.0, DEPS), 1);
        final Hep3Vector f1 = new Hep3Vector(1.0);
        exitUnless(test(f1, 1.0, 0.0, 0.0, FEPS), 1);
        final Hep3Vector d2 = new Hep3Vector(1.0, 1.0);
        exitUnless(test(d2, 1.0, 1.0, 0.0, DEPS), 1);
        final Hep3Vector f2 = new Hep3Vector(1.0, 1.0);
        exitUnless(test(f2, 1.0, 1.0, 0.0, FEPS), 1);
        final Hep3Vector d3 = new Hep3Vector(1.0, 1.0, 1.0);
        exitUnless(test(d3, 1.0, 1.0, 1.0, DEPS), 1);
        final Hep3Vector f3 = new Hep3Vector(1.0, 1.0, 1.0);
        exitUnless(test(f3, 1.0, 1.0, 1.0, FEPS), 1);
        final Hep3Vector d4 = new Hep3Vector(f3);
        exitUnless(test(d4, 1.0, 1.0, 1.0, DEPS), 1);
        final Hep3Vector f4 = new Hep3Vector(d3);
        exitUnless(test(f4, 1.0, 1.0, 1.0, FEPS), 1);
        final Hep2Vector t3 = new Hep2Vector(d4);
        exitUnless(test2(t3, 1.0, 1.0, DEPS), 1);
        // test input/output from a stream
        Hep3Vector.read(io.cin(), d0);
        exitUnless(test(d0, 1.1, 2.2, 3.3, DEPS), 1);
        Hep3Vector.read(io.cin(), f0);
        exitUnless(test(f0, 3.0, 2.0, 1.0, FEPS), 1);
        say(io.cout, d0, "\n");
        say(io.cout, f0, "\n");
        // test assignment:
        d4.assign(d1);
        exitUnless(test(d4, 1.0, 0.0, 0.0, DEPS), 2);
        f4.assign(f1);
        exitUnless(test(f4, 1.0, 0.0, 0.0, FEPS), 2);
        d4.assign(f1);
        exitUnless(test(d4, 1.0, 0.0, 0.0, FEPS), 2);
        f4.assign(d1);
        exitUnless(test(f4, 1.0, 0.0, 0.0, FEPS), 2);
        // test addition:
        d4.assign(d1.plus(d2));
        exitUnless(test(d4, 2.0, 1.0, 0.0, DEPS), 3);
        d4.assign(f1.plus(d2));
        exitUnless(test(d4, 2.0, 1.0, 0.0, FEPS), 3);
        d4.assign(d1.plus(f2));
        exitUnless(test(d4, 2.0, 1.0, 0.0, FEPS), 3);
        d4.assign(f1.plus(f2));
        exitUnless(test(d4, 2.0, 1.0, 0.0, FEPS), 3);
        d4.plusAssign(d3);
        exitUnless(test(d4, 3.0, 2.0, 1.0, FEPS), 3);
        d4.plusAssign(f3);
        exitUnless(test(d4, 4.0, 3.0, 2.0, FEPS), 3);
        f4.assign(d1.plus(d2));
        exitUnless(test(f4, 2.0, 1.0, 0.0, FEPS), 3);
        f4.assign(f1.plus(d2));
        exitUnless(test(f4, 2.0, 1.0, 0.0, FEPS), 3);
        f4.assign(d1.plus(f2));
        exitUnless(test(f4, 2.0, 1.0, 0.0, FEPS), 3);
        f4.assign(f1.plus(f2));
        exitUnless(test(f4, 2.0, 1.0, 0.0, FEPS), 3);
        f4.plusAssign(d3);
        exitUnless(test(f4, 3.0, 2.0, 1.0, FEPS), 3);
        f4.plusAssign(f3);
        exitUnless(test(f4, 4.0, 3.0, 2.0, FEPS), 3);
        // test subtraction
        d4.minusAssign(d3);
        exitUnless(test(d4, 3.0, 2.0, 1.0, FEPS), 4);
        d4.minusAssign(f3);
        exitUnless(test(d4, 2.0, 1.0, 0.0, FEPS), 4);
        f4.minusAssign(d3);
        exitUnless(test(f4, 3.0, 2.0, 1.0, FEPS), 4);
        f4.minusAssign(f3);
        exitUnless(test(f4, 2.0, 1.0, 0.0, FEPS), 4);
        d4.assign(d1.minus(d2));
        exitUnless(test(d4, 0.0, -1.0, 0.0, DEPS), 4);
        d4.assign(f1.minus(d2));
        exitUnless(test(d4, 0.0, -1.0, 0.0, FEPS), 4);
        d4.assign(d1.minus(f2));
        exitUnless(test(d4, 0.0, -1.0, 0.0, FEPS), 4);
        d4.assign(f1.minus(f2));
        exitUnless(test(d4, 0.0, -1.0, 0.0, FEPS), 4);
        f4.assign(d1.minus(d2));
        exitUnless(test(f4, 0.0, -1.0, 0.0, FEPS), 4);
        f4.assign(f1.minus(d2));
        exitUnless(test(f4, 0.0, -1.0, 0.0, FEPS), 4);
        f4.assign(d1.minus(f2));
        exitUnless(test(f4, 0.0, -1.0, 0.0, FEPS), 4);
        f4.assign(f1.minus(f2));
        exitUnless(test(f4, 0.0, -1.0, 0.0, FEPS), 4);
        // test unary minus:
        exitUnless(test(d3.negate(), -1.0, -1.0, -1.0, DEPS), 5);
        exitUnless(test(f3.negate(), -1.0, -1.0, -1.0, FEPS), 5);
        exitUnless(test(d1.negate(), -1.0, 0.0, 0.0, DEPS), 5);
        exitUnless(test(f1.negate(), -1.0, 0.0, 0.0, FEPS), 5);
        // test scaling (a*v and v*a are the same products):
        exitUnless(test(d3.times(2.0), 2.0, 2.0, 2.0, DEPS), 6);
        exitUnless(test(d3.times(2.0), 2.0, 2.0, 2.0, DEPS), 6);
        exitUnless(test(d1.times(2.0), 2.0, 0.0, 0.0, DEPS), 6);
        exitUnless(test(d1.times(2.0), 2.0, 0.0, 0.0, DEPS), 6);
        exitUnless(test(f3.times(2.0f), 2.0, 2.0, 2.0, FEPS), 6);
        exitUnless(test(f3.times(2.0f), 2.0, 2.0, 2.0, FEPS), 6);
        exitUnless(test(f1.times(2.0f), 2.0, 0.0, 0.0, FEPS), 6);
        exitUnless(test(f1.times(2.0f), 2.0, 0.0, 0.0, FEPS), 6);
        exitUnless(test(d4.timesAssign(2.0), 0.0, -2.0, 0.0, FEPS), 6);
        exitUnless(test(f4.timesAssign(2.0), 0.0, -2.0, 0.0, FEPS), 6);
        // testing scalar and vector product:
        exitUnless(approx(d4.dot(d1), 0.0, DEPS), 7);
        exitUnless(approx(d4.dot(f1), 0.0, FEPS), 7);
        exitUnless(approx(f4.dot(d1), 0.0, FEPS), 7);
        exitUnless(approx(f4.dot(f1), 0.0, FEPS), 7);
        exitUnless(approx(d4.dot(d1), 0.0, DEPS), 7);
        exitUnless(approx(d4.dot(f1), 0.0, FEPS), 7);
        exitUnless(approx(f4.dot(d1), 0.0, FEPS), 7);
        exitUnless(approx(f4.dot(f1), 0.0, FEPS), 7);
        exitUnless(approx(d4.dot(d2), -2.0, DEPS), 7);
        exitUnless(approx(d4.dot(f2), -2.0, FEPS), 7);
        exitUnless(approx(f4.dot(d2), -2.0, FEPS), 7);
        exitUnless(approx(f4.dot(f2), -2.0, FEPS), 7);
        exitUnless(approx(d4.dot(d2), -2.0, DEPS), 7);
        exitUnless(approx(d4.dot(f2), -2.0, FEPS), 7);
        exitUnless(approx(f4.dot(d2), -2.0, FEPS), 7);
        exitUnless(approx(f4.dot(f2), -2.0, FEPS), 7);
        d4.assign(d1.cross(d2));
        exitUnless(test(d4, 0.0, 0.0, 1.0, DEPS), 7);
        d4.assign(d2.cross(d1));
        exitUnless(test(d4, 0.0, 0.0, -1.0, DEPS), 7);
        f4.assign(f1.cross(d2));
        exitUnless(test(f4, 0.0, 0.0, 1.0, FEPS), 7);
        f4.assign(d2.cross(f1));
        exitUnless(test(f4, 0.0, 0.0, -1.0, FEPS), 7);
        // testing ptot and pt:
        d4.assign(d1.plus(f2).plus(d3));
        f4.assign(d1.plus(f2).plus(d3));
        exitUnless(approx(d4.mag2(), 14.0, FEPS), 8);
        exitUnless(approx(d4.mag(), Math.sqrt(14.0), FEPS), 8);
        exitUnless(approx(d4.perp2(), 13.0, FEPS), 8);
        exitUnless(approx(d4.perp(), Math.sqrt(13.0), FEPS), 8);
        exitUnless(approx(f4.mag2(), 14.0, FEPS), 8);
        exitUnless(approx(f4.mag(), Math.sqrt(14.0), FEPS), 8);
        exitUnless(approx(f4.perp2(), 13.0, FEPS), 8);
        exitUnless(approx(f4.perp(), Math.sqrt(13.0), FEPS), 8);
        // testing angles:
        d4.assign(d2.minus(d1.times(2.0)));
        f4.assign(d2.minus(f1.times(2.0f)));
        exitUnless(approx(d1.phi(), 0.0, DEPS), 9);
        exitUnless(approx(d1.theta(), halfpi, DEPS), 9);
        exitUnless(approx(d1.cosTheta(), 0.0, DEPS), 9);
        exitUnless(approx(d2.phi(), halfpi * 0.5, DEPS), 9);
        exitUnless(approx(d2.theta(), halfpi, DEPS), 9);
        exitUnless(approx(d2.cosTheta(), 0.0, DEPS), 9);
        exitUnless(approx(d2.negate().phi(), -3.0 * halfpi * 0.5, DEPS), 9);
        exitUnless(approx(d4.phi(), 3.0 * halfpi * 0.5, DEPS), 9);
        exitUnless(approx(f1.phi(), 0.0, FEPS), 9);
        exitUnless(approx(f1.theta(), halfpi, FEPS), 9);
        exitUnless(approx(f1.cosTheta(), 0.0, FEPS), 9);
        exitUnless(approx(f2.phi(), halfpi * 0.5, FEPS), 9);
        exitUnless(approx(f2.theta(), halfpi, FEPS), 9);
        exitUnless(approx(f2.cosTheta(), 0.0, FEPS), 9);
        exitUnless(approx(f2.negate().phi(), -3.0 * halfpi * 0.5, FEPS), 9);
        exitUnless(approx(f4.phi(), 3.0 * halfpi * 0.5, FEPS), 9);
        d4.assign(d3.minus(d1));
        exitUnless(approx(d4.theta(), halfpi * 0.5, DEPS), 9);
        exitUnless(approx(d4.negate().theta(), 3.0 * halfpi * 0.5, DEPS), 9);
        exitUnless(approx(d4.negate().cosTheta(), -Math.sqrt(0.5), DEPS), 9);
        d4.assign(d3.minus(d2));
        exitUnless(approx(d4.theta(), 0.0, DEPS), 9);
        exitUnless(approx(d4.cosTheta(), 1.0, DEPS), 9);
        exitUnless(approx(d4.negate().theta(), pi, DEPS), 9);
        exitUnless(approx(d4.negate().cosTheta(), -1.0, DEPS), 9);
        f4.assign(d3.minus(d1));
        exitUnless(approx(f4.theta(), halfpi * 0.5, FEPS), 9);
        exitUnless(approx(f4.negate().theta(), 3.0 * halfpi * 0.5, FEPS), 9);
        exitUnless(approx(f4.negate().cosTheta(), -Math.sqrt(0.5), FEPS), 9);
        f4.assign(d3.minus(d2));
        exitUnless(approx(f4.theta(), 0.0, FEPS), 9);
        exitUnless(approx(f4.cosTheta(), 1.0, FEPS), 9);
        exitUnless(approx(f4.negate().theta(), pi, FEPS), 9);
        exitUnless(approx(f4.negate().cosTheta(), -1.0, FEPS), 9);
        d4.assign(d2.minus(d1.times(2.0)));
        exitUnless(approx(d4.angle(d2), halfpi, DEPS), 9);
        f4.assign(d2.minus(d1.times(2.0)));
        exitUnless(approx(f4.angle(f2), halfpi, FEPS), 9);
        // testing rotations
        d4.assign(d1);
        d4.rotateZ(halfpi);
        exitUnless(test(d4, 0.0, 1.0, 0.0, DEPS), 10);
        d4.rotateY(25.3);
        exitUnless(test(d4, 0.0, 1.0, 0.0, DEPS), 10);
        d4.rotateZ(halfpi);
        exitUnless(test(d4, -1.0, 0.0, 0.0, DEPS), 10);
        d4.rotateY(halfpi);
        exitUnless(test(d4, 0.0, 0.0, 1.0, DEPS), 10);
        d4.rotateZ(2.6);
        exitUnless(test(d4, 0.0, 0.0, 1.0, DEPS), 10);
        d4.rotateY(pi * 0.25);
        exitUnless(test(d4, Math.sqrt(0.5), 0.0, Math.sqrt(0.5), DEPS), 10);
        f4.assign(f1);
        f4.rotateZ(halfpi);
        exitUnless(test(f4, 0.0, 1.0, 0.0, FEPS), 10);
        f4.rotateY(25.3);
        exitUnless(test(f4, 0.0, 1.0, 0.0, FEPS), 10);
        f4.rotateZ(halfpi);
        exitUnless(test(f4, -1.0, 0.0, 0.0, FEPS), 10);
        f4.rotateY(halfpi);
        exitUnless(test(f4, 0.0, 0.0, 1.0, FEPS), 10);
        f4.rotateZ(2.6);
        exitUnless(test(f4, 0.0, 0.0, 1.0, FEPS), 10);
        f4.rotateY(pi * 0.25);
        exitUnless(test(f4, Math.sqrt(0.5), 0.0, Math.sqrt(0.5), FEPS), 10);
        d4.assign(d1);
        d4.rotate(d4.angle(d3), d4.cross(d3));
        d4.timesAssign(d3.mag());
        exitUnless(test(d4, 1.0, 1.0, 1.0, DEPS), 10);
        d4.assign(d1);
        d4.rotate(0.23, d4.cross(d3));
        exitUnless(approx(d4.angle(d1), 0.23, DEPS), 10);
        f4.assign(d1);
        f4.rotate(f4.angle(d3), f4.cross(d3));
        f4.timesAssign(f3.mag());
        exitUnless(test(f4, 1.0, 1.0, 1.0, FEPS), 10);
        f4.assign(f1);
        f4.rotate(0.23, f4.cross(d3));
        exitUnless(approx(f4.angle(f1), 0.23, FEPS), 10);
        exitUnless(approx(f4.angle(d3), f1.angle(d3) - 0.23, FEPS), 10);
        // test rotation maticies:
        d4.assign(d1);
        final HepRotation r0 = new HepRotation();
        final HepRotation r1 = new HepRotation();
        final HepRotation r2 = new HepRotation();
        final HepRotation r3 = new HepRotation();
        final HepRotation r4 = new HepRotation();
        final HepRotation r5 = new HepRotation();
        r1.rotateZ(halfpi);
        r2.rotateY(halfpi);
        r4.rotate(d4.angle(d3), d4.cross(d3));
        r5.rotate(0.23, d4.cross(d3));
        d4.assign(r4.inverse().times(d3));
        exitUnless(test(d4, d3.mag(), 0.0, 0.0, DEPS), 11);
        d4.assign(r5.times(d3));
        exitUnless(approx(d1.angle(d4), d1.angle(d3) + 0.23, DEPS), 11);
        f4.assign(r4.inverse().times(f3));
        exitUnless(test(f4, f3.mag(), 0.0, 0.0, FEPS), 11);
        f4.assign(r5.times(d3));
        exitUnless(approx(d1.angle(f4), f1.angle(f3) + 0.23, FEPS), 11);
        r5.assign(r2.times(r1).times(r3.inverse()).times(r0).times(r0.inverse()));
        d4.assign(d3);
        d4.timesAssign(r3.inverse());
        d4.timesAssign(r1);
        d4.timesAssign(r2);
        exitUnless(test(d4, 1.0, 1.0, 1.0, DEPS), 11);
        r5.invert();
        d4.assign(r5.times(d4));
        exitUnless(test(d4, 1.0, 1.0, 1.0, DEPS), 11);
        d2.assign(new Hep3Vector(1.0, -0.5, 2.1));
        d1.assign(d2);
        d3.assign(new Hep3Vector(-0.3, 1.1, 1.5));
        d4.assign(d3.unit());
        d4.timesAssign(d3.mag());
        exitUnless(test(d4, d3.x(), d3.y(), d3.z(), DEPS), 11);
        r0.rotate(0.10, d1.cross(d3));
        d1.timesAssign(r0);
        exitUnless(approx(d1.angle(d3), d2.angle(d3) - 0.1, DEPS), 12);
        exitUnless(approx(d1.angle(d2), 0.1, DEPS), 12);
        return 0;
    }

    /* ---- testLorentzVector ---- */

    private static boolean test(Io io, HepLorentzVector p, double x, double y, double z, double e, double eps) {
        final boolean t = approx(p.x(), x, eps) && approx(p.y(), y, eps) && approx(p.z(), z, eps) && approx(p.t(), e, eps);
        if (!t) say(io.cerr, p, "\n", x, "\t", y, "\t", z, "\t", e, "\n");
        return t;
    }

    private static boolean test(Io io, HepLorentzVector p, HepLorentzVector q, double eps) {
        final boolean t = approx(p.x(), q.x(), eps) && approx(p.y(), q.y(), eps) && approx(p.z(), q.z(), eps)
            && approx(p.t(), q.t(), eps);
        if (!t) say(io.cerr, p, "\n", q, "\n");
        return t;
    }

    /** conversion_test(Hep3Vector&, HepLorentzVector&): v3 is v4's own space part, through its conversion. */
    private static void conversionTest(Io io, Hep3Vector v3, HepLorentzVector v4) {
        v3.assign(new Hep3Vector(3., 2., 1.));
        check(io, v3.x() == v4.x() && v3.y() == v4.y() && v3.z() == v4.z(),
            "v3.x() == v4.x() && v3.y() == v4.y() && v3.z() == v4.z()", 55,
            "void conversion_test(CLHEP::Hep3Vector&, CLHEP::HepLorentzVector&)");
    }

    /** conversion_test(const Hep3Vector&, const HepLorentzVector&). */
    private static void conversionTestConst(Io io, Hep3Vector v3, HepLorentzVector v4) {
        check(io, v3.x() == v4.x() && v3.y() == v4.y() && v3.z() == v4.z(),
            "v3.x() == v4.x() && v3.y() == v4.y() && v3.z() == v4.z()", 59,
            "void conversion_test(const CLHEP::Hep3Vector&, const CLHEP::HepLorentzVector&)");
    }

    static int testLorentzVector(Io io) {
        final HepLorentzVector v4 = new HepLorentzVector(1., 2., 3., 4.);
        final HepLorentzVector v4const = new HepLorentzVector(1., 2., 3., 4);
        conversionTest(io, v4.spaceRef(), v4);
        conversionTestConst(io, v4const.spaceRef(), v4const);
        final Hep3Vector f3x = new Hep3Vector(1.0);
        final Hep3Vector f3y = new Hep3Vector(0.0, 1.0);
        final Hep3Vector f3z = new Hep3Vector(0.0, 0.0, 1.0);
        final Hep3Vector d30 = new Hep3Vector();
        final Hep3Vector d3x = new Hep3Vector(1.0);
        final Hep3Vector d3y = new Hep3Vector(0.0, 1.0);
        final Hep3Vector d3z = new Hep3Vector(0.0, 0.0, 1.0);
        // test constructors:
        final HepLorentzVector d0 = new HepLorentzVector();
        exitUnless(test(io, d0, 0.0, 0.0, 0.0, 0.0, DEPS), 1);
        final HepLorentzVector d1 = new HepLorentzVector(d3x, 1.0);
        exitUnless(test(io, d1, 1.0, 0.0, 0.0, 1.0, DEPS), 1);
        final HepLorentzVector d2 = new HepLorentzVector(d3x.plus(d3y), Math.sqrt(2.0));
        exitUnless(test(io, d2, 1.0, 1.0, 0.0, Math.sqrt(2.0), DEPS), 1);
        final HepLorentzVector d3 = new HepLorentzVector(d3z.plus(d2.vect()), Math.sqrt(3.0));
        exitUnless(test(io, d3, 1.0, 1.0, 1.0, Math.sqrt(3.0), DEPS), 1);
        final HepLorentzVector d4 = new HepLorentzVector(0.0, 0.0, 0.0, 1.0);
        exitUnless(test(io, d4, 0.0, 0.0, 0.0, 1.0, DEPS), 1);
        final HepLorentzVector d5 = new HepLorentzVector(f3x, f3x.mag());
        exitUnless(test(io, d5, d1, FEPS), 1);
        final HepLorentzVector d6 = new HepLorentzVector(d3x.plus(f3y), d3x.plus(f3y).mag());
        exitUnless(test(io, d6, d2, FEPS), 1);
        final HepLorentzVector d7 = new HepLorentzVector(f3x.plus(f3y).plus(f3z), f3x.plus(f3y).plus(f3z).mag());
        exitUnless(test(io, d7, d3, FEPS), 1);
        final HepLorentzVector f0 = new HepLorentzVector();
        exitUnless(test(io, f0, 0.0, 0.0, 0.0, 0.0, FEPS), 1);
        final HepLorentzVector f1 = new HepLorentzVector(f3x, 1.0);
        exitUnless(test(io, f1, 1.0, 0.0, 0.0, 1.0, FEPS), 1);
        final HepLorentzVector f2 = new HepLorentzVector(f3x.plus(f3y), Math.sqrt(2.0));
        exitUnless(test(io, f2, 1.0, 1.0, 0.0, Math.sqrt(2.0), FEPS), 1);
        final HepLorentzVector f3 = new HepLorentzVector(f3z.plus(f2.vect()), Math.sqrt(3.0));
        exitUnless(test(io, f3, 1.0, 1.0, 1.0, Math.sqrt(3.0), FEPS), 1);
        final HepLorentzVector f4 = new HepLorentzVector(0.0, 0.0, 0.0, 1.0);
        exitUnless(test(io, f4, 0.0, 0.0, 0.0, 1.0, FEPS), 1);
        final HepLorentzVector f5 = new HepLorentzVector(d3x, d3x.mag());
        exitUnless(test(io, f5, f1, FEPS), 1);
        final HepLorentzVector f6 = new HepLorentzVector(f3x.plus(d3y), f3x.plus(d3y).mag());
        exitUnless(test(io, f6, f2, FEPS), 1);
        final HepLorentzVector f7 = new HepLorentzVector(d3x.plus(d3y).plus(d3z), d3x.plus(d3y).plus(d3z).mag());
        exitUnless(test(io, f7, f3, FEPS), 1);
        final HepLorentzVector d8 = new HepLorentzVector(f7);
        exitUnless(test(io, d8, d7, FEPS), 1);
        final HepLorentzVector d9 = new HepLorentzVector(d7);
        exitUnless(test(io, d9, d7, DEPS), 1);
        final HepLorentzVector f8 = new HepLorentzVector(f7);
        exitUnless(test(io, f8, d7, FEPS), 1);
        final HepLorentzVector f9 = new HepLorentzVector(d7);
        exitUnless(test(io, f9, d7, FEPS), 1);
        final HepLorentzVector d10 = new HepLorentzVector(1.0, 1.0, 1.0, Math.sqrt(3.0));
        exitUnless(test(io, d10, d7, FEPS), 1);
        final HepLorentzVector f10 = new HepLorentzVector(1.0, 1.0, 1.0, Math.sqrt(3.0));
        exitUnless(test(io, f10, f7, FEPS), 1);
        final HepLorentzVector d11 = new HepLorentzVector(d3x.plus(d3y).plus(d3z), 1.0);
        exitUnless(test(io, d11, 1.0, 1.0, 1.0, 1.0, DEPS), 1);
        final HepLorentzVector f11 = new HepLorentzVector(d3x.plus(d3y).plus(d3z), 1.0);
        exitUnless(test(io, f11, 1.0, 1.0, 1.0, 1.0, FEPS), 1);
        // test input/output from a stream
        HepLorentzVector.read(io.cin(), d0);
        exitUnless(test(io, d0, 1.1, 2.2, 3.3, 4.4, DEPS), 1);
        HepLorentzVector.read(io.cin(), f0);
        exitUnless(test(io, f0, 4.0, 3.0, 2.0, 1.0, FEPS), 1);
        say(io.cout, d0, "\n");
        say(io.cout, f0, "\n");
        // testing assignment
        d6.assign(d7);
        exitUnless(test(io, d6, d7, DEPS), 2);
        d6.assign(f7);
        exitUnless(test(io, d6, d7, FEPS), 2);
        f6.assign(d7);
        exitUnless(test(io, f6, f7, FEPS), 2);
        f6.assign(f7);
        exitUnless(test(io, f6, f7, FEPS), 2);
        //testing addition and subtraction:
        d11.assign(d3.plus(d7).plus(f3));
        exitUnless(test(io, d11, 3.0, 3.0, 3.0, Math.sqrt(27.0), FEPS), 4);
        f11.assign(d3.plus(d7).plus(f3));
        exitUnless(test(io, f11, 3.0, 3.0, 3.0, Math.sqrt(27.0), FEPS), 4);
        d11.plusAssign(d3);
        exitUnless(test(io, d11, 4.0, 4.0, 4.0, Math.sqrt(48.0), FEPS), 4);
        f11.plusAssign(f3);
        exitUnless(test(io, f11, 4.0, 4.0, 4.0, Math.sqrt(48.0), FEPS), 4);
        d11.assign(d3.plus(d7).minus(f3));
        exitUnless(test(io, d11, 1.0, 1.0, 1.0, Math.sqrt(3.0), FEPS), 4);
        exitUnless(test(io, d11.negate(), -1.0, -1.0, -1.0, -Math.sqrt(3.0), FEPS), 4);
        f11.assign(d3.plus(f7).minus(d3));
        exitUnless(test(io, f11, 1.0, 1.0, 1.0, Math.sqrt(3.0), FEPS), 4);
        exitUnless(test(io, f11.negate(), -1.0, -1.0, -1.0, -Math.sqrt(3.0), FEPS), 4);
        d11.minusAssign(d3);
        exitUnless(test(io, d11, 0.0, 0.0, 0.0, 0.0, FEPS), 4);
        f11.minusAssign(f3);
        exitUnless(test(io, f11, 0.0, 0.0, 0.0, 0.0, FEPS), 4);
        d11.assign(new HepLorentzVector(1.0, 2.0, 3.0, 4.0));
        d11.timesAssign(2.);
        exitUnless(test(io, d11, 2.0, 4.0, 6.0, 8.0, DEPS), 4);
        d11.assign(new HepLorentzVector(1.0, 2.0, 3.0, 4.0).times(2.));
        exitUnless(test(io, d11, 2.0, 4.0, 6.0, 8.0, DEPS), 4);
        d11.assign(new HepLorentzVector(1.0, 2.0, 3.0, 4.0).times(2.));
        exitUnless(test(io, d11, 2.0, 4.0, 6.0, 8.0, DEPS), 4);
        // testing scalar products:
        exitUnless(approx(d1.dot(d2), Math.sqrt(2.0) - 1.0, DEPS), 5);
        exitUnless(approx(d3.dot(d7), 0.0, FEPS), 5);
        exitUnless(approx(d2.dot(f1), Math.sqrt(2.0) - 1.0, FEPS), 5);
        exitUnless(approx(f3.dot(d7), 0.0, FEPS), 5);
        // testing components:
        d11.assign(new HepLorentzVector(1.0, 1.0, 1.0, Math.sqrt(7.0)));
        exitUnless(approx(d11.mag2(), 4.0, DEPS), 6);
        exitUnless(approx(d11.mag(), 2.0, DEPS), 6);
        exitUnless(approx(new Hep3Vector(d11.vect()).mag2(), 3.0, DEPS), 6);
        exitUnless(approx(new Hep3Vector(d11.vect()).mag(), Math.sqrt(3.0), DEPS), 6);
        exitUnless(approx(d11.perp2(), 2.0, DEPS), 6);
        exitUnless(approx(d11.perp(), Math.sqrt(2.0), DEPS), 6);
        f11.assign(new HepLorentzVector(1.0, 1.0, 1.0, Math.sqrt(7.0)));
        exitUnless(approx(f11.mag2(), 4.0, FEPS), 6);
        exitUnless(approx(f11.mag(), 2.0, FEPS), 6);
        exitUnless(approx(f11.vect().mag2(), 3.0, FEPS), 6);
        exitUnless(approx(f11.vect().mag(), Math.sqrt(3.0), FEPS), 6);
        exitUnless(approx(f11.perp2(), 2.0, FEPS), 6);
        exitUnless(approx(f11.perp(), Math.sqrt(2.0), FEPS), 6);
        // testing boosts:
        d1.assign(new HepLorentzVector(1.0, 2.0, -1.0, 3.0));
        d3.assign(d1);
        d5.assign(d3);
        d2.assign(new HepLorentzVector(-1.0, 1.0, 2.0, 4.0));
        d4.assign(d2);
        d6.assign(d4);
        final double M = d1.plus(d2).mag();
        final double dm1 = d1.mag();
        final double dm2 = d2.mag();
        final double p2 = (sqr(M) - sqr(dm1 + dm2)) * (sqr(M) - sqr(dm1 - dm2)) / (4.0 * sqr(M));
        d30.assign(d1.plus(d2).boostVector().negate());
        d1.boost(d30);
        final double phi = d1.phi();
        final double theta = d1.theta();
        d1.rotateZ(-phi);
        d1.rotateY(-theta);
        final HepRotation r = new HepRotation();
        r.rotateZ(-phi);
        final HepLorentzRotation r1 = new HepLorentzRotation(d30);
        final HepLorentzRotation r2 = new HepLorentzRotation(r);
        final HepLorentzRotation r3 = new HepLorentzRotation();
        final HepLorentzRotation r4 = new HepLorentzRotation();
        final HepLorentzRotation r5 = new HepLorentzRotation();
        r3.rotateY(-theta);
        r4.assign(r3.times(r2).times(r1));
        d2.timesAssign(r4);
        exitUnless(test(io, d1, 0.0, 0.0, Math.sqrt(p2), Math.sqrt(p2 + sqr(dm1)), DEPS), 7);
        exitUnless(test(io, d2, 0.0, 0.0, -Math.sqrt(p2), Math.sqrt(p2 + sqr(dm2)), DEPS), 7);
        d1.transform(r4.inverse());
        exitUnless(test(io, d1, d3, DEPS), 7);
        r5.timesAssign(r3);
        r5.timesAssign(r);
        r5.timesAssign(r1);
        r5.invert();
        d2.timesAssign(r5);
        exitUnless(test(io, d2, d4, DEPS), 7);
        r4.assign(r1);
        r4.rotateZ(-phi);
        r4.rotateY(-theta);
        d3.timesAssign(r4);
        d4.assign(r4.times(d6));
        exitUnless(test(io, d3, 0.0, 0.0, Math.sqrt(p2), Math.sqrt(p2 + sqr(dm1)), DEPS), 7);
        exitUnless(test(io, d4, 0.0, 0.0, -Math.sqrt(p2), Math.sqrt(p2 + sqr(dm2)), DEPS), 7);
        r5.assign(r1.inverse());
        r5.timesAssign(r.inverse());
        r5.timesAssign(r3.inverse());
        d4.transform(r5);
        d3.transform(r5);
        exitUnless(test(io, d4, d6, DEPS), 7);
        exitUnless(test(io, d3, d5, DEPS), 7);
        r5.assign(r1);
        r5.transform(r);
        r5.transform(r3);
        d4.transform(r5);
        d3.transform(r5);
        exitUnless(test(io, d3, 0.0, 0.0, Math.sqrt(p2), Math.sqrt(p2 + sqr(dm1)), DEPS), 7);
        exitUnless(test(io, d4, 0.0, 0.0, -Math.sqrt(p2), Math.sqrt(p2 + sqr(dm2)), DEPS), 7);
        return 0;
    }

    /* ---- testRotation ---- */

    private static final double DEL = 10.e-16;

    /**
     * Its asserts compare elements for equality with the cosines and sines of constant angles, which g++ computes
     * when it compiles (with MPFR, correctly rounded): fdlibm's StrictMath gives those bits for these angles. The
     * rotations call the C library's: MinGW's sin(pi/4) is an ulp off, so that the test fails natively there, on
     * its line 61, as it fails here when it calls the same C library.
     */
    static int testRotation(Io io) {
        int i;
        int k;
        final double angA = pi / 3;
        final double angB = pi / 4;
        final double angC = pi / 6;
        final double cosA = StrictMath.cos(angA);
        final double sinA = StrictMath.sin(angA);
        final double cosB = StrictMath.cos(angB);
        final double sinB = StrictMath.sin(angB);
        final double cosC = StrictMath.cos(angC);
        final double sinC = StrictMath.sin(angC);
        final HepRotation R = new HepRotation();
        check(io, R.xx() == 1, "R.xx() == 1", 33);
        check(io, R.xy() == 0, "R.xy() == 0", 34);
        check(io, R.xz() == 0, "R.xz() == 0", 35);
        check(io, R.yx() == 0, "R.yx() == 0", 36);
        check(io, R.yy() == 1, "R.yy() == 1", 37);
        check(io, R.yz() == 0, "R.yz() == 0", 38);
        check(io, R.zx() == 0, "R.zx() == 0", 39);
        check(io, R.zy() == 0, "R.zy() == 0", 40);
        check(io, R.zz() == 1, "R.zz() == 1", 41);
        check(io, R.isIdentity(), "R.isIdentity()", 43);
        R.assign(new HepRotation());
        R.rotateX(angA);
        check(io, R.xx() == 1, "R.xx() == 1", 47);
        check(io, R.xy() == 0, "R.xy() == 0", 48);
        check(io, R.xz() == 0, "R.xz() == 0", 49);
        check(io, R.yx() == 0, "R.yx() == 0", 50);
        check(io, R.yy() == cosA, "R.yy() == cosA", 51);
        check(io, R.yz() == -sinA, "R.yz() ==-sinA", 52);
        check(io, R.zx() == 0, "R.zx() == 0", 53);
        check(io, R.zy() == sinA, "R.zy() == sinA", 54);
        check(io, R.zz() == cosA, "R.zz() == cosA", 55);
        R.assign(new HepRotation());
        R.rotateY(angB);
        check(io, R.xx() == cosB, "R.xx() == cosB", 59);
        check(io, R.xy() == 0, "R.xy() == 0", 60);
        check(io, R.xz() == sinB, "R.xz() == sinB", 61);
        check(io, R.yx() == 0, "R.yx() == 0", 62);
        check(io, R.yy() == 1, "R.yy() == 1", 63);
        check(io, R.yz() == 0, "R.yz() == 0", 64);
        check(io, R.zx() == -sinB, "R.zx() ==-sinB", 65);
        check(io, R.zy() == 0, "R.zy() == 0", 66);
        check(io, R.zz() == cosB, "R.zz() == cosB", 67);
        R.assign(new HepRotation());
        R.rotateZ(angC);
        check(io, R.xx() == cosC, "R.xx() == cosC", 71);
        check(io, R.xy() == -sinC, "R.xy() ==-sinC", 72);
        check(io, R.xz() == 0, "R.xz() == 0", 73);
        check(io, R.yx() == sinC, "R.yx() == sinC", 74);
        check(io, R.yy() == cosC, "R.yy() == cosC", 75);
        check(io, R.yz() == 0, "R.yz() == 0", 76);
        check(io, R.zx() == 0, "R.zx() == 0", 77);
        check(io, R.zy() == 0, "R.zy() == 0", 78);
        check(io, R.zz() == 1, "R.zz() == 1", 79);
        R.assign(new HepRotation());
        R.rotateZ(angC);
        R.rotateY(angB);
        R.rotateZ(angA);
        final HepRotation RR = new HepRotation(R);
        check(io, Math.abs(RR.xx() - cosA * cosB * cosC + sinA * sinC) < DEL, "std::abs(RR.xx() - cosA*cosB*cosC + sinA*sinC) < DEL", 87);
        check(io, Math.abs(RR.xy() + cosA * cosB * sinC + sinA * cosC) < DEL, "std::abs(RR.xy() + cosA*cosB*sinC + sinA*cosC) < DEL", 88);
        check(io, Math.abs(RR.xz() - cosA * sinB) < DEL, "std::abs(RR.xz() - cosA*sinB) < DEL", 89);
        check(io, Math.abs(RR.yx() - sinA * cosB * cosC - cosA * sinC) < DEL, "std::abs(RR.yx() - sinA*cosB*cosC - cosA*sinC) < DEL", 90);
        check(io, Math.abs(RR.yy() + sinA * cosB * sinC - cosA * cosC) < DEL, "std::abs(RR.yy() + sinA*cosB*sinC - cosA*cosC) < DEL", 91);
        check(io, Math.abs(RR.yz() - sinA * sinB) < DEL, "std::abs(RR.yz() - sinA*sinB) < DEL", 92);
        check(io, Math.abs(RR.zx() + sinB * cosC) < DEL, "std::abs(RR.zx() + sinB*cosC) < DEL", 93);
        check(io, Math.abs(RR.zy() - sinB * sinC) < DEL, "std::abs(RR.zy() - sinB*sinC) < DEL", 94);
        check(io, Math.abs(RR.zz() - cosB) < DEL, "std::abs(RR.zz() - cosB) < DEL", 95);
        RR.assign(new HepRotation());
        check(io, !RR.isEqual(R), "RR != R", 98);
        RR.assign(R);
        check(io, RR.isEqual(R), "RR == R", 100);
        check(io, R.get(0, 0) == R.xx(), "R(0,0) == R.xx()", 102);
        check(io, R.get(0, 1) == R.xy(), "R(0,1) == R.xy()", 103);
        check(io, R.get(0, 2) == R.xz(), "R(0,2) == R.xz()", 104);
        check(io, R.get(1, 0) == R.yx(), "R(1,0) == R.yx()", 105);
        check(io, R.get(1, 1) == R.yy(), "R(1,1) == R.yy()", 106);
        check(io, R.get(1, 2) == R.yz(), "R(1,2) == R.yz()", 107);
        check(io, R.get(2, 0) == R.zx(), "R(2,0) == R.zx()", 108);
        check(io, R.get(2, 1) == R.zy(), "R(2,1) == R.zy()", 109);
        check(io, R.get(2, 2) == R.zz(), "R(2,2) == R.zz()", 110);
        for (i = 0; i < 3; i++) {
            for (k = 0; k < 3; k++) check(io, RR.get(i, k) == R.row(i).get(k), "RR(i,k) == R[i][k]", 114);
        }
        final HepRotation A = new HepRotation();
        final HepRotation B = new HepRotation();
        final HepRotation C = new HepRotation();
        A.rotateZ(angA);
        B.rotateY(angB);
        C.rotateZ(angC);
        R.assign(A);
        R.timesAssign(B);
        R.timesAssign(C);
        final Hep3Vector V = new Hep3Vector(1, 2, 3);
        V.assign(R.times(V));
        check(io, Math.abs(V.x() - R.xx() - 2. * R.xy() - 3. * R.xz()) < DEL, "std::abs(V.x()-R.xx()-2.*R.xy()-3.*R.xz()) < DEL", 126);
        check(io, Math.abs(V.y() - R.yx() - 2. * R.yy() - 3. * R.yz()) < DEL, "std::abs(V.y()-R.yx()-2.*R.yy()-3.*R.yz()) < DEL", 127);
        check(io, Math.abs(V.z() - R.zx() - 2. * R.zy() - 3. * R.zz()) < DEL, "std::abs(V.z()-R.zx()-2.*R.zy()-3.*R.zz()) < DEL", 128);
        R.assign(A.times(B).times(C));
        sameElements(io, RR, R, 131);
        R.assign(C);
        R.transform(B);
        R.transform(A);
        sameElements(io, RR, R, 144);
        R.assign(RR.inverse());
        for (i = 0; i < 3; i++) {
            for (k = 0; k < 3; k++) check(io, RR.get(i, k) == R.row(k).get(i), "RR(i,k) == R[k][i]", 157);
        }
        R.invert();
        check(io, RR.isEqual(R), "RR == R", 162);
        R.assign(new HepRotation());
        R.rotateAxes(new Hep3Vector(RR.xx(), RR.yx(), RR.zx()), new Hep3Vector(RR.xy(), RR.yy(), RR.zy()),
            new Hep3Vector(RR.xz(), RR.yz(), RR.zz()));
        check(io, RR.isEqual(R), "RR == R", 168);
        double ang = twopi / 9.;
        R.assign(new HepRotation());
        R.rotate(ang, V);
        RR.assign(new HepRotation());
        RR.rotateZ(-(V.phi()));
        RR.rotateY(-(V.theta()));
        RR.rotateZ(ang);
        RR.rotateY(V.theta());
        RR.rotateZ(V.phi());
        sameElements(io, RR, R, 181);
        final Hep3Vector Vu = V.unit();
        ang = R.getAngleAxis(V);
        check(io, Math.abs(ang - twopi / 9.) < DEL, "std::abs(ang - CLHEP::twopi/9.) < DEL", 193);
        check(io, Math.abs(V.x() - Vu.x()) < DEL, "std::abs(V.x() - Vu.x()) < DEL", 194);
        check(io, Math.abs(V.y() - Vu.y()) < DEL, "std::abs(V.y() - Vu.y()) < DEL", 195);
        check(io, Math.abs(V.z() - Vu.z()) < DEL, "std::abs(V.z() - Vu.z()) < DEL", 196);
        check(io, Math.abs(RR.phiX() - CMath.atan2(RR.yx(), RR.xx())) < DEL, "std::abs(RR.phiX()-std::atan2(RR.yx(),RR.xx())) < DEL", 198);
        check(io, Math.abs(RR.phiY() - CMath.atan2(RR.yy(), RR.xy())) < DEL, "std::abs(RR.phiY()-std::atan2(RR.yy(),RR.xy())) < DEL", 199);
        check(io, Math.abs(RR.phiZ() - CMath.atan2(RR.yz(), RR.xz())) < DEL, "std::abs(RR.phiZ()-std::atan2(RR.yz(),RR.xz())) < DEL", 200);
        check(io, Math.abs(RR.thetaX() - CMath.acos(RR.zx())) < DEL, "std::abs(RR.thetaX()-std::acos(RR.zx())) < DEL", 202);
        check(io, Math.abs(RR.thetaY() - CMath.acos(RR.zy())) < DEL, "std::abs(RR.thetaY()-std::acos(RR.zy())) < DEL", 203);
        check(io, Math.abs(RR.thetaZ() - CMath.acos(RR.zz())) < DEL, "std::abs(RR.thetaZ()-std::acos(RR.zz())) < DEL", 204);
        return 0;
    }

    /** The nine asserts std::abs(RR.xx() - R.xx()) &lt; DEL... from that line on. */
    private static void sameElements(Io io, HepRotation RR, HepRotation R, int line) {
        check(io, Math.abs(RR.xx() - R.xx()) < DEL, "std::abs(RR.xx() - R.xx()) < DEL", line);
        check(io, Math.abs(RR.xy() - R.xy()) < DEL, "std::abs(RR.xy() - R.xy()) < DEL", line + 1);
        check(io, Math.abs(RR.xz() - R.xz()) < DEL, "std::abs(RR.xz() - R.xz()) < DEL", line + 2);
        check(io, Math.abs(RR.yx() - R.yx()) < DEL, "std::abs(RR.yx() - R.yx()) < DEL", line + 3);
        check(io, Math.abs(RR.yy() - R.yy()) < DEL, "std::abs(RR.yy() - R.yy()) < DEL", line + 4);
        check(io, Math.abs(RR.yz() - R.yz()) < DEL, "std::abs(RR.yz() - R.yz()) < DEL", line + 5);
        check(io, Math.abs(RR.zx() - R.zx()) < DEL, "std::abs(RR.zx() - R.zx()) < DEL", line + 6);
        check(io, Math.abs(RR.zy() - R.zy()) < DEL, "std::abs(RR.zy() - R.zy()) < DEL", line + 7);
        check(io, Math.abs(RR.zz() - R.zz()) < DEL, "std::abs(RR.zz() - R.zz()) < DEL", line + 8);
    }

    /* ---- testRotationAxis ---- */

    private static boolean equal(double a, double b) {
        final double eps = 1e-9;
        return Math.abs(a - b) < eps;
    }

    private static boolean equal(Hep3Vector a, Hep3Vector b) {
        final double eps = 1e-10;
        return Math.abs(a.x() - b.x()) < eps && Math.abs(a.y() - b.y()) < eps && Math.abs(a.z() - b.z()) < eps;
    }

    /** One of the rotations by 180 degrees (or none) around a given axis: its angle and axis checked. */
    private static void axisCheck(Io io, String title, HepRotation rot, Hep3Vector axis, HepRep3x3 m, double delta, int line) {
        say(io.cout, title, "\n");
        rot.set(m);
        if (delta == 0) {
            check(io, equal(rot.delta(), 0), "equal(rot.delta(),0)", line);
            check(io, equal(rot.axis(), axis), "equal(rot.axis(),axis)", line + 1);
        } else {
            check(io, equal(rot.delta(), pi), "equal(rot.delta(),pi)", line);
            check(io, equal(rot.axis(), axis) || equal(rot.axis(), axis.negate()), "equal(rot.axis(),axis) || equal(rot.axis(),-axis)", line + 1);
        }
    }

    /**
     * 130 million rotations, as many as the C++ makes (natively some 13 s). In the inner loops x, y and z, which
     * depend on the and phi alone, are computed once for all the angles a: the C++ recomputes the same values.
     */
    static int testRotationAxis(Io io) {
        final COStream cout = io.cout;
        say(cout, "\n=== Check rotation around specified axes", "\n");
        final HepRotation rot = new HepRotation();
        final Hep3Vector axis = new Hep3Vector();
        axis.set(0, 0, 1);
        axisCheck(io, "Zero rotation - OK", rot, axis, new HepRep3x3(1, 0, 0, 0, 1, 0, 0, 0, 1), 0, 34);
        axis.set(1, 0, 0);
        axisCheck(io, "Rotation by 180 degrees around X  - OK", rot, axis, new HepRep3x3(1, 0, 0, 0, -1, 0, 0, 0, -1), pi, 42);
        axis.set(0, 1, 0);
        axisCheck(io, "Rotation by 180 degrees around Y  - OK", rot, axis, new HepRep3x3(-1, 0, 0, 0, 1, 0, 0, 0, -1), pi, 50);
        axis.set(0, 0, 1);
        axisCheck(io, "Rotation by 180 degrees around Z  - OK", rot, axis, new HepRep3x3(-1, 0, 0, 0, -1, 0, 0, 0, 1), pi, 58);
        axis.assign(new Hep3Vector(1, 1, 0).unit());
        axisCheck(io, "Rotation by 180 degrees around XY - OK", rot, axis, new HepRep3x3(0, 1, 0, 1, 0, 0, 0, 0, -1), pi, 66);
        axis.assign(new Hep3Vector(0, 1, 1).unit());
        axisCheck(io, "Rotation by 180 degrees around YZ - OK", rot, axis, new HepRep3x3(-1, 0, 0, 0, 0, 1, 0, 1, 0), pi, 74);
        axis.assign(new Hep3Vector(1, 0, 1).unit());
        axisCheck(io, "Rotation by 180 degrees around ZX - OK", rot, axis, new HepRep3x3(0, 0, 1, 0, -1, 0, 1, 0, 0), pi, 82);
        final HepRotation matrix = new HepRotation();
        final Hep3Vector vinput = new Hep3Vector();
        final Hep3Vector voutput = new Hep3Vector();
        final int step = 1;
        say(cout, "\n=== Check all possible axes and angles (step = ", step, " degree)", "\n");
        for (int the = 0; the <= 180; the += step) {
            for (int phi = 0; phi < 360; phi += step) {
                final double z = CMath.cos(the * deg);
                final double y = CMath.sin(the * deg) * CMath.sin(phi * deg);
                final double x = CMath.sin(the * deg) * CMath.cos(phi * deg);
                for (int a = 0; a < 360; a += step) {
                    final double ang = a * deg;
                    // Set rotation matrix
                    vinput.set(x, y, z);
                    matrix.set(vinput, ang);
                    // Get axis/angle
                    voutput.assign(matrix.axis());
                    double del = matrix.delta();
                    if (a > 180) {
                        del = twopi - del;
                        voutput.assign(voutput.negate());
                    }
                    // Check angle
                    if (a != 180) check(io, equal(ang, del), "equal(ang,del)", 114);
                    // Check axis
                    if (a == 0) continue;
                    if (a == 180) {
                        check(io, equal(vinput, voutput) || equal(vinput.negate(), voutput), "equal(vinput,voutput) || equal(-vinput,voutput)", 119);
                    } else {
                        check(io, equal(vinput, voutput), "equal(vinput,voutput)", 121);
                    }
                }
            }
        }
        say(cout, "OK", "\n");
        int istep = 10000;
        say(cout, "\n=== Check angles near 0 (step = 1/", istep, " degree)", "\n");
        for (int the = 0; the <= 180; the += 5) {
            for (int phi = 0; phi < 360; phi += 5) {
                final double z = CMath.cos(the * deg);
                final double y = CMath.sin(the * deg) * CMath.sin(phi * deg);
                final double x = CMath.sin(the * deg) * CMath.cos(phi * deg);
                for (int a = -istep; a <= istep; a += 1) {
                    final double ang = 0. + a * (deg / istep);
                    vinput.set(x, y, z);
                    matrix.set(vinput, ang);
                    voutput.assign(matrix.axis());
                    double del = matrix.delta();
                    if (a < 0) {
                        del = -del;
                        voutput.assign(voutput.negate());
                    }
                    check(io, equal(ang, del), "equal(ang,del)", 154);
                    if (a == 0) continue;
                    check(io, equal(vinput, voutput), "equal(vinput,voutput)", 158);
                }
            }
        }
        say(cout, "OK", "\n");
        istep = 10000;
        say(cout, "\n=== Check angles near 180 (step = 1/", istep, " degree)", "\n");
        for (int the = 0; the <= 180; the += 5) {
            for (int phi = 0; phi < 360; phi += 5) {
                final double z = CMath.cos(the * deg);
                final double y = CMath.sin(the * deg) * CMath.sin(phi * deg);
                final double x = CMath.sin(the * deg) * CMath.cos(phi * deg);
                for (int a = -istep; a <= istep; a += 1) {
                    final double ang = pi + a * (deg / istep);
                    vinput.set(x, y, z);
                    matrix.set(vinput, ang);
                    voutput.assign(matrix.axis());
                    double del = matrix.delta();
                    if (a > 0) {
                        del = twopi - del;
                        voutput.assign(voutput.negate());
                    }
                    if (a != 0) check(io, equal(ang, del), "equal(ang,del)", 190);
                    if (a == 0) {
                        check(io, equal(vinput, voutput) || equal(vinput.negate(), voutput), "equal(vinput,voutput) || equal(-vinput,voutput)", 194);
                    } else {
                        check(io, equal(vinput, voutput), "equal(vinput,voutput)", 196);
                    }
                }
            }
        }
        say(cout, "OK", "\n");
        say(cout, "\n");
        return 0;
    }

    /* ---- testSubscripts ---- */

    /** testSubscripts' Rot3: a HepRotation whose elements are set directly. */
    private static final class Rot3 extends HepRotation {
        void setMatrix(double xx1, double xy1, double xz1, double yx1, double yy1, double yz1, double zx1, double zy1, double zz1) {
            rxx = xx1;
            rxy = xy1;
            rxz = xz1;
            ryx = yx1;
            ryy = yy1;
            ryz = yz1;
            rzx = zx1;
            rzy = zy1;
            rzz = zz1;
        }
    }

    /** testSubscripts' Rot4: a HepLorentzRotation whose elements are set directly. */
    private static final class Rot4 extends HepLorentzRotation {
        void setMatrix(double xx1, double xy1, double xz1, double xt1, double yx1, double yy1, double yz1, double yt1,
                       double zx1, double zy1, double zz1, double zt1, double tx1, double ty1, double tz1, double tt1) {
            mxx = xx1;
            mxy = xy1;
            mxz = xz1;
            mxt = xt1;
            myx = yx1;
            myy = yy1;
            myz = yz1;
            myt = yt1;
            mzx = zx1;
            mzy = zy1;
            mzz = zz1;
            mzt = zt1;
            mtx = tx1;
            mty = ty1;
            mtz = tz1;
            mtt = tt1;
        }
    }

    static int testSubscripts(Io io) {
        // Test ThreeVector subscripting: V3[i] and V3(i) alike
        final Hep3Vector V3 = new Hep3Vector();
        final Hep3Vector ConstV3 = new Hep3Vector(1., 2., 3.);
        V3.set(0, ConstV3.get(0));
        V3.set(1, ConstV3.get(1));
        V3.set(2, ConstV3.get(2));
        check(io, V3.isEqual(ConstV3), "V3 == ConstV3", 56);
        V3.set(0, ConstV3.get(2));
        V3.set(1, ConstV3.get(1));
        V3.set(2, ConstV3.get(0));
        check(io, V3.isEqual(new Hep3Vector(3., 2., 1.)), "V3 == Hep3Vector(3.,2.,1.)", 61);
        // Test LorentzVector subscripting
        final HepLorentzVector V4 = new HepLorentzVector();
        final HepLorentzVector ConstV4 = new HepLorentzVector(1., 2., 3., 4);
        V4.set(0, ConstV4.get(0));
        V4.set(1, ConstV4.get(1));
        V4.set(2, ConstV4.get(2));
        V4.set(3, ConstV4.get(3));
        check(io, V4.isEqual(ConstV4), "V4 == ConstV4", 72);
        V4.set(0, ConstV4.get(3));
        V4.set(1, ConstV4.get(2));
        V4.set(2, ConstV4.get(1));
        V4.set(3, ConstV4.get(0));
        check(io, V4.isEqual(new HepLorentzVector(4., 3., 2., 1.)), "V4 == HepLorentzVector(4.,3.,2.,1.)", 78);
        // Test Rotation subscripting
        int i;
        int j;
        int k;
        final Rot3 R3 = new Rot3();
        R3.setMatrix(1., 2., 3., 4., 5., 6., 7., 8., 9.);
        k = 1;
        for (i = 0; i < 3; i++) {
            for (j = 0; j < 3; j++) {
                check(io, R3.get(i, j) == k, "R3(i,j) == double(k)", 90);
                check(io, R3.row(i).get(j) == k, "R3[i][j] == double(k)", 91);
                k++;
            }
        }
        // Test LorentzRotation subscripting
        final Rot4 R4 = new Rot4();
        R4.setMatrix(1., 2., 3., 4., 5., 6., 7., 8., 9., 10., 11., 12., 13., 14., 15., 16.);
        k = 1;
        for (i = 0; i < 4; i++) {
            for (j = 0; j < 4; j++) {
                check(io, R4.get(i, j) == k, "R4(i,j) == double(k)", 104);
                check(io, R4.row(i).get(j) == k, "R4[i][j] == double(k)", 105);
                k++;
            }
        }
        return 0;
    }
}
