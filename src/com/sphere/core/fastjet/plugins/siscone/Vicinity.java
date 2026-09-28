package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.plugins.StdAlgorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * siscone::Cvicinity: for a parent particle, the points where circles of
 * radius R through it and through each particle within 2R are centred, in
 * order of angle around the parent (each particle giving two, one on each
 * side).
 */
class Vicinity {

    /** Cvicinity_inclusion: whether a particle is in the cone, in the cocircular border. */
    static final class Inclusion {
        boolean cone;
        boolean cocirc;
    }

    /** Cvicinity_elm. */
    static final class Elm {
        Cmomentum v;
        Inclusion isInside;
        double eta;
        double phi;
        double angle;
        boolean side;
        double cocircularRange;
        final List<Elm> cocircular = new ArrayList<>();
    }

    Cmomentum parent;
    double VR;
    double VR2;
    /** Cvicinity::R, R2 (Cstable_cones has its own, of the same values). */
    double vR;
    double vR2;
    double invREpsCocirc;
    double invR2EpsCocirc;
    int nPart;
    final List<Cmomentum> plist = new ArrayList<>();
    final List<Inclusion> pincluded = new ArrayList<>();
    Elm[] veList = new Elm[0];
    final List<Elm> vicinity = new ArrayList<>();
    int vicinitySize;
    private double pcx;
    private double pcy;

    void setParticleList(List<Cmomentum> particleList) {
        vicinity.clear();
        nPart = 0;
        plist.clear();
        pincluded.clear();
        for (Cmomentum p : particleList) {
            if (Math.abs(p.pz) != p.E) {
                final Cmomentum c = p.copy();
                plist.add(c);
                pincluded.add(new Inclusion());
                c.index = nPart;
                c.ref.randomize();
                nPart++;
            }
        }
        veList = new Elm[2 * nPart];
        for (int k = 0; k < 2 * nPart; k++) veList[k] = new Elm();
        int j = 0;
        for (int i = 0; i < nPart; i++) {
            veList[j].v = veList[j + 1].v = plist.get(i);
            veList[j].isInside = veList[j + 1].isInside = pincluded.get(i);
            j += 2;
        }
    }

    void build(Cmomentum parentIn, double vrIn) {
        parent = parentIn;
        VR = vrIn;
        VR2 = VR * VR;
        vR2 = 0.25 * VR2;
        vR = 0.5 * VR;
        invREpsCocirc = 1.0 / vR / Geom.EPSILON_COCIRCULAR;
        invR2EpsCocirc = 0.5 / vR / Geom.EPSILON_COCIRCULAR;
        vicinity.clear();
        pcx = parent.eta;
        pcy = parent.phi;
        for (int i = 0; i < nPart; i++) appendToVicinity(plist.get(i));
        StdAlgorithms.sort(vicinity, (a, b) -> a.angle < b.angle);
        vicinitySize = vicinity.size();
    }

    static double sortAngle(double s, double c) {
        if (s == 0) return (c > 0) ? 0.0 : 2.0;
        final double t = c / s;
        return (s > 0) ? 1 - t / (1 + Math.abs(t)) : 3 - t / (1 + Math.abs(t));
    }

    private void appendToVicinity(Cmomentum v) {
        if (v == parent) return;
        final int i = 2 * v.index;
        final double dx = v.eta - pcx;
        double dy = v.phi - pcy;
        if (dy > Geom.M_PI) dy -= Geom.TWOPI;
        else if (dy < -Geom.M_PI) dy += Geom.TWOPI;
        final double d2 = dx * dx + dy * dy;
        if (d2 < VR2) {
            final double tmp = Math.sqrt(VR2 / d2 - 1);
            double c = 0.5 * (dx - dy * tmp);
            double s = 0.5 * (dy + dx * tmp);
            final Elm e0 = veList[i];
            e0.angle = sortAngle(s, c);
            e0.eta = pcx + c;
            e0.phi = Geom.phiInRange(pcy + s);
            e0.side = true;
            e0.cocircular.clear();
            vicinity.add(e0);
            c = 0.5 * (dx + dy * tmp);
            s = 0.5 * (dy - dx * tmp);
            final Elm e1 = veList[i + 1];
            e1.angle = sortAngle(s, c);
            e1.eta = pcx + c;
            e1.phi = Geom.phiInRange(pcy + s);
            e1.side = false;
            e1.cocircular.clear();
            vicinity.add(e1);
            // the range over which a point is cocircular
            final double opx = pcx - e1.eta;
            final double opy = Geom.phiInRange(pcy - e1.phi);
            final double ocx = v.eta - e1.eta;
            final double ocy = Geom.phiInRange(v.phi - e1.phi);
            c = opx * ocx + opy * ocy;
            s = Math.abs(opx * ocy - opy * ocx);
            final double invErr1 = s * invREpsCocirc;
            final double invErr2Sq = (vR2 - c) * invR2EpsCocirc;
            e0.cocircularRange = Geom.pow2(invErr1) > invErr2Sq ? 1.0 / invErr1 : Math.sqrt(1.0 / invErr2Sq);
            e1.cocircularRange = e0.cocircularRange;
        }
    }
}
