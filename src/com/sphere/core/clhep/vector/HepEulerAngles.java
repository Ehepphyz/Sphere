package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepEulerAngles (Vector/EulerAngles.h, .icc, EulerAngles.cc): a rotation as the Euler angles
 * phi, theta, psi of Goldstein's convention (z, then x', then z''). Nearness compares the rotations'
 * matrices.
 */
public final class HepEulerAngles implements Comparable<HepEulerAngles> {

    private static double tolerance = Hep3Vector.ToleranceTicks * 1.0e-8;

    private double phi_;
    private double theta_;
    private double psi_;

    public HepEulerAngles() {
    }

    public HepEulerAngles(double phi1, double ttheta, double psi1) {
        phi_ = phi1;
        theta_ = ttheta;
        psi_ = psi1;
    }

    public HepEulerAngles(HepEulerAngles o) {
        this(o.phi_, o.theta_, o.psi_);
    }

    public double getPhi() {
        return phi_;
    }

    public double phi() {
        return phi_;
    }

    public HepEulerAngles setPhi(double phi1) {
        phi_ = phi1;
        return this;
    }

    public double getTheta() {
        return theta_;
    }

    public double theta() {
        return theta_;
    }

    public HepEulerAngles setTheta(double ttheta) {
        theta_ = ttheta;
        return this;
    }

    public double getPsi() {
        return psi_;
    }

    public double psi() {
        return psi_;
    }

    public HepEulerAngles setPsi(double psi1) {
        psi_ = psi1;
        return this;
    }

    public HepEulerAngles set(double phi1, double ttheta, double psi1) {
        phi_ = phi1;
        theta_ = ttheta;
        psi_ = psi1;
        return this;
    }

    /** phi first, then theta, then psi. */
    public int compare(HepEulerAngles ea) {
        return phi_ < ea.phi_ ? -1
            : phi_ > ea.phi_ ? +1
            : theta_ < ea.theta_ ? -1
            : theta_ > ea.theta_ ? +1
            : psi_ < ea.psi_ ? -1
            : psi_ > ea.psi_ ? +1
            : 0;
    }

    @Override
    public int compareTo(HepEulerAngles o) {
        return compare(o);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepEulerAngles e && compare(e) == 0;
    }

    @Override
    public int hashCode() {
        return (Double.hashCode(phi_) * 31 + Double.hashCode(theta_)) * 31 + Double.hashCode(psi_);
    }

    public static double getTolerance() {
        return tolerance;
    }

    public static double setTolerance(double tol) {
        final double old = tolerance;
        tolerance = tol;
        return old;
    }

    /** The rotation matrix of the Euler angles, row by row. */
    static void rep(HepEulerAngles ex, double[] array) {
        final double sinPhi = sin(ex.phi()), cosPhi = cos(ex.phi());
        final double sinTheta = sin(ex.theta()), cosTheta = cos(ex.theta());
        final double sinPsi = sin(ex.psi()), cosPsi = cos(ex.psi());
        array[0] = cosPsi * cosPhi - sinPsi * cosTheta * sinPhi;
        array[1] = cosPsi * sinPhi + sinPsi * cosTheta * cosPhi;
        array[2] = sinPsi * sinTheta;
        array[3] = -sinPsi * cosPhi - cosPsi * cosTheta * sinPhi;
        array[4] = -sinPsi * sinPhi + cosPsi * cosTheta * cosPhi;
        array[5] = cosPsi * sinTheta;
        array[6] = sinTheta * sinPhi;
        array[7] = -sinTheta * cosPhi;
        array[8] = cosTheta;
    }

    double distance(HepEulerAngles ex) {
        final double[] thisRep = new double[9];
        final double[] exRep = new double[9];
        rep(this, thisRep);
        rep(ex, exRep);
        double sum = 0.0;
        for (int i = 0; i < 9; i++) {
            sum += thisRep[i] * exRep[i];
        }
        final double d = 3.0 - sum;
        return (d >= 0) ? d : 0;
    }

    public boolean isNear(HepEulerAngles ex) {
        return isNear(ex, tolerance);
    }

    public boolean isNear(HepEulerAngles ex, double epsilon) {
        return distance(ex) <= epsilon * epsilon;
    }

    public double howNear(HepEulerAngles ex) {
        return sqrt(distance(ex));
    }

    /** operator&lt;&lt;: "(phi, theta, psi)". */
    public COStream print(COStream os) {
        return os.put("(").put(phi()).put(", ").put(theta()).put(", ").put(psi()).put(")");
    }

    public static CIStream read(CIStream is, HepEulerAngles ea) {
        final double[] v = new double[3];
        ZMinput.input3doubles(is, "HepEulerAngle", v);
        ea.set(v[0], v[1], v[2]);
        return is;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
