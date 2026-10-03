package com.sphere.core.fjcontrib.jetswithoutjets;

import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-particle information jet-like event shapes are built from,
 * fastjet::jwj::EventStorage (JetsWithoutJets 1.0.0; D. Bertolini,
 * T. Chan and J. Thaler, JHEP 1404 (2014) 013): for every particle i, the
 * scalar pt within R_jet (and R_sub) of it, the mass of that neighbourhood,
 * the neighbours themselves, and whether it passes the pt cut and the
 * trimming condition. A particle's weight pt_i / pt_in_Rjet makes the sum of
 * the weights over a would-be jet equal to one, which is how jets are
 * counted without jets.
 *
 * <p>The neighbour search goes, as in the C++ contrib, either through every
 * particle or through a rapidity-azimuth grid of cells at least 2 R_jet
 * wide (the "local storage"); both visit the candidates in increasing index
 * order, so they give the same sums bit for bit. Beyond the C++ contrib the
 * storage says how close its decisions were to the cut
 * ({@link #closestCutMargin()}): a particle whose pt_in_Rjet sits at the pt
 * cut flips the jet count under an arbitrarily small change of the event.
 */
public class EventStorage {

    private static final double TWOPI = 2.0 * Math.PI;

    /** One particle and its neighbourhood, fastjet::jwj::ParticleStorage. */
    public static final class ParticleStorage {
        private final PseudoJet pj;
        private final double rap;
        private final double phi;
        private final double pt;
        private final double m;
        private final double px;
        private final double py;
        private double ptInRjet;
        private double ptInRsub;
        private double mInRjet;
        private double weight;
        private boolean includeParticle;
        private int[] neighbors = new int[0];

        ParticleStorage(PseudoJet p) {
            pj = p;
            rap = p.rap();
            phi = p.phi();
            pt = p.pt();
            m = p.m();
            px = p.px();
            py = p.py();
        }

        public PseudoJet pseudoJet() { return pj; }
        public double rap() { return rap; }
        public double phi() { return phi; }
        public double pt() { return pt; }
        public double m() { return m; }
        public double px() { return px; }
        public double py() { return py; }
        public double ptInRjet() { return ptInRjet; }
        public double ptInRsub() { return ptInRsub; }
        public double mInRjet() { return mInRjet; }
        public double weight() { return weight; }
        public boolean includeParticle() { return includeParticle; }
        public int[] neighbors() { return neighbors.clone(); }

        public double deltaRsq(ParticleStorage other) {
            final double deltaRap = rap - other.rap;
            double deltaPhi = Math.abs(phi - other.phi);
            if (deltaPhi > Math.PI) deltaPhi = TWOPI - deltaPhi;
            return deltaRap * deltaRap + deltaPhi * deltaPhi;
        }
    }

    /**
     * The rapidity-azimuth cells, fastjet::jwj::LocalStorage: cell k holds the
     * particles whose coordinate over the spread floors or ceils to k, so it
     * covers two spreads centred on k * spread, and a particle looks up the
     * cell its coordinate rounds to.
     */
    private static final class LocalStorage {
        private static final double RAPMAX = 10.0;
        private int maxRapIndex;
        private double rapSpread;
        private int maxPhiIndex;
        private double phiSpread;
        private int[][][] regionStorage;
        private boolean[][] aboveCutBool;
        private double[][] regionSum;

        /** C's round(): halfway cases away from zero. */
        private static int cRound(double x) {
            final double ax = Math.abs(x);
            double t = Math.floor(ax);
            if (ax - t >= 0.5) t += 1.0;
            return (int) Math.copySign(t, x);
        }

        void establishStorage(List<ParticleStorage> particles, double rjet, double ptcut) {
            maxRapIndex = (int) Math.floor(RAPMAX / rjet);
            rapSpread = 2.0 * RAPMAX / Math.floor(RAPMAX / rjet);
            maxPhiIndex = (int) Math.floor(Math.PI / rjet);
            phiSpread = 2.0 * Math.PI / Math.floor(Math.PI / rjet);
            final IntList[][] cells = new IntList[maxRapIndex][maxPhiIndex];
            for (int r = 0; r < maxRapIndex; r++) {
                for (int p = 0; p < maxPhiIndex; p++) cells[r][p] = new IntList();
            }
            for (int i = 0; i < particles.size(); i++) {
                final double rap = particles.get(i).rap();
                final double phi = particles.get(i).phi();
                int lowRap = (int) Math.floor((rap + RAPMAX) / rapSpread);
                int highRap = (int) Math.ceil((rap + RAPMAX) / rapSpread);
                final int lowPhi = (int) Math.floor(phi / phiSpread);
                int highPhi = (int) Math.ceil(phi / phiSpread);
                if (highPhi >= maxPhiIndex) highPhi = highPhi - maxPhiIndex;
                if (lowRap < 0) lowRap = 0;
                if (lowRap >= maxRapIndex) lowRap = maxRapIndex - 1;
                if (highRap < 0) highRap = 0;
                if (highRap >= maxRapIndex) highRap = maxRapIndex - 1;
                cells[lowRap][lowPhi].add(i);
                if (lowPhi != highPhi) cells[lowRap][highPhi].add(i);
                if (lowRap != highRap) cells[highRap][lowPhi].add(i);
                if (lowRap != highRap && lowPhi != highPhi) cells[highRap][highPhi].add(i);
            }
            regionStorage = new int[maxRapIndex][maxPhiIndex][];
            aboveCutBool = new boolean[maxRapIndex][maxPhiIndex];
            regionSum = new double[maxRapIndex][maxPhiIndex];
            for (int r = 0; r < maxRapIndex; r++) {
                for (int p = 0; p < maxPhiIndex; p++) {
                    regionStorage[r][p] = cells[r][p].toArray();
                    double sum = 0;
                    for (int id : regionStorage[r][p]) sum += particles.get(id).pt();
                    regionSum[r][p] = sum;
                    aboveCutBool[r][p] = sum >= ptcut;
                }
            }
        }

        private int rapIndex(ParticleStorage p) {
            int i = cRound((p.rap() + RAPMAX) / rapSpread);
            if (i < 0) i = 0;
            if (i >= maxRapIndex) i = maxRapIndex - 1;
            return i;
        }

        private int phiIndex(ParticleStorage p) {
            int i = cRound(p.phi() / phiSpread);
            if (i >= maxPhiIndex) i = i - maxPhiIndex;
            return i;
        }

        int[] storageFor(ParticleStorage p) {
            return regionStorage[rapIndex(p)][phiIndex(p)];
        }

        boolean aboveCutFor(ParticleStorage p) {
            return aboveCutBool[rapIndex(p)][phiIndex(p)];
        }

        /** The scalar pt of the cell: a bound on the particle's pt_in_Rjet. */
        double regionSumFor(ParticleStorage p) {
            return regionSum[rapIndex(p)][phiIndex(p)];
        }
    }

    /** A growable int array. */
    static final class IntList {
        private int[] a = new int[8];
        private int n;

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, 2 * n);
            a[n++] = v;
        }

        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    private final double rjet;
    private final double ptcut;
    private final double rsub;
    private final double fcut;
    private final boolean useLocalStorage;
    private final boolean storeNeighbors;
    private final boolean storeMass;
    private final List<ParticleStorage> storage = new ArrayList<>();
    private int[] ids = new int[0];
    private double closestCutMargin = Double.POSITIVE_INFINITY;

    public EventStorage(double rjet, double ptcut, double rsub, double fcut, boolean useLocalStorage, boolean storeNeighbors,
                        boolean storeMass) {
        this.rjet = rjet;
        this.ptcut = ptcut;
        this.rsub = rsub;
        this.fcut = fcut;
        this.useLocalStorage = useLocalStorage;
        this.storeNeighbors = storeNeighbors;
        this.storeMass = storeMass;
    }

    public EventStorage(double rjet, double ptcut, double rsub, double fcut, boolean useLocalStorage, boolean storeNeighbors) {
        this(rjet, ptcut, rsub, fcut, useLocalStorage, storeNeighbors, false);
    }

    public EventStorage(double rjet, double ptcut, double rsub, double fcut, boolean useLocalStorage) {
        this(rjet, ptcut, rsub, fcut, useLocalStorage, true, false);
    }

    public EventStorage(double rjet, double ptcut, double rsub, double fcut) {
        this(rjet, ptcut, rsub, fcut, true, true, false);
    }

    /** No trimming: R_sub = R_jet and fcut = 1. */
    public EventStorage(double rjet, double ptcut, boolean useLocalStorage, boolean storeNeighbors, boolean storeMass) {
        this(rjet, ptcut, rjet, 1.0, useLocalStorage, storeNeighbors, storeMass);
    }

    public EventStorage(double rjet, double ptcut, boolean useLocalStorage, boolean storeNeighbors) {
        this(rjet, ptcut, rjet, 1.0, useLocalStorage, storeNeighbors, false);
    }

    public EventStorage(double rjet, double ptcut) {
        this(rjet, ptcut, rjet, 1.0, true, true, false);
    }

    public void establishStorage(List<PseudoJet> particles) {
        establishBasicStorage(particles);
        establishDerivedStorage();
    }

    public int size() { return storage.size(); }
    public double Rjet() { return rjet; }
    public double ptcut() { return ptcut; }
    public double Rsub() { return rsub; }
    public double fcut() { return fcut; }
    public boolean storeNeighbors() { return storeNeighbors; }
    public boolean storeMass() { return storeMass; }
    public ParticleStorage get(int i) { return storage.get(i); }

    public List<PseudoJet> particlesNearTo(int id) {
        final int[] neighbors = storage.get(id).neighbors;
        final List<PseudoJet> answer = new ArrayList<>(neighbors.length);
        for (int n : neighbors) answer.add(storage.get(n).pseudoJet().copy());
        return answer;
    }

    /**
     * The smallest relative distance, over the particles whose neighbourhood
     * was examined, of pt_in_Rjet to the pt cut and of pt_in_Rsub/pt_in_Rjet
     * to fcut (when trimming): the jet count and every shape built on this
     * storage are stable under changes of the particles' pt smaller than it.
     */
    public double closestCutMargin() { return closestCutMargin; }

    /** The number of particles passing the pt cut and the trimming condition. */
    public int includedCount() {
        int n = 0;
        for (ParticleStorage p : storage) if (p.includeParticle) n++;
        return n;
    }

    public String parameterString() {
        return "R_jet=" + Fmt.g(rjet) + ", pT_cut=" + Fmt.g(ptcut) + ", R_sub=" + Fmt.g(rsub) + ", fcut=" + Fmt.g(fcut);
    }

    public String description() {
        return "Event Storage with " + parameterString();
    }

    private void establishBasicStorage(List<PseudoJet> particles) {
        storage.clear();
        ids = new int[particles.size()];
        for (int i = 0; i < particles.size(); i++) {
            storage.add(new ParticleStorage(particles.get(i).copy()));
            ids[i] = i;
        }
    }

    private void establishDerivedStorage() {
        final LocalStorage local = new LocalStorage();
        if (useLocalStorage) local.establishStorage(storage, rjet, ptcut);
        int[] region = ids;
        final double[] out = new double[3];
        final IntList neighbors = new IntList();
        closestCutMargin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < storage.size(); i++) {
            final ParticleStorage p = storage.get(i);
            p.includeParticle = false;
            if (useLocalStorage) {
                if (!local.aboveCutFor(p)) {
                    // pt_in_Rjet <= cell sum < ptcut: a lower bound on this particle's margin
                    closestCutMargin = Math.min(closestCutMargin, (ptcut - local.regionSumFor(p)) / ptcut);
                    continue;
                }
                region = local.storageFor(p);
            }
            neighbors.n = 0;
            localInfo(i, region, out, neighbors);
            final double ptInRjet = out[0];
            final double ptInRsub = out[1];
            if (ptcut > 0) closestCutMargin = Math.min(closestCutMargin, Math.abs(ptInRjet - ptcut) / ptcut);
            if (ptInRjet < ptcut) continue;
            if (rsub > rjet) throw new IllegalStateException("EventStorage: R_sub > R_jet");
            if (fcut > 0 && fcut < 1 && ptInRjet > 0) {
                closestCutMargin = Math.min(closestCutMargin, Math.abs(ptInRsub / ptInRjet - fcut) / fcut);
            }
            if (ptInRsub / ptInRjet < fcut) continue;
            p.includeParticle = true;
            p.ptInRjet = ptInRjet;
            p.ptInRsub = ptInRsub;
            p.mInRjet = out[2];
            p.neighbors = neighbors.toArray();
            p.weight = p.pt / ptInRjet;
        }
    }

    private void localInfo(int id, int[] region, double[] out, IntList neighbors) {
        final double rjetsq = rjet * rjet;
        final double rsubsq = rsub * rsub;
        double ptInRjet = 0.0;
        double ptInRsub = 0.0;
        final PseudoJet pjInRjet = new PseudoJet(0.0, 0.0, 0.0, 0.0);
        final ParticleStorage me = storage.get(id);
        for (int k : region) {
            final ParticleStorage other = storage.get(k);
            final double deltaRsq = me.deltaRsq(other);
            if (deltaRsq <= rjetsq) {
                ptInRjet += other.pt;
                if (storeMass) pjInRjet.plusEqual(other.pj);
                if (storeNeighbors) neighbors.add(k);
                if (deltaRsq <= rsubsq) ptInRsub += other.pt;
            }
        }
        out[0] = ptInRjet;
        out[1] = ptInRsub;
        out[2] = pjInRjet.m();
    }
}
