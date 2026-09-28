package com.sphere.core.fastjet;

import java.util.Arrays;

/**
 * The lazy tilings of FastJet 3.1, LazyTiling9 (tiles of size R, 3x3
 * neighbourhood) and LazyTiling25 (tiles of size R/2, 5x5 neighbourhood).
 *
 * Each tile remembers the largest nearest-neighbour distance of the jets it
 * holds, and a neighbouring tile is only searched when its edge is closer
 * than that, which is what makes these strategies fast at large N.
 *
 * The tile geometry is judged in doubles. Under {@link Precision#DD} each
 * distance to a tile is lowered by a margin far above the rounding of the
 * doubles, so a tile is only ever searched more often than strictly needed,
 * never less, and the neighbours found are the exact 106-bit ones.
 */
class LazyTilingEngine extends BriefJetEngine {

    static final double TILE_EDGE_SECURITY_MARGIN = 1.0e-7;
    /** Extra margin in DD mode, for the doubles used for the tile geometry. */
    static final double DD_GEOMETRY_MARGIN = 1.0e-9;

    private final int reach;

    // tiles
    int[] tileHead;
    boolean[] tileTagged;
    int[][] tileNeighbours;
    int[] tileRhStart;
    boolean[] tilePeriodic;
    double[] tileMaxNN;
    double[] tileEtaCentre;
    double[] tilePhiCentre;
    double tilesEtaMin, tilesEtaMax, tileSizeEta, tileSizePhi, tileHalfSizeEta, tileHalfSizePhi;
    int nTilesPhi, tilesIetaMin, tilesIetaMax;

    // jets
    int[] tileIndex, previous, next;
    boolean[] heapUpdateNeeded;

    int[] forMinheap = new int[64];
    int nForMinheap;

    LazyTilingEngine(ClusterSequence cs, int reach) {
        super(cs);
        this.reach = reach;
        initialiseTiles();
    }

    @Override
    void allocate(int n) {
        super.allocate(n);
        tileIndex = new int[n];
        previous = new int[n];
        next = new int[n];
        heapUpdateNeeded = new boolean[n];
    }

    int tileIndexOf(int ieta, int iphi) {
        return (ieta - tilesIetaMin) * nTilesPhi + (iphi + nTilesPhi) % nTilesPhi;
    }

    private void initialiseTiles() {
        final double r = cs.jetDef.R();
        double defaultSize = Math.max(0.1, r) / reach;
        if (dd) {
            defaultSize *= 1.0 + 1e-12;
        }
        tileSizeEta = defaultSize;
        final int minPhiTiles = 2 * reach + 1;
        nTilesPhi = Math.max(minPhiTiles, (int) Math.floor(TWOPI / defaultSize));
        tileSizePhi = TWOPI / nTilesPhi;

        final double[] extent = tilingExtent(jets);
        tilesEtaMin = extent[0];
        tilesEtaMax = extent[1];

        final int minEtaTiles = reach + 1;
        if (tilesEtaMax - tilesEtaMin < minEtaTiles * tileSizeEta) {
            tileSizeEta = (tilesEtaMax - tilesEtaMin) / minEtaTiles;
            tilesIetaMin = 0;
            tilesIetaMax = minEtaTiles - 1;
            tilesEtaMax -= tileSizeEta;
        } else {
            tilesIetaMin = (int) Math.floor(tilesEtaMin / tileSizeEta);
            tilesIetaMax = (int) Math.floor(tilesEtaMax / tileSizeEta);
            tilesEtaMin = tilesIetaMin * tileSizeEta;
            tilesEtaMax = tilesIetaMax * tileSizeEta;
        }
        tileHalfSizeEta = tileSizeEta * 0.5;
        tileHalfSizePhi = tileSizePhi * 0.5;

        final boolean[] usePeriodic = new boolean[nTilesPhi];
        if (nTilesPhi <= minPhiTiles) {
            Arrays.fill(usePeriodic, true);
        } else {
            for (int k = 0; k < reach; k++) {
                usePeriodic[k] = true;
                usePeriodic[nTilesPhi - 1 - k] = true;
            }
        }

        final int nTiles = (tilesIetaMax - tilesIetaMin + 1) * nTilesPhi;
        tileHead = new int[nTiles];
        tileTagged = new boolean[nTiles];
        tileNeighbours = new int[nTiles][];
        tileRhStart = new int[nTiles];
        tilePeriodic = new boolean[nTiles];
        tileMaxNN = new double[nTiles];
        tileEtaCentre = new double[nTiles];
        tilePhiCentre = new double[nTiles];
        final int[] buffer = new int[(2 * reach + 1) * (2 * reach + 1)];
        for (int ieta = tilesIetaMin; ieta <= tilesIetaMax; ieta++) {
            for (int iphi = 0; iphi < nTilesPhi; iphi++) {
                final int t = tileIndexOf(ieta, iphi);
                tileHead[t] = -1;
                int k = 0;
                buffer[k++] = t;
                for (int d = 1; d <= reach; d++) {
                    if (ieta > tilesIetaMin + (d - 1)) {
                        for (int idphi = -reach; idphi <= reach; idphi++) {
                            buffer[k++] = tileIndexOf(ieta - d, iphi + idphi);
                        }
                    }
                }
                for (int d = 1; d <= reach; d++) {
                    buffer[k++] = tileIndexOf(ieta, iphi - d);
                }
                tileRhStart[t] = k;
                for (int d = 1; d <= reach; d++) {
                    buffer[k++] = tileIndexOf(ieta, iphi + d);
                }
                for (int d = 1; d <= reach; d++) {
                    if (ieta < tilesIetaMax - (d - 1)) {
                        for (int idphi = -reach; idphi <= reach; idphi++) {
                            buffer[k++] = tileIndexOf(ieta + d, iphi + idphi);
                        }
                    }
                }
                tileNeighbours[t] = Arrays.copyOf(buffer, k);
                tileTagged[t] = false;
                tilePeriodic[t] = usePeriodic[iphi];
                tileMaxNN[t] = 0;
                tileEtaCentre[t] = (ieta - tilesIetaMin + 0.5) * tileSizeEta + tilesEtaMin;
                tilePhiCentre[t] = (iphi + 0.5) * tileSizePhi;
            }
        }
    }

