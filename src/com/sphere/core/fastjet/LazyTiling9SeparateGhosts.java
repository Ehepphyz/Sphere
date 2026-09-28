package com.sphere.core.fastjet;

import java.util.Arrays;

/**
 * The N2MHTLazy9AntiKtSeparateGhosts strategy: for anti-kt with ghosts, the
 * ghosts are kept in lists of their own, only ever serve as neighbours of real
 * jets, and are left unclustered at the end, which is all an area needs.
 */
final class LazyTiling9SeparateGhosts extends BriefJetEngine {

    /** Below this pt^2 an input is a ghost. */
    static volatile double ghostPt2Threshold = 1e-100;

    private int[] tileHead, tileGhostHead;
    private boolean[] tileTagged;
    private int[][] tileNeighbours;
    private int[] tileRhStart;
    private double[] tileMaxNN, tileEtaCentre, tilePhiCentre;
    private double tilesEtaMin, tilesEtaMax, tileSizeEta, tileSizePhi, tileHalfSizeEta, tileHalfSizePhi;
    private int nTilesPhi, tilesIetaMin, tilesIetaMax;

    private int[] tileIndex, previous, next;
    private boolean[] isGhost, heapUpdateNeeded;
    private int[] forMinheap = new int[64];
    private int nForMinheap;

    LazyTiling9SeparateGhosts(ClusterSequence cs) {
        super(cs);
        initialiseTiles();
    }

