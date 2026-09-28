package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.plugins.StdAlgorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * siscone::Cstable_cones: the seedless search for all stable cones of radius
 * R (G.P. Salam, G. Soyez, JHEP 05 (2007) 086). For every particle taken as
 * the parent, the circles through it and each neighbour are visited in
 * order of angle, the cone contents being updated one particle at a time;
 * each candidate goes to the hash with whether its parent and child are
 * where they should be, cocircular configurations being enumerated apart.
 * The stable ones are checked once more against all particles at the end.
 */
class StableCones extends Vicinity {

    /** Cborder_store: a particle on a cocircular border, by angle about the centre. */
    private static final class BorderStore {
        final Cmomentum mom;
        final double angle;
        boolean isIn;

        BorderStore(Cmomentum mom, double centreEta, double centrePhi) {
            this.mom = mom;
            this.angle = CRMath.atan2(mom.phi - centrePhi, mom.eta - centreEta);
        }
    }

    final List<Cmomentum> protocones = new ArrayList<>();
    HashCones hc;
    int nbTot;
    /** Cstable_cones::R, R2. */
    double R;
    double R2;

    private final Cmomentum cone = new Cmomentum();
    private Cmomentum child;
    private Elm centre;
    private int centreIdx;
    private int firstCone;
    private final Cmomentum coneCandidate = new Cmomentum();
    private final List<Creference[]> multipleCentreDone = new ArrayList<>();
    private double dpt;

    void init(List<Cmomentum> particleList) {
        hc = null;
        protocones.clear();
        multipleCentreDone.clear();
        setParticleList(particleList);
    }

    int getStableCones(double radius) {
        if (nPart == 0) return 0;
        R = radius;
        R2 = R * R;
        hc = new HashCones(nPart, R2);
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
        if (centre.side) {
            coneCandidate.assign(cone);
            if (cone.ref.notEmpty()) hc.insert(coneCandidate, parent, child, false, false);
            coneCandidate.assign(cone);
            coneCandidate.add(parent.plus(child));
            hc.insert(coneCandidate, parent, child, true, true);
        } else {
            coneCandidate.assign(cone.plus(parent));
            hc.insert(coneCandidate, parent, child, true, false);
            coneCandidate.assign(cone.plus(child));
            hc.insert(coneCandidate, parent, child, false, true);
        }
        nbTot += 2;
    }

    /** Moves to the next centre; true when back to the first (the C++ recursion made a loop). */
    private boolean updateCone() {
        while (true) {
            centreIdx++;
            if (centreIdx == vicinitySize) centreIdx = 0;
            if (centreIdx == firstCone) return true;
            if (!centre.side) {
                cone.add(child);
                centre.isInside.cone = true;
                dpt += Math.abs(child.px) + Math.abs(child.py);
            }
            centre = vicinity.get(centreIdx);
            child = centre.v;
            if (cocircularCheck()) continue;
            if (centre.side && cone.ref.notEmpty()) {
                cone.subtract(child);
                centre.isInside.cone = false;
                dpt += Math.abs(child.px) + Math.abs(child.py);
            }
            if ((dpt > Geom.PT_TSHOLD * (Math.abs(cone.px) + Math.abs(cone.py))) && cone.ref.notEmpty()) {
                recomputeConeContents();
            }
            if (cone.ref.isEmpty()) {
                cone.assign(new Cmomentum());
                dpt = 0.0;
            }
            return false;
        }
    }

    private int proceedWithStability() {
        for (int i = 0; i <= hc.mask; i++) {
            for (HashCones.Element elm = hc.hashArray[i]; elm != null; elm = elm.next) {
                if (elm.isStable && circleIntersect(elm.eta, elm.phi).sameAs(elm.ref)) {
                    protocones.add(new Cmomentum(elm.eta, elm.phi, elm.ref));
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

    private void testConeCocircular(Cmomentum borderlessCone, List<Cmomentum> borderList) {
        final List<BorderStore> borderVect = new ArrayList<>(borderList.size());
        for (Cmomentum m : borderList) borderVect.add(new BorderStore(m, centre.eta, centre.phi));
        StdAlgorithms.sort(borderVect, (a, b) -> a.angle < b.angle);
        final int size = borderVect.size();
        int start = 0;
        final int end = 0;
        int mid;
        Cmomentum candidate = borderlessCone.copy();
        candidate.buildEtaPhi();
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

    private void testStability(Cmomentum candidate, List<BorderStore> borderVect) {
        candidate.buildEtaPhi();
        boolean stable = true;
        for (BorderStore b : borderVect) {
            if (isInside(candidate, b.mom) ^ b.isIn) {
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
            dpt += Math.abs(child.px) + Math.abs(child.py);
        }
        final List<Inclusion> removedFromCone = new ArrayList<>();
        final List<Inclusion> putInBorder = new ArrayList<>();
        final List<Cmomentum> borderList = new ArrayList<>();
        final Cmomentum coneRemoval = new Cmomentum();
        final Cmomentum border = parent.copy();
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
        final Cmomentum borderlessCone = cone.copy();
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
        for (Inclusion in : removedFromCone) in.cone = true;
        for (Inclusion in : putInBorder) in.cocirc = false;
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
        cone.assign(new Cmomentum());
        for (int i = 0; i < vicinitySize; i++) {
            final Elm e = vicinity.get(i);
            if (e.side && e.isInside.cone) cone.add(e.v);
        }
        dpt = 0.0;
    }

    private void recomputeConeContentsIfNeeded(Cmomentum thisCone, double[] thisDpt) {
        if (thisDpt[0] > Geom.PT_TSHOLD * (Math.abs(thisCone.px) + Math.abs(thisCone.py))) {
            if (cone.ref.isEmpty()) {
                thisCone.assign(new Cmomentum());
            } else {
                thisCone.assign(new Cmomentum());
                for (int i = 0; i < vicinitySize; i++) {
                    final Elm e = vicinity.get(i);
                    if (e.side && e.isInside.cone) thisCone.add(e.v);
                }
            }
            thisDpt[0] = 0.0;
        }
    }

    private Creference circleIntersect(double cx, double cy) {
        final Creference intersection = new Creference();
        for (int i = 0; i < nPart; i++) {
            final Cmomentum p = plist.get(i);
            final double dx = p.eta - cx;
            double dy = Math.abs(p.phi - cy);
            if (dy > Geom.M_PI) dy -= Geom.TWOPI;
            if (dx * dx + dy * dy < R2) intersection.xor(p.ref);
        }
        return intersection;
    }

    private boolean isInside(Cmomentum centreIn, Cmomentum v) {
        final double dx = centreIn.eta - v.eta;
        double dy = Math.abs(centreIn.phi - v.phi);
        if (dy > Geom.M_PI) dy -= Geom.TWOPI;
        return dx * dx + dy * dy < R2;
    }
}
