package com.sphere.core.fastjet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * T. Chan's dynamic closest pair in two dimensions, as fastjet::ClosestPair2D:
 * the points are kept along three shifted Z-order curves, each in a search
 * tree whose nodes are also linked in a ring, and every point looks for its
 * neighbour among the thirty that follow it on each curve.
 *
 * The coordinates and the distances are pairs, so under double-double
 * arithmetic the neighbours are those of the 106-bit points; the curves
 * themselves only need the doubles.
 */
final class ClosestPair2D {

    private static final long TWOPOW31 = 2147483648L;
    private static final int NSHIFT = 3;
    private static final int REMOVE_HEAP_ENTRY = 1;
    private static final int REVIEW_HEAP_ENTRY = 2;
    private static final int REVIEW_NEIGHBOUR = 4;

    /** A point's position on one curve. */
    static final class Shuffle {
        long x, y;
        int point;

        boolean lessThan(Shuffle q) {
            if (floorLn2Less(x ^ q.x, y ^ q.y)) {
                return y < q.y;
            }
            return x < q.x;
        }

        Shuffle copy() {
            final Shuffle s = new Shuffle();
            s.x = x;
            s.y = y;
            s.point = point;
            return s;
        }
    }

    static boolean floorLn2Less(long x, long y) {
        if (x > y) return false;
        return x < (x ^ y);
    }

    /** fastjet::SearchTree, for shuffles: a binary tree whose nodes also form a ring. */
    static final class SearchTree {
        final class Node {
            Shuffle value;
            Node left, right, parent, successor, predecessor;

            boolean treelinksNull() {
                return parent == null && left == null && right == null;
            }

            void nullifyTreelinks() {
                parent = null;
                left = null;
                right = null;
            }

            void resetParentsLinkToMe(Node xx) {
                if (parent == null) return;
                if (parent.right == this) parent.right = xx;
                else parent.left = xx;
            }
        }

        private final Node[] nodes;
        private final ArrayList<Node> availableNodes;
        private Node topNode;
        private int nRemoves;

        SearchTree(List<Shuffle> init, int maxSize) {
            nodes = new Node[maxSize];
            for (int i = 0; i < maxSize; i++) nodes[i] = new Node();
            availableNodes = new ArrayList<>(maxSize);
            for (int i = init.size(); i < maxSize; i++) {
                availableNodes.add(nodes[i]);
            }
            final int n = init.size();
            nRemoves = 0;
            for (int i = 0; i < n; i++) {
                nodes[i].value = init.get(i);
                nodes[i].predecessor = i > 0 ? nodes[i - 1] : null;
                nodes[i].successor = i + 1 < maxSize ? nodes[i + 1] : null;
                nodes[i].nullifyTreelinks();
            }
            nodes[0].predecessor = nodes[n - 1];
            nodes[n - 1].successor = nodes[0];
            final int scale = (n + 1) / 2;
            final int top = Math.min(n - 1, scale);
            nodes[top].parent = null;
            topNode = nodes[top];
            doInitialConnections(top, scale, 0, n);
        }

        private void doInitialConnections(int thisOne, int scale, int leftEdge, int rightEdge) {
            final int refNewScale = (scale + 1) / 2;
            int newScale = refNewScale;
            boolean didChild = false;
            while (true) {
                final int left = thisOne - newScale;
                if (left >= leftEdge && nodes[left].treelinksNull()) {
                    nodes[left].parent = nodes[thisOne];
                    nodes[thisOne].left = nodes[left];
                    doInitialConnections(left, newScale, leftEdge, thisOne);
                    didChild = true;
                    break;
                }
                final int old = newScale;
                newScale = (old + 1) / 2;
                if (newScale == old) break;
            }
            if (!didChild) nodes[thisOne].left = null;
            newScale = refNewScale;
            didChild = false;
            while (true) {
                final int right = thisOne + newScale;
                if (right < rightEdge && nodes[right].treelinksNull()) {
                    nodes[right].parent = nodes[thisOne];
                    nodes[thisOne].right = nodes[right];
                    doInitialConnections(right, newScale, thisOne + 1, rightEdge);
                    didChild = true;
                    break;
                }
                final int old = newScale;
                newScale = (old + 1) / 2;
                if (newScale == old) break;
            }
            if (!didChild) nodes[thisOne].right = null;
        }

