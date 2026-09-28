package com.sphere.core.fastjet;

import java.util.Arrays;

/**
 * S. Fortune's sweep-line Voronoi diagram, in the C++ form of S. O'Sullivan
 * that FastJet carries (Voronoi.cc), translated to Java. The free lists of
 * the C version are left to the garbage collector; the order in which edges
 * are produced, and so the order of every sum made over them, is kept.
 */
final class VoronoiDiagramGenerator {

    private static final int LE = 0;
    private static final int RE = 1;
    private static final LimitedWarning WARNING_DEGENERACY = new LimitedWarning();

    static final class VPoint {
        double x, y;

        VPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class GraphEdge {
        double x1, y1, x2, y2;
        int point1, point2;
        GraphEdge next;
    }

    private static final class Site {
        double x, y;
        int sitenbr;
        int refcnt;
    }

    private static final class Edge {
        double a, b, c;
        final Site[] ep = new Site[2];
        final Site[] reg = new Site[2];
        int edgenbr;
    }

    private static final Edge DELETED = new Edge();

    private static final class Halfedge {
        Halfedge elLeft, elRight;
        Edge elEdge;
        int elRefcnt;
        int elPm;
        Site vertex;
        double ystar;
        Halfedge pqNext;
    }

    private Halfedge[] elHash;
    private Halfedge elLeftEnd, elRightEnd;
    private int elHashSize;
    private double xmin, xmax, ymin, ymax, deltax, deltay;
    private Site[] sites;
    private int nsites;
    private int siteidx;
    private int sqrtNsites;
    private int nvertices;
    private Site bottomsite;
    private int nedges;
    private int pqHashSize;
    private Halfedge[] pqHash;
    private int pqCount;
    private int pqMin;
    private double borderMinX, borderMaxX, borderMinY, borderMaxY;
    private GraphEdge allEdges;
    private GraphEdge iteratorEdges;

    boolean generateVoronoi(VPoint[] parentSites, double minX, double maxX, double minY, double maxY) {
        allEdges = null;
        nsites = parentSites.length;
        sites = new Site[nsites];
        xmax = xmin = parentSites[0].x;
        ymax = ymin = parentSites[0].y;
        for (int i = 0; i < nsites; i++) {
            final double x = parentSites[i].x;
            final double y = parentSites[i].y;
            final Site s = new Site();
            s.x = x;
            s.y = y;
            s.sitenbr = i;
            s.refcnt = 0;
            sites[i] = s;
            if (x < xmin) xmin = x;
            else if (x > xmax) xmax = x;
            if (y < ymin) ymin = y;
            else if (y > ymax) ymax = y;
        }
        Arrays.sort(sites, (s1, s2) -> {
            if (s1.y < s2.y) return -1;
            if (s1.y > s2.y) return 1;
            if (s1.x < s2.x) return -1;
            if (s1.x > s2.x) return 1;
            return 0;
        });
        int offset = 0;
        for (int is = 1; is < nsites; is++) {
            if (sites[is].y == sites[is - 1].y && sites[is].x == sites[is - 1].x) {
                offset++;
            } else if (offset > 0) {
                sites[is - offset] = sites[is];
            }
        }
        if (offset > 0) {
            nsites -= offset;
            WARNING_DEGENERACY.warn("VoronoiDiagramGenerator: two (or more) particles are degenerate in rapidity and azimuth, Voronoi cell assigned to the first of each set of degenerate particles.");
        }
        siteidx = 0;
        geominit();
        double t;
        if (minX > maxX) {
            t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minY > maxY) {
            t = minY;
            minY = maxY;
            maxY = t;
        }
        borderMinX = minX;
        borderMinY = minY;
        borderMaxX = maxX;
        borderMaxY = maxY;
        siteidx = 0;
        voronoi();
        return true;
    }

    void resetIterator() {
        iteratorEdges = allEdges;
    }

    GraphEdge next() {
        if (iteratorEdges == null) return null;
        final GraphEdge e = iteratorEdges;
        iteratorEdges = iteratorEdges.next;
        return e;
    }

