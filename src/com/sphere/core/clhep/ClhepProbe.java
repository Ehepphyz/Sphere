package com.sphere.core.clhep;

import com.sphere.core.clhep.evaluator.Evaluator;
import com.sphere.core.clhep.genfun.DefiniteIntegral;
import com.sphere.core.clhep.genfun.RKIntegrator;
import com.sphere.core.clhep.genfun.Square;
import com.sphere.core.clhep.genfun.Variable;
import com.sphere.core.clhep.geometry.Point3D;
import com.sphere.core.clhep.geometry.Rotate3D;
import com.sphere.core.clhep.geometry.Transform3D;
import com.sphere.core.clhep.geometry.Translate3D;
import com.sphere.core.clhep.geometry.Vector3D;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.RandGauss;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepLorentzVector;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * What ':clhep ping' checks: real work in each package, against what the mathematics and the physics require
 * rather than against numbers recorded from an earlier run. A boost keeps the invariant mass, a rotation
 * times its inverse and a matrix times its inverse are the identity, the Evaluator reads CLHEP's units,
 * MIXMAX is flat and RandGauss is normal, the integral of x² over [0, 1] is 1/3, y' = -y from 1 reaches 1/e.
 * The engines are seeded and the distributions instances, so that no state a later command sees is touched.
 */
public final class ClhepProbe {

    private ClhepProbe() {
    }

    /** Does the probe's work; returns what was checked, throws on a wrong answer. */
    public static String check() throws Exception {
        return StdStreams.with(StdStreams.NONE, ClhepProbe::run);
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what);
    }

    private static String run() {
        // Vector: a boost keeps the mass, a rotation the length, and undoes with its inverse
        final HepLorentzVector p = new HepLorentzVector(1.0, 2.0, 3.0, 10.0);
        final double m = p.m();
        final HepLorentzVector boosted = new HepLorentzVector(p).boost(0.3, -0.2, 0.5);
        require(Math.abs(boosted.m() - m) <= 1e-12 * m, "a boost changed the invariant mass");
        final HepRotation r = new HepRotation(0.3, 0.7, 1.1);
        final Hep3Vector v = new Hep3Vector(1.5, -2.0, 0.25);
        final Hep3Vector rv = r.times(v);
        require(Math.abs(rv.mag() - v.mag()) <= 1e-14 * v.mag(), "a rotation changed a length");
        require(r.times(r.inverse()).isNear(new HepRotation(), 1e-14), "a rotation times its inverse is not the identity");

        // Matrix: a symmetric positive matrix times its inverse
        final HepSymMatrix s = new HepSymMatrix(4);
        for (int i = 1; i <= 4; i++) {
            for (int j = 1; j <= i; j++) s.set(i, j, i == j ? 4.0 + i : 1.0 / (i + j));
        }
        final HepMatrix prod = new HepMatrix(s).times(new HepMatrix(s.inverse()));
        for (int i = 1; i <= 4; i++) {
            for (int j = 1; j <= 4; j++) {
                require(Math.abs(prod.get(i, j) - (i == j ? 1.0 : 0.0)) <= 1e-12, "a 4x4 matrix times its inverse is not the identity");
            }
        }

        // Geometry: a placement times its inverse
        final Transform3D t = new Translate3D(1.0, -2.0, 3.0).times(new Rotate3D(0.8, new Vector3D(1.0, 1.0, 0.5)));
        final Point3D q = new Point3D(0.3, 0.2, -0.7);
        final Point3D back = t.inverse().times(t.times(q));
        require(back.distance(q) <= 1e-14, "a transformation and its inverse do not give the point back");

        // Evaluator: units and mathematics
        final Evaluator ev = new Evaluator();
        ev.setStdMath();
        ev.setSystemOfUnits();
        final double gev = ev.evaluate("2*GeV/MeV");
        require(ev.status() == Evaluator.OK && gev == 2000.0, "the Evaluator read 2*GeV/MeV as " + gev);
        final double half = ev.evaluate("sin(pi/6)");
        require(ev.status() == Evaluator.OK && Math.abs(half - 0.5) <= 1e-15, "the Evaluator read sin(pi/6) as " + half);

        // Random: MIXMAX is flat, RandGauss is normal
        final MixMaxRng engine = new MixMaxRng(12345L);
        double sum = 0;
        for (int i = 0; i < 20000; i++) {
            final double x = engine.flat();
            require(x > 0 && x < 1, "MIXMAX gave " + x + ", outside (0, 1)");
            sum += x;
        }
        require(Math.abs(sum / 20000 - 0.5) <= 0.01, "the mean of 20000 MIXMAX flats is " + sum / 20000);
        final RandGauss gauss = new RandGauss(engine, 0.0, 1.0);
        double g1 = 0;
        double g2 = 0;
        for (int i = 0; i < 20000; i++) {
            final double x = gauss.fire();
            g1 += x;
            g2 += x * x;
        }
        require(Math.abs(g1 / 20000) <= 0.04 && Math.abs(g2 / 20000 - 1) <= 0.06, "RandGauss is not normal");

        // GenericFunctions: an integral and a differential equation
        final double integral = new DefiniteIntegral(0.0, 1.0).apply(new Square());
        require(Math.abs(integral - 1.0 / 3) <= 1e-9, "the integral of x^2 over [0, 1] came out " + integral);
        final RKIntegrator rk = new RKIntegrator();
        rk.addDiffEquation(new Variable(0, 1).negate(), "y", 1.0);
        final double y1 = rk.getFunction(0).call(1.0);
        require(Math.abs(y1 - Math.exp(-1)) <= 1e-5, "y' = -y from y(0) = 1 gave y(1) = " + y1);

        return "boosts keep the mass, rotations, matrices and placements undo with their inverses, the Evaluator reads units, "
            + "MIXMAX is flat and RandGauss normal, the integral of x^2 is 1/3, y' = -y gives 1/e";
    }
}
