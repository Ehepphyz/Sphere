package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;

/**
 * The three-vector helpers of the e+e- Lund plane, namespace lund_plane of
 * LundEEHelpers.hh: cross products, 1 - cos theta stable at small angles,
 * signed angles between planes, and 3x3 rotations.
 */
public final class LundEEHelpers {

    private static final double EPS = Math.ulp(1.0);
    private static final double SQRT_EPS = Math.sqrt(EPS);

    private LundEEHelpers() {
    }

    /** The three-vector cross product; light-like when asked, else of zero energy. */
    public static PseudoJet crossProduct(PseudoJet p1, PseudoJet p2, boolean lightlike) {
        final double px = p1.py() * p2.pz() - p2.py() * p1.pz();
        final double py = p1.pz() * p2.px() - p2.pz() * p1.px();
        final double pz = p1.px() * p2.py() - p2.px() * p1.py();
        final double e = lightlike ? Math.sqrt(px * px + py * py + pz * pz) : 0.0;
        return new PseudoJet(px, py, pz, e);
    }

    public static PseudoJet crossProduct(PseudoJet p1, PseudoJet p2) {
        return crossProduct(p1, p2, false);
    }

    /** An angle mapped into [-pi, pi]. */
    public static double mapToPi(double phi) {
        if (phi < -Math.PI) return phi + 2 * Math.PI;
        if (phi > Math.PI) return phi - 2 * Math.PI;
        return phi;
    }

    public static double dotProduct3d(PseudoJet a, PseudoJet b) {
        return a.px() * b.px() + a.py() * b.py() + a.pz() * b.pz();
    }

    /** 1 - cos theta between the three-momenta, accurate when they are nearly parallel. */
    public static double oneMinusCostheta(PseudoJet p1, PseudoJet p2) {
        if (p1.m2() == 0 && p2.m2() == 0) {
            return PseudoJet.dotProduct(p1, p2) / (p1.E() * p2.E());
        }
        final double p1mod = p1.modp();
        final double p2mod = p2.modp();
        final double p1p2mod = p1mod * p2mod;
        final double dot = dotProduct3d(p1, p2);
        if (dot > (1 - EPS) * p1p2mod) {
            final PseudoJet cross = crossProduct(p1, p2, false);
            return -cross.m2() / (p1p2mod * (p1p2mod + dot));
        }
        return 1.0 - dot / p1p2mod;
    }

    /** The angle between the planes of normals n1 and n2, signed by the direction n, in (-pi, pi]. */
    public static double signedAngleBetweenPlanes(PseudoJet n1, PseudoJet n2, PseudoJet n) {
        if (!(Math.abs(n1.modp() - 1) < SQRT_EPS && Math.abs(n2.modp() - 1) < SQRT_EPS)) {
            throw new FastJetException("signed_angle_between_planes: the normals must have unit length");
        }
        final double omcost = oneMinusCostheta(n1, n2);
        final double theta;
        if (Math.abs(omcost - 2) < SQRT_EPS) {
            theta = Math.PI;
        } else if (omcost > SQRT_EPS) {
            theta = CRMath.acos(1.0 - omcost);
        } else {
            theta = Math.sqrt(2. * omcost);
        }
        final double sign = dotProduct3d(crossProduct(n1, n2), n);
        return sign > 0 ? theta : -theta;
    }

    /** A 3x3 matrix, Matrix3, with the rotations the Lund plane uses. */
    public static final class Matrix3 {
        private final double[][] m = new double[3][3];

        public Matrix3() {
        }

        /** unit times the identity. */
        public Matrix3(double unit) {
            m[0][0] = unit;
            m[1][1] = unit;
            m[2][2] = unit;
        }

        public Matrix3(double[][] mat) {
            for (int i = 0; i < 3; i++) System.arraycopy(mat[i], 0, m[i], 0, 3);
        }

        public double get(int i, int j) {
            return m[i][j];
        }

        /** A rotation about z by phi. */
        public static Matrix3 azimuthalRotation(double phi) {
            final double c = CRMath.cos(phi);
            final double s = CRMath.sin(phi);
            return new Matrix3(new double[][]{{c, s, 0}, {-s, c, 0}, {0, 0, 1}});
        }

        /** A rotation in the z-x plane by theta. */
        public static Matrix3 polarRotation(double theta) {
            final double c = CRMath.cos(theta);
            final double s = CRMath.sin(theta);
            return new Matrix3(new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}});
        }

        /**
         * The rotation that takes z to the direction of p; unless the
         * pre-rotation is skipped, azimuths far from p are kept when p is
         * close to z.
         */
        public static Matrix3 fromDirection(PseudoJet p, boolean skipPreRotation) {
            final double pt = p.pt();
            final double modp = p.modp();
            final double cosTheta = p.pz() / modp;
            final double sinTheta = pt / modp;
            final double cosPhi;
            final double sinPhi;
            if (pt > 0.0) {
                cosPhi = p.px() / pt;
                sinPhi = p.py() / pt;
            } else {
                cosPhi = 1.0;
                sinPhi = 0.0;
            }
            final Matrix3 phiRot = new Matrix3(new double[][]{{cosPhi, -sinPhi, 0}, {sinPhi, cosPhi, 0}, {0, 0, 1}});
            final Matrix3 thetaRot = new Matrix3(new double[][]{{cosTheta, 0, sinTheta}, {0, 1, 0}, {-sinTheta, 0, cosTheta}});
            return skipPreRotation ? phiRot.times(thetaRot) : phiRot.times(thetaRot.times(phiRot.transpose()));
        }

        public static Matrix3 fromDirection(PseudoJet p) {
            return fromDirection(p, false);
        }

        public static Matrix3 fromDirectionNoPreRotn(PseudoJet p) {
            return fromDirection(p, true);
        }

        public Matrix3 transpose() {
            final Matrix3 r = new Matrix3(m);
            double t = r.m[0][1]; r.m[0][1] = r.m[1][0]; r.m[1][0] = t;
            t = r.m[0][2]; r.m[0][2] = r.m[2][0]; r.m[2][0] = t;
            t = r.m[1][2]; r.m[1][2] = r.m[2][1]; r.m[2][1] = t;
            return r;
        }

        public Matrix3 times(Matrix3 other) {
            final Matrix3 r = new Matrix3();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    for (int k = 0; k < 3; k++) r.m[i][j] += m[i][k] * other.m[k][j];
                }
            }
            return r;
        }

        /** The matrix applied to the three-momentum; the energy and the rest of the jet are kept. */
        public PseudoJet times(PseudoJet p) {
            final double[] res = new double[3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) res[i] += m[i][j] * p.get(j);
            }
            final PseudoJet r = p.copy();
            r.resetMomentum(res[0], res[1], res[2], p.get(3));
            return r;
        }

        @Override
        public String toString() {
            final StringBuilder s = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                s.append(Fmt.g(m[i][0])).append(' ').append(Fmt.g(m[i][1])).append(' ').append(Fmt.g(m[i][2])).append('\n');
            }
            return s.toString();
        }
    }
}