    private void elInitialize() {
        elHashSize = 2 * sqrtNsites;
        elHash = new Halfedge[elHashSize];
        elLeftEnd = heCreate(null, 0);
        elRightEnd = heCreate(null, 0);
        elLeftEnd.elLeft = null;
        elLeftEnd.elRight = elRightEnd;
        elRightEnd.elLeft = elLeftEnd;
        elRightEnd.elRight = null;
        elHash[0] = elLeftEnd;
        elHash[elHashSize - 1] = elRightEnd;
    }

    private Halfedge heCreate(Edge e, int pm) {
        final Halfedge answer = new Halfedge();
        answer.elEdge = e;
        answer.elPm = pm;
        answer.pqNext = null;
        answer.vertex = null;
        answer.elRefcnt = 0;
        return answer;
    }

    private void elInsert(Halfedge lb, Halfedge newHe) {
        newHe.elLeft = lb;
        newHe.elRight = lb.elRight;
        lb.elRight.elLeft = newHe;
        lb.elRight = newHe;
    }

    private Halfedge elGetHash(int b) {
        if (b < 0 || b >= elHashSize) return null;
        final Halfedge he = elHash[b];
        if (he == null || he.elEdge != DELETED) return he;
        elHash[b] = null;
        he.elRefcnt -= 1;
        return null;
    }

    private Halfedge elLeftBnd(double px, double py) {
        int bucket;
        if (px < xmin) {
            bucket = 0;
        } else if (px >= xmax) {
            bucket = elHashSize - 1;
        } else {
            bucket = (int) ((px - xmin) / deltax * elHashSize);
            if (bucket >= elHashSize) bucket = elHashSize - 1;
        }
        Halfedge he = elGetHash(bucket);
        if (he == null) {
            for (int i = 1; ; i++) {
                if ((he = elGetHash(bucket - i)) != null) break;
                if ((he = elGetHash(bucket + i)) != null) break;
            }
        }
        if (he == elLeftEnd || (he != elRightEnd && rightOf(he, px, py))) {
            do {
                he = he.elRight;
            } while (he != elRightEnd && rightOf(he, px, py));
            he = he.elLeft;
        } else {
            do {
                he = he.elLeft;
            } while (he != elLeftEnd && !rightOf(he, px, py));
        }
        if (bucket > 0 && bucket < elHashSize - 1) {
            if (elHash[bucket] != null) {
                elHash[bucket].elRefcnt -= 1;
            }
            elHash[bucket] = he;
            elHash[bucket].elRefcnt += 1;
        }
        return he;
    }

    private void elDelete(Halfedge he) {
        he.elLeft.elRight = he.elRight;
        he.elRight.elLeft = he.elLeft;
        he.elEdge = DELETED;
    }

    private Site leftreg(Halfedge he) {
        if (he.elEdge == null) return bottomsite;
        return he.elPm == LE ? he.elEdge.reg[LE] : he.elEdge.reg[RE];
    }

    private Site rightreg(Halfedge he) {
        if (he.elEdge == null) return bottomsite;
        return he.elPm == LE ? he.elEdge.reg[RE] : he.elEdge.reg[LE];
    }

    private void geominit() {
        nvertices = 0;
        nedges = 0;
        final double sn = (double) nsites + 4;
        sqrtNsites = (int) Math.sqrt(sn);
        deltay = ymax - ymin;
        deltax = xmax - xmin;
    }

    private Edge bisect(Site s1, Site s2) {
        final Edge newedge = new Edge();
        newedge.reg[0] = s1;
        newedge.reg[1] = s2;
        s1.refcnt++;
        s2.refcnt++;
        newedge.ep[0] = null;
        newedge.ep[1] = null;
        final double dx = s2.x - s1.x;
        final double dy = s2.y - s1.y;
        final double adx = dx > 0 ? dx : -dx;
        final double ady = dy > 0 ? dy : -dy;
        newedge.c = s1.x * dx + s1.y * dy + (dx * dx + dy * dy) * 0.5;
        if (adx > ady) {
            newedge.a = 1.0;
            newedge.b = dy / dx;
            newedge.c /= dx;
        } else {
            newedge.b = 1.0;
            newedge.a = dx / dy;
            newedge.c /= dy;
        }
        newedge.edgenbr = nedges;
        nedges++;
        return newedge;
    }

