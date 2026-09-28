package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.plugins.StdAlgorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * siscone_spherical::CSphstable_cones and sph_hash_cones: the stable-cone
 * search of {@link StableCones} on the sphere, cones being angular discs
 * tested through the tangent of the angle.
 */
class SphStableCones extends SphVicinity {

    /** sph_hash_element and sph_hash_cones. */
    static final class Hash {
        static final class Element {
            final Sph3 centre = new Sph3();
            boolean isStable;
            Element next;
        }

        final Element[] hashArray;
        final int mask;
        int nCones;
        final double tan2R;

        Hash(int np, double radius) {
            int nbits = (int) (CRMath.log(np * radius * radius * np / 4.0) / CRMath.log(2.0));
            if (nbits < 1) nbits = 1;
            final int size = 1 << nbits;
            hashArray = new Element[size];
            mask = size - 1;
            final double t = CRMath.tan(radius);
            tan2R = t * t;
        }

        void insert(SphMomentum v, SphMomentum parent, SphMomentum child, boolean pIo, boolean cIo) {
            final int index = v.ref.r0 & mask;
            Element elm = hashArray[index];
            while (true) {
                if (elm == null) {
                    elm = new Element();
                    elm.centre.assign3(v);
                    elm.isStable = !((Sph3.isCloser(v, parent, tan2R) ^ pIo) || (Sph3.isCloser(v, child, tan2R) ^ cIo));
                    elm.next = hashArray[index];
                    hashArray[index] = elm;
                    nCones++;
                    return;
                }
                if (v.ref.sameAs(elm.centre.ref)) {
                    if (elm.isStable) {
                        elm.isStable = !((Sph3.isCloser(v, parent, tan2R) ^ pIo)
                                         || (Sph3.isCloser(v, child, tan2R) ^ cIo));
                    }
                    return;
                }
                elm = elm.next;
            }
        }

        void insert(SphMomentum v) {
            final int index = v.ref.r0 & mask;
            Element elm = hashArray[index];
            while (true) {
                if (elm == null) {
                    elm = new Element();
                    elm.centre.assign3(v);
                    elm.isStable = true;
                    elm.next = hashArray[index];
                    hashArray[index] = elm;
                    nCones++;
                    return;
                }
                if (v.ref.sameAs(elm.centre.ref)) return;
                elm = elm.next;
            }
        }
    }

    private static final class BorderStore {
        final SphMomentum mom;
        final double angle;
        boolean isIn;

        BorderStore(SphMomentum momentum, Sph3 centre, Sph3 dir1, Sph3 dir2) {
            mom = momentum;
            final Sph3 diff = momentum.minus3(centre);
            angle = CRMath.atan2(Sph3.dot(diff, dir2), Sph3.dot(diff, dir1));
        }
    }

    final List<SphMomentum> protocones = new ArrayList<>();
    Hash hc;
    double R;
    double R2;
    double tan2R;

    private final SphMomentum cone = new SphMomentum();
    private SphMomentum child;
    private Elm centre;
    private int centreIdx;
    private int firstCone;
    private final SphMomentum coneCandidate = new SphMomentum();
    private final List<Creference[]> multipleCentreDone = new ArrayList<>();
    private double dpt;

    void init(List<SphMomentum> particleList) {
        hc = null;
        protocones.clear();
        multipleCentreDone.clear();
        setParticleList(particleList);
    }

    int getStableCones(double radius) {
        if (nPart == 0) return 0;
        R = radius;
        R2 = R * R;
        final double t = CRMath.tan(R);
        tan2R = t * t;
        hc = new Hash(nPart, R);
        for (int pIdx = 0; pIdx < nPart; pIdx++) {
            build(plist.get(pIdx), 2.0 * R);
            if (vicinitySize == 0) {
                protocones.add(parent.copy());
                continue;
            }
            initCone();
            do {
                testCone();
            } while (!updateCone());
        }
        return proceedWithStability();
    }

    private void initCone() {
        firstCone = 0;
        prepareCocircularLists();
        centre = vicinity.get(firstCone);
        child = centre.v;
        centreIdx = firstCone;
        computeConeContents();
    }