    int tileIndexOfPoint(double eta, double phi) {
        int ieta;
        if (eta <= tilesEtaMin) {
            ieta = 0;
        } else if (eta >= tilesEtaMax) {
            ieta = tilesIetaMax - tilesIetaMin;
        } else {
            ieta = (int) ((eta - tilesEtaMin) / tileSizeEta);
            if (ieta > tilesIetaMax - tilesIetaMin) {
                ieta = tilesIetaMax - tilesIetaMin;
            }
        }
        final int iphi = phiTileAcrossZero(((int) ((phi + TWOPI) / tileSizePhi)) % nTilesPhi, phi, nTilesPhi);
        return iphi + ieta * nTilesPhi;
    }

    /**
     * Deviation from FastJet 3.5.2, shared with LazyTiling9Alt. The phi tile
     * is (phi + 2 pi) / size, and with 25, 41 or 50 tiles (R in [0.2417,
     * 0.2513], [0.1496, 0.1532], [0.1232, 0.1257]) this rounds a point at
     * phi = 0 exactly (py = 0, px &gt; 0) into the last tile, 2 pi away from
     * where it is. Distances within a tile are then taken without
     * periodicity, pairs across phi = 0 are missed, and the C++ N2MHTLazy9
     * and N2MHTLazy25 give other jets than N2Plain without any warning
     * (N2MHTLazy9Alt ends on "trying to recombine an object that has
     * previously been recombined"). Here such a point goes to the tile that
     * contains it; every other point keeps the C++ tile, so all other
     * results stay identical to the C++.
     */
    static int phiTileAcrossZero(int iphi, double phi, int nTilesPhi) {
        if (iphi == nTilesPhi - 1 && phi < PI) return 0;
        if (iphi == 0 && phi > PI) return nTilesPhi - 1;
        return iphi;
    }

    void tjSetJetInfo(int s, int jetIndex) {
        setJetInfo(s, jetIndex);
        tileIndex[s] = tileIndexOfPoint(etaH[s], phiH[s]);
        final int t = tileIndex[s];
        previous[s] = -1;
        next[s] = tileHead[t];
        if (next[s] >= 0) {
            previous[next[s]] = s;
        }
        tileHead[t] = s;
    }

    void removeFromTiles(int s) {
        final int t = tileIndex[s];
        if (previous[s] < 0) {
            tileHead[t] = next[s];
        } else {
            next[previous[s]] = next[s];
        }
        if (next[s] >= 0) {
            previous[next[s]] = previous[s];
        }
    }