    private Site intersect(Halfedge el1, Halfedge el2) {
        final Edge e1 = el1.elEdge;
        final Edge e2 = el2.elEdge;
        if (e1 == null || e2 == null) return null;
        if (e1.reg[1] == e2.reg[1]) return null;
        final double dx = e2.reg[1].x - e1.reg[1].x;
        final double dy = e2.reg[1].y - e1.reg[1].y;
        final double dxref = e1.reg[1].x - e1.reg[0].x;
        final double dyref = e1.reg[1].y - e1.reg[0].y;
        double d, xint, yint;
        if (dx * dx + dy * dy < 1e-14 * (dxref * dxref + dyref * dyref)) {
            final double adx = dx > 0 ? dx : -dx;
            final double ady = dy > 0 ? dy : -dy;
            double a, b;
            double c = e1.reg[1].x * dx + e1.reg[1].y * dy + (dx * dx + dy * dy) * 0.5;
            if (adx > ady) {
                a = 1.0;
                b = dy / dx;
                c /= dx;
            } else {
                b = 1.0;
                a = dx / dy;
                c /= dy;
            }
            d = e1.a * b - e1.b * a;
            if (-1.0e-10 < d && d < 1.0e-10) return null;
            xint = (e1.c * b - c * e1.b) / d;
            yint = (c * e1.a - e1.c * a) / d;
        } else {
            d = e1.a * e2.b - e1.b * e2.a;
            if (-1.0e-10 < d && d < 1.0e-10) return null;
            xint = (e1.c * e2.b - e2.c * e1.b) / d;
            yint = (e2.c * e1.a - e1.c * e2.a) / d;
        }
        final double y1 = e1.reg[1].y;
        final double y2 = e2.reg[1].y;
        final Halfedge el;
        final Edge e;
        if (y1 < y2 || (y1 == y2 && e1.reg[1].x < e2.reg[1].x)) {
            el = el1;
            e = e1;
        } else {
            el = el2;
            e = e2;
        }
        final boolean rightOfSite = xint >= e.reg[1].x;
        if ((rightOfSite && el.elPm == LE) || (!rightOfSite && el.elPm == RE)) return null;
        final Site v = new Site();
        v.refcnt = 0;
        v.x = xint;
        v.y = yint;
        return v;
    }

    private boolean rightOf(Halfedge el, double px, double py) {
        final Edge e = el.elEdge;
        final Site topsite = e.reg[1];
        final boolean rightOfSite = px > topsite.x;
        if (rightOfSite && el.elPm == LE) return true;
        if (!rightOfSite && el.elPm == RE) return false;
        boolean above;
        if (e.a == 1.0) {
            final double dyp = py - topsite.y;
            final double dxp = px - topsite.x;
            boolean fast = false;
            if ((!rightOfSite & (e.b < 0.0)) | (rightOfSite & (e.b >= 0.0))) {
                above = dyp >= e.b * dxp;
                fast = above;
            } else {
                above = px + py * e.b > e.c;
                if (e.b < 0.0) above = !above;
                if (!above) fast = true;
            }
            if (!fast) {
                final double dxs = topsite.x - e.reg[0].x;
                above = e.b * (dxp * dxp - dyp * dyp) < dxs * dyp * (1.0 + 2.0 * dxp / dxs + e.b * e.b);
                if (e.b < 0.0) above = !above;
            }
        } else {
            final double yl = e.c - e.a * px;
            final double t1 = py - yl;
            final double t2 = px - topsite.x;
            final double t3 = yl - topsite.y;
            above = t1 * t1 > t2 * t2 + t3 * t3;
        }
        return el.elPm == LE ? above : !above;
    }

    private void endpoint(Edge e, int lr, Site s) {
        e.ep[lr] = s;
        s.refcnt++;
        if (e.ep[RE - lr] == null) return;
        clipLine(e);
        e.reg[LE].refcnt--;
        e.reg[RE].refcnt--;
    }

