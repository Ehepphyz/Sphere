package com.sphere.core.clhep.vector;

/**
 * CLHEP's HepBoostZ (Vector/BoostZ.h, .icc, BoostZ.cc): a Lorentz boost along the z axis, as its beta and gamma.
 * Products of two such boosts stay of this kind; with others they are HepLorentzRotations.
 */
public final class HepBoostZ extends HepAxialBoost implements Comparable<HepBoostZ> {

    /** The identity. */
    public HepBoostZ() {
        super(0.0, 1.0);
    }

    /** A boost by beta; a speed of c or more is reported and thrown. */
    public HepBoostZ(double bbeta) {
        super(0.0, 1.0);
        set(bbeta);
    }

    public HepBoostZ(HepBoostZ b) {
        super(b.beta_, b.gamma_);
    }

    HepBoostZ(double bbeta, double ggamma) {
        super(bbeta, ggamma);
    }

    public HepBoostZ copy() {
        return new HepBoostZ(this);
    }

    public HepBoostZ assign(HepBoostZ b) {
        beta_ = b.beta_;
        gamma_ = b.gamma_;
        return this;
    }

    public HepBoostZ set(double bbeta) {
        setBeta(bbeta, "Z");
        return this;
    }

    @Override
    public Hep3Vector boostVector() {
        return new Hep3Vector(0, 0, beta_);
    }

    @Override
    public Hep3Vector getDirection() {
        return new Hep3Vector(0.0, 0.0, 1.0);
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
    public double xt() {
        return 0.0;
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
    public double yt() {
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
        return gamma();
    }

    @Override
    public double zt() {
        return beta() * gamma();
    }

    @Override
    public double tx() {
        return 0.0;
    }

    @Override
    public double ty() {
        return 0.0;
    }

    @Override
    public double tz() {
        return beta() * gamma();
    }

    @Override
    public double tt() {
        return gamma();
    }

    @Override
    public HepLorentzVector col1() {
        return new HepLorentzVector(1, 0, 0, 0);
    }

    @Override
    public HepLorentzVector col2() {
        return new HepLorentzVector(0, 1, 0, 0);
    }

    @Override
    public HepLorentzVector col3() {
        return new HepLorentzVector(0, 0, gamma(), beta() * gamma());
    }

    @Override
    public HepLorentzVector col4() {
        return new HepLorentzVector(0, 0, beta() * gamma(), gamma());
    }

    @Override
    public HepRep4x4 rep4x4() {
        final double bg = beta_ * gamma_;
        return new HepRep4x4(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, gamma_, bg, 0, 0, bg, gamma_);
    }

    @Override
    public HepRep4x4Symmetric rep4x4Symmetric() {
        final double bg = beta_ * gamma_;
        return new HepRep4x4Symmetric(1, 0, 0, 0, 1, 0, 0, gamma_, bg, gamma_);
    }

    @Override
    public HepLorentzVector times(HepLorentzVector p) {
        final double bg = beta_ * gamma_;
        return new HepLorentzVector(p.x(), p.y(), gamma_ * p.z() + bg * p.t(), gamma_ * p.t() + bg * p.z());
    }

    /** this * b: still a boost along the same axis, by the relativistic sum of the betas. */
    public HepBoostZ times(HepBoostZ b) {
        return new HepBoostZ((beta() + b.beta()) / (1 + beta() * b.beta()));
    }

    @Override
    public double distance2(HepBoost b) {
        return b.distance2(this);
    }

    public double distance2(HepBoostZ b) {
        return distance2Same(b);
    }

    public double howNear(HepBoostZ b) {
        return Math.sqrt(distance2(b));
    }

    public boolean isNear(HepBoostZ b) {
        return isNear(b, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoostZ b, double epsilon) {
        return distance2(b) <= epsilon * epsilon;
    }

    public HepBoostZ inverse() {
        return new HepBoostZ(-beta(), gamma());
    }

    public static HepBoostZ inverseOf(HepBoostZ b) {
        return new HepBoostZ(-b.beta(), b.gamma());
    }

    public HepBoostZ invert() {
        beta_ = -beta_;
        return this;
    }

    public int compare(HepBoostZ b) {
        if (beta() < b.beta()) {
            return -1;
        } else if (beta() > b.beta()) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public int compareTo(HepBoostZ b) {
        return compare(b);
    }

    /** operator==: the same beta. */
    public boolean isEqual(HepBoostZ b) {
        return beta_ == b.beta_;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepBoostZ b && isEqual(b);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(beta_);
    }

    @Override
    protected char axisName() {
        return 'Z';
    }
}