    /** The squared distance from a point in tile `ownTile` to the tile `tile`. */
    double distanceToTile(double eta, double phi, int ownTile, int tile) {
        double deta;
        if (tileEtaCentre[ownTile] == tileEtaCentre[tile]) {
            deta = 0;
        } else {
            deta = Math.abs(eta - tileEtaCentre[tile]) - tileHalfSizeEta;
        }
        double dphi = Math.abs(phi - tilePhiCentre[tile]);
        if (dphi > PI) {
            dphi = TWOPI - dphi;
        }
        dphi -= tileHalfSizePhi;
        if (dphi < 0) {
            dphi = 0;
        }
        final double d = dphi * dphi + deta * deta;
        return dd ? d - DD_GEOMETRY_MARGIN : d;
    }

    double distanceToTile(int s, int tile) {
        return distanceToTile(etaH[s], phiH[s], tileIndex[s], tile);
    }

    /** NN distance of a slot compared with a double: nn <= x. */
    boolean nnAtMost(int s, double x) {
        return nnH[s] < x || (nnH[s] == x && nnL[s] <= 0.0);
    }

    boolean nnBelow(int s, double x) {
        return nnH[s] < x || (nnH[s] == x && nnL[s] < 0.0);
    }

    void pushForMinheap(int s) {
        if (!heapUpdateNeeded[s]) {
            heapUpdateNeeded[s] = true;
            if (nForMinheap == forMinheap.length) {
                forMinheap = Arrays.copyOf(forMinheap, nForMinheap * 2);
            }
            forMinheap[nForMinheap++] = s;
        }
    }

    int addUntaggedNeighboursUsingMaxInfo(double eta, double phi, int ownTile, int[] union, int nNear) {
        for (int nt : tileNeighbours[ownTile]) {
            if (tileTagged[nt]) continue;
            final double dist = distanceToTile(eta, phi, ownTile, nt) - TILE_EDGE_SECURITY_MARGIN;
            if (dist > tileMaxNN[nt]) continue;
            tileTagged[nt] = true;
            union[nNear++] = nt;
        }
        return nNear;
    }

    void updateJetXJetINN(int jetX, int jetI) {
        dist(jetI, jetX);
        if (distLessThanNN(jetI)) {
            if (jetI != jetX) {
                setNNFromDist(jetI, jetX);
                pushForMinheap(jetI);
            }
        }
        if (distLessThanNN(jetX)) {
            if (jetI != jetX) {
                setNNFromDist(jetX, jetI);
            }
        }
    }

    void setNN(int jetI) {
        nnH[jetI] = r2H;
        nnL[jetI] = r2L;
        nn[jetI] = -1;
        pushForMinheap(jetI);
        final int tile = tileIndex[jetI];
        for (int nt : tileNeighbours[tile]) {
            if (nnBelow(jetI, distanceToTile(jetI, nt))) continue;
            for (int jetJ = tileHead[nt]; jetJ >= 0; jetJ = next[jetJ]) {
                dist(jetI, jetJ);
                if (distLessThanNN(jetI) && jetJ != jetI) {
                    setNNFromDist(jetI, jetJ);
                }
            }
        }
    }

    void updateTileMax(int s) {
        final int t = tileIndex[s];
        if (tileMaxNN[t] < nnH[s]) {
            tileMaxNN[t] = nnH[s];
        }
    }

