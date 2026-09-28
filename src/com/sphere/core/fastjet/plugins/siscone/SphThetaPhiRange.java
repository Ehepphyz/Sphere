package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;

/**
 * siscone_spherical::CSphtheta_phi_range: the (theta, phi) extent of a jet
 * as two 32-bit masks of cells. (The upper phi bound is built from c_phi-R,
 * as in the C++.)
 */
final class SphThetaPhiRange {

    static double thetaMin = 0.0;
    static double thetaMax = Geom.M_PI;
    private static final int PHI_RANGE_MASK = 0xFFFFFFFF;

    int thetaRange;
    int phiRange;

    SphThetaPhiRange() {
    }

    SphThetaPhiRange(SphThetaPhiRange o) {
        thetaRange = o.thetaRange;
        phiRange = o.phiRange;
    }

    SphThetaPhiRange(double cTheta, double cPhi, double R) {
        final double xmin = max(cTheta - R, thetaMin + 0.00001);
        final double xmax = min(cTheta + R, thetaMax - 0.00001);
        int cellMin = thetaCell(xmin);
        int cellMax = thetaCell(xmax);
        thetaRange = (cellMax - cellMin) + cellMax;
        double ymin;
        double ymax;
        double extra = CRMath.asin(R / Geom.M_PI);
        if (xmin <= thetaMin + extra) {
            ymin = -Geom.M_PI + 0.00001;
            ymax = Geom.M_PI - 0.00001;
        } else if (xmax >= thetaMax - extra) {
            ymin = -Geom.M_PI + 0.00001;
            ymax = Geom.M_PI - 0.00001;
        } else {
            extra = max(1.0 / CRMath.sin(xmin), 1.0 / CRMath.sin(xmax));
            ymin = (cPhi - R) * extra;
            while (ymin < -Geom.M_PI) ymin += Geom.TWOPI;
            while (ymin > Geom.M_PI) ymin -= Geom.TWOPI;
            ymax = (cPhi - R) * extra;
            while (ymax < -Geom.M_PI) ymax += Geom.TWOPI;
            while (ymax > Geom.M_PI) ymax -= Geom.TWOPI;
        }
        cellMin = phiCell(ymin);
        cellMax = phiCell(ymax);
        if (ymax > ymin) {
            phiRange = (cellMax - cellMin) + cellMax;
        } else {
            phiRange = (cellMin == cellMax) ? PHI_RANGE_MASK : ((PHI_RANGE_MASK ^ (cellMin - cellMax)) + cellMax);
        }
    }

    private static double max(double a, double b) {
        return (a < b) ? b : a;
    }

    private static double min(double a, double b) {
        return (b < a) ? b : a;
    }

    static int thetaCell(double theta) {
        if (theta >= thetaMax) return 1 << 31;
        return 1 << ((int) (32 * ((theta - thetaMin) / (thetaMax - thetaMin))));
    }

    static int phiCell(double phi) {
        return 1 << (((int) (32 * phi / Geom.TWOPI + 16)) % 32);
    }

    void addParticle(double theta, double phi) {
        final int cell = thetaCell(theta);
        thetaRange |= cell;
        if (cell == 0x1 || cell == 0x80000000) phiRange = 0xffffffff;
        else phiRange |= phiCell(phi);
    }

    static boolean isRangeOverlap(SphThetaPhiRange r1, SphThetaPhiRange r2) {
        return (r1.thetaRange & r2.thetaRange) != 0 && (r1.phiRange & r2.phiRange) != 0;
    }

    static SphThetaPhiRange union(SphThetaPhiRange r1, SphThetaPhiRange r2) {
        final SphThetaPhiRange t = new SphThetaPhiRange();
        t.thetaRange = r1.thetaRange | r2.thetaRange;
        t.phiRange = r1.phiRange | r2.phiRange;
        return t;
    }
}
