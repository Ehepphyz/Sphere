package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;

/**
 * siscone_spherical::CSph3vector: a three-vector with its cached norm, polar
 * and azimuthal angles and a reference.
 *
 * Its arithmetic is the C++ one to the letter: + and - act on the
 * components only (not the cached norm, nor the reference), *= on the
 * components only, /= on the components and the cached norm.
 */
public class Sph3 {

    public double px;
    public double py;
    public double pz;
    public double norm;
    public double theta;
    public double phi;
    final Creference ref = new Creference();

    public Sph3() {
    }

    /** CSph3vector(px, py, pz): the norm is built, the angles left at zero. */
    public Sph3(double px, double py, double pz) {
        this.px = px;
        this.py = py;
        this.pz = pz;
        buildNorm();
    }

    /** The copy constructor (and operator=, which copies the same fields). */
    public Sph3 copy3() {
        final Sph3 c = new Sph3();
        c.assign3(this);
        return c;
    }

    void assign3(Sph3 v) {
        px = v.px;
        py = v.py;
        pz = v.pz;
        norm = v.norm;
        theta = v.theta;
        phi = v.phi;
        ref.set(v.ref);
    }

    Sph3 plus3(Sph3 v) {
        final Sph3 t = copy3();
        t.addVec(v);
        return t;
    }

    Sph3 minus3(Sph3 v) {
        final Sph3 t = copy3();
        t.subVec(v);
        return t;
    }

    Sph3 div3(double r) {
        final Sph3 t = copy3();
        t.divEq(r);
        return t;
    }

    void addVec(Sph3 v) {
        px += v.px;
        py += v.py;
        pz += v.pz;
    }

    void subVec(Sph3 v) {
        px -= v.px;
        py -= v.py;
        pz -= v.pz;
    }

    void mulEq(double r) {
        px *= r;
        py *= r;
        pz *= r;
    }

    void divEq(double r) {
        px /= r;
        py /= r;
        pz /= r;
        norm /= r;
    }

    public double perp() {
        return Math.sqrt(perp2());
    }

    public double perp2() {
        return px * px + py * py;
    }

    public double norm() {
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    public double norm2() {
        return px * px + py * py + pz * pz;
    }

    public double phiOf() {
        return CRMath.atan2(py, px);
    }

    public double thetaOf() {
        return CRMath.atan2(perp(), pz);
    }

    void buildNorm() {
        norm = norm();
    }

    void buildThetaPhi() {
        theta = thetaOf();
        phi = phiOf();
    }

    /** Two directions orthogonal to this one (the C++ comparisons of signed components kept). */
    void angularDirections(Sph3 dir1, Sph3 dir2) {
        final Sph3 d1;
        if (px < py) {
            d1 = (pz < px) ? new Sph3(-py, px, 0.0) : new Sph3(0.0, -pz, py);
        } else {
            d1 = (pz < py) ? new Sph3(-py, px, 0.0) : new Sph3(-pz, 0.0, px);
        }
        dir1.assign3(d1);
        dir2.assign3(cross(this, dir1));
    }

    static double dot(Sph3 v1, Sph3 v2) {
        return v1.px * v2.px + v1.py * v2.py + v1.pz * v2.pz;
    }

    static Sph3 cross(Sph3 v1, Sph3 v2) {
        return new Sph3(v1.py * v2.pz - v1.pz * v2.py, v1.pz * v2.px - v1.px * v2.pz, v1.px * v2.py - v1.py * v2.px);
    }

    static double norm2Cross(Sph3 v1, Sph3 v2) {
        return Geom.pow2(v1.py * v2.pz - v1.pz * v2.py) + Geom.pow2(v1.pz * v2.px - v1.px * v2.pz)
            + Geom.pow2(v1.px * v2.py - v1.py * v2.px);
    }

    /** The angle between two directions. */
    static double distance(Sph3 v1, Sph3 v2) {
        return CRMath.atan2(Math.sqrt(norm2Cross(v1, v2)), dot(v1, v2));
    }

    /** Whether v2 is within the angle whose squared tangent is tan2R of v1. */
    static boolean isCloser(Sph3 v1, Sph3 v2, double tan2R) {
        final double dot = dot(v1, v2);
        return (dot >= 0) && (norm2Cross(v1, v2) <= tan2R * dot * dot);
    }

    /** operator*(double, CSph3vector). */
    static Sph3 times(double r, Sph3 v) {
        final Sph3 t = v.copy3();
        t.mulEq(r);
        return t;
    }
}