        int size() {
            return nodes.length - availableNodes.size();
        }

        Node somewhere() {
            return topNode;
        }

        void remove(Node node) {
            node.predecessor.successor = node.successor;
            node.successor.predecessor = node.predecessor;
            if (node.left == null && node.right == null) {
                node.resetParentsLinkToMe(null);
            } else if (node.left != null && node.right == null) {
                node.resetParentsLinkToMe(node.left);
                node.left.parent = node.parent;
                if (topNode == node) topNode = node.left;
            } else if (node.left == null) {
                node.resetParentsLinkToMe(node.right);
                node.right.parent = node.parent;
                if (topNode == node) topNode = node.right;
            } else {
                Node replacement;
                final boolean usePredecessor = (nRemoves % 2 == 1);
                if (usePredecessor) {
                    replacement = node.predecessor;
                    if (replacement != node.left) {
                        if (replacement.left != null) {
                            replacement.left.parent = replacement.parent;
                        }
                        replacement.resetParentsLinkToMe(replacement.left);
                        replacement.left = node.left;
                    }
                    replacement.parent = node.parent;
                    replacement.right = node.right;
                } else {
                    replacement = node.successor;
                    if (replacement != node.right) {
                        if (replacement.right != null) {
                            replacement.right.parent = replacement.parent;
                        }
                        replacement.resetParentsLinkToMe(replacement.right);
                        replacement.right = node.right;
                    }
                    replacement.parent = node.parent;
                    replacement.left = node.left;
                }
                node.resetParentsLinkToMe(replacement);
                if (node.left != replacement) node.left.parent = replacement;
                if (node.right != replacement) node.right.parent = replacement;
                if (topNode == node) topNode = replacement;
            }
            node.nullifyTreelinks();
            node.predecessor = null;
            node.successor = null;
            nRemoves++;
            availableNodes.add(node);
        }

        Node insert(Shuffle value) {
            final Node node = availableNodes.remove(availableNodes.size() - 1);
            node.value = value;
            Node location = topNode;
            Node oldLocation = null;
            boolean onLeft = true;
            while (location != null) {
                oldLocation = location;
                onLeft = value.lessThan(location.value);
                location = onLeft ? location.left : location.right;
            }
            node.parent = oldLocation;
            if (onLeft) node.parent.left = node;
            else node.parent.right = node;
            node.left = null;
            node.right = null;
            node.predecessor = findPredecessor(node);
            if (node.predecessor != null) {
                node.successor = node.predecessor.successor;
                node.predecessor.successor = node;
                node.successor.predecessor = node;
            } else {
                node.successor = findSuccessor(node);
                node.predecessor = node.successor.predecessor;
                node.successor.predecessor = node;
                node.predecessor.successor = node;
            }
            return node;
        }

        private Node findPredecessor(Node node) {
            if (node.left != null) {
                Node n = node.left;
                while (n.right != null) n = n.right;
                return n;
            }
            Node last = node;
            Node n = node.parent;
            while (n != null) {
                if (n.right == last) return n;
                last = n;
                n = n.parent;
            }
            return null;
        }

        private Node findSuccessor(Node node) {
            if (node.right != null) {
                Node n = node.right;
                while (n.left != null) n = n.left;
                return n;
            }
            Node last = node;
            Node n = node.parent;
            while (n != null) {
                if (n.left == last) return n;
                last = n;
                n = n.parent;
            }
            return null;
        }
    }

    // points
    private final double[] xH, xL, yH, yL;
    private final int[] neighbour;
    private final double[] nnH, nnL;
    private final int[] reviewFlag;
    private final SearchTree.Node[][] circ;

