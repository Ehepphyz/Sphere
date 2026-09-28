package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.plugins.StdAlgorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * siscone::Csplit_merge: the protojets found by the stable-cone passes are
 * split or merged, hardest first in the chosen scale, until none overlap
 * (or, in progressive-removal mode, the hardest stable cone of each pass is
 * taken as a jet and its particles removed).
 */
class SplitMerge {

    /** Esplit_merge_scale. */
    public enum Scale {
        SM_PT("pt (IR unsafe)"),
        SM_ET("Et (boost dep.)"),
        SM_MT("mt (IR safe except for pairs of identical decayed heavy particles)"),
        SM_PTTILDE("pttilde (scalar sum of pt's)");

        private final String label;

        Scale(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A user-defined ordering of the stable cones (progressive removal). */
    public interface UserScale {
        double scale(Cjet jet);

        default boolean isLarger(Cjet a, Cjet b) {
            return a.smVar2 > b.smVar2;
        }
    }

    /** siscone::Cjet. */
    public static final class Cjet {
        public Cmomentum v = new Cmomentum();
        public double ptTilde;
        public int n;
        public final List<Integer> contents;
        public double smVar2;
        EtaPhiRange range = new EtaPhiRange();
        public int pass = -2;

        Cjet() {
            contents = new ArrayList<>();
        }

        /** The copy constructor. */
        Cjet(Cjet o) {
            v = o.v.copy();
            ptTilde = o.ptTilde;
            n = o.n;
            contents = new ArrayList<>(o.contents);
            smVar2 = o.smVar2;
            range = new EtaPhiRange(o.range);
            pass = o.pass;
        }
    }

    int n;
    final List<Cmomentum> particles = new ArrayList<>();
    final List<Double> pt = new ArrayList<>();
    int nLeft;
    final List<Cmomentum> pRemain = new ArrayList<>();
    final List<Cmomentum> pUncolHard = new ArrayList<>();
    int nPass;
    double mostAmbiguousSplit;
    final List<Cjet> jets = new ArrayList<>();
    private int[] indices;
    private int idxSize;
    Scale splitMergeScale = Scale.SM_PTTILDE;
    double smVar2HardestCutOff = -Double.MAX_VALUE;
    double stableConeSoftPt2Cutoff = -1.0;
    private double ptMin2;
    private boolean usePtWeightedSplitting;
    private UserScale userScale;
    private StdMultiset<Cjet> candidates = new StdMultiset<>(this::ptComparison);

    void setPtWeightedSplitting(boolean b) {
        usePtWeightedSplitting = b;
    }

    void setUserScale(UserScale s) {
        userScale = s;
    }

    /** Csplit_merge_ptcomparison::operator(). */
    boolean ptComparison(Cjet jet1, Cjet jet2) {
        final double q1 = jet1.smVar2;
        final double q2 = jet2.smVar2;
        boolean res = q1 > q2;
        if (Math.abs(q1 - q2) < Geom.EPSILON_SPLITMERGE * ((q1 < q2) ? q2 : q1) && !jet1.v.ref.sameAs(jet2.v.ref)) {
            final Cmomentum difference = new Cmomentum();
            final double[] ptTildeDifference = new double[1];
            getDifference(jet1, jet2, difference, ptTildeDifference);
            final Cmomentum sum = jet1.v.copy();
            sum.add(jet2.v);
            final double ptTildeSum = jet1.ptTilde + jet2.ptTilde;
            final double qdiff = switch (splitMergeScale) {
                case SM_MT -> sum.E * difference.E - sum.pz * difference.pz;
                case SM_PT -> sum.px * difference.px + sum.py * difference.py;
                case SM_PTTILDE -> ptTildeSum * ptTildeDifference[0];
                case SM_ET -> jet1.v.E * jet1.v.E
                    * ((sum.px * difference.px + sum.py * difference.py) * jet1.v.pz * jet1.v.pz
                       - jet1.v.perp2() * sum.pz * difference.pz)
                    + sum.E * difference.E * (jet1.v.perp2() + jet1.v.pz * jet1.v.pz) * jet2.v.perp2();
            };
            res = qdiff > 0;
        }
        return res;
    }

    private void getDifference(Cjet j1, Cjet j2, Cmomentum v, double[] ptTilde) {
        int i1 = 0;
        int i2 = 0;
        v.assign(new Cmomentum());
        ptTilde[0] = 0.0;
        do {
            final int c1 = j1.contents.get(i1);
            final int c2 = j2.contents.get(i2);
            if (c1 == c2) {
                i1++;
                i2++;
            } else if (c1 < c2) {
                v.add(particles.get(c1));
                ptTilde[0] += pt.get(c1);
                i1++;
            } else {
                v.subtract(particles.get(c2));
                ptTilde[0] -= pt.get(c2);
                i2++;
            }
        } while (i1 < j1.n && i2 < j2.n);
        while (i1 < j1.n) {
            v.add(particles.get(j1.contents.get(i1)));
            ptTilde[0] += pt.get(j1.contents.get(i1));
            i1++;
        }
        while (i2 < j2.n) {
            v.subtract(particles.get(j2.contents.get(i2)));
            ptTilde[0] -= pt.get(j2.contents.get(i2));
            i2++;
        }
    }

    void initParticles(List<Cmomentum> in) {
        fullClear();
        for (Cmomentum p : in) particles.add(p.copy());
        n = particles.size();
        pt.clear();
        for (int i = 0; i < n; i++) pt.add(particles.get(i).perp());
        initPleft();
        indices = new int[n];
    }

    void initPleft() {
        int j = 0;
        double etaMin = 0.0;
        double etaMax = 0.0;
        pRemain.clear();
        for (int i = 0; i < n; i++) {
            final Cmomentum p = particles.get(i);
            p.ref.randomize();
            if (Math.abs(p.pz) < p.E) {
                final Cmomentum c = p.copy();
                pRemain.add(c);
                c.parentIndex = i;
                c.index = 1;
                j++;
                p.index = 0;
                etaMin = (p.eta < etaMin) ? p.eta : etaMin;
                etaMax = (etaMax < p.eta) ? p.eta : etaMax;
            } else {
                p.index = -1;
            }
        }
        nLeft = pRemain.size();
        nPass = 0;
        EtaPhiRange.etaMin = etaMin - 0.01;
        EtaPhiRange.etaMax = etaMax + 0.01;
        mergeCollinearAndRemoveSoft();
    }

    void partialClear() {
        candidates = new StdMultiset<>(this::ptComparison);
        mostAmbiguousSplit = Double.MAX_VALUE;
        jets.clear();
        pRemain.clear();
    }

    void fullClear() {
        partialClear();
        indices = null;
        particles.clear();
    }

    void mergeCollinearAndRemoveSoft() {
        pUncolHard.clear();
        final List<Cmomentum> pSorted = new ArrayList<>(nLeft);
        for (int i = 0; i < nLeft; i++) pSorted.add(pRemain.get(i).copy());
        StdAlgorithms.sort(pSorted, (a, b) -> a.eta < b.eta);
        int i = 0;
        while (i < nLeft) {
            if (pSorted.get(i).perp2() < stableConeSoftPt2Cutoff) {
                i++;
                continue;
            }
            boolean collinear = false;
            int j = i + 1;
            while (j < nLeft && Math.abs(pSorted.get(j).eta - pSorted.get(i).eta) < Geom.EPSILON_COLLINEAR && !collinear) {
                double dphi = Math.abs(pSorted.get(j).phi - pSorted.get(i).phi);
                if (dphi > Geom.M_PI) dphi = Geom.TWOPI - dphi;
                if (dphi < Geom.EPSILON_COLLINEAR) {
                    pSorted.get(j).add(pSorted.get(i));
                    collinear = true;
                }
                j++;
            }
            if (!collinear) pUncolHard.add(pSorted.get(i).copy());
            i++;
        }
    }

    int addProtocones(List<Cmomentum> protocones, double R2, double ptmin) {
        if (protocones.isEmpty()) return 1;
        ptMin2 = ptmin * ptmin;
        final double R = Math.sqrt(R2);
        for (Cmomentum c : protocones) {
            final double eta = c.eta;
            final double phi = c.phi;
            final Cjet jet = new Cjet();
            for (int i = 0; i < nLeft; i++) {
                final Cmomentum v = pRemain.get(i);
                final double dx = eta - v.eta;
                double dy = Math.abs(phi - v.phi);
                if (dy > Geom.M_PI) dy -= Geom.TWOPI;
                if (dx * dx + dy * dy < R2) {
                    jet.contents.add(v.parentIndex);
                    jet.v.add(v);
                    jet.ptTilde += pt.get(v.parentIndex);
                    v.index = 0;
                }
            }
            jet.n = jet.contents.size();
            c.assign(jet.v);
            c.eta = eta;
            c.phi = phi;
            jet.range = new EtaPhiRange(eta, phi, R);
            insert(jet);
        }
        nPass++;
        int j = 0;
        for (int i = 0; i < nLeft; i++) {
            final Cmomentum pi = pRemain.get(i);
            if (pi.index != 0) {
                final Cmomentum pj = pRemain.get(j);
                final int parentIndex = pi.parentIndex;
                pj.assign(pi);
                pj.parentIndex = parentIndex;
                pj.index = 1;
                particles.get(pj.parentIndex).index = nPass;
                j++;
            }
        }
        nLeft = j;
        while (pRemain.size() > j) pRemain.remove(pRemain.size() - 1);
        mergeCollinearAndRemoveSoft();
        return 0;
    }

    int addHardestProtoconeToJets(List<Cmomentum> protocones, double R2, double ptmin) {
        if (protocones.isEmpty()) return 1;
        ptMin2 = ptmin * ptmin;
        final double R = Math.sqrt(R2);
        Cjet jet = new Cjet();
        boolean foundJet = false;
        for (Cmomentum c : protocones) {
            final double eta = c.eta;
            final double phi = c.phi;
            final Cjet cand = new Cjet();
            for (int i = 0; i < nLeft; i++) {
                final Cmomentum v = pRemain.get(i);
                final double dx = eta - v.eta;
                double dy = Math.abs(phi - v.phi);
                if (dy > Geom.M_PI) dy -= Geom.TWOPI;
                if (dx * dx + dy * dy < R2) {
                    cand.contents.add(v.parentIndex);
                    cand.v.add(v);
                    cand.ptTilde += pt.get(v.parentIndex);
                    v.index = 0;
                }
            }
            cand.n = cand.contents.size();
            c.assign(cand.v);
            c.eta = eta;
            c.phi = phi;
            cand.range = new EtaPhiRange(eta, phi, R);
            if (cand.v.perp2() < ptMin2) continue;
            if (userScale != null) {
                cand.smVar2 = userScale.scale(cand);
                cand.smVar2 *= Math.abs(cand.smVar2);
            } else {
                cand.smVar2 = getSmVar2(cand.v, cand.ptTilde);
            }
            if (!foundJet || (userScale != null ? userScale.isLarger(cand, jet) : ptComparison(cand, jet))) {
                jet = cand;
                foundJet = true;
            }
        }
        if (!foundJet) return 1;
        final Cjet added = new Cjet(jet);
        jets.add(added);
        added.v.buildEtaPhi();
        int pRemainIndex = 0;
        int contentsIndex = 0;
        for (int index = 0; index < nLeft; index++) {
            final Cmomentum pr = pRemain.get(index);
            if (contentsIndex < jet.contents.size() && pr.parentIndex == jet.contents.get(contentsIndex)) {
                particles.get(pr.parentIndex).index = nPass;
                contentsIndex++;
            } else {
                final Cmomentum dst = pRemain.get(pRemainIndex);
                final int parentIndex = pr.parentIndex;
                dst.assign(pr);
                dst.parentIndex = parentIndex;
                dst.index = 1;
                pRemainIndex++;
            }
        }
        final int newSize = nLeft - jet.contents.size();
        while (pRemain.size() > newSize) pRemain.remove(pRemain.size() - 1);
        nLeft = pRemain.size();
        added.pass = particles.get(jet.contents.get(0)).index;
        nPass++;
        mergeCollinearAndRemoveSoft();
        return 0;
    }

    int perform(double overlapTshold, double ptmin) {
        ptMin2 = ptmin * ptmin;
        if (candidates.isEmpty()) return 0;
        if (overlapTshold >= 1.0 || overlapTshold <= 0) {
            throw new IllegalArgumentException("Illegal value for overlap_tshold, f = "
                + com.sphere.core.fastjet.Fmt.g(overlapTshold) + "  (legal values are 0<f<1)");
        }
        final double overlapTshold2 = overlapTshold * overlapTshold;
        final double[] overlap2 = new double[1];
        do {
            if (candidates.size() > 0) {
                StdMultiset.Node<Cjet> j1 = candidates.begin();
                if (j1.value().smVar2 < smVar2HardestCutOff) break;
                StdMultiset.Node<Cjet> j2 = candidates.next(j1);
                while (j2 != candidates.end()) {
                    if (getOverlap(j1.value(), j2.value(), overlap2)) {
                        if (overlap2[0] < overlapTshold2 * j2.value().smVar2) {
                            split(j1, j2);
                        } else {
                            merge(j1, j2);
                        }
                        j1 = candidates.begin();
                        j2 = j1;
                    }
                    if (j2 != candidates.end()) j2 = candidates.next(j2);
                }
                if (j1 != candidates.end()) {
                    final Cjet jet = new Cjet(j1.value());
                    jets.add(jet);
                    jet.v.buildEtaPhi();
                    // (an empty jet has no pass; the C++ reads out of its contents)
                    if (!j1.value().contents.isEmpty()) jet.pass = particles.get(j1.value().contents.get(0)).index;
                    candidates.erase(j1);
                }
            }
        } while (candidates.size() > 0);
        StdAlgorithms.sort(jets, (a, b) -> a.v.perp2() > b.v.perp2());
        return jets.size();
    }

    private boolean getOverlap(Cjet j1, Cjet j2, double[] overlap2) {
        if (!EtaPhiRange.isRangeOverlap(j1.range, j2.range)) return false;
        int i1 = 0;
        int i2 = 0;
        idxSize = 0;
        boolean isOverlap = false;
        final Cmomentum v = new Cmomentum();
        final double[] ptTilde = {0.0};
        do {
            final int c1 = j1.contents.get(i1);
            final int c2 = j2.contents.get(i2);
            if (c1 < c2) {
                indices[idxSize] = c1;
                i1++;
            } else if (c1 > c2) {
                indices[idxSize] = c2;
                i2++;
            } else {
                v.add(particles.get(c1));
                ptTilde[0] += pt.get(c1);
                indices[idxSize] = c1;
                i1++;
                i2++;
                isOverlap = true;
            }
            idxSize++;
        } while (i1 < j1.n && i2 < j2.n);
        if (isOverlap) {
            while (i1 < j1.n) indices[idxSize++] = j1.contents.get(i1++);
            while (i2 < j2.n) indices[idxSize++] = j2.contents.get(i2++);
        }
        overlap2[0] = getSmVar2(v, ptTilde[0]);
        return isOverlap;
    }

    private void split(StdMultiset.Node<Cjet> itJ1, StdMultiset.Node<Cjet> itJ2) {
        final Cjet j1 = itJ1.value();
        final Cjet j2 = itJ2.value();
        final Cjet jet1 = new Cjet();
        final Cjet jet2 = new Cjet();
        Cmomentum tmp = j1.v.copy();
        tmp.buildEtaPhi();
        final double eta1 = tmp.eta;
        final double phi1 = tmp.phi;
        final double pt1Weight = usePtWeightedSplitting ? 1.0 / tmp.perp2() : 1.0;
        tmp = j2.v.copy();
        tmp.buildEtaPhi();
        final double eta2 = tmp.eta;
        final double phi2 = tmp.phi;
        final double pt2Weight = usePtWeightedSplitting ? 1.0 / tmp.perp2() : 1.0;
        int i1 = 0;
        int i2 = 0;
        do {
            final int c1 = j1.contents.get(i1);
            final int c2 = j2.contents.get(i2);
            if (c1 < c2) {
                addTo(jet1, c1);
                i1++;
            } else if (c1 > c2) {
                addTo(jet2, c2);
                i2++;
            } else {
                final Cmomentum v = particles.get(c1);
                final double dx1 = eta1 - v.eta;
                double dy1 = Math.abs(phi1 - v.phi);
                if (dy1 > Geom.M_PI) dy1 -= Geom.TWOPI;
                final double dx2 = eta2 - v.eta;
                double dy2 = Math.abs(phi2 - v.phi);
                if (dy2 > Geom.M_PI) dy2 -= Geom.TWOPI;
                final double d1sq = (dx1 * dx1 + dy1 * dy1) * pt1Weight;
                final double d2sq = (dx2 * dx2 + dy2 * dy2) * pt2Weight;
                if (Math.abs(d1sq - d2sq) < mostAmbiguousSplit) mostAmbiguousSplit = Math.abs(d1sq - d2sq);
                if (d1sq < d2sq) addTo(jet1, c1);
                else addTo(jet2, c2);
                i1++;
                i2++;
            }
        } while (i1 < j1.n && i2 < j2.n);
        while (i1 < j1.n) addTo(jet1, j1.contents.get(i1++));
        while (i2 < j2.n) addTo(jet2, j2.contents.get(i2++));
        jet1.n = jet1.contents.size();
        jet2.n = jet2.contents.size();
        candidates.erase(itJ1);
        candidates.erase(itJ2);
        insert(jet1);
        insert(jet2);
    }

    private void addTo(Cjet jet, int c) {
        final Cmomentum v = particles.get(c);
        jet.contents.add(c);
        jet.v.add(v);
        jet.ptTilde += pt.get(c);
        jet.range.addParticle(v.eta, v.phi);
    }

    private void merge(StdMultiset.Node<Cjet> itJ1, StdMultiset.Node<Cjet> itJ2) {
        final Cjet jet = new Cjet();
        for (int i = 0; i < idxSize; i++) {
            jet.contents.add(indices[i]);
            jet.v.add(particles.get(indices[i]));
            jet.ptTilde += pt.get(indices[i]);
        }
        jet.n = jet.contents.size();
        jet.range = EtaPhiRange.union(itJ1.value().range, itJ2.value().range);
        candidates.erase(itJ1);
        candidates.erase(itJ2);
        insert(jet);
    }

    private boolean insert(Cjet jet) {
        if (jet.v.perp2() < ptMin2) return false;
        jet.smVar2 = getSmVar2(jet.v, jet.ptTilde);
        candidates.insert(jet);
        return true;
    }

    double getSmVar2(Cmomentum v, double ptTilde) {
        return switch (splitMergeScale) {
            case SM_PT -> v.perp2();
            case SM_MT -> v.perpmass2();
            case SM_PTTILDE -> ptTilde * ptTilde;
            case SM_ET -> v.Et2();
        };
    }
}
