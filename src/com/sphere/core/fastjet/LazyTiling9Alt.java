package com.sphere.core.fastjet;

import java.util.Arrays;

/**
 * The N2MHTLazy9Alt strategy: LazyTiling9 with the distance to a neighbouring
 * tile measured to its nearest edge or corner rather than from its centre,
 * and a different order of the updates.
 */
final class LazyTiling9Alt extends BriefJetEngine {

    // How the distance to a neighbour is measured, as in FastJet's Tile.
    private static final int CENTRE = 0, LEFT = 1, RIGHT = 2, BOTTOM = 3, TOP = 4,
        LEFT_TOP = 5, LEFT_BOTTOM = 6, RIGHT_TOP = 7, RIGHT_BOTTOM = 8;

    private int[] tileHead;
    private boolean[] tileTagged;
    private int[][] tileNeighbours;
    private int[][] tileNeighbourFn;
    private int[] tileRhStart;
    private boolean[] tilePeriodic;
    private double[] tileMaxNN;
    private double[] tileEtaMin, tileEtaMax, tilePhiMin, tilePhiMax;
    private double tilesEtaMin, tilesEtaMax, tileSizeEta, tileSizePhi;
    private int nTilesPhi, tilesIetaMin, tilesIetaMax;

    private int[] tileIndex, previous, next;
    private boolean[] heapUpdateNeeded;
    private int[] forMinheap = new int[64];
    private int nForMinheap;

