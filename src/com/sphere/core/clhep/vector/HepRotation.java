package com.sphere.core.clhep.vector;

import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import static com.sphere.core.clhep.CMath.acos;
import static com.sphere.core.clhep.CMath.atan2;
import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepRotation (Vector/Rotation.h, .icc, Rotation.cc, RotationA.cc, RotationC.cc, RotationE.cc,
 * RotationIO.cc, RotationL.cc, RotationP.cc): a rotation in three dimensions as its 3x3 matrix, made
 * from an axis and angle, Euler angles, columns or rows, or step by step (rotateX, rotate about an
 * axis...). Geant4's G4RotationMatrix is this class.
 *
 * <p>{@code r * v} is {@link #times(Hep3Vector)}, {@code r1 * r2} {@link #times(HepRotation)},
 * {@code r1 *= r2} {@link #timesAssign} (r1 = r1 * r2) and {@link #transform} r1 = r2 * r1.
 */
public class HepRotation implements Comparable<HepRotation> {

    public static final HepRotation IDENTITY = new HepRotation();

    protected double rxx, rxy, rxz, ryx, ryy, ryz, rzx, rzy, rzz;

    /** The identity. */
    public HepRotation() {
        rxx = 1.0;
        ryy = 1.0;
        rzz = 1.0;
    }

    public HepRotation(HepRotation m1) {
        rxx = m1.rxx;
        rxy = m1.rxy;
        rxz = m1.rxz;
        ryx = m1.ryx;
        ryy = m1.ryy;
        ryz = m1.ryz;
        rzx = m1.rzx;
        rzy = m1.rzy;
        rzz = m1.rzz;
    }

    /** The matrix of a HepRotationX, Y or Z. */
    public HepRotation(HepAxialRotation r) {
        rxx = r.xx();
        rxy = r.xy();
        rxz = r.xz();
        ryx = r.yx();
        ryy = r.yy();
        ryz = r.yz();
        rzx = r.zx();
        rzy = r.zy();
        rzz = r.zz();
    }

    /** The nine elements, row by row (protected in C++). */
    protected HepRotation(double mxx, double mxy, double mxz, double myx, double myy, double myz,
                          double mzx, double mzy, double mzz) {
        rxx = mxx;
        rxy = mxy;
        rxz = mxz;
        ryx = myx;
        ryy = myy;
        ryz = myz;
        rzx = mzx;
        rzy = mzy;
        rzz = mzz;
    }

    public HepRotation(HepRep3x3 m1) {
        this(m1.xx_, m1.xy_, m1.xz_, m1.yx_, m1.yy_, m1.yz_, m1.zx_, m1.zy_, m1.zz_);
    }

    /** By delta about an axis. */
    public HepRotation(Hep3Vector aaxis, double ddelta) {
        set(aaxis, ddelta);
    }

    public HepRotation(HepAxisAngle ax) {
        set(ax.axis(), ax.delta());
    }

    /** From Euler angles. */
    public HepRotation(double phi1, double theta1, double psi1) {
        set(phi1, theta1, psi1);
    }

    public HepRotation(HepEulerAngles e) {
        set(e.phi(), e.theta(), e.psi());
    }

    /** From its three columns, made orthonormal (what cannot be is said on cerr). */
    public HepRotation(Hep3Vector ccolX, Hep3Vector ccolY, Hep3Vector ccolZ) {
        set(ccolX, ccolY, ccolZ);
    }

    public HepRotation copy() {
        return new HepRotation(this);
    }

    /** operator=. */
    public HepRotation assign(HepRotation m1) {
        rxx = m1.rxx;
        rxy = m1.rxy;
        rxz = m1.rxz;
        ryx = m1.ryx;
        ryy = m1.ryy;
        ryz = m1.ryz;
        rzx = m1.rzx;
        rzy = m1.rzy;
        rzz = m1.rzz;
        return this;
    }

    public HepRotation set(HepRep3x3 m1) {
        rxx = m1.xx_;
        rxy = m1.xy_;
        rxz = m1.xz_;
        ryx = m1.yx_;
        ryy = m1.yy_;
        ryz = m1.yz_;
        rzx = m1.zx_;
        rzy = m1.zy_;
        rzz = m1.zz_;
        return this;
    }

    /** set and operator= of a HepRotationX, Y or Z. */
    public HepRotation set(HepAxialRotation r) {
        return set(r.rep3x3());
    }

    public HepRotation assign(HepAxialRotation r) {
        return set(r.rep3x3());
    }

    // ------------------------------------------------------------------
    // Elements
    // ------------------------------------------------------------------

    public double xx() {
        return rxx;
    }

    public double xy() {
        return rxy;
    }

    public double xz() {
        return rxz;
    }

    public double yx() {
        return ryx;
    }

    public double yy() {
        return ryy;
    }

    public double yz() {
        return ryz;
    }

    public double zx() {
        return rzx;
    }

    public double zy() {
        return rzy;
    }

    public double zz() {
        return rzz;
    }

    public HepRep3x3 rep3x3() {
        return new HepRep3x3(rxx, rxy, rxz, ryx, ryy, ryz, rzx, rzy, rzz);
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

    public HepRep4x4 rep4x4() {
        return new HepRep4x4(rxx, rxy, rxz, 0.0, ryx, ryy, ryz, 0.0, rzx, rzy, rzz, 0.0, 0.0, 0.0, 0.0, 1.0);
    }

    public Hep3Vector colX() {
        return new Hep3Vector(rxx, ryx, rzx);
    }

    public Hep3Vector colY() {
        return new Hep3Vector(rxy, ryy, rzy);
    }

    public Hep3Vector colZ() {
        return new Hep3Vector(rxz, ryz, rzz);
    }

    public Hep3Vector rowX() {
        return new Hep3Vector(rxx, rxy, rxz);
    }

    public Hep3Vector rowY() {
        return new Hep3Vector(ryx, ryy, ryz);
    }

    public Hep3Vector rowZ() {
        return new Hep3Vector(rzx, rzy, rzz);
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

    /** operator()(i, j): the element at row i, column j; bad indices are said on cerr and give 0. */
    public double get(int i, int j) {
        if (i == 0) {
            if (j == 0) return xx();
            if (j == 1) return xy();
            if (j == 2) return xz();
        } else if (i == 1) {
            if (j == 0) return yx();
            if (j == 1) return yy();
            if (j == 2) return yz();
        } else if (i == 2) {
            if (j == 0) return zx();
            if (j == 1) return zy();
            if (j == 2) return zz();
        }
        StdStreams.cerr().print("HepRotation subscripting: bad indices (" + i + "," + j + ")\n");
        return 0.0;
    }

    /** operator[](i): the row i, read by its own [j]. */
    public Row row(int i) {
        return new Row(this, i);
    }

    /** HepRotation_row: r[i][j]. */
    public record Row(HepRotation rr, int ii) {
        public double get(int jj) {
            return rr.get(ii, jj);
        }
    }

    // ------------------------------------------------------------------
    // Axis and angle
    // ------------------------------------------------------------------

    public HepRotation set(Hep3Vector aaxis, double ddelta) {
        final double sinDelta = sin(ddelta), cosDelta = cos(ddelta);
        final double oneMinusCosDelta = 1.0 - cosDelta;
        final Hep3Vector u = aaxis.unit();
        final double uX = u.getX();
        final double uY = u.getY();
        final double uZ = u.getZ();
        rxx = oneMinusCosDelta * uX * uX + cosDelta;
        rxy = oneMinusCosDelta * uX * uY - sinDelta * uZ;
        rxz = oneMinusCosDelta * uX * uZ + sinDelta * uY;
        ryx = oneMinusCosDelta * uY * uX + sinDelta * uZ;
        ryy = oneMinusCosDelta * uY * uY + cosDelta;
        ryz = oneMinusCosDelta * uY * uZ - sinDelta * uX;
        rzx = oneMinusCosDelta * uZ * uX - sinDelta * uY;
        rzy = oneMinusCosDelta * uZ * uY + sinDelta * uX;
        rzz = oneMinusCosDelta * uZ * uZ + cosDelta;
        return this;
    }

    public HepRotation set(HepAxisAngle ax) {
        return set(ax.axis(), ax.delta());
    }

    public double delta() {
        final double cosdelta = (rxx + ryy + rzz - 1.0) / 2.0;
        if (cosdelta > 1.0) {
            return 0;
        } else if (cosdelta < -1.0) {
            return SystemOfUnits.pi;
        } else {
            return acos(cosdelta);
        }
    }

    public Hep3Vector axis() {
        final double eps = 1e-15;
        final double Ux = rzy - ryz;
        final double Uy = rxz - rzx;
        final double Uz = ryx - rxy;
        if (Math.abs(Ux) < eps && Math.abs(Uy) < eps && Math.abs(Uz) < eps) {
            final double cosdelta = (rxx + ryy + rzz - 1.0) / 2.0;
            if (cosdelta > 0.0) return new Hep3Vector(0, 0, 1);   // angle = 0, any axis is good
            final double xxt = (rxx + 1) / 2;
            final double yyt = (ryy + 1) / 2;
            final double zzt = (rzz + 1) / 2;
            final double xyt = (rxy + ryx) / 4;
            final double xzt = (rxz + rzx) / 4;
            final double yzt = (ryz + rzy) / 4;
            double x, y, z;
            if (xxt > ryy && xxt > rzz) {
                x = sqrt(xxt);
                if (rzy - ryz < 0) x = -x;
                y = xyt / x;
                z = xzt / x;
                return new Hep3Vector(x, y, z).unit();
            } else if (yyt > zzt) {
                y = sqrt(yyt);
                if (rxz - rzx < 0) y = -y;
                x = xyt / y;
                z = yzt / y;
                return new Hep3Vector(x, y, z).unit();
            } else {
                z = sqrt(zzt);
                if (ryx - rxy < 0) z = -z;
                x = xzt / z;
                y = yzt / z;
                return new Hep3Vector(x, y, z).unit();
            }
        } else {
            return new Hep3Vector(Ux, Uy, Uz).unit();
        }
    }

    public HepAxisAngle axisAngle() {
        return new HepAxisAngle(axis(), delta());
    }

    public double getDelta() {
        return delta();
    }

    public Hep3Vector getAxis() {
        return axis();
    }

    public void setAxis(Hep3Vector aaxis) {
        set(aaxis, delta());
    }

    public void setDelta(double ddelta) {
        set(axis(), ddelta);
    }

    /** getAngleAxis(angle, axis): the axis into the vector given, the angle returned. */
    public double getAngleAxis(Hep3Vector aaxis) {
        final double cosa = 0.5 * (xx() + yy() + zz() - 1);
        final double cosa1 = 1 - cosa;
        if (cosa1 <= 0) {
            aaxis.assign(new Hep3Vector(0, 0, 1));
            return 0;
        }
        double x = 0, y = 0, z = 0;
        if (xx() > cosa) x = sqrt((xx() - cosa) / cosa1);
        if (yy() > cosa) y = sqrt((yy() - cosa) / cosa1);
        if (zz() > cosa) z = sqrt((zz() - cosa) / cosa1);
        if (zy() < yz()) x = -x;
        if (xz() < zx()) y = -y;
        if (yx() < xy()) z = -z;
        final double angle = (cosa < -1.) ? acos(-1.) : acos(cosa);
        aaxis.assign(new Hep3Vector(x, y, z));
        return angle;
    }

    // ------------------------------------------------------------------
    // Euler angles
    // ------------------------------------------------------------------

    public HepRotation set(double phi1, double theta1, double psi1) {
        final double sinPhi = sin(phi1), cosPhi = cos(phi1);
        final double sinTheta = sin(theta1), cosTheta = cos(theta1);
        final double sinPsi = sin(psi1), cosPsi = cos(psi1);
        rxx = cosPsi * cosPhi - cosTheta * sinPhi * sinPsi;
        rxy = cosPsi * sinPhi + cosTheta * cosPhi * sinPsi;
        rxz = sinPsi * sinTheta;
        ryx = -sinPsi * cosPhi - cosTheta * sinPhi * cosPsi;
        ryy = -sinPsi * sinPhi + cosTheta * cosPhi * cosPsi;
        ryz = cosPsi * sinTheta;
        rzx = sinTheta * sinPhi;
        rzy = -sinTheta * cosPhi;
        rzz = cosTheta;
        return this;
    }

    public HepRotation set(HepEulerAngles e) {
        return set(e.phi(), e.theta(), e.psi());
    }

    public double phi() {
        double s2 = 1.0 - rzz * rzz;
        if (s2 < 0) {
            ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "HepRotation::phi() finds | rzz | > 1 ", "HepRotation::phi");
            s2 = 0;
        }
        final double sinTheta = sqrt(s2);
        if (sinTheta < .01) {   // for theta close to 0 or pi, the more stable eulerAngles()
            return eulerAngles().phi();
        }
        final double cscTheta = 1 / sinTheta;
        double cosabsphi = -rzy * cscTheta;
        if (fabs(cosabsphi) > 1) {
            ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "HepRotation::phi() finds | cos phi | > 1 ", "HepRotation::phi");
            cosabsphi = 1;
        }
        final double absPhi = acos(cosabsphi);
        if (rzx > 0) {
            return absPhi;
        } else if (rzx < 0) {
            return -absPhi;
        } else {
            return (rzy < 0) ? 0 : SystemOfUnits.pi;
        }
    }

    public double theta() {
        return HepAxialRotation.safe_acos(rzz);
    }

    public double psi() {
        double sinTheta;
        if (fabs(rzz) > 1) {
            ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "HepRotation::psi() finds | rzz | > 1", "HepRotation::psi");
            sinTheta = 0;
        } else {
            sinTheta = sqrt(1.0 - rzz * rzz);
        }
        if (sinTheta < .01) {
            return eulerAngles().psi();
        }
        final double cscTheta = 1 / sinTheta;
        double cosabspsi = ryz * cscTheta;
        if (fabs(cosabspsi) > 1) {
            ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "HepRotation::psi() finds | cos psi | > 1", "HepRotation::psi");
            cosabspsi = 1;
        }
        final double absPsi = acos(cosabspsi);
        if (rxz > 0) {
            return absPsi;
        } else if (rxz < 0) {
            return -absPsi;
        } else {
            return (ryz > 0) ? 0 : SystemOfUnits.pi;
        }
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

    /** psi and phi shifted by pi together: psiPhi[0] is psi, psiPhi[1] phi. */
    private static void correctByPi(double[] psiPhi) {
        if (psiPhi[0] > 0) {
            psiPhi[0] -= SystemOfUnits.pi;
        } else {
            psiPhi[0] += SystemOfUnits.pi;
        }
        if (psiPhi[1] > 0) {
            psiPhi[1] -= SystemOfUnits.pi;
        } else {
            psiPhi[1] += SystemOfUnits.pi;
        }
    }

    private static void correctPsiPhi(double rxz, double rzx, double ryz, double rzy, double[] psiPhi) {
        final double[] w = {rxz, rzx, ryz, -rzy};
        double maxw = Math.abs(w[0]);
        int imax = 0;
        for (int i = 1; i < 4; ++i) {
            if (Math.abs(w[i]) > maxw) {
                maxw = Math.abs(w[i]);
                imax = i;
            }
        }
        switch (imax) {
            case 0 -> {
                if (w[0] > 0 && psiPhi[0] < 0) correctByPi(psiPhi);
                if (w[0] < 0 && psiPhi[0] > 0) correctByPi(psiPhi);
            }
            case 1 -> {
                if (w[1] > 0 && psiPhi[1] < 0) correctByPi(psiPhi);
                if (w[1] < 0 && psiPhi[1] > 0) correctByPi(psiPhi);
            }
            case 2 -> {
                if (w[2] > 0 && Math.abs(psiPhi[0]) > SystemOfUnits.halfpi) correctByPi(psiPhi);
                if (w[2] < 0 && Math.abs(psiPhi[0]) < SystemOfUnits.halfpi) correctByPi(psiPhi);
            }
            default -> {
                if (w[3] > 0 && Math.abs(psiPhi[1]) > SystemOfUnits.halfpi) correctByPi(psiPhi);
                if (w[3] < 0 && Math.abs(psiPhi[1]) < SystemOfUnits.halfpi) correctByPi(psiPhi);
            }
        }
    }

    public HepEulerAngles eulerAngles() {
        final double theta1 = HepAxialRotation.safe_acos(rzz);
        double psiPlusPhi, psiMinusPhi;
        if (rzz > 1 || rzz < -1) {
            ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "HepRotation::eulerAngles() finds | rzz | > 1 ",
                "HepRotation::eulerAngles");
        }
        double cosTheta = rzz;
        if (cosTheta > 1) cosTheta = 1;
        if (cosTheta < -1) cosTheta = -1;
        if (cosTheta == 1) {
            psiPlusPhi = atan2(rxy - ryx, rxx + ryy);
            psiMinusPhi = 0;
        } else if (cosTheta >= 0) {
            psiPlusPhi = atan2(rxy - ryx, rxx + ryy);
            final double s1 = -rxy - ryx;   // sin(psi-phi) * (1 - cos theta)
            final double c = rxx - ryy;     // cos(psi-phi) * (1 - cos theta)
            psiMinusPhi = atan2(s1, c);
        } else if (cosTheta > -1) {
            psiMinusPhi = atan2(-rxy - ryx, rxx - ryy);
            final double s1 = rxy - ryx;    // sin(psi+phi) * (1 + cos theta)
            final double c = rxx + ryy;     // cos(psi+phi) * (1 + cos theta)
            psiPlusPhi = atan2(s1, c);
        } else {   // cosTheta == -1
            psiMinusPhi = atan2(-rxy - ryx, rxx - ryy);
            psiPlusPhi = 0;
        }
        final double[] psiPhi = {.5 * (psiPlusPhi + psiMinusPhi), .5 * (psiPlusPhi - psiMinusPhi)};
        correctPsiPhi(rxz, rzx, ryz, rzy, psiPhi);
        return new HepEulerAngles(psiPhi[1], theta1, psiPhi[0]);
    }

    public void setPhi(double phi1) {
        set(phi1, theta(), psi());
    }

    public void setTheta(double theta1) {
        set(phi(), theta1, psi());
    }

    public void setPsi(double psi1) {
        set(phi(), theta(), psi1);
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
        return HepAxialRotation.safe_acos(zx());
    }

    public double thetaY() {
        return HepAxialRotation.safe_acos(zy());
    }

    public double thetaZ() {
        return HepAxialRotation.safe_acos(zz());
    }

    // ------------------------------------------------------------------
    // Columns and rows
    // ------------------------------------------------------------------

    /**
     * setCols: v1 = u1, v2 = u2 made orthogonal to u1, v3 = v1 x v2 (into v[0], v[1], v[2]); false when
     * that looks more like a reflection.
     */
    private boolean setCols(Hep3Vector u1, Hep3Vector u2, Hep3Vector u3, double u1u2, Hep3Vector[] v) {
        if ((1 - fabs(u1u2)) <= Hep4RotationInterface.tolerance) {
            ZMxpv.throwC(ZMxpv.PARALLEL_COLS, "All three cols supplied for a Rotation are parallel --"
                + "\n    an arbitrary rotation will be returned", "HepRotation::setCols");
            setArbitrarily(u1, v);
            return true;
        }
        v[0] = new Hep3Vector(u1);
        v[1] = u2.minus(u1.times(u1u2)).unit();
        v[2] = v[0].cross(v[1]);
        return v[2].dot(u3) >= 0;
    }

    private void setArbitrarily(Hep3Vector ccolX, Hep3Vector[] v) {
        v[0] = ccolX.unit();
        v[1] = v[0].cross(new Hep3Vector(0, 0, 1));
        if (v[1].mag2() != 0) {
            v[1] = v[1].unit();
        } else {
            v[1] = new Hep3Vector(1, 0, 0);
        }
        v[2] = v[0].cross(v[1]);
    }

    public HepRotation set(Hep3Vector ccolX, Hep3Vector ccolY, Hep3Vector ccolZ) {
        final Hep3Vector ucolX = ccolX.unit();
        final Hep3Vector ucolY = ccolY.unit();
        final Hep3Vector ucolZ = ccolZ.unit();
        final double u1u2 = ucolX.dot(ucolY);
        final double f12 = fabs(u1u2);
        if (f12 > Hep4RotationInterface.tolerance) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "col's X and Y supplied for Rotation are not close to orthogonal",
                "HepRotation::set");
        }
        final double u1u3 = ucolX.dot(ucolZ);
        final double f13 = fabs(u1u3);
        if (f13 > Hep4RotationInterface.tolerance) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "col's X and Z supplied for Rotation are not close to orthogonal",
                "HepRotation::set");
        }
        final double u2u3 = ucolY.dot(ucolZ);
        final double f23 = fabs(u2u3);
        if (f23 > Hep4RotationInterface.tolerance) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "col's Y and Z supplied for Rotation are not close to orthogonal",
                "HepRotation::set");
        }
        final Hep3Vector[] w = new Hep3Vector[3];
        Hep3Vector v1, v2, v3;
        if ((f12 <= f13) && (f12 <= f23)) {
            final boolean isRotation = setCols(ucolX, ucolY, ucolZ, u1u2, w);
            v1 = w[0];
            v2 = w[1];
            v3 = w[2];
            if (!isRotation) {
                ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "col's X Y and Z supplied form closer to a reflection than a "
                    + "Rotation \n     col Z is set to col X cross col Y", "HepRotation::set");
            }
        } else if (f13 <= f23) {
            final boolean isRotation = setCols(ucolZ, ucolX, ucolY, u1u3, w);
            v3 = w[0];
            v1 = w[1];
            v2 = w[2];
            if (!isRotation) {
                ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "col's X Y and Z supplied form closer to a reflection than a "
                    + "Rotation \n     col Y is set to col Z cross col X", "HepRotation::set");
            }
        } else {
            final boolean isRotation = setCols(ucolY, ucolZ, ucolX, u2u3, w);
            v2 = w[0];
            v3 = w[1];
            v1 = w[2];
            if (!isRotation) {
                ZMxpv.throwC(ZMxpv.IMPROPER_ROTATION, "col's X Y and Z supplied form closer to a reflection than a "
                    + "Rotation \n     col X is set to col Y cross col Z", "HepRotation::set");
            }
        }
        rxx = v1.x();
        ryx = v1.y();
        rzx = v1.z();
        rxy = v2.x();
        ryy = v2.y();
        rzy = v2.z();
        rxz = v3.x();
        ryz = v3.y();
        rzz = v3.z();
        return this;
    }

    public HepRotation setRows(Hep3Vector rrowX, Hep3Vector rrowY, Hep3Vector rrowZ) {
        set(rrowX, rrowY, rrowZ);
        invert();
        return this;
    }

    /** Brings the matrix back to a rotation: averaged with its inverse transposed, then remade from its axis and angle. */
    public void rectify() {
        final double det = rxx * ryy * rzz + rxy * ryz * rzx + rxz * ryx * rzy - rxx * ryz * rzy - rxy * ryx * rzz
            - rxz * ryy * rzx;
        if (det <= 0) {
            ZMxpv.throwA(ZMxpv.IMPROPER_ROTATION, "Attempt to rectify a Rotation with determinant <= 0\n",
                "HepRotation::rectify");
        }
        final double di = 1.0 / det;
        final double xx1 = (ryy * rzz - ryz * rzy) * di;
        final double xy1 = (rzy * rxz - rzz * rxy) * di;
        final double xz1 = (rxy * ryz - rxz * ryy) * di;
        final double yx1 = (ryz * rzx - ryx * rzz) * di;
        final double yy1 = (rzz * rxx - rzx * rxz) * di;
        final double yz1 = (rxz * ryx - rxx * ryz) * di;
        final double zx1 = (ryx * rzy - ryy * rzx) * di;
        final double zy1 = (rzx * rxy - rzy * rxx) * di;
        final double zz1 = (rxx * ryy - rxy * ryx) * di;
        rxx = .5 * (rxx + xx1);
        rxy = .5 * (rxy + yx1);
        rxz = .5 * (rxz + zx1);
        ryx = .5 * (ryx + xy1);
        ryy = .5 * (ryy + yy1);
        ryz = .5 * (ryz + zy1);
        rzx = .5 * (rzx + xz1);
        rzy = .5 * (rzy + yz1);
        rzz = .5 * (rzz + zz1);
        final double del = delta();
        Hep3Vector u = axis();
        u = u.unit();
        set(u, del);
    }

    // ------------------------------------------------------------------
    // Comparisons
    // ------------------------------------------------------------------

    public boolean isIdentity() {
        return rxx == 1.0 && rxy == 0.0 && rxz == 0.0 && ryx == 0.0 && ryy == 1.0 && ryz == 0.0 && rzx == 0.0
            && rzy == 0.0 && rzz == 1.0;
    }

    /** zz first, then zy... down to xx. */
    public int compare(HepRotation r) {
        if (rzz < r.rzz) return -1;
        else if (rzz > r.rzz) return 1;
        else if (rzy < r.rzy) return -1;
        else if (rzy > r.rzy) return 1;
        else if (rzx < r.rzx) return -1;
        else if (rzx > r.rzx) return 1;
        else if (ryz < r.ryz) return -1;
        else if (ryz > r.ryz) return 1;
        else if (ryy < r.ryy) return -1;
        else if (ryy > r.ryy) return 1;
        else if (ryx < r.ryx) return -1;
        else if (ryx > r.ryx) return 1;
        else if (rxz < r.rxz) return -1;
        else if (rxz > r.rxz) return 1;
        else if (rxy < r.rxy) return -1;
        else if (rxy > r.rxy) return 1;
        else if (rxx < r.rxx) return -1;
        else if (rxx > r.rxx) return 1;
        else return 0;
    }

    @Override
    public int compareTo(HepRotation r) {
        return compare(r);
    }

    /** operator==: the nine elements equal. */
    public boolean isEqual(HepRotation r) {
        return rxx == r.rxx && rxy == r.rxy && rxz == r.rxz && ryx == r.ryx && ryy == r.ryy && ryz == r.ryz
            && rzx == r.rzx && rzy == r.rzy && rzz == r.rzz;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepRotation r && isEqual(r);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(new double[] {rxx, rxy, rxz, ryx, ryy, ryz, rzx, rzy, rzz});
    }

    public boolean lessThan(HepRotation r) {
        return compare(r) < 0;
    }

    public boolean lessOrEqual(HepRotation r) {
        return compare(r) <= 0;
    }

    public boolean greaterThan(HepRotation r) {
        return compare(r) > 0;
    }

    public boolean greaterOrEqual(HepRotation r) {
        return compare(r) >= 0;
    }

    public double distance2(HepRotation r) {
        final double sum = rxx * r.rxx + rxy * r.rxy + rxz * r.rxz + ryx * r.ryx + ryy * r.ryy + ryz * r.ryz
            + rzx * r.rzx + rzy * r.rzy + rzz * r.rzz;
        final double answer = 3.0 - sum;
        return (answer >= 0) ? answer : 0;
    }

    public double howNear(HepRotation r) {
        return sqrt(distance2(r));
    }

    public boolean isNear(HepRotation r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotation r, double epsilon) {
        return distance2(r) <= epsilon * epsilon;
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

    public double howNear(HepLorentzRotation lt) {
        return sqrt(distance2(lt));
    }

    public double howNear(HepBoost lt) {
        return sqrt(distance2(lt));
    }

    public boolean isNear(HepLorentzRotation lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepLorentzRotation lt, double epsilon) {
        return distance2(lt) <= epsilon * epsilon;
    }

    public boolean isNear(HepBoost lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoost lt, double epsilon) {
        return distance2(lt) <= epsilon * epsilon;
    }

    public double norm2() {
        final double answer = 3.0 - rxx - ryy - rzz;
        return (answer >= 0) ? answer : 0;
    }

    public void decompose(HepAxisAngle rotation, Hep3Vector boost) {
        boost.set(0, 0, 0);
        rotation.assign(axisAngle());
    }

    public void decompose(Hep3Vector boost, HepAxisAngle rotation) {
        boost.set(0, 0, 0);
        rotation.assign(axisAngle());
    }

    public static double getTolerance() {
        return Hep4RotationInterface.tolerance;
    }

    public static double setTolerance(double tol) {
        return Hep4RotationInterface.setTolerance(tol);
    }

    // ------------------------------------------------------------------
    // Application and products
    // ------------------------------------------------------------------

    /** r * v. */
    public Hep3Vector times(Hep3Vector p) {
        return new Hep3Vector(rxx * p.x() + rxy * p.y() + rxz * p.z(),
            ryx * p.x() + ryy * p.y() + ryz * p.z(),
            rzx * p.x() + rzy * p.y() + rzz * p.z());
    }

    /** r(v): as r * v. */
    public Hep3Vector apply(Hep3Vector p) {
        final double x = p.x();
        final double y = p.y();
        final double z = p.z();
        return new Hep3Vector(rxx * x + rxy * y + rxz * z, ryx * x + ryy * y + ryz * z, rzx * x + rzy * y + rzz * z);
    }

    public HepLorentzVector apply(HepLorentzVector w) {
        return new HepLorentzVector(apply(w.vect()), w.t());
    }

    public HepLorentzVector times(HepLorentzVector p) {
        return apply(p);
    }

    public HepRotation times(HepRotation r) {
        return new HepRotation(rxx * r.rxx + rxy * r.ryx + rxz * r.rzx,
            rxx * r.rxy + rxy * r.ryy + rxz * r.rzy,
            rxx * r.rxz + rxy * r.ryz + rxz * r.rzz,
            ryx * r.rxx + ryy * r.ryx + ryz * r.rzx,
            ryx * r.rxy + ryy * r.ryy + ryz * r.rzy,
            ryx * r.rxz + ryy * r.ryz + ryz * r.rzz,
            rzx * r.rxx + rzy * r.ryx + rzz * r.rzx,
            rzx * r.rxy + rzy * r.ryy + rzz * r.rzy,
            rzx * r.rxz + rzy * r.ryz + rzz * r.rzz);
    }

    public HepRotation times(HepRotationX rx) {
        final double yy1 = rx.yy();
        final double yz1 = rx.yz();
        final double zy1 = -yz1;
        final double zz1 = yy1;
        return new HepRotation(
            rxx, rxy * yy1 + rxz * zy1, rxy * yz1 + rxz * zz1,
            ryx, ryy * yy1 + ryz * zy1, ryy * yz1 + ryz * zz1,
            rzx, rzy * yy1 + rzz * zy1, rzy * yz1 + rzz * zz1);
    }

    public HepRotation times(HepRotationY ry) {
        final double xx1 = ry.xx();
        final double xz1 = ry.xz();
        final double zx1 = -xz1;
        final double zz1 = xx1;
        return new HepRotation(
            rxx * xx1 + rxz * zx1, rxy, rxx * xz1 + rxz * zz1,
            ryx * xx1 + ryz * zx1, ryy, ryx * xz1 + ryz * zz1,
            rzx * xx1 + rzz * zx1, rzy, rzx * xz1 + rzz * zz1);
    }

    public HepRotation times(HepRotationZ rz) {
        final double xx1 = rz.xx();
        final double xy1 = rz.xy();
        final double yx1 = -xy1;
        final double yy1 = xx1;
        return new HepRotation(
            rxx * xx1 + rxy * yx1, rxx * xy1 + rxy * yy1, rxz,
            ryx * xx1 + ryy * yx1, ryx * xy1 + ryy * yy1, ryz,
            rzx * xx1 + rzy * yx1, rzx * xy1 + rzy * yy1, rzz);
    }

    /** rx * r. */
    public static HepRotation times(HepRotationX rx, HepRotation r) {
        final HepRep3x3 mmm = r.rep3x3();
        final double c = rx.yy();
        final double ss = rx.zy();
        return new HepRotation(mmm.xx_, mmm.xy_, mmm.xz_,
            c * mmm.yx_ - ss * mmm.zx_, c * mmm.yy_ - ss * mmm.zy_, c * mmm.yz_ - ss * mmm.zz_,
            ss * mmm.yx_ + c * mmm.zx_, ss * mmm.yy_ + c * mmm.zy_, ss * mmm.yz_ + c * mmm.zz_);
    }

    /** ry * r. */
    public static HepRotation times(HepRotationY ry, HepRotation r) {
        final HepRep3x3 mmm = r.rep3x3();
        final double c = ry.xx();
        final double ss = ry.xz();
        return new HepRotation(c * mmm.xx_ + ss * mmm.zx_, c * mmm.xy_ + ss * mmm.zy_, c * mmm.xz_ + ss * mmm.zz_,
            mmm.yx_, mmm.yy_, mmm.yz_,
            -ss * mmm.xx_ + c * mmm.zx_, -ss * mmm.xy_ + c * mmm.zy_, -ss * mmm.xz_ + c * mmm.zz_);
    }

    /** rz * r. */
    public static HepRotation times(HepRotationZ rz, HepRotation r) {
        final HepRep3x3 mmm = r.rep3x3();
        final double c = rz.xx();
        final double ss = rz.yx();
        return new HepRotation(c * mmm.xx_ - ss * mmm.yx_, c * mmm.xy_ - ss * mmm.yy_, c * mmm.xz_ - ss * mmm.yz_,
            ss * mmm.xx_ + c * mmm.yx_, ss * mmm.xy_ + c * mmm.yy_, ss * mmm.xz_ + c * mmm.yz_,
            mmm.zx_, mmm.zy_, mmm.zz_);
    }

    /** rx * r, ry * r or rz * r for a rotation known only as about an axis. */
    public static HepRotation times(HepAxialRotation a, HepRotation r) {
        if (a instanceof HepRotationX x) return times(x, r);
        if (a instanceof HepRotationY y) return times(y, r);
        return times((HepRotationZ) a, r);
    }

    /** this = this * r. */
    public HepRotation timesAssign(HepRotation r) {
        return assign(times(r));
    }

    public HepRotation timesAssign(HepRotationX r) {
        return assign(times(r));
    }

    public HepRotation timesAssign(HepRotationY r) {
        return assign(times(r));
    }

    public HepRotation timesAssign(HepRotationZ r) {
        return assign(times(r));
    }

    /** this = r * this. */
    public HepRotation transform(HepRotation r) {
        return assign(r.times(this));
    }

    public HepRotation transform(HepRotationX r) {
        return assign(times(r, this));
    }

    public HepRotation transform(HepRotationY r) {
        return assign(times(r, this));
    }

    public HepRotation transform(HepRotationZ r) {
        return assign(times(r, this));
    }

    public HepRotation rotateX(double a) {
        final double c1 = cos(a);
        final double s1 = sin(a);
        final double x1 = ryx, y1 = ryy, z1 = ryz;
        ryx = c1 * x1 - s1 * rzx;
        ryy = c1 * y1 - s1 * rzy;
        ryz = c1 * z1 - s1 * rzz;
        rzx = s1 * x1 + c1 * rzx;
        rzy = s1 * y1 + c1 * rzy;
        rzz = s1 * z1 + c1 * rzz;
        return this;
    }

    public HepRotation rotateY(double a) {
        final double c1 = cos(a);
        final double s1 = sin(a);
        final double x1 = rzx, y1 = rzy, z1 = rzz;
        rzx = c1 * x1 - s1 * rxx;
        rzy = c1 * y1 - s1 * rxy;
        rzz = c1 * z1 - s1 * rxz;
        rxx = s1 * x1 + c1 * rxx;
        rxy = s1 * y1 + c1 * rxy;
        rxz = s1 * z1 + c1 * rxz;
        return this;
    }

    public HepRotation rotateZ(double a) {
        final double c1 = cos(a);
        final double s1 = sin(a);
        final double x1 = rxx, y1 = rxy, z1 = rxz;
        rxx = c1 * x1 - s1 * ryx;
        rxy = c1 * y1 - s1 * ryy;
        rxz = c1 * z1 - s1 * ryz;
        ryx = s1 * x1 + c1 * ryx;
        ryy = s1 * y1 + c1 * ryy;
        ryz = s1 * z1 + c1 * ryz;
        return this;
    }

    /** Rotated by a about an axis (a rotation applied after this one). */
    public HepRotation rotate(double a, Hep3Vector aaxis) {
        if (a != 0.0) {
            final double ll = aaxis.mag();
            if (ll == 0.0) {
                ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "HepRotation: zero axis", "HepRotation::rotate");
            } else {
                final double sa = sin(a), ca = cos(a);
                final double dx = aaxis.x() / ll, dy = aaxis.y() / ll, dz = aaxis.z() / ll;
                final HepRotation m1 = new HepRotation(
                    ca + (1 - ca) * dx * dx, (1 - ca) * dx * dy - sa * dz, (1 - ca) * dx * dz + sa * dy,
                    (1 - ca) * dy * dx + sa * dz, ca + (1 - ca) * dy * dy, (1 - ca) * dy * dz - sa * dx,
                    (1 - ca) * dz * dx - sa * dy, (1 - ca) * dz * dy + sa * dx, ca + (1 - ca) * dz * dz);
                transform(m1);
            }
        }
        return this;
    }

    /** Rotated so that the axes become the new ones given; bad axes are said on cerr. */
    public HepRotation rotateAxes(Hep3Vector newX, Hep3Vector newY, Hep3Vector newZ) {
        final double del = 0.001;
        final Hep3Vector w = newX.cross(newY);
        if (Math.abs(newZ.x() - w.x()) > del || Math.abs(newZ.y() - w.y()) > del || Math.abs(newZ.z() - w.z()) > del
            || Math.abs(newX.mag2() - 1.) > del || Math.abs(newY.mag2() - 1.) > del || Math.abs(newZ.mag2() - 1.) > del
            || Math.abs(newX.dot(newY)) > del || Math.abs(newY.dot(newZ)) > del || Math.abs(newZ.dot(newX)) > del) {
            StdStreams.cerr().print("HepRotation::rotateAxes: bad axis vectors\n");
            return this;
        } else {
            return transform(new HepRotation(newX.x(), newY.x(), newZ.x(), newX.y(), newY.y(), newZ.y(),
                newX.z(), newY.z(), newZ.z()));
        }
    }

    /** The transpose. */
    public HepRotation inverse() {
        return new HepRotation(rxx, ryx, rzx, rxy, ryy, rzy, rxz, ryz, rzz);
    }

    public static HepRotation inverseOf(HepRotation r) {
        return r.inverse();
    }

    public HepRotation invert() {
        return assign(inverse());
    }

    // ------------------------------------------------------------------
    // Print
    // ------------------------------------------------------------------

    /** The matrix in three rows of width 11, precision 6 (left on the stream, as setprecision is in C++). */
    public COStream print(COStream os) {
        os.put("\n   [ ( ").setw(11).setprecision(6).put(xx()).put("   ").setw(11).setprecision(6).put(xy())
            .put("   ").setw(11).setprecision(6).put(xz()).put(")\n")
            .put("     ( ").setw(11).setprecision(6).put(yx()).put("   ").setw(11).setprecision(6).put(yy())
            .put("   ").setw(11).setprecision(6).put(yz()).put(")\n")
            .put("     ( ").setw(11).setprecision(6).put(zx()).put("   ").setw(11).setprecision(6).put(zy())
            .put("   ").setw(11).setprecision(6).put(zz()).put(") ]\n");
        return os;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