    @Override
    void allocate(int n) {
        super.allocate(n);
        tileIndex = new int[n];
        previous = new int[n];
        next = new int[n];
        isGhost = new boolean[n];
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
        tileHalfSizeEta = tileSizeEta * 0.5;
        tileHalfSizePhi = tileSizePhi * 0.5;

        final int nTiles = (tilesIetaMax - tilesIetaMin + 1) * nTilesPhi;
        tileHead = new int[nTiles];
        tileGhostHead = new int[nTiles];
        tileTagged = new boolean[nTiles];
        tileNeighbours = new int[nTiles][];
        tileRhStart = new int[nTiles];
        tileMaxNN = new double[nTiles];
        tileEtaCentre = new double[nTiles];
        tilePhiCentre = new double[nTiles];
        final int[] buffer = new int[9];
        for (int ieta = tilesIetaMin; ieta <= tilesIetaMax; ieta++) {
            for (int iphi = 0; iphi < nTilesPhi; iphi++) {
                final int t = tileIndexOf(ieta, iphi);
                tileHead[t] = -1;
                tileGhostHead[t] = -1;
                int k = 0;
                buffer[k++] = t;
                if (ieta > tilesIetaMin) {
                    for (int idphi = -1; idphi <= +1; idphi++) {
                        buffer[k++] = tileIndexOf(ieta - 1, iphi + idphi);
                    }
                }
                buffer[k++] = tileIndexOf(ieta, iphi - 1);
                tileRhStart[t] = k;
                buffer[k++] = tileIndexOf(ieta, iphi + 1);
                if (ieta < tilesIetaMax) {
                    for (int idphi = -1; idphi <= +1; idphi++) {
                        buffer[k++] = tileIndexOf(ieta + 1, iphi + idphi);
                    }
                }
                tileNeighbours[t] = Arrays.copyOf(buffer, k);
                tileTagged[t] = false;
                tileMaxNN[t] = 0;
                tileEtaCentre[t] = (ieta + 0.5) * tileSizeEta;
                tilePhiCentre[t] = (iphi + 0.5) * tileSizePhi;
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
        final int iphi = ((int) ((phi + TWOPI) / tileSizePhi)) % nTilesPhi;
        return iphi + ieta * nTilesPhi;
    }

    private void tjSetJetInfo(int s, int jetIndex, boolean ghost) {
        setJetInfo(s, jetIndex);
        isGhost[s] = ghost;
        tileIndex[s] = tileIndexOfPoint(etaH[s], phiH[s]);
        final int t = tileIndex[s];
        previous[s] = -1;
        if (ghost) {
            next[s] = tileGhostHead[t];
            tileGhostHead[t] = s;
        } else {
            next[s] = tileHead[t];
            tileHead[t] = s;
        }
        if (next[s] >= 0) {
            previous[next[s]] = s;
        }
    }

    private void removeFromTiles(int s) {
        final int t = tileIndex[s];
        if (previous[s] < 0) {
            if (isGhost[s]) {
                tileGhostHead[t] = next[s];
            } else {
                tileHead[t] = next[s];
            }
        } else {
            next[previous[s]] = next[s];
        }
        if (next[s] >= 0) {
            previous[next[s]] = previous[s];
        }
    }

    private double distanceToTile(double eta, double phi, int ownTile, int tile) {
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
        return dd ? d - LazyTilingEngine.DD_GEOMETRY_MARGIN : d;
    }

    private double distanceToTile(int s, int tile) {
        return distanceToTile(etaH[s], phiH[s], tileIndex[s], tile);
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
        for (int nt : tileNeighbours[ownTile]) {
            if (tileTagged[nt]) continue;
            final double dist = distanceToTile(eta, phi, ownTile, nt) - LazyTilingEngine.TILE_EDGE_SECURITY_MARGIN;
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
        for (int nt : tileNeighbours[tile]) {
            final double dtt = distanceToTile(jetI, nt);
            if (nnH[jetI] < dtt || (nnH[jetI] == dtt && nnL[jetI] < 0.0)) continue;
            for (int jetJ = tileHead[nt]; jetJ >= 0; jetJ = next[jetJ]) {
                dist(jetI, jetJ);
                if (distLessThanNN(jetI) && jetJ != jetI) {
                    setNNFromDist(jetI, jetJ);
                }
            }
            for (int jetJ = tileGhostHead[nt]; jetJ >= 0; jetJ = next[jetJ]) {
                dist(jetI, jetJ);
                if (distLessThanNN(jetI)) {
                    setNNFromDist(jetI, jetJ);
                }
            }
        }
    }

    void run() {
        final int ntot = jets.size();
        if (ntot == 0) return;
        allocate(ntot);
        double oldBEta = 0, oldBPhi = 0;
        int oldBTile = 0;
        final int[] tileUnion = new int[3 * 9];
        int slot = 0;
        for (int i = 0; i < ntot; i++) {
            if (!(jets.get(i).perp2() < ghostPt2Threshold)) {
                tjSetJetInfo(slot++, i, false);
            }
        }
        int nreal = slot;
        for (int i = 0; i < ntot; i++) {
            if (jets.get(i).perp2() < ghostPt2Threshold) {
                tjSetJetInfo(slot++, i, true);
            }
        }

        for (int t = 0; t < tileHead.length; t++) {
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                for (int b = tileHead[t]; b != a; b = next[b]) {
                    distNotPeriodic(a, b);
                    if (distLessThanNN(a)) setNNFromDist(a, b);
                    if (distLessThanNN(b)) setNNFromDist(b, a);
                }
                for (int b = tileGhostHead[t]; b >= 0; b = next[b]) {
                    distNotPeriodic(a, b);
                    if (distLessThanNN(a)) setNNFromDist(a, b);
                }
            }
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                if (nnH[a] > tileMaxNN[t]) tileMaxNN[t] = nnH[a];
            }
        }
        for (int t = 0; t < tileHead.length; t++) {
            final int[] nbs = tileNeighbours[t];
            for (int k = tileRhStart[t]; k < nbs.length; k++) {
                final int rt = nbs[k];
                for (int a = tileHead[t]; a >= 0; a = next[a]) {
                    final double dtt = distanceToTile(a, rt);
                    final boolean relevantForA = dttAtMostNN(dtt, a);
                    final boolean relevantForR = dtt <= tileMaxNN[rt];
                    if (relevantForA || relevantForR) {
                        for (int b = tileHead[rt]; b >= 0; b = next[b]) {
                            dist(a, b);
                            if (distLessThanNN(a)) setNNFromDist(a, b);
                            if (distLessThanNN(b)) setNNFromDist(b, a);
                        }
                    }
                    if (relevantForA) {
                        for (int b = tileGhostHead[rt]; b >= 0; b = next[b]) {
                            dist(a, b);
                            if (distLessThanNN(a)) setNNFromDist(a, b);
                        }
                    }
                }
            }
            for (int k = 1; k < tileRhStart[t]; k++) {
                final int lt = nbs[k];
                for (int a = tileHead[t]; a >= 0; a = next[a]) {
                    final double dtt = distanceToTile(a, lt);
                    if (dttAtMostNN(dtt, a)) {
                        for (int b = tileGhostHead[lt]; b >= 0; b = next[b]) {
                            dist(a, b);
                            if (distLessThanNN(a)) setNNFromDist(a, b);
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

        final double[] vH = new double[nreal];
        final double[] vL = new double[nreal];
        for (int i = 0; i < nreal; i++) {
            diJ(i);
            vH[i] = rH;
            vL[i] = rL;
            heapUpdateNeeded[i] = false;
        }
        final MinHeap minheap = new MinHeap(vH, vL, nreal);

        while (nreal > 0) {
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
                tjSetJetInfo(jetB, nnew, false);
            } else {
                cs.doIBRecombinationStep(jetsIndex[jetA], dijH, dijL);
                removeFromTiles(jetA);
            }
            if (!isGhost[jetA]) {
                minheap.remove(jetA);
                nreal--;
            }

            int nNear = addUntaggedUsingMaxInfo(etaH[jetA], phiH[jetA], tileIndex[jetA], tileUnion, 0);
            if (jetB >= 0) {
                nNear = addUntaggedUsingMaxInfo(oldBEta, oldBPhi, oldBTile, tileUnion, nNear);
                heapUpdateNeeded[jetB] = false;
                pushForMinheap(jetB);
            }
            if (jetB >= 0) {
                for (int nt : tileNeighbours[tileIndex[jetB]]) {
                    final double dtt = distanceToTile(jetB, nt);
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
                    if (relevantForB) {
                        for (int jetI = tileGhostHead[nt]; jetI >= 0; jetI = next[jetI]) {
                            dist(jetB, jetI);
                            if (distLessThanNN(jetB)) {
                                setNNFromDist(jetB, jetI);
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
        }
    }
}