    private final SearchTree[] trees = new SearchTree[NSHIFT];
    private final BriefJetEngine.MinHeap heap;
    private final ArrayDeque<Integer> availablePoints = new ArrayDeque<>();
    private final ArrayList<Integer> pointsUnderReview = new ArrayList<>();
    private final double leftX, leftY, range;
    private final long[] shifts = new long[NSHIFT];
    private final long[] relShifts = new long[NSHIFT];
    private final int cpSearchRange = 30;
    private final boolean dd;

    /** Result registers of closestPair(). */
    int id1, id2;
    double dist2H, dist2L;

    ClosestPair2D(double[] px, double[] pxl, double[] py, double[] pyl, int nPositions,
                  double leftCornerX, double leftCornerY, double rightCornerX, double rightCornerY,
                  boolean dd) {
        this.dd = dd;
        final int maxSize = nPositions;
        xH = new double[maxSize];
        xL = new double[maxSize];
        yH = new double[maxSize];
        yL = new double[maxSize];
        neighbour = new int[maxSize];
        nnH = new double[maxSize];
        nnL = new double[maxSize];
        reviewFlag = new int[maxSize];
        circ = new SearchTree.Node[maxSize][NSHIFT];
        leftX = leftCornerX;
        leftY = leftCornerY;
        range = Math.max(rightCornerX - leftCornerX, rightCornerY - leftCornerY);

        final List<Shuffle> shuffles = new ArrayList<>(nPositions);
        for (int i = 0; i < nPositions; i++) {
            xH[i] = px[i];
            xL[i] = pxl[i];
            yH[i] = py[i];
            yL[i] = pyl[i];
            nnH[i] = Double.MAX_VALUE;
            nnL[i] = 0.0;
            reviewFlag[i] = 0;
            shuffles.add(point2shuffle(i, 0));
        }
        for (int ishift = 0; ishift < NSHIFT; ishift++) {
            shifts[ishift] = (long) (((TWOPOW31 * 1.0) * ishift) / NSHIFT);
            relShifts[ishift] = ishift == 0 ? 0 : shifts[ishift] - shifts[ishift - 1];
        }
        for (int ishift = 0; ishift < NSHIFT; ishift++) {
            if (ishift > 0) {
                final long rel = relShifts[ishift];
                for (Shuffle s : shuffles) {
                    s.x += rel;
                    s.y += rel;
                }
            }
            shuffles.sort((a, b) -> a.lessThan(b) ? -1 : (b.lessThan(a) ? 1 : 0));
            final List<Shuffle> copies = new ArrayList<>(shuffles.size());
            for (Shuffle s : shuffles) copies.add(s.copy());
            trees[ishift] = new SearchTree(copies, maxSize);
            SearchTree.Node c = trees[ishift].somewhere();
            final SearchTree.Node start = c;
            final int cpRange = Math.min(cpSearchRange, nPositions - 1);
            do {
                final int thisPoint = c.value.point;
                circ[thisPoint][ishift] = c;
                SearchTree.Node other = c;
                for (int i = 0; i < cpRange; i++) {
                    other = other.successor;
                    distance2(thisPoint, other.value.point);
                    if (lt(rH, rL, nnH[thisPoint], nnL[thisPoint])) {
                        nnH[thisPoint] = rH;
                        nnL[thisPoint] = rL;
                        neighbour[thisPoint] = other.value.point;
                    }
                }
                c = c.successor;
            } while (c != start);
        }
        final double[] mH = Arrays.copyOf(nnH, maxSize);
        final double[] mL = Arrays.copyOf(nnL, maxSize);
        heap = new BriefJetEngine.MinHeap(mH, mL, maxSize);
    }

    private double rH, rL;

