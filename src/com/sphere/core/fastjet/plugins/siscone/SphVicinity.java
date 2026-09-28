package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.plugins.StdAlgorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * siscone_spherical::CSphvicinity: the centres, on the unit sphere, of the
 * cones of angular radius R through the parent and each particle within 2R
 * of it, in order of angle about the parent.
 */
class SphVicinity {

    static final class Elm {
        SphMomentum v;
        Vicinity.Inclusion isInside;
        final Sph3 centre = new Sph3();
        double angle;
        boolean side;
        double cocircularRange;
        final List<Elm> cocircular = new ArrayList<>();
    }

    SphMomentum parent;
    double VR;
    double VR2;
    double cosVR;
    double vR;
    double vR2;
    double vTan2R;
    double d2R;
    double invREpsCocirc;
    double invR2EpsCocirc;
    int nPart;
    final List<SphMomentum> plist = new ArrayList<>();
    final List<Vicinity.Inclusion> pincluded = new ArrayList<>();
    Elm[] veList = new Elm[0];
    final List<Elm> vicinity = new ArrayList<>();
    int vicinitySize;
    private Sph3 parentCentre = new Sph3();
    private final Sph3 angularDir1 = new Sph3();
    private final Sph3 angularDir2 = new Sph3();

    void setParticleList(List<SphMomentum> particleList) {
        vicinity.clear();
        nPart = 0;
        plist.clear();
        pincluded.clear();
        for (SphMomentum p : particleList) {
            final SphMomentum c = p.copy();
            plist.add(c);
            pincluded.add(new Vicinity.Inclusion());
            c.index = nPart;
            c.ref.randomize();
            nPart++;
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

    void build(SphMomentum parentIn, double vrIn) {
        parent = parentIn;
        VR = vrIn;
        VR2 = VR * VR;
        cosVR = CRMath.cos(VR);
        vR2 = 0.25 * VR2;
        vR = 0.5 * VR;
        final double tmp = CRMath.tan(vR);
        vTan2R = tmp * tmp;
        d2R = 2.0 * (1 - CRMath.cos(vR));
        invREpsCocirc = 1.0 / vR / Geom.EPSILON_COCIRCULAR;
        invR2EpsCocirc = 0.5 / vR / Geom.EPSILON_COCIRCULAR;
        vicinity.clear();
        parentCentre = parent.div3(parent.norm);
        parentCentre.angularDirections(angularDir1, angularDir2);
        angularDir1.divEq(angularDir1.norm);
        angularDir2.divEq(angularDir2.norm);
        for (int i = 0; i < nPart; i++) appendToVicinity(plist.get(i));
        StdAlgorithms.sort(vicinity, (a, b) -> a.angle < b.angle);
        vicinitySize = vicinity.size();
    }

    private void appendToVicinity(SphMomentum v) {
        if (v == parent) return;
        final int i = 2 * v.index;
        double dot = Sph3.dot(parentCentre, v);
        final Sph3 vnormal = v.copy3();
        vnormal.divEq(v.norm);
        dot /= v.norm;
        if (dot > cosVR) {
            final Sph3 cross = Sph3.cross(parentCentre, vnormal);
            final Sph3 median = parentCentre.plus3(vnormal);
            final double amplT = Math.sqrt((vTan2R * (1 + dot) + (dot - 1)) * (1 + dot));
            final Sph3 transverse = Sph3.times(amplT, cross).div3(cross.norm);
            final Elm e0 = veList[i];
            e0.centre.assign3(median.plus3(transverse));
            e0.centre.buildNorm();
            e0.centre.divEq(e0.centre.norm);
            Sph3 diff = e0.centre.minus3(parentCentre);
            e0.angle = Vicinity.sortAngle(Sph3.dot(angularDir2, diff), Sph3.dot(angularDir1, diff));
            e0.side = true;
            e0.cocircular.clear();
            vicinity.add(e0);
            final Elm e1 = veList[i + 1];
            e1.centre.assign3(median.minus3(transverse));
            e1.centre.buildNorm();
            e1.centre.divEq(e1.centre.norm);
            diff = e1.centre.minus3(parentCentre);
            e1.angle = Vicinity.sortAngle(Sph3.dot(angularDir2, diff), Sph3.dot(angularDir1, diff));
            e1.side = false;
            e1.cocircular.clear();
            vicinity.add(e1);
            final Sph3 op = parentCentre.minus3(e1.centre);
            final Sph3 oc = vnormal.minus3(e1.centre);
            final double invErr1 = Sph3.cross(op, oc).norm * invREpsCocirc;
            final double invErr2Sq = (d2R - Sph3.dot(op, oc)) * invR2EpsCocirc;
            e0.cocircularRange = Geom.pow2(invErr1) > invErr2Sq ? 1.0 / invErr1 : Math.sqrt(1.0 / invErr2Sq);
            e1.cocircularRange = e0.cocircularRange;
        }
    }
}
