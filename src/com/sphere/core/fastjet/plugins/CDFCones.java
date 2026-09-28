package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.CRMath;

import java.util.ArrayList;
import java.util.List;

/**
 * The classes of the CDF cone code (namespace cdf of FastJet's CDFCones
 * plugin, from the implementation by J. Huston): four-vectors, calorimeter
 * towers, centroids and clusters, shared by {@link CDFJetCluPlugin} and
 * {@link CDFMidPointPlugin}.
 *
 * Towers and clusters are compared by value, as in the C++ code (two
 * particles of identical momenta are then the same tower to it).
 */
final class CDFCones {

    private CDFCones() {
    }

    static final double PI = Math.PI;

    /** cdf::LorentzVector. */
    static final class LorentzVector {
        double px, py, pz, E;

        LorentzVector() {
        }

        LorentzVector(double px, double py, double pz, double e) {
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.E = e;
        }

        LorentzVector(LorentzVector o) {
            this(o.px, o.py, o.pz, o.E);
        }

        double p() {
            return Math.sqrt(px * px + py * py + pz * pz);
        }

        double pt() {
            return Math.sqrt(px * px + py * py);
        }

        double mt() {
            return Math.sqrt((E - pz) * (E + pz));
        }

        double y() {
            return 0.5 * CRMath.log((E + pz) / (E - pz));
        }

        double Et() {
            return E / p() * pt();
        }

        double eta() {
            return 0.5 * CRMath.log((p() + pz) / (p() - pz));
        }

        double phi() {
            double r = CRMath.atan2(py, px);
            if (r < 0) r += 2 * PI;
            return r;
        }

        void add(LorentzVector v) {
            px += v.px;
            py += v.py;
            pz += v.pz;
            E += v.E;
        }

        void subtract(LorentzVector v) {
            px -= v.px;
            py -= v.py;
            pz -= v.pz;
            E -= v.E;
        }

        boolean isEqual(LorentzVector v) {
            return px == v.px && py == v.py && pz == v.pz && E == v.E;
        }
    }

    static final double[] TOWER_THETA = {3.000, 5.700, 8.400, 11.100, 13.800, 16.500, 19.200, 21.900, 24.600,
        27.300, 30.000, 33.524, 36.822, 40.261, 43.614, 47.436, 51.790, 56.735, 62.310, 68.516, 75.297, 82.526,
        90.000};

    /** cdf::CalTower: the CDF calorimeter cell a direction falls in. */
    static final class CalTower {
        final double Et, eta, phi;
        int iEta, iPhi;

        CalTower(double et0, double eta0, double phi0) {
            this.Et = et0;
            this.eta = eta0;
            this.phi = phi0;
            if (Math.abs(eta) < -CRMath.log(CRMath.tan(TOWER_THETA[0] * PI / 180 / 2))) {
                // the C++ leaves the index unset when no cell is found; it
                // always is, the last boundary being eta = 0
                iEta = -1;
                if (eta <= 0) {
                    for (int i = 0; i < 22; i++) {
                        if (eta < -CRMath.log(CRMath.tan((180 - TOWER_THETA[i + 1]) * PI / 180 / 2))) {
                            iEta = 4 + i;
                            break;
                        }
                    }
                } else {
                    for (int i = 0; i < 22; i++) {
                        if (-eta < -CRMath.log(CRMath.tan((180 - TOWER_THETA[i + 1]) * PI / 180 / 2))) {
                            iEta = 47 - i;
                            break;
                        }
                    }
                }
                if ((iEta >= 8 && iEta < 14) || (iEta >= 38 && iEta < 44)) {
                    iPhi = ((int) (phi / 2 / PI * 48)) % 48;
                } else {
                    iPhi = ((int) (phi / 2 / PI * 24)) % 24;
                }
            } else {
                iEta = -1;
                iPhi = -1;
            }
        }

        boolean isEqual(CalTower c) {
            return Et == c.Et && eta == c.eta && phi == c.phi && iEta == c.iEta && iPhi == c.iPhi;
        }
    }

    /** cdf::PhysicsTower: a particle and its tower. */
    static final class PhysicsTower {
        final LorentzVector fourVector;
        final CalTower calTower;
        int fjindex = -1;