    private void distance2(int a, int b) {
        if (!dd) {
            final double dx = xH[a] - xH[b];
            final double dy = yH[a] - yH[b];
            rH = dx * dx + dy * dy;
            rL = 0.0;
            return;
        }
        double s = xH[a] - xH[b];
        double e = DD.twoSumErr(xH[a], -xH[b], s) + (xL[a] - xL[b]);
        final double dxh = s + e;
        final double dxl = e - (dxh - s);
        s = yH[a] - yH[b];
        e = DD.twoSumErr(yH[a], -yH[b], s) + (yL[a] - yL[b]);
        final double dyh = s + e;
        final double dyl = e - (dyh - s);
        final double p1 = dxh * dxh;
        final double p1e = DD.twoProdErr(dxh, dxh, p1) + 2.0 * dxh * dxl;
        final double p2 = dyh * dyh;
        final double p2e = DD.twoProdErr(dyh, dyh, p2) + 2.0 * dyh * dyl;
        s = p1 + p2;
        e = DD.twoSumErr(p1, p2, s) + p1e + p2e;
        rH = s + e;
        rL = e - (rH - s);
    }

    private static boolean lt(double ah, double al, double bh, double bl) {
        return ah < bh || (ah == bh && al < bl);
    }

    private Shuffle point2shuffle(int point, long shift) {
        final double rx = (xH[point] - leftX) / range;
        final double ry = (yH[point] - leftY) / range;
        final Shuffle s = new Shuffle();
        s.x = (long) (TWOPOW31 * rx) + shift;
        s.y = (long) (TWOPOW31 * ry) + shift;
        s.point = point;
        return s;
    }

    int size() {
        return xH.length - availablePoints.size();
    }

    /** The closest pair, into id1 < id2 and (dist2H, dist2L). */
    void closestPair() {
        int a = heap.minloc();
        int b = neighbour[a];
        dist2H = nnH[a];
        dist2L = nnL[a];
        if (a > b) {
            final int t = a;
            a = b;
            b = t;
        }
        id1 = a;
        id2 = b;
    }

    private void addLabel(int point, int flag) {
        if (reviewFlag[point] == 0) pointsUnderReview.add(point);
        reviewFlag[point] |= flag;
    }

    private void setLabel(int point, int flag) {
        if (reviewFlag[point] == 0) pointsUnderReview.add(point);
        reviewFlag[point] = flag;
    }

    void remove(int id) {
        removeFromSearchTree(id);
        dealWithPointsToReview();
    }

    private void removeFromSearchTree(int pointToRemove) {
        availablePoints.push(pointToRemove);
        setLabel(pointToRemove, REMOVE_HEAP_ENTRY);
        final int cpRange = Math.min(cpSearchRange, size() - 1);
        for (int ishift = 0; ishift < NSHIFT; ishift++) {
            final SearchTree.Node removedCirc = circ[pointToRemove][ishift];
            SearchTree.Node rightEnd = removedCirc.successor;
            trees[ishift].remove(removedCirc);
            SearchTree.Node leftEnd = rightEnd;
            final SearchTree.Node origRightEnd = rightEnd;
            for (int i = 0; i < cpRange; i++) {
                leftEnd = leftEnd.predecessor;
            }
            if (size() - 1 < cpSearchRange) {
                leftEnd = leftEnd.predecessor;
                rightEnd = rightEnd.predecessor;
            }
            do {
                final int leftPoint = leftEnd.value.point;
                if (neighbour[leftPoint] == pointToRemove) {
                    addLabel(leftPoint, REVIEW_NEIGHBOUR);
                } else {
                    distance2(leftPoint, rightEnd.value.point);
                    if (lt(rH, rL, nnH[leftPoint], nnL[leftPoint])) {
                        neighbour[leftPoint] = rightEnd.value.point;
                        nnH[leftPoint] = rH;
                        nnL[leftPoint] = rL;
                        addLabel(leftPoint, REVIEW_HEAP_ENTRY);
                    }
                }
                rightEnd = rightEnd.successor;
                leftEnd = leftEnd.successor;
            } while (leftEnd != origRightEnd);
        }
    }