    private static double dist(Site s, Site t) {
        final double dx = s.x - t.x;
        final double dy = s.y - t.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void makevertex(Site v) {
        v.sitenbr = nvertices;
        nvertices += 1;
    }

    private void pqInsert(Halfedge he, Site v, double offset) {
        he.vertex = v;
        v.refcnt++;
        he.ystar = v.y + offset;
        Halfedge last = pqHash[pqBucket(he)];
        Halfedge next;
        while ((next = last.pqNext) != null
               && (he.ystar > next.ystar || (he.ystar == next.ystar && v.x > next.vertex.x))) {
            last = next;
        }
        he.pqNext = last.pqNext;
        last.pqNext = he;
        pqCount += 1;
    }

    private void pqDelete(Halfedge he) {
        if (he.vertex != null) {
            Halfedge last = pqHash[pqBucket(he)];
            while (last.pqNext != he) last = last.pqNext;
            last.pqNext = he.pqNext;
            pqCount -= 1;
            he.vertex.refcnt--;
            he.vertex = null;
        }
    }

    private int pqBucket(Halfedge he) {
        int bucket;
        final double hey = he.ystar;
        if (hey < ymin) {
            bucket = 0;
        } else if (hey >= ymax) {
            bucket = pqHashSize - 1;
        } else {
            bucket = (int) ((hey - ymin) / deltay * pqHashSize);
            if (bucket >= pqHashSize) bucket = pqHashSize - 1;
        }
        if (bucket < pqMin) pqMin = bucket;
        return bucket;
    }

    private boolean pqEmpty() {
        return pqCount == 0;
    }

    private double[] pqMinPoint() {
        while (pqHash[pqMin].pqNext == null) pqMin += 1;
        return new double[]{pqHash[pqMin].pqNext.vertex.x, pqHash[pqMin].pqNext.ystar};
    }

    private Halfedge pqExtractMin() {
        final Halfedge curr = pqHash[pqMin].pqNext;
        pqHash[pqMin].pqNext = curr.pqNext;
        pqCount -= 1;
        return curr;
    }

    private void pqInitialize() {
        pqCount = 0;
        pqMin = 0;
        pqHashSize = 4 * sqrtNsites;
        pqHash = new Halfedge[pqHashSize];
        for (int i = 0; i < pqHashSize; i++) {
            pqHash[i] = new Halfedge();
            pqHash[i].pqNext = null;
        }
    }

    private void pushGraphEdge(double x1, double y1, double x2, double y2, Site s1, Site s2) {
        final GraphEdge newEdge = new GraphEdge();
        newEdge.next = allEdges;
        allEdges = newEdge;
        newEdge.x1 = x1;
        newEdge.y1 = y1;
        newEdge.x2 = x2;
        newEdge.y2 = y2;
        newEdge.point1 = s1.sitenbr;
        newEdge.point2 = s2.sitenbr;
    }

    private void clipLine(Edge e) {
        final double pxmin = borderMinX;
        final double pxmax = borderMaxX;
        final double pymin = borderMinY;
        final double pymax = borderMaxY;
        final Site s1;
        final Site s2;
        double x1, x2, y1, y2;
        if (e.a == 1.0 && e.b >= 0.0) {
            s1 = e.ep[1];
            s2 = e.ep[0];
        } else {
            s1 = e.ep[0];
            s2 = e.ep[1];
        }
        if (e.a == 1.0) {
            y1 = pymin;
            if (s1 != null && s1.y > pymin) y1 = s1.y;
            if (y1 > pymax) y1 = pymax;
            x1 = e.c - e.b * y1;
            y2 = pymax;
            if (s2 != null && s2.y < pymax) y2 = s2.y;
            if (y2 < pymin) y2 = pymin;
            x2 = e.c - e.b * y2;
            if (((x1 > pxmax) & (x2 > pxmax)) | ((x1 < pxmin) & (x2 < pxmin))) return;
            if (x1 > pxmax) { x1 = pxmax; y1 = (e.c - x1) / e.b; }
            if (x1 < pxmin) { x1 = pxmin; y1 = (e.c - x1) / e.b; }
            if (x2 > pxmax) { x2 = pxmax; y2 = (e.c - x2) / e.b; }
            if (x2 < pxmin) { x2 = pxmin; y2 = (e.c - x2) / e.b; }
        } else {
            x1 = pxmin;
            if (s1 != null && s1.x > pxmin) x1 = s1.x;
            if (x1 > pxmax) x1 = pxmax;
            y1 = e.c - e.a * x1;
            x2 = pxmax;
            if (s2 != null && s2.x < pxmax) x2 = s2.x;
            if (x2 < pxmin) x2 = pxmin;
            y2 = e.c - e.a * x2;
            if (((y1 > pymax) & (y2 > pymax)) | ((y1 < pymin) & (y2 < pymin))) return;
            if (y1 > pymax) { y1 = pymax; x1 = (e.c - y1) / e.a; }
            if (y1 < pymin) { y1 = pymin; x1 = (e.c - y1) / e.a; }
            if (y2 > pymax) { y2 = pymax; x2 = (e.c - y2) / e.a; }
            if (y2 < pymin) { y2 = pymin; x2 = (e.c - y2) / e.a; }
        }
        pushGraphEdge(x1, y1, x2, y2, e.reg[0], e.reg[1]);
    }

    private void voronoi() {
        Site newsite, bot, top, temp, p, v;
        double[] newintstar = null;
        int pm;
        Halfedge lbnd, rbnd, llbnd, rrbnd, bisector;
        Edge e;
        pqInitialize();
        bottomsite = nextone();
        elInitialize();
        newsite = nextone();
        while (true) {
            if (!pqEmpty()) {
                newintstar = pqMinPoint();
            }
            if (newsite != null && (pqEmpty() || newsite.y < newintstar[1]
                    || (newsite.y == newintstar[1] && newsite.x < newintstar[0]))) {
                lbnd = elLeftBnd(newsite.x, newsite.y);
                rbnd = lbnd.elRight;
                bot = rightreg(lbnd);
                e = bisect(bot, newsite);
                bisector = heCreate(e, LE);
                elInsert(lbnd, bisector);
                if ((p = intersect(lbnd, bisector)) != null) {
                    pqDelete(lbnd);
                    pqInsert(lbnd, p, dist(p, newsite));
                }
                lbnd = bisector;
                bisector = heCreate(e, RE);
                elInsert(lbnd, bisector);
                if ((p = intersect(bisector, rbnd)) != null) {
                    pqInsert(bisector, p, dist(p, newsite));
                }
                newsite = nextone();
            } else if (!pqEmpty()) {
                lbnd = pqExtractMin();
                llbnd = lbnd.elLeft;
                rbnd = lbnd.elRight;
                rrbnd = rbnd.elRight;
                bot = leftreg(lbnd);
                top = rightreg(rbnd);
                v = lbnd.vertex;
                makevertex(v);
                endpoint(lbnd.elEdge, lbnd.elPm, v);
                endpoint(rbnd.elEdge, rbnd.elPm, v);
                elDelete(lbnd);
                pqDelete(rbnd);
                elDelete(rbnd);
                pm = LE;
                if (bot.y > top.y) {
                    temp = bot;
                    bot = top;
                    top = temp;
                    pm = RE;
                }
                e = bisect(bot, top);
                bisector = heCreate(e, pm);
                elInsert(llbnd, bisector);
                endpoint(e, RE - pm, v);
                v.refcnt--;
                if ((p = intersect(llbnd, bisector)) != null) {
                    pqDelete(llbnd);
                    pqInsert(llbnd, p, dist(p, bot));
                }
                if ((p = intersect(bisector, rrbnd)) != null) {
                    pqInsert(bisector, p, dist(p, bot));
                }
            } else {
                break;
            }
        }
        for (lbnd = elLeftEnd.elRight; lbnd != elRightEnd; lbnd = lbnd.elRight) {
            clipLine(lbnd.elEdge);
        }
    }

    private Site nextone() {
        if (siteidx < nsites) {
            return sites[siteidx++];
        }
        return null;
    }
}
