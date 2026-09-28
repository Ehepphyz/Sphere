package com.sphere.core.fastjet.plugins.siscone;

/**
 * siscone::Ceta_phi_range: the (eta, phi) extent of a jet as two 32-bit
 * masks of cells, for a quick overlap test. The eta cells span
 * [etaMin, etaMax], which the split-merge resets for each event, as the
 * static members of the C++ class are.
 */
final class EtaPhiRange {

    static double etaMin = -100.0;
    static double etaMax = 100.0;
    private static final int PHI_RANGE_MASK = 0xFFFFFFFF;

    int etaRange;
    int phiRange;

    EtaPhiRange() {
    }

    EtaPhiRange(EtaPhiRange o) {
        etaRange = o.etaRange;
        phiRange = o.phiRange;
    }

    EtaPhiRange(double cEta, double cPhi, double R) {
        double xmin = max(cEta - R, etaMin + 0.0001);
        double xmax = min(cEta + R, etaMax - 0.0001);
        int cellMin = etaCell(xmin);
        int cellMax = etaCell(xmax);
        etaRange = (cellMax - cellMin) + cellMax;
        xmin = Geom.phiInRange(cPhi - R);
        xmax = Geom.phiInRange(cPhi + R);
        cellMin = phiCell(xmin);
        cellMax = phiCell(xmax);
        if (xmax > xmin) {
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

    /** 1u << cell, the shift taken modulo 32 as the x86 instruction does. */
    static int etaCell(double eta) {
        return 1 << ((int) (32 * ((eta - etaMin) / (etaMax - etaMin))));
    }

    static int phiCell(double phi) {
        return 1 << (((int) (32 * phi / Geom.TWOPI + 16)) % 32);
    }

    void addParticle(double eta, double phi) {
        etaRange |= etaCell(eta);
        phiRange |= phiCell(phi);
    }

    static boolean isRangeOverlap(EtaPhiRange r1, EtaPhiRange r2) {
        return (r1.etaRange & r2.etaRange) != 0 && (r1.phiRange & r2.phiRange) != 0;
    }

    static EtaPhiRange union(EtaPhiRange r1, EtaPhiRange r2) {
        final EtaPhiRange tmp = new EtaPhiRange();
        tmp.etaRange = r1.etaRange | r2.etaRange;
        tmp.phiRange = r1.phiRange | r2.phiRange;
        return tmp;
    }
}
