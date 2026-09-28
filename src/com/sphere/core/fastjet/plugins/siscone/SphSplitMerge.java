package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.plugins.StdAlgorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * siscone_spherical::CSphsplit_merge: the split-merge of {@link SplitMerge}
 * on the sphere, in energy (E or Etilde) rather than transverse momentum.
 */
class SphSplitMerge {

    /** Esplit_merge_scale (spherical). */
    public enum Scale {
        SM_E("E (IR unsafe for pairs of identical decayed heavy particles)"),
        SM_ETILDE("Etilde (sum of E.[1+sin^2(theta_{i,jet})])");

        private final String label;

        Scale(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public interface UserScale {
        double scale(Jet jet);

        default boolean isLarger(Jet a, Jet b) {
            return a.smVar2 > b.smVar2;
        }
    }

    /** CSphjet. */
    public static final class Jet {
        public SphMomentum v = new SphMomentum();
        public double eTilde;
        public int n;
        public final List<Integer> contents;
        public double smVar2;
        SphThetaPhiRange range = new SphThetaPhiRange();
        public int pass = -2;

        Jet() {
            contents = new ArrayList<>();
        }

        Jet(Jet o) {
            v = o.v.copy();
            eTilde = o.eTilde;
            n = o.n;
            contents = new ArrayList<>(o.contents);
            smVar2 = o.smVar2;
            range = new SphThetaPhiRange(o.range);
            pass = o.pass;
        }
    }

    int n;
    final List<SphMomentum> particles = new ArrayList<>();
    final List<Double> particlesNorm2 = new ArrayList<>();
    int nLeft;
    final List<SphMomentum> pRemain = new ArrayList<>();
    final List<SphMomentum> pUncolHard = new ArrayList<>();
    int nPass;
    double mostAmbiguousSplit;
    final List<Jet> jets = new ArrayList<>();
    private int[] indices;
    private int idxSize;
    Scale splitMergeScale = Scale.SM_ETILDE;
    double smVar2HardestCutOff = -Double.MAX_VALUE;
    double stableConeSoftE2Cutoff = -1.0;
    private double eMin;
    private boolean useEWeightedSplitting;
    private UserScale userScale;
    private StdMultiset<Jet> candidates = new StdMultiset<>(this::ptComparison);

    void setEWeightedSplitting(boolean b) {
        useEWeightedSplitting = b;
    }

    void setUserScale(UserScale s) {
        userScale = s;
    }

    boolean ptComparison(Jet jet1, Jet jet2) {
        final double q1 = jet1.smVar2;
        final double q2 = jet2.smVar2;
        boolean res = q1 > q2;
        if (Math.abs(q1 - q2) < Geom.EPSILON_SPLITMERGE * ((q1 < q2) ? q2 : q1) && !jet1.v.ref.sameAs(jet2.v.ref)) {
            final SphMomentum difference = new SphMomentum();
            final double[] eTildeDifference = new double[1];
            getDifference(jet1, jet2, difference, eTildeDifference);
            final SphMomentum sum = jet1.v.copy();
            sum.add(jet2.v);
            final double eTildeSum = jet1.eTilde + jet2.eTilde;
            final double qdiff = switch (splitMergeScale) {
                case SM_ETILDE -> eTildeSum * eTildeDifference[0];
                case SM_E -> sum.E * difference.E;
            };
            res = qdiff > 0;
        }
        return res;
    }

    private void getDifference(Jet j1, Jet j2, SphMomentum v, double[] eTilde) {
        int i1 = 0;
        int i2 = 0;
        v.assign(new SphMomentum());
        eTilde[0] = 0.0;
        final Sph3 jet1Axis = j1.v.copy3();
        jet1Axis.divEq(j1.v.E);
        final Sph3 jet2Axis = j2.v.copy3();
        jet2Axis.divEq(j2.v.E);
        do {
            final int c1 = j1.contents.get(i1);
            final int c2 = j2.contents.get(i2);
            if (c1 == c2) {
                final SphMomentum p = particles.get(c1);
                eTilde[0] += p.E * ((Sph3.norm2Cross(p, jet1Axis) - Sph3.norm2Cross(p, jet2Axis)) / particlesNorm2.get(c1));
                i1++;
                i2++;
            } else if (c1 < c2) {
                final SphMomentum p = particles.get(c1);
                v.add(p);
                eTilde[0] += p.E * Sph3.norm2Cross(p, jet1Axis) / particlesNorm2.get(c1);
                i1++;
            } else {
                final SphMomentum p = particles.get(c2);
                v.subtract(p);
                eTilde[0] -= p.E * Sph3.norm2Cross(p, jet2Axis) / particlesNorm2.get(c2);
                i2++;
            }
        } while (i1 < j1.n && i2 < j2.n);
        while (i1 < j1.n) {
            final int c = j1.contents.get(i1++);
            final SphMomentum p = particles.get(c);
            v.add(p);
            eTilde[0] += p.E * Sph3.norm2Cross(p, jet1Axis) / particlesNorm2.get(c);
        }
        while (i2 < j2.n) {
            final int c = j2.contents.get(i2++);
            final SphMomentum p = particles.get(c);
            v.subtract(p);
            eTilde[0] -= p.E * Sph3.norm2Cross(p, jet2Axis) / particlesNorm2.get(c);
        }
        eTilde[0] += v.E;
    }

    void initParticles(List<SphMomentum> in) {
        fullClear();
        for (SphMomentum p : in) particles.add(p.copy());
        n = particles.size();
        particlesNorm2.clear();
        for (int i = 0; i < n; i++) particlesNorm2.add(particles.get(i).norm2());
        initPleft();
        indices = new int[n];
    }

    void initPleft() {
        pRemain.clear();
        for (int i = 0; i < n; i++) {
            final SphMomentum p = particles.get(i);
            p.ref.randomize();
            final SphMomentum c = p.copy();
            pRemain.add(c);
            c.parentIndex = i;
            c.index = 1;
            p.index = 0;
        }
        nLeft = pRemain.size();
        nPass = 0;
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
        final List<SphMomentum> pSorted = new ArrayList<>(nLeft);
        for (int i = 0; i < nLeft; i++) pSorted.add(pRemain.get(i).copy());
        StdAlgorithms.sort(pSorted, (a, b) -> a.theta < b.theta);
        int i = 0;
        while (i < nLeft) {
            if (pSorted.get(i).E * pSorted.get(i).E < stableConeSoftE2Cutoff) {
                i++;
                continue;
            }
            boolean collinear = false;
            int j = i + 1;
            while (j < nLeft && Math.abs(pSorted.get(j).theta - pSorted.get(i).theta) < Geom.EPSILON_COLLINEAR && !collinear) {
                double dphi = Math.abs(pSorted.get(j).phi - pSorted.get(i).phi);
                if (dphi > Geom.M_PI) dphi = Geom.TWOPI - dphi;
                if (dphi < Geom.EPSILON_COLLINEAR) {
                    pSorted.get(j).add(pSorted.get(i));
                    pSorted.get(j).buildNorm();
                    collinear = true;
                }
                j++;
            }
            if (!collinear) pUncolHard.add(pSorted.get(i).copy());
            i++;
        }
    }

    private void computeEtilde(Jet jet) {
        jet.v.buildNorm();
        jet.eTilde = 0.0;
        final Sph3 jetAxis = jet.v.copy3();
        jetAxis.divEq(jet.v.E);
        for (int c : jet.contents) {
            final SphMomentum p = particles.get(c);
            jet.eTilde += p.E * (1.0 + Sph3.norm2Cross(p, jetAxis) / particlesNorm2.get(c));
        }
    }

    int addProtocones(List<SphMomentum> protocones, double R2, double emin) {
        if (protocones.isEmpty()) return 1;
        eMin = emin;
        final double R = Math.sqrt(R2);
        final double t = com.sphere.core.fastjet.CRMath.tan(R);
        final double tan2R = t * t;
        for (SphMomentum c : protocones) {
            final Jet jet = new Jet();
            for (int i = 0; i < nLeft; i++) {
                final SphMomentum v = pRemain.get(i);
                if (Sph3.isCloser(v, c, tan2R)) {
                    jet.contents.add(v.parentIndex);
                    jet.v.add(v);
                    v.index = 0;
                }
            }
            jet.n = jet.contents.size();
            computeEtilde(jet);
            c.assign(jet.v);
            c.buildThetaPhi();
            jet.range = new SphThetaPhiRange(c.theta, c.phi, R);
            insert(jet);
        }
        nPass++;
        int j = 0;
        for (int i = 0; i < nLeft; i++) {
            final SphMomentum pi = pRemain.get(i);
            if (pi.index != 0) {
                final SphMomentum pj = pRemain.get(j);
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

    int addHardestProtoconeToJets(List<SphMomentum> protocones, double R2, double emin) {
        if (protocones.isEmpty()) return 1;
        eMin = emin;
        final double R = Math.sqrt(R2);
        final double t = com.sphere.core.fastjet.CRMath.tan(R);
        final double tan2R = t * t;
        Jet jet = new Jet();
        boolean foundJet = false;
        for (SphMomentum c : protocones) {
            final Jet cand = new Jet();
            for (int i = 0; i < nLeft; i++) {
                final SphMomentum v = pRemain.get(i);
                if (Sph3.isCloser(v, c, tan2R)) {
                    cand.contents.add(v.parentIndex);
                    cand.v.add(v);
                    v.index = 0;
                }
            }
            cand.n = cand.contents.size();
            computeEtilde(cand);
            c.assign(cand.v);
            c.buildThetaPhi();
            cand.range = new SphThetaPhiRange(c.theta, c.phi, R);
            if (cand.v.E < eMin) continue;
            if (userScale != null) {
                cand.smVar2 = userScale.scale(cand);
                cand.smVar2 *= Math.abs(cand.smVar2);
            } else {
                cand.smVar2 = getSmVar2(cand.v, cand.eTilde);
            }
            if (!foundJet || (userScale != null ? userScale.isLarger(cand, jet) : ptComparison(cand, jet))) {
                jet = cand;
                foundJet = true;
            }
        }
        if (!foundJet) return 1;
        final Jet added = new Jet(jet);
        jets.add(added);
        added.v.buildThetaPhi();
        added.v.buildNorm();
        int pRemainIndex = 0;
        int contentsIndex = 0;
        for (int index = 0; index < nLeft; index++) {
            final SphMomentum pr = pRemain.get(index);
            if (contentsIndex < jet.contents.size() && pr.parentIndex == jet.contents.get(contentsIndex)) {
                particles.get(pr.parentIndex).index = nPass;
                contentsIndex++;
            } else {
                final SphMomentum dst = pRemain.get(pRemainIndex);
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

    int perform(double overlapTshold, double emin) {
        eMin = emin;
        if (candidates.isEmpty()) return 0;
        if (overlapTshold >= 1.0 || overlapTshold <= 0) {
            throw new IllegalArgumentException("Illegal value for overlap_tshold, f = "
                + com.sphere.core.fastjet.Fmt.g(overlapTshold) + "  (legal values are 0<f<1)");
        }
        final double overlapTshold2 = overlapTshold * overlapTshold;
        final double[] overlap2 = new double[1];
        do {
            if (candidates.size() > 0) {
                StdMultiset.Node<Jet> j1 = candidates.begin();
                if (j1.value().smVar2 < smVar2HardestCutOff) break;
                StdMultiset.Node<Jet> j2 = candidates.next(j1);
                while (j2 != candidates.end()) {
                    if (getOverlap(j1.value(), j2.value(), overlap2)) {
                        if (overlap2[0] < overlapTshold2 * Geom.pow2(j2.value().v.E)) {
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
                    final Jet jet = new Jet(j1.value());
                    jets.add(jet);
                    jet.v.buildThetaPhi();
                    jet.v.buildNorm();
                    if (!j1.value().contents.isEmpty()) jet.pass = particles.get(j1.value().contents.get(0)).index;
                    candidates.erase(j1);
                }
            }
        } while (candidates.size() > 0);
        StdAlgorithms.sort(jets, (a, b) -> a.v.E > b.v.E);
        return jets.size();
    }

    private boolean getOverlap(Jet j1, Jet j2, double[] overlap2) {
        if (!SphThetaPhiRange.isRangeOverlap(j1.range, j2.range)) return false;
        int i1 = 0;
        int i2 = 0;
        idxSize = 0;
        boolean isOverlap = false;
        final SphMomentum v = new SphMomentum();
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
                v.add(particles.get(c2));
                indices[idxSize] = c2;
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
        overlap2[0] = Geom.pow2(v.E);
        return isOverlap;
    }

    private void addTo(Jet jet, int c) {
        final SphMomentum v = particles.get(c);
        jet.contents.add(c);
        jet.v.add(v);
        jet.range.addParticle(v.theta, v.phi);
    }

    private void split(StdMultiset.Node<Jet> itJ1, StdMultiset.Node<Jet> itJ2) {
        final Jet j1 = itJ1.value();
        final Jet j2 = itJ2.value();
        final Jet jet1 = new Jet();
        final Jet jet2 = new Jet();
        final double e1Weight = useEWeightedSplitting ? 1.0 / j1.v.E / j1.v.E : 1.0;
        final double e2Weight = useEWeightedSplitting ? 1.0 / j2.v.E / j2.v.E : 1.0;
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
                final SphMomentum v = particles.get(c1);
                final double d1 = Sph3.distance(j1.v, v) * e1Weight;
                final double d2 = Sph3.distance(j2.v, v) * e2Weight;
                if (Math.abs(d1 - d2) < mostAmbiguousSplit) mostAmbiguousSplit = Math.abs(d1 - d2);
                if (d1 < d2) addTo(jet1, c1);
                else addTo(jet2, c2);
                i1++;
                i2++;
            }
        } while (i1 < j1.n && i2 < j2.n);
        while (i1 < j1.n) addTo(jet1, j1.contents.get(i1++));
        while (i2 < j2.n) addTo(jet2, j2.contents.get(i2++));
        jet1.n = jet1.contents.size();
        jet2.n = jet2.contents.size();
        computeEtilde(jet1);
        computeEtilde(jet2);
        candidates.erase(itJ1);
        candidates.erase(itJ2);
        insert(jet1);
        insert(jet2);
    }

    private void merge(StdMultiset.Node<Jet> itJ1, StdMultiset.Node<Jet> itJ2) {
        final Jet jet = new Jet();
        for (int i = 0; i < idxSize; i++) {
            jet.contents.add(indices[i]);
            jet.v.add(particles.get(indices[i]));
        }
        jet.n = jet.contents.size();
        computeEtilde(jet);
        jet.range = SphThetaPhiRange.union(itJ1.value().range, itJ2.value().range);
        candidates.erase(itJ1);
        candidates.erase(itJ2);
        insert(jet);
    }

    private boolean insert(Jet jet) {
        if (jet.v.E < eMin) return false;
        jet.smVar2 = getSmVar2(jet.v, jet.eTilde);
        candidates.insert(jet);
        return true;
    }

    double getSmVar2(SphMomentum v, double eTilde) {
        return switch (splitMergeScale) {
            case SM_E -> v.E * v.E;
            case SM_ETILDE -> eTilde * eTilde;
        };
    }
}