    private void testCone() {
        if (parent.E >= child.E) {
            coneCandidate.assign(cone);
            if (cone.ref.notEmpty()) hc.insert(coneCandidate, parent, child, false, false);
            coneCandidate.add(parent);
            hc.insert(coneCandidate, parent, child, true, false);
            coneCandidate.assign(cone);
            coneCandidate.add(child);
            hc.insert(coneCandidate, parent, child, false, true);
            coneCandidate.add(parent);
            hc.insert(coneCandidate, parent, child, true, true);
        }
    }

    private static double absSum3(Sph3 v) {
        return Math.abs(v.px) + Math.abs(v.py) + Math.abs(v.pz);
    }

    private boolean updateCone() {
        while (true) {
            centreIdx++;
            if (centreIdx == vicinitySize) centreIdx = 0;
            if (centreIdx == firstCone) return true;
            if (!centre.side) {
                cone.add(child);
                centre.isInside.cone = true;
                dpt += absSum3(child);
            }
            centre = vicinity.get(centreIdx);
            child = centre.v;
            if (cocircularCheck()) continue;
            if (centre.side && cone.ref.notEmpty()) {
                cone.subtract(child);
                centre.isInside.cone = false;
                dpt += absSum3(child);
            }
            if ((dpt > Geom.PT_TSHOLD * absSum3(cone)) && cone.ref.notEmpty()) recomputeConeContents();
            if (cone.ref.isEmpty()) {
                cone.assign(new SphMomentum());
                dpt = 0.0;
            }
            return false;
        }
    }

    private int proceedWithStability() {
        for (int i = 0; i <= hc.mask; i++) {
            for (Hash.Element elm = hc.hashArray[i]; elm != null; elm = elm.next) {
                if (elm.isStable && circleIntersect(elm.centre).sameAs(elm.centre.ref)) {
                    protocones.add(new SphMomentum(elm.centre, 1.0));
                }
            }
        }
        hc = null;
        return protocones.size();
    }

    private void prepareCocircularLists() {
        final int size = vicinity.size();
        int here = 0;
        do {
            final Elm herePntr = vicinity.get(here);
            int search = here;
            while (true) {
                search = (search + 1 == size) ? 0 : search + 1;
                if (Geom.absDphi(vicinity.get(search).angle, herePntr.angle) < herePntr.cocircularRange
                    && search != here) {
                    vicinity.get(search).cocircular.add(herePntr);
                } else {
                    break;
                }
            }
            search = here;
            while (true) {
                search = (search == 0) ? size - 1 : search - 1;
                if (Geom.absDphi(vicinity.get(search).angle, herePntr.angle) < herePntr.cocircularRange
                    && search != here) {
                    vicinity.get(search).cocircular.add(herePntr);
                } else {
                    break;
                }
            }
            here = (here + 1 == size) ? 0 : here + 1;
        } while (here != 0);
    }

    private void testConeCocircular(SphMomentum borderlessCone, List<SphMomentum> borderList) {
        final Sph3 dir1 = new Sph3();
        final Sph3 dir2 = new Sph3();
        centre.centre.angularDirections(dir1, dir2);
        dir1.divEq(dir1.norm);
        dir2.divEq(dir2.norm);
        final List<BorderStore> borderVect = new ArrayList<>(borderList.size());
        for (SphMomentum m : borderList) borderVect.add(new BorderStore(m, centre.centre, dir1, dir2));
        StdAlgorithms.sort(borderVect, (a, b) -> a.angle < b.angle);
        final int size = borderVect.size();
        int start = 0;
        final int end = 0;
        int mid;
        final SphMomentum candidate = borderlessCone.copy();
        if (candidate.ref.notEmpty()) testStability(candidate, borderVect);
        do {
            mid = start;
            do {
                borderVect.get(mid).isIn = false;
                mid = (mid + 1 == size) ? 0 : mid + 1;
            } while (mid != start);
            candidate.assign(borderlessCone);
            while (true) {
                mid = (mid + 1 == size) ? 0 : mid + 1;
                if (mid == start) break;
                borderVect.get(mid).isIn = true;
                candidate.add(borderVect.get(mid).mom);
                testStability(candidate, borderVect);
            }
            start = (start + 1 == size) ? 0 : start + 1;
        } while (start != end);
        borderVect.get(mid).isIn = true;
        candidate.add(borderVect.get(mid).mom);
        testStability(candidate, borderVect);
    }

