package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.sqrt;

/**
 * What CLHEP's HepBoostX, HepBoostY and HepBoostZ share (Vector/BoostX.h and its two siblings): a
 * boost along a fixed axis, kept as its beta and gamma. Each class gives its matrix and its product
 * with a four-vector; the rest, written three times in C++, is here.
 */
public abstract class HepAxialBoost {

    protected double beta_;
    protected double gamma_;

    protected HepAxialBoost(double bbeta, double ggamma) {
        beta_ = bbeta;
        gamma_ = ggamma;
    }

    /** set(beta): beta and its gamma; a speed of c or more is reported and thrown. */
    protected void setBeta(double bbeta, String axis) {
        final double b2 = bbeta * bbeta;
        if (b2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Beta supplied to set HepBoost" + axis + " represents speed >= c.",
                "HepBoost" + axis + "::set");
        }
        beta_ = bbeta;
        gamma_ = 1.0 / sqrt(1.0 - b2);
    }

    public double beta() {
        return beta_;
    }

    public double gamma() {
        return gamma_;
    }

    public abstract Hep3Vector boostVector();

    public abstract Hep3Vector getDirection();

    public abstract double xx();

    public abstract double xy();

    public abstract double xz();

    public abstract double xt();

    public abstract double yx();

    public abstract double yy();

    public abstract double yz();

    public abstract double yt();

    public abstract double zx();

    public abstract double zy();

    public abstract double zz();

    public abstract double zt();

    public abstract double tx();

    public abstract double ty();

    public abstract double tz();

    public abstract double tt();

    public abstract HepLorentzVector col1();

    public abstract HepLorentzVector col2();

    public abstract HepLorentzVector col3();

    public abstract HepLorentzVector col4();

    public abstract HepRep4x4 rep4x4();

    public abstract HepRep4x4Symmetric rep4x4Symmetric();

    /** operator*: the four-vector boosted. */
    public abstract HepLorentzVector times(HepLorentzVector p);

    public abstract double distance2(HepBoost b);

    protected abstract char axisName();

    public HepLorentzVector row1() {
        return new HepLorentzVector(col1());
    }

    public HepLorentzVector row2() {
        return new HepLorentzVector(col2());
    }

    public HepLorentzVector row3() {
        return new HepLorentzVector(col3());
    }

    public HepLorentzVector row4() {
        return new HepLorentzVector(col4());
    }

    public HepLorentzVector apply(HepLorentzVector w) {
        return times(w);
    }

    public HepLorentzVector vectorMultiplication(HepLorentzVector w) {
        return times(w);
    }

    public void decompose(HepRotation rotation, HepBoost boost) {
        final HepAxisAngle vdelta = new HepAxisAngle();
        rotation.assign(new HepRotation(vdelta));
        final Hep3Vector bbeta = boostVector();
        boost.assign(new HepBoost(bbeta));
    }

    public void decompose(HepAxisAngle rotation, Hep3Vector boost) {
        rotation.assign(new HepAxisAngle());
        boost.assign(boostVector());
    }

    public void decompose(HepBoost boost, HepRotation rotation) {
        decompose(rotation, boost);
    }

    public void decompose(Hep3Vector boost, HepAxisAngle rotation) {
        decompose(rotation, boost);
    }

    public boolean isIdentity() {
        return beta() == 0;
    }

    /** The distance to a boost along the same axis. */
    protected double distance2Same(HepAxialBoost b) {
        final double d = beta() * gamma() - b.beta() * b.gamma();
        return d * d;
    }

    public double distance2(HepRotation r) {
        final double db2 = norm2();
        final double dr2 = r.norm2();
        return db2 + dr2;
    }

    public double distance2(HepLorentzRotation lt) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        lt.decompose(b1, r1);
        final double db2 = distance2(b1);
        final double dr2 = r1.norm2();
        return db2 + dr2;
    }

    public double howNear(HepBoost b) {
        return sqrt(distance2(b));
    }

    public double howNear(HepRotation r) {
        return sqrt(distance2(r));
    }

    public double howNear(HepLorentzRotation lt) {
        return sqrt(distance2(lt));
    }

    public boolean isNear(HepBoost b) {
        return isNear(b, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoost b, double epsilon) {
        return distance2(b) <= epsilon * epsilon;
    }

    public boolean isNear(HepRotation r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotation r, double epsilon) {
        final double db2 = norm2();
        if (db2 > epsilon * epsilon) return false;
        final double dr2 = r.norm2();
        return db2 + dr2 <= epsilon * epsilon;
    }

    public boolean isNear(HepLorentzRotation lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    /** As CLHEP: the distance to the identity boost, and true unless the summed distances are zero. */
    public boolean isNear(HepLorentzRotation lt, double epsilon) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        final double db2 = distance2(b1);
        lt.decompose(b1, r1);
        if (db2 > epsilon * epsilon) return false;
        final double dr2 = r1.norm2();
        return (db2 + dr2) != 0;
    }

    public double norm2() {
        final double bg = beta_ * gamma_;
        return bg * bg;
    }

    public void rectify() {
        double b2 = beta_ * beta_;
        if (b2 >= 1) {
            beta_ = 1.0 - 1.0e-8;
            b2 = beta_ * beta_;
        }
        gamma_ = 1.0 / sqrt(1.0 - b2);
    }

    public HepLorentzRotation times(HepBoost b) {
        final HepLorentzRotation me = new HepLorentzRotation(this);
        return me.times(b);
    }

    public HepLorentzRotation times(HepRotation r) {
        final HepLorentzRotation me = new HepLorentzRotation(this);
        return me.times(r);
    }

    public HepLorentzRotation times(HepLorentzRotation lt) {
        final HepLorentzRotation me = new HepLorentzRotation(this);
        return me.times(lt);
    }

    public static double getTolerance() {
        return Hep4RotationInterface.tolerance;
    }

    public static double setTolerance(double tol) {
        return Hep4RotationInterface.setTolerance(tol);
    }

    /** "Boost in X direction (beta = b, gamma = g) ". */
    public COStream print(COStream os) {
        return os.put("Boost in ").put(axisName()).put(" direction (beta = ").put(beta_).put(", gamma = ").put(gamma_)
            .put(") ");
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