        PhysicsTower(LorentzVector v) {
            fourVector = new LorentzVector(v);
            calTower = new CalTower(v.Et(), v.eta(), v.phi());
        }

        double Et() {
            return calTower.Et;
        }

        double eta() {
            return calTower.eta;
        }

        double phi() {
            return calTower.phi;
        }

        int iEta() {
            return calTower.iEta;
        }

        int iPhi() {
            return calTower.iPhi;
        }

        boolean isEqual(PhysicsTower p) {
            return fourVector.isEqual(p.fourVector) && calTower.isEqual(p.calTower);
        }
    }

    /** cdf::Centroid: an Et-weighted (eta, phi). */
    static final class Centroid {
        double Et, eta, phi;

        Centroid() {
        }

        Centroid(double et, double eta, double phi) {
            this.Et = et;
            this.eta = eta;
            this.phi = phi;
        }

        Centroid(Centroid c) {
            this(c.Et, c.eta, c.phi);
        }

        void add(Centroid c) {
            final double newEt = Et + c.Et;
            eta = (Et * eta + c.Et * c.eta) / newEt;
            double dPhi = c.phi - phi;
            if (dPhi > PI) dPhi -= 2 * PI;
            else if (dPhi < -PI) dPhi += 2 * PI;
            phi += dPhi * c.Et / newEt;
            while (phi < 0) phi += 2 * PI;
            while (phi >= 2 * PI) phi -= 2 * PI;
            Et = newEt;
        }

        void subtract(Centroid c) {
            final double newEt = Et - c.Et;
            eta = (Et * eta - c.Et * c.eta) / newEt;
            double dPhi = c.phi - phi;
            if (dPhi > PI) dPhi -= 2 * PI;
            else if (dPhi < -PI) dPhi += 2 * PI;
            phi -= dPhi * c.Et / newEt;
            while (phi < 0) phi += 2 * PI;
            while (phi >= 2 * PI) phi -= 2 * PI;
            Et = newEt;
        }

        boolean isEqual(Centroid c) {
            return Et == c.Et && eta == c.eta && phi == c.phi;
        }
    }

    /** cdf::Cluster: towers, their summed four-vector, centroid and scalar pt. */
    static final class Cluster {
        final List<PhysicsTower> towerList;
        LorentzVector fourVector;
        Centroid centroid;
        double ptTilde;

        Cluster() {
            towerList = new ArrayList<>();
            clear();
        }

        Cluster(Cluster o) {
            towerList = new ArrayList<>(o.towerList);
            fourVector = new LorentzVector(o.fourVector);
            centroid = new Centroid(o.centroid);
            ptTilde = o.ptTilde;
        }

        void clear() {
            towerList.clear();
            fourVector = new LorentzVector();
            centroid = new Centroid();
            ptTilde = 0.0;
        }

        void addTower(PhysicsTower p) {
            towerList.add(p);
            fourVector.add(p.fourVector);
            centroid.add(new Centroid(p.Et(), p.eta(), p.phi()));
            ptTilde += p.fourVector.pt();
        }

        void removeTower(PhysicsTower p) {
            for (int i = 0; i < towerList.size(); i++) {
                final PhysicsTower t = towerList.get(i);
                if (t.isEqual(p)) {
                    fourVector.subtract(t.fourVector);
                    centroid.subtract(new Centroid(t.Et(), t.eta(), t.phi()));
                    ptTilde -= t.fourVector.pt();
                    towerList.remove(i);
                    break;
                }
            }
        }

        int size() {
            return towerList.size();
        }
    }

    /* the cdf cluster comparisons */

    static boolean fourVectorEtGreater(Cluster c1, Cluster c2) {
        return c1.fourVector.Et() > c2.fourVector.Et();
    }

    static boolean centroidEtGreater(Cluster c1, Cluster c2) {
        return c1.centroid.Et > c2.centroid.Et;
    }

    static boolean ptGreater(Cluster c1, Cluster c2) {
        return c1.fourVector.pt() > c2.fourVector.pt();
    }

    static boolean mtGreater(Cluster c1, Cluster c2) {
        return c1.fourVector.mt() > c2.fourVector.mt();
    }

    static boolean ptTildeGreater(Cluster c1, Cluster c2) {
        return c1.ptTilde > c2.ptTilde;
    }
}