    private void testStability(SphMomentum candidate, List<BorderStore> borderVect) {
        boolean stable = true;
        for (BorderStore b : borderVect) {
            if (Sph3.isCloser(candidate, b.mom, tan2R) ^ b.isIn) {
                stable = false;
                break;
            }
        }
        if (stable) hc.insert(candidate);
    }

    private boolean cocircularCheck() {
        if (centre.cocircular.isEmpty()) return false;
        if (centre.side && cone.ref.notEmpty()) {
            cone.subtract(child);
            centre.isInside.cone = false;
            dpt += absSum3(child);
        }
        final List<Vicinity.Inclusion> removedFromCone = new ArrayList<>();
        final List<Vicinity.Inclusion> putInBorder = new ArrayList<>();
        final List<SphMomentum> borderList = new ArrayList<>();
        final SphMomentum coneRemoval = new SphMomentum();
        final SphMomentum border = parent.copy();
        borderList.add(parent);
        centre.cocircular.add(centre);
        for (Elm it : centre.cocircular) {
            if (it.isInside.cone) {
                coneRemoval.add(it.v);
                it.isInside.cone = false;
                removedFromCone.add(it.isInside);
            }
            if (!it.isInside.cocirc) {
                border.add(it.v);
                it.isInside.cocirc = true;
                putInBorder.add(it.isInside);
                borderList.add(it.v);
            }
        }
        final SphMomentum borderlessCone = cone.copy();
        borderlessCone.subtract(coneRemoval);
        boolean consider = true;
        for (Creference[] done : multipleCentreDone) {
            if (done[0].sameAs(borderlessCone.ref) && done[1].sameAs(border.ref)) consider = false;
        }
        if (consider) {
            multipleCentreDone.add(new Creference[]{new Creference(borderlessCone.ref), new Creference(border.ref)});
            final double localDpt = Math.abs(coneRemoval.px) + Math.abs(coneRemoval.py);
            final double[] totalDpt = {dpt + localDpt};
            recomputeConeContentsIfNeeded(borderlessCone, totalDpt);
            if (totalDpt[0] == 0) {
                cone.assign(borderlessCone.plus(coneRemoval));
                dpt = localDpt;
            }
            testConeCocircular(borderlessCone, borderList);
        }
        for (Vicinity.Inclusion in : removedFromCone) in.cone = true;
        for (Vicinity.Inclusion in : putInBorder) in.cocirc = false;
        return true;
    }

    private void computeConeContents() {
        final int size = vicinity.size();
        final int start = firstCone;
        int here = start;
        do {
            if (!vicinity.get(here).side) vicinity.get(here).isInside.cone = true;
            here = (here + 1 == size) ? 0 : here + 1;
            if (vicinity.get(here).side) vicinity.get(here).isInside.cone = false;
        } while (here != start);
        recomputeConeContents();
    }

    private void recomputeConeContents() {
        cone.assign(new SphMomentum());
        for (int i = 0; i < vicinitySize; i++) {
            final Elm e = vicinity.get(i);
            if (e.side && e.isInside.cone) cone.add(e.v);
        }
        dpt = 0.0;
    }

    private void recomputeConeContentsIfNeeded(SphMomentum thisCone, double[] thisDpt) {
        if (thisDpt[0] > Geom.PT_TSHOLD * (Math.abs(thisCone.px) + Math.abs(thisCone.py))) {
            thisCone.assign(new SphMomentum());
            if (!cone.ref.isEmpty()) {
                for (int i = 0; i < vicinitySize; i++) {
                    final Elm e = vicinity.get(i);
                    if (e.side && e.isInside.cone) thisCone.add(e.v);
                }
            }
            thisDpt[0] = 0.0;
        }
    }

    private Creference circleIntersect(Sph3 coneCentre) {
        final Creference intersection = new Creference();
        for (int i = 0; i < nPart; i++) {
            if (Sph3.isCloser(coneCentre, plist.get(i), tan2R)) intersection.xor(plist.get(i).ref);
        }
        return intersection;
    }
}
