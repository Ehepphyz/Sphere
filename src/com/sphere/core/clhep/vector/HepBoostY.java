package com.sphere.core.clhep.vector;

/**
 * CLHEP's HepBoostY (Vector/BoostY.h, .icc, BoostY.cc): a Lorentz boost along the y axis, as its beta and gamma.
 * Products of two such boosts stay of this kind; with others they are HepLorentzRotations.
 */
public final class HepBoostY extends HepAxialBoost implements Comparable<HepBoostY> {

    /** The identity. */
    public HepBoostY() {
        super(0.0, 1.0);
    }

    /** A boost by beta; a speed of c or more is reported and thrown. */
    public HepBoostY(double bbeta) {
        super(0.0, 1.0);
        set(bbeta);
    }

    public HepBoostY(HepBoostY b) {
        super(b.beta_, b.gamma_);
    }

    HepBoostY(double bbeta, double ggamma) {
        super(bbeta, ggamma);
    }

    public HepBoostY copy() {
        return new HepBoostY(this);
    }

    public HepBoostY assign(HepBoostY b) {
        beta_ = b.beta_;
        gamma_ = b.gamma_;
        return this;
    }

    public HepBoostY set(double bbeta) {
        setBeta(bbeta, "Y");
        return this;
    }

    @Override
    public Hep3Vector boostVector() {
        return new Hep3Vector(0, beta_, 0);
    }

    @Override
    public Hep3Vector getDirection() {
        return new Hep3Vector(0.0, 1.0, 0.0);
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
        return gamma();
    }

    @Override
    public double yz() {
        return 0.0;
    }

    @Override
    public double yt() {
        return beta() * gamma();
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
    public double zt() {
        return 0.0;
    }

    @Override
    public double tx() {
        return 0.0;
    }

    @Override
    public double ty() {
        return beta() * gamma();
    }

    @Override
    public double tz() {
        return 0.0;
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
        return new HepLorentzVector(0, gamma(), 0, beta() * gamma());
    }

    @Override
    public HepLorentzVector col3() {
        return new HepLorentzVector(0, 0, 1, 0);
    }

    @Override
    public HepLorentzVector col4() {
        return new HepLorentzVector(0, beta() * gamma(), 0, gamma());
    }

    @Override
    public HepRep4x4 rep4x4() {
        final double bg = beta_ * gamma_;
        return new HepRep4x4(1, 0, 0, 0, 0, gamma_, 0, bg, 0, 0, 1, 0, 0, bg, 0, gamma_);
    }

    @Override
    public HepRep4x4Symmetric rep4x4Symmetric() {
        final double bg = beta_ * gamma_;
        return new HepRep4x4Symmetric(1, 0, 0, 0, gamma_, 0, bg, 1, 0, gamma_);
    }

    @Override
    public HepLorentzVector times(HepLorentzVector p) {
        final double bg = beta_ * gamma_;
        return new HepLorentzVector(p.x(), gamma_ * p.y() + bg * p.t(), p.z(), gamma_ * p.t() + bg * p.y());
    }

    /** this * b: still a boost along the same axis, by the relativistic sum of the betas. */
    public HepBoostY times(HepBoostY b) {
        return new HepBoostY((beta() + b.beta()) / (1 + beta() * b.beta()));
    }

    @Override
    public double distance2(HepBoost b) {
        return b.distance2(this);
    }

    public double distance2(HepBoostY b) {
        return distance2Same(b);
    }

    public double howNear(HepBoostY b) {
        return Math.sqrt(distance2(b));
    }

    public boolean isNear(HepBoostY b) {
        return isNear(b, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoostY b, double epsilon) {
        return distance2(b) <= epsilon * epsilon;
    }

    public HepBoostY inverse() {
        return new HepBoostY(-beta(), gamma());
    }

    public static HepBoostY inverseOf(HepBoostY b) {
        return new HepBoostY(-b.beta(), b.gamma());
    }

    public HepBoostY invert() {
        beta_ = -beta_;
        return this;
    }

    public int compare(HepBoostY b) {
        if (beta() < b.beta()) {
            return -1;
        } else if (beta() > b.beta()) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public int compareTo(HepBoostY b) {
        return compare(b);
    }

    /** operator==: the same beta. */
    public boolean isEqual(HepBoostY b) {
        return beta_ == b.beta_;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepBoostY b && isEqual(b);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(beta_);
    }

    @Override
    protected char axisName() {
        return 'Y';
    }
}