    LazyTiling9Alt(ClusterSequence cs) {
        super(cs);
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

    private int tileIndexOf(int ieta, int iphi) {
        return (ieta - tilesIetaMin) * nTilesPhi + (iphi + nTilesPhi) % nTilesPhi;
    }

    private void initialiseTiles() {
        double defaultSize = Math.max(0.1, cs.jetDef.R());
        if (dd) {
            defaultSize *= 1.0 + 1e-12;
        }
        tileSizeEta = defaultSize;
        nTilesPhi = Math.max(3, (int) Math.floor(TWOPI / defaultSize));
        tileSizePhi = TWOPI / nTilesPhi;
        tilesEtaMin = 0.0;
        tilesEtaMax = 0.0;
        final double maxrap = 7.0;
        for (PseudoJet j : jets) {
            final double eta = j.rap();
            if (Math.abs(eta) < maxrap) {
                if (eta < tilesEtaMin) tilesEtaMin = eta;
                if (eta > tilesEtaMax) tilesEtaMax = eta;
            }
        }
        tilesIetaMin = (int) Math.floor(tilesEtaMin / tileSizeEta);
        tilesIetaMax = (int) Math.floor(tilesEtaMax / tileSizeEta);
        tilesEtaMin = tilesIetaMin * tileSizeEta;
        tilesEtaMax = tilesIetaMax * tileSizeEta;

        final boolean[] usePeriodic = new boolean[nTilesPhi];
        if (nTilesPhi <= 3) {
            Arrays.fill(usePeriodic, true);
        } else {
            usePeriodic[0] = true;
            usePeriodic[nTilesPhi - 1] = true;
        }
        final int nTiles = (tilesIetaMax - tilesIetaMin + 1) * nTilesPhi;
        tileHead = new int[nTiles];
        tileTagged = new boolean[nTiles];
        tileNeighbours = new int[nTiles][];
        tileNeighbourFn = new int[nTiles][];
        tileRhStart = new int[nTiles];
        tilePeriodic = new boolean[nTiles];
        tileMaxNN = new double[nTiles];
        tileEtaMin = new double[nTiles];
        tileEtaMax = new double[nTiles];
        tilePhiMin = new double[nTiles];
        tilePhiMax = new double[nTiles];
        final int[] nb = new int[9];
        final int[] fn = new int[9];
        for (int ieta = tilesIetaMin; ieta <= tilesIetaMax; ieta++) {
            for (int iphi = 0; iphi < nTilesPhi; iphi++) {
                final int t = tileIndexOf(ieta, iphi);
                tileHead[t] = -1;
                int k = 0;
                nb[k] = t; fn[k++] = CENTRE;
                if (ieta > tilesIetaMin) {
                    nb[k] = tileIndexOf(ieta - 1, iphi - 1); fn[k++] = LEFT_BOTTOM;
                    nb[k] = tileIndexOf(ieta - 1, iphi); fn[k++] = LEFT;
                    nb[k] = tileIndexOf(ieta - 1, iphi + 1); fn[k++] = LEFT_TOP;
                }
                nb[k] = tileIndexOf(ieta, iphi - 1); fn[k++] = BOTTOM;
                tileRhStart[t] = k;
                nb[k] = tileIndexOf(ieta, iphi + 1); fn[k++] = TOP;
                if (ieta < tilesIetaMax) {
                    nb[k] = tileIndexOf(ieta + 1, iphi - 1); fn[k++] = RIGHT_BOTTOM;
                    nb[k] = tileIndexOf(ieta + 1, iphi); fn[k++] = RIGHT;
                    nb[k] = tileIndexOf(ieta + 1, iphi + 1); fn[k++] = RIGHT_TOP;
                }
                tileNeighbours[t] = Arrays.copyOf(nb, k);
                tileNeighbourFn[t] = Arrays.copyOf(fn, k);
                tileTagged[t] = false;
                tilePeriodic[t] = usePeriodic[iphi];
                tileMaxNN[t] = 0;
                tileEtaMin[t] = ieta * tileSizeEta;
                tileEtaMax[t] = (ieta + 1) * tileSizeEta;
                tilePhiMin[t] = iphi * tileSizePhi;
                tilePhiMax[t] = (iphi + 1) * tileSizePhi;
            }
        }
    }

    private int tileIndexOfPoint(double eta, double phi) {
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
        final int iphi = LazyTilingEngine.phiTileAcrossZero(((int) ((phi + TWOPI) / tileSizePhi)) % nTilesPhi, phi, nTilesPhi);
        return iphi + ieta * nTilesPhi;
    }

    /**
     * The distance from a point to an edge or a corner of its own tile. It
     * is not periodic in phi, so it relies on every point lying in its own
     * tile (see LazyTilingEngine.phiTileAcrossZero).
     */
    private double edgeDistance(int fn, int ownTile, double eta, double phi) {
        final double d;
        switch (fn) {
            case CENTRE -> d = 0;
            case LEFT -> {
                final double deta = eta - tileEtaMin[ownTile];
                d = deta * deta;
            }
            case RIGHT -> {
                final double deta = eta - tileEtaMax[ownTile];
                d = deta * deta;
            }
            case BOTTOM -> {
                final double dphi = phi - tilePhiMin[ownTile];
                d = dphi * dphi;
            }
            case TOP -> {
                final double dphi = phi - tilePhiMax[ownTile];
                d = dphi * dphi;
            }
            case LEFT_TOP -> {
                final double deta = eta - tileEtaMin[ownTile];
                final double dphi = phi - tilePhiMax[ownTile];
                d = deta * deta + dphi * dphi;
            }
            case LEFT_BOTTOM -> {
                final double deta = eta - tileEtaMin[ownTile];
                final double dphi = phi - tilePhiMin[ownTile];
                d = deta * deta + dphi * dphi;
            }
            case RIGHT_TOP -> {
                final double deta = eta - tileEtaMax[ownTile];
                final double dphi = phi - tilePhiMax[ownTile];
                d = deta * deta + dphi * dphi;
            }
            default -> {
                final double deta = eta - tileEtaMax[ownTile];
                final double dphi = phi - tilePhiMin[ownTile];
                d = deta * deta + dphi * dphi;
            }
        }
        return (dd && fn != CENTRE) ? d - LazyTilingEngine.DD_GEOMETRY_MARGIN : d;
    }

    private void tjSetJetInfo(int s, int jetIndex) {
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

    private void removeFromTiles(int s) {
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

    private boolean dttAtMostNN(double dtt, int s) {
        return dtt < nnH[s] || (dtt == nnH[s] && nnL[s] >= 0.0);
    }

    private void pushForMinheap(int s) {
        if (!heapUpdateNeeded[s]) {
            heapUpdateNeeded[s] = true;
            if (nForMinheap == forMinheap.length) {
                forMinheap = Arrays.copyOf(forMinheap, nForMinheap * 2);
            }
            forMinheap[nForMinheap++] = s;
        }
    }

    private int addUntaggedUsingMaxInfo(double eta, double phi, int ownTile, int[] union, int nNear) {
        final int[] nbs = tileNeighbours[ownTile];
        final int[] fns = tileNeighbourFn[ownTile];
        for (int k = 0; k < nbs.length; k++) {
            final int nt = nbs[k];
            if (tileTagged[nt]) continue;
            final double dist = edgeDistance(fns[k], ownTile, eta, phi) - LazyTilingEngine.TILE_EDGE_SECURITY_MARGIN;
            if (dist > tileMaxNN[nt]) continue;
            tileTagged[nt] = true;
            union[nNear++] = nt;
        }
        return nNear;
    }

    private void updateJetXJetINN(int jetX, int jetI) {
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

    private void setNN(int jetI) {
        nnH[jetI] = r2H;
        nnL[jetI] = r2L;
        nn[jetI] = -1;
        pushForMinheap(jetI);
        final int tile = tileIndex[jetI];
        final int[] nbs = tileNeighbours[tile];
        final int[] fns = tileNeighbourFn[tile];
        for (int k = 0; k < nbs.length; k++) {
            final double dtt = edgeDistance(fns[k], tile, etaH[jetI], phiH[jetI]);
            if (nnH[jetI] < dtt || (nnH[jetI] == dtt && nnL[jetI] < 0.0)) continue;
            for (int jetJ = tileHead[nbs[k]]; jetJ >= 0; jetJ = next[jetJ]) {
                dist(jetI, jetJ);
                if (distLessThanNN(jetI) && jetJ != jetI) {
                    setNNFromDist(jetI, jetJ);
                }
            }
        }
    }

    void run() {
        int n = jets.size();
        if (n == 0) return;
        allocate(n);
        double oldBEta = 0, oldBPhi = 0;
        int oldBTile = 0;
        final int[] tileUnion = new int[3 * 9];
        for (int i = 0; i < n; i++) {
            tjSetJetInfo(i, i);
        }
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
        for (int t = 0; t < tileHead.length; t++) {
            final int[] nbs = tileNeighbours[t];
            final int[] fns = tileNeighbourFn[t];
            for (int k = tileRhStart[t]; k < nbs.length; k++) {
                final int rt = nbs[k];
                for (int a = tileHead[t]; a >= 0; a = next[a]) {
                    final double dtt = edgeDistance(fns[k], t, etaH[a], phiH[a]);
                    final boolean relevantForA = dttAtMostNN(dtt, a);
                    final boolean relevantForR = dtt <= tileMaxNN[rt];
                    if (relevantForA || relevantForR) {
                        for (int b = tileHead[rt]; b >= 0; b = next[b]) {
                            if (tilePeriodic[t]) dist(a, b); else distNotPeriodic(a, b);
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

            int nNear = addUntaggedUsingMaxInfo(etaH[jetA], phiH[jetA], tileIndex[jetA], tileUnion, 0);
            if (jetB >= 0) {
                nNear = addUntaggedUsingMaxInfo(oldBEta, oldBPhi, oldBTile, tileUnion, nNear);
                heapUpdateNeeded[jetB] = false;
                pushForMinheap(jetB);
            }
            if (jetB >= 0) {
                final int bTile = tileIndex[jetB];
                final int[] nbs = tileNeighbours[bTile];
                final int[] fns = tileNeighbourFn[bTile];
                for (int k = 0; k < nbs.length; k++) {
                    final int nt = nbs[k];
                    final double dtt = edgeDistance(fns[k], bTile, etaH[jetB], phiH[jetB]);
                    final boolean relevantForB = dttAtMostNN(dtt, jetB);
                    final boolean relevantForNear = dtt <= tileMaxNN[nt];
                    if (relevantForB || relevantForNear) {
                        if (tileTagged[nt]) {
                            for (int jetI = tileHead[nt]; jetI >= 0; jetI = next[jetI]) {
                                if (nn[jetI] == jetA || nn[jetI] == jetB) setNN(jetI);
                                updateJetXJetINN(jetB, jetI);
                            }
                            tileTagged[nt] = false;
                        } else {
                            for (int jetI = tileHead[nt]; jetI >= 0; jetI = next[jetI]) {
                                updateJetXJetINN(jetB, jetI);
                            }
                        }
                    }
                }
            }
            for (int itile = 0; itile < nNear; itile++) {
                final int tile = tileUnion[itile];
                if (!tileTagged[tile]) continue;
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
                final int t = tileIndex[jetI];
                if (tileMaxNN[t] < nnH[jetI]) tileMaxNN[t] = nnH[jetI];
            }
            n--;
        }
    }
}