    void run() {
        int n = jets.size();
        if (n == 0) return;
        allocate(n);
        double oldBEta = 0, oldBPhi = 0;
        int oldBTile = 0;
        final int[] tileUnion = new int[3 * (2 * reach + 1) * (2 * reach + 1)];
        for (int i = 0; i < n; i++) {
            tjSetJetInfo(i, i);
        }

        // neighbours within each tile
        for (int t = 0; t < tileHead.length; t++) {
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                for (int b = tileHead[t]; b != a; b = next[b]) {
                    distNotPeriodic(a, b);
                    if (distLessThanNN(a)) setNNFromDist(a, b);
                    if (distLessThanNN(b)) setNNFromDist(b, a);
                }
            }
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                if (nnH[a] > tileMaxNN[t]) tileMaxNN[t] = nnH[a];
            }
        }
        // then with the right-hand tiles, only where it can matter
        for (int t = 0; t < tileHead.length; t++) {
            final int[] nb = tileNeighbours[t];
            final boolean periodic = tilePeriodic[t];
            for (int k = tileRhStart[t]; k < nb.length; k++) {
                final int rt = nb[k];
                for (int a = tileHead[t]; a >= 0; a = next[a]) {
                    final double dtt = distanceToTile(a, rt);
                    final boolean relevantForA = nnAtMostReversed(dtt, a);
                    final boolean relevantForR = dtt <= tileMaxNN[rt];
                    if (relevantForA || relevantForR) {
                        for (int b = tileHead[rt]; b >= 0; b = next[b]) {
                            if (periodic) dist(a, b); else distNotPeriodic(a, b);
                            if (distLessThanNN(a)) setNNFromDist(a, b);
                            if (distLessThanNN(b)) setNNFromDist(b, a);
                        }
                    }
                }
            }
        }
        for (int t = 0; t < tileHead.length; t++) {
            tileMaxNN[t] = 0;
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                if (nnH[a] > tileMaxNN[t]) tileMaxNN[t] = nnH[a];
            }
        }

        final double[] vH = new double[n];
        final double[] vL = new double[n];
        for (int i = 0; i < n; i++) {
            diJ(i);
            vH[i] = rH;
            vL[i] = rL;
            heapUpdateNeeded[i] = false;
        }
        final MinHeap minheap = new MinHeap(vH, vL, n);

        while (n > 0) {
            normalise(minheap.minvalH(), minheap.minvalL());
            final double dijH = rH;
            final double dijL = rL;
            int jetA = minheap.minloc();
            int jetB = nn[jetA];
            if (jetB >= 0) {
                if (jetA < jetB) {
                    final int t = jetA;
                    jetA = jetB;
                    jetB = t;
                }
                final int nnew = cs.doIJRecombinationStep(jetsIndex[jetA], jetsIndex[jetB], dijH, dijL);
                removeFromTiles(jetA);
                oldBEta = etaH[jetB];
                oldBPhi = phiH[jetB];
                oldBTile = tileIndex[jetB];
                removeFromTiles(jetB);
                tjSetJetInfo(jetB, nnew);
            } else {
                cs.doIBRecombinationStep(jetsIndex[jetA], dijH, dijL);
                removeFromTiles(jetA);
            }
            minheap.remove(jetA);

            int nNear = 0;
            if (jetB >= 0) {
                final int bTile = tileIndex[jetB];
                for (int nt : tileNeighbours[bTile]) {
                    final double dtt = distanceToTile(jetB, nt);
                    final boolean relevantForB = nnAtMostReversed(dtt, jetB);
                    final boolean relevantForNear = dtt <= tileMaxNN[nt];
                    if (!(relevantForB || relevantForNear)) continue;
                    tileUnion[nNear++] = nt;
                    tileTagged[nt] = true;
                    for (int jetI = tileHead[nt]; jetI >= 0; jetI = next[jetI]) {
                        if (nn[jetI] == jetA || nn[jetI] == jetB) setNN(jetI);
                        updateJetXJetINN(jetB, jetI);
                    }
                }
            }
            final int nDone = nNear;
            nNear = addUntaggedNeighboursUsingMaxInfo(etaH[jetA], phiH[jetA], tileIndex[jetA], tileUnion, nNear);
            if (jetB >= 0) {
                nNear = addUntaggedNeighboursUsingMaxInfo(oldBEta, oldBPhi, oldBTile, tileUnion, nNear);
                heapUpdateNeeded[jetB] = false;
                pushForMinheap(jetB);
            }
            for (int itile = 0; itile < nDone; itile++) {
                tileTagged[tileUnion[itile]] = false;
            }
            for (int itile = nDone; itile < nNear; itile++) {
                final int tile = tileUnion[itile];
                tileTagged[tile] = false;
                for (int jetI = tileHead[tile]; jetI >= 0; jetI = next[jetI]) {
                    if (nn[jetI] == jetA || (nn[jetI] == jetB && jetB >= 0)) {
                        setNN(jetI);
                    }
                }
            }
            while (nForMinheap > 0) {
                final int jetI = forMinheap[--nForMinheap];
                diJ(jetI);
                minheap.update(jetI, rH, rL);
                heapUpdateNeeded[jetI] = false;
                updateTileMax(jetI);
            }
            n--;
        }
    }

    /** dtt <= NN_dist of the slot, the tile distance being a double. */
    boolean nnAtMostReversed(double dtt, int s) {
        return dtt < nnH[s] || (dtt == nnH[s] && nnL[s] >= 0.0);
    }
}