    private void dealWithPointsToReview() {
        final int cpRange = Math.min(cpSearchRange, size() - 1);
        while (!pointsUnderReview.isEmpty()) {
            final int thisPoint = pointsUnderReview.remove(pointsUnderReview.size() - 1);
            if ((reviewFlag[thisPoint] & REMOVE_HEAP_ENTRY) != 0) {
                heap.remove(thisPoint);
            } else {
                if ((reviewFlag[thisPoint] & REVIEW_NEIGHBOUR) != 0) {
                    nnH[thisPoint] = Double.MAX_VALUE;
                    nnL[thisPoint] = 0.0;
                    for (int ishift = 0; ishift < NSHIFT; ishift++) {
                        SearchTree.Node other = circ[thisPoint][ishift];
                        for (int i = 0; i < cpRange; i++) {
                            other = other.successor;
                            distance2(thisPoint, other.value.point);
                            if (lt(rH, rL, nnH[thisPoint], nnL[thisPoint])) {
                                nnH[thisPoint] = rH;
                                nnL[thisPoint] = rL;
                                neighbour[thisPoint] = other.value.point;
                            }
                        }
                    }
                }
                heap.update(thisPoint, nnH[thisPoint], nnL[thisPoint]);
            }
            reviewFlag[thisPoint] = 0;
        }
    }

    /** Removes two points and inserts one; returns its id. */
    int replace(int idA, int idB, double x, double xl, double y, double yl) {
        removeFromSearchTree(idA);
        removeFromSearchTree(idB);
        final int newPoint = availablePoints.pop();
        xH[newPoint] = x;
        xL[newPoint] = xl;
        yH[newPoint] = y;
        yL[newPoint] = yl;
        insertIntoSearchTree(newPoint);
        dealWithPointsToReview();
        return newPoint;
    }

    /** Removes some points and inserts others; returns the new ids. */
    int[] replaceMany(int[] idsToRemove, int nRemove, double[][] newPositions, int nNew) {
        for (int i = 0; i < nRemove; i++) {
            removeFromSearchTree(idsToRemove[i]);
        }
        final int[] newIds = new int[nNew];
        for (int i = 0; i < nNew; i++) {
            final int newPoint = availablePoints.pop();
            xH[newPoint] = newPositions[i][0];
            xL[newPoint] = newPositions[i][1];
            yH[newPoint] = newPositions[i][2];
            yL[newPoint] = newPositions[i][3];
            insertIntoSearchTree(newPoint);
            newIds[i] = newPoint;
        }
        dealWithPointsToReview();
        return newIds;
    }

    private void insertIntoSearchTree(int newPoint) {
        setLabel(newPoint, REVIEW_HEAP_ENTRY);
        nnH[newPoint] = Double.MAX_VALUE;
        nnL[newPoint] = 0.0;
        final int cpRange = Math.min(cpSearchRange, size() - 1);
        for (int ishift = 0; ishift < NSHIFT; ishift++) {
            final Shuffle newShuffle = point2shuffle(newPoint, shifts[ishift]);
            final SearchTree.Node newCirc = trees[ishift].insert(newShuffle);
            circ[newPoint][ishift] = newCirc;
            SearchTree.Node rightEdge = newCirc.successor;
            SearchTree.Node leftEdge = newCirc;
            for (int i = 0; i < cpRange; i++) {
                leftEdge = leftEdge.predecessor;
            }
            do {
                final int leftPoint = leftEdge.value.point;
                final int rightPoint = rightEdge.value.point;
                distance2(leftPoint, newPoint);
                if (lt(rH, rL, nnH[leftPoint], nnL[leftPoint])) {
                    nnH[leftPoint] = rH;
                    nnL[leftPoint] = rL;
                    neighbour[leftPoint] = newPoint;
                    addLabel(leftPoint, REVIEW_HEAP_ENTRY);
                }
                distance2(newPoint, rightPoint);
                if (lt(rH, rL, nnH[newPoint], nnL[newPoint])) {
                    nnH[newPoint] = rH;
                    nnL[newPoint] = rL;
                    neighbour[newPoint] = rightPoint;
                }
                if (neighbour[leftPoint] == rightPoint) {
                    addLabel(leftPoint, REVIEW_NEIGHBOUR);
                }
                rightEdge = rightEdge.successor;
                leftEdge = leftEdge.successor;
            } while (leftEdge != newCirc);
        }
    }
}
