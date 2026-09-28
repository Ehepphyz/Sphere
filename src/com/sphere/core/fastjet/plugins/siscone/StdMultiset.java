package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.plugins.StdAlgorithms;

/**
 * std::multiset as libstdc++ builds it: a red-black tree with the header
 * node, insert_equal (new elements after their equals) and the
 * insert-and-rebalance / rebalance-for-erase of its tree.cc.
 *
 * SISCone's split-merge orders its candidates with a comparison that is not
 * a strict weak ordering (two nearly equal scales are compared through the
 * jets' difference), so the order of its elements depends on the shape of
 * the tree; replaying the library's tree keeps it the C++ one.
 *
 * @param <T> the elements
 */
public final class StdMultiset<T> {

    /** A node; the header is one too, with no value. */
    public static final class Node<T> {
        T value;
        Node<T> parent;
        Node<T> left;
        Node<T> right;
        boolean red;

        public T value() {
            return value;
        }
    }

    private final StdAlgorithms.Less<? super T> comp;
    private final Node<T> header = new Node<>();
    private int size;

    public StdMultiset(StdAlgorithms.Less<? super T> comp) {
        this.comp = comp;
        header.red = true;
        header.left = header;
        header.right = header;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** begin(): the leftmost node, or end() when empty. */
    public Node<T> begin() {
        return header.left;
    }

    /** end(): the header. */
    public Node<T> end() {
        return header;
    }

    /** operator++ on an iterator. */
    public Node<T> next(Node<T> x) {
        if (x.right != null) {
            x = x.right;
            while (x.left != null) x = x.left;
            return x;
        }
        Node<T> y = x.parent;
        while (x == y.right) {
            x = y;
            y = y.parent;
        }
        if (x.right != y) x = y;
        return x;
    }

    /** insert(value): _M_insert_equal. */
    public Node<T> insert(T v) {
        Node<T> x = header.parent;
        Node<T> y = header;
        while (x != null) {
            y = x;
            x = comp.test(v, x.value) ? x.left : x.right;
        }
        final boolean insertLeft = (y == header) || comp.test(v, y.value);
        final Node<T> z = new Node<>();
        z.value = v;
        insertAndRebalance(insertLeft, z, y);
        size++;
        return z;
    }

    /** erase(iterator). */
    public void erase(Node<T> z) {
        rebalanceForErase(z);
        size--;
    }

    /* ------------------------------------------------------------------ */
    /* tree.cc                                                             */
    /* ------------------------------------------------------------------ */

    private void rotateLeft(Node<T> x) {
        final Node<T> y = x.right;
        x.right = y.left;
        if (y.left != null) y.left.parent = x;
        y.parent = x.parent;
        if (x == header.parent) header.parent = y;
        else if (x == x.parent.left) x.parent.left = y;
        else x.parent.right = y;
        y.left = x;
        x.parent = y;
    }

    private void rotateRight(Node<T> x) {
        final Node<T> y = x.left;
        x.left = y.right;
        if (y.right != null) y.right.parent = x;
        y.parent = x.parent;
        if (x == header.parent) header.parent = y;
        else if (x == x.parent.right) x.parent.right = y;
        else x.parent.left = y;
        y.right = x;
        x.parent = y;
    }

    private void insertAndRebalance(boolean insertLeft, Node<T> x, Node<T> p) {
        x.parent = p;
        x.left = null;
        x.right = null;
        x.red = true;
        if (insertLeft) {
            p.left = x;
            if (p == header) {
                header.parent = x;
                header.right = x;
            } else if (p == header.left) {
                header.left = x;
            }
        } else {
            p.right = x;
            if (p == header.right) header.right = x;
        }
        while (x != header.parent && x.parent.red) {
            final Node<T> xpp = x.parent.parent;
            if (x.parent == xpp.left) {
                final Node<T> y = xpp.right;
                if (y != null && y.red) {
                    x.parent.red = false;
                    y.red = false;
                    xpp.red = true;
                    x = xpp;
                } else {
                    if (x == x.parent.right) {
                        x = x.parent;
                        rotateLeft(x);
                    }
                    x.parent.red = false;
                    xpp.red = true;
                    rotateRight(xpp);
                }
            } else {
                final Node<T> y = xpp.left;
                if (y != null && y.red) {
                    x.parent.red = false;
                    y.red = false;
                    xpp.red = true;
                    x = xpp;
                } else {
                    if (x == x.parent.left) {
                        x = x.parent;
                        rotateRight(x);
                    }
                    x.parent.red = false;
                    xpp.red = true;
                    rotateLeft(xpp);
                }
            }
        }
        header.parent.red = false;
    }

    private static <T> Node<T> minimum(Node<T> x) {
        while (x.left != null) x = x.left;
        return x;
    }

    private static <T> Node<T> maximum(Node<T> x) {
        while (x.right != null) x = x.right;
        return x;
    }

    private void rebalanceForErase(Node<T> z) {
        Node<T> y = z;
        Node<T> x;
        Node<T> xParent;
        if (y.left == null) {
            x = y.right;
        } else if (y.right == null) {
            x = y.left;
        } else {
            y = y.right;
            while (y.left != null) y = y.left;
            x = y.right;
        }
        if (y != z) {
            // relink y in place of z; y is z's successor
            z.left.parent = y;
            y.left = z.left;
            if (y != z.right) {
                xParent = y.parent;
                if (x != null) x.parent = y.parent;
                y.parent.left = x;
                y.right = z.right;
                z.right.parent = y;
            } else {
                xParent = y;
            }
            if (header.parent == z) header.parent = y;
            else if (z.parent.left == z) z.parent.left = y;
            else z.parent.right = y;
            y.parent = z.parent;
            final boolean c = y.red;
            y.red = z.red;
            z.red = c;
            y = z;
        } else {
            xParent = y.parent;
            if (x != null) x.parent = y.parent;
            if (header.parent == z) header.parent = x;
            else if (z.parent.left == z) z.parent.left = x;
            else z.parent.right = x;
            if (header.left == z) {
                if (z.right == null) header.left = z.parent;
                else header.left = minimum(x);
            }
            if (header.right == z) {
                if (z.left == null) header.right = z.parent;
                else header.right = maximum(x);
            }
        }
        if (!y.red) {
            while (x != header.parent && (x == null || !x.red)) {
                if (x == xParent.left) {
                    Node<T> w = xParent.right;
                    if (w.red) {
                        w.red = false;
                        xParent.red = true;
                        rotateLeft(xParent);
                        w = xParent.right;
                    }
                    if ((w.left == null || !w.left.red) && (w.right == null || !w.right.red)) {
                        w.red = true;
                        x = xParent;
                        xParent = xParent.parent;
                    } else {
                        if (w.right == null || !w.right.red) {
                            w.left.red = false;
                            w.red = true;
                            rotateRight(w);
                            w = xParent.right;
                        }
                        w.red = xParent.red;
                        xParent.red = false;
                        if (w.right != null) w.right.red = false;
                        rotateLeft(xParent);
                        break;
                    }
                } else {
                    Node<T> w = xParent.left;
                    if (w.red) {
                        w.red = false;
                        xParent.red = true;
                        rotateRight(xParent);
                        w = xParent.left;
                    }
                    if ((w.right == null || !w.right.red) && (w.left == null || !w.left.red)) {
                        w.red = true;
                        x = xParent;
                        xParent = xParent.parent;
                    } else {
                        if (w.left == null || !w.left.red) {
                            w.right.red = false;
                            w.red = true;
                            rotateLeft(w);
                            w = xParent.left;
                        }
                        w.red = xParent.red;
                        xParent.red = false;
                        if (w.left != null) w.left.red = false;
                        rotateRight(xParent);
                        break;
                    }
                }
            }
            if (x != null) x.red = false;
        }
    }
}
