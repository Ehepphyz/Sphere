package com.sphere.core.fjcontrib.internal;

import java.util.Comparator;
import java.util.List;

/**
 * std::sort as libstdc++ implements it: introsort (median-of-three
 * quicksort, heapsort past 2 log2 n levels) followed by an insertion sort of
 * blocks of 16. It is not stable, and the order it leaves equal elements in
 * is a property of these exact steps; the contribs that sort by one key
 * (distances, rapidities, kt) and then walk the result depend on it when
 * keys tie, so it is reproduced here swap for swap rather than replaced by
 * Java's stable sort.
 */
public final class StdSort {

    private static final int THRESHOLD = 16;

    private StdSort() {
    }

    private static int lg(int n) {
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    /* ================================================================== */
    /* Objects with a comparator ("less" is compare &lt; 0)                */
    /* ================================================================== */

    public static <T> void sort(List<T> list, Comparator<? super T> c) {
        @SuppressWarnings("unchecked")
        final T[] a = (T[]) list.toArray();
        sort(a, 0, a.length, c);
        for (int i = 0; i < a.length; i++) list.set(i, a[i]);
    }

    public static <T> void sort(T[] a, int first, int last, Comparator<? super T> c) {
        if (first == last) return;
        introsortLoop(a, first, last, 2 * lg(last - first), c);
        finalInsertionSort(a, first, last, c);
    }

    private static <T> boolean less(T x, T y, Comparator<? super T> c) {
        return c.compare(x, y) < 0;
    }

    private static <T> void swap(T[] a, int i, int j) {
        final T t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static <T> void introsortLoop(T[] a, int first, int last, int depthLimit, Comparator<? super T> c) {
        while (last - first > THRESHOLD) {
            if (depthLimit == 0) {
                heapSort(a, first, last, c);
                return;
            }
            --depthLimit;
            final int cut = unguardedPartitionPivot(a, first, last, c);
            introsortLoop(a, cut, last, depthLimit, c);
            last = cut;
        }
    }

    private static <T> int unguardedPartitionPivot(T[] a, int first, int last, Comparator<? super T> c) {
        final int mid = first + (last - first) / 2;
        moveMedianToFirst(a, first, first + 1, mid, last - 1, c);
        return unguardedPartition(a, first + 1, last, first, c);
    }

    private static <T> void moveMedianToFirst(T[] a, int result, int x, int y, int z, Comparator<? super T> c) {
        if (less(a[x], a[y], c)) {
            if (less(a[y], a[z], c)) swap(a, result, y);
            else if (less(a[x], a[z], c)) swap(a, result, z);
            else swap(a, result, x);
        } else if (less(a[x], a[z], c)) {
            swap(a, result, x);
        } else if (less(a[y], a[z], c)) {
            swap(a, result, z);
        } else {
            swap(a, result, y);
        }
    }

    private static <T> int unguardedPartition(T[] a, int first, int last, int pivot, Comparator<? super T> c) {
        while (true) {
            while (less(a[first], a[pivot], c)) ++first;
            --last;
            while (less(a[pivot], a[last], c)) --last;
            if (!(first < last)) return first;
            swap(a, first, last);
            ++first;
        }
    }

    private static <T> void finalInsertionSort(T[] a, int first, int last, Comparator<? super T> c) {
        if (last - first > THRESHOLD) {
            insertionSort(a, first, first + THRESHOLD, c);
            for (int i = first + THRESHOLD; i != last; ++i) unguardedLinearInsert(a, i, c);
        } else {
            insertionSort(a, first, last, c);
        }
    }

    private static <T> void insertionSort(T[] a, int first, int last, Comparator<? super T> c) {
        if (first == last) return;
        for (int i = first + 1; i != last; ++i) {
            if (less(a[i], a[first], c)) {
                final T val = a[i];
                System.arraycopy(a, first, a, first + 1, i - first);
                a[first] = val;
            } else {
                unguardedLinearInsert(a, i, c);
            }
        }
    }

    private static <T> void unguardedLinearInsert(T[] a, int last, Comparator<? super T> c) {
        final T val = a[last];
        int next = last - 1;
        while (less(val, a[next], c)) {
            a[last] = a[next];
            last = next;
            --next;
        }
        a[last] = val;
    }

    /** std::__partial_sort(first, last, last): make_heap then sort_heap. */
    private static <T> void heapSort(T[] a, int first, int last, Comparator<? super T> c) {
        final int len = last - first;
        if (len >= 2) {
            int parent = (len - 2) / 2;
            while (true) {
                final T value = a[first + parent];
                adjustHeap(a, first, parent, len, value, c);
                if (parent == 0) break;
                parent--;
            }
        }
        while (last - first > 1) {
            --last;
            final T value = a[last];
            a[last] = a[first];
            adjustHeap(a, first, 0, last - first, value, c);
        }
    }

    private static <T> void adjustHeap(T[] a, int first, int holeIndex, int len, T value, Comparator<? super T> c) {
        final int topIndex = holeIndex;
        int secondChild = holeIndex;
        while (secondChild < (len - 1) / 2) {
            secondChild = 2 * (secondChild + 1);
            if (less(a[first + secondChild], a[first + secondChild - 1], c)) secondChild--;
            a[first + holeIndex] = a[first + secondChild];
            holeIndex = secondChild;
        }
        if ((len & 1) == 0 && secondChild == (len - 2) / 2) {
            secondChild = 2 * (secondChild + 1);
            a[first + holeIndex] = a[first + secondChild - 1];
            holeIndex = secondChild - 1;
        }
        int parent = (holeIndex - 1) / 2;
        while (holeIndex > topIndex && less(a[first + parent], value, c)) {
            a[first + holeIndex] = a[first + parent];
            holeIndex = parent;
            parent = (holeIndex - 1) / 2;
        }
        a[first + holeIndex] = value;
    }

    /* ================================================================== */
    /* A double key with a long payload, compared on the key only          */
    /* ================================================================== */

    /**
     * Sorts n (key, payload) pairs by increasing key, as std::sort on
     * std::pair with a comparator reading only .first: the order of equal
     * keys is the one libstdc++ leaves.
     */
    public static void sortByKey(double[] key, long[] payload, int n) {
        if (n == 0) return;
        kIntrosort(key, payload, 0, n, 2 * lg(n));
        kFinalInsertion(key, payload, 0, n);
    }

    private static void kSwap(double[] k, long[] p, int i, int j) {
        final double tk = k[i];
        k[i] = k[j];
        k[j] = tk;
        final long tp = p[i];
        p[i] = p[j];
        p[j] = tp;
    }

    private static void kIntrosort(double[] k, long[] p, int first, int last, int depthLimit) {
        while (last - first > THRESHOLD) {
            if (depthLimit == 0) {
                kHeapSort(k, p, first, last);
                return;
            }
            --depthLimit;
            final int mid = first + (last - first) / 2;
            kMedianToFirst(k, p, first, first + 1, mid, last - 1);
            final int cut = kPartition(k, p, first + 1, last, first);
            kIntrosort(k, p, cut, last, depthLimit);
            last = cut;
        }
    }

    private static void kMedianToFirst(double[] k, long[] p, int result, int x, int y, int z) {
        if (k[x] < k[y]) {
            if (k[y] < k[z]) kSwap(k, p, result, y);
            else if (k[x] < k[z]) kSwap(k, p, result, z);
            else kSwap(k, p, result, x);
        } else if (k[x] < k[z]) {
            kSwap(k, p, result, x);
        } else if (k[y] < k[z]) {
            kSwap(k, p, result, z);
        } else {
            kSwap(k, p, result, y);
        }
    }

    private static int kPartition(double[] k, long[] p, int first, int last, int pivot) {
        while (true) {
            while (k[first] < k[pivot]) ++first;
            --last;
            while (k[pivot] < k[last]) --last;
            if (!(first < last)) return first;
            kSwap(k, p, first, last);
            ++first;
        }
    }

    private static void kFinalInsertion(double[] k, long[] p, int first, int last) {
        if (last - first > THRESHOLD) {
            kInsertion(k, p, first, first + THRESHOLD);
            for (int i = first + THRESHOLD; i != last; ++i) kLinearInsert(k, p, i);
        } else {
            kInsertion(k, p, first, last);
        }
    }

    private static void kInsertion(double[] k, long[] p, int first, int last) {
        if (first == last) return;
        for (int i = first + 1; i != last; ++i) {
            if (k[i] < k[first]) {
                final double vk = k[i];
                final long vp = p[i];
                System.arraycopy(k, first, k, first + 1, i - first);
                System.arraycopy(p, first, p, first + 1, i - first);
                k[first] = vk;
                p[first] = vp;
            } else {
                kLinearInsert(k, p, i);
            }
        }
    }

    private static void kLinearInsert(double[] k, long[] p, int last) {
        final double vk = k[last];
        final long vp = p[last];
        int next = last - 1;
        while (vk < k[next]) {
            k[last] = k[next];
            p[last] = p[next];
            last = next;
            --next;
        }
        k[last] = vk;
        p[last] = vp;
    }

    private static void kHeapSort(double[] k, long[] p, int first, int last) {
        final int len = last - first;
        if (len >= 2) {
            int parent = (len - 2) / 2;
            while (true) {
                kAdjust(k, p, first, parent, len, k[first + parent], p[first + parent]);
                if (parent == 0) break;
                parent--;
            }
        }
        while (last - first > 1) {
            --last;
            final double vk = k[last];
            final long vp = p[last];
            k[last] = k[first];
            p[last] = p[first];
            kAdjust(k, p, first, 0, last - first, vk, vp);
        }
    }

    private static void kAdjust(double[] k, long[] p, int first, int holeIndex, int len, double vk, long vp) {
        final int topIndex = holeIndex;
        int secondChild = holeIndex;
        while (secondChild < (len - 1) / 2) {
            secondChild = 2 * (secondChild + 1);
            if (k[first + secondChild] < k[first + secondChild - 1]) secondChild--;
            k[first + holeIndex] = k[first + secondChild];
            p[first + holeIndex] = p[first + secondChild];
            holeIndex = secondChild;
        }
        if ((len & 1) == 0 && secondChild == (len - 2) / 2) {
            secondChild = 2 * (secondChild + 1);
            k[first + holeIndex] = k[first + secondChild - 1];
            p[first + holeIndex] = p[first + secondChild - 1];
            holeIndex = secondChild - 1;
        }
        int parent = (holeIndex - 1) / 2;
        while (holeIndex > topIndex && k[first + parent] < vk) {
            k[first + holeIndex] = k[first + parent];
            p[first + holeIndex] = p[first + parent];
            holeIndex = parent;
            parent = (holeIndex - 1) / 2;
        }
        k[first + holeIndex] = vk;
        p[first + holeIndex] = vp;
    }

    /* ================================================================== */
    /* FastJet's objects_sorted_by_values                                  */
    /* ================================================================== */

    /**
     * fastjet::objects_sorted_by_values: the objects in increasing order of
     * the values, the indices sorted by std::sort with IndexedSortHelper, so
     * that ties come out in FastJet's order (the fastjet port's sorted_by_*
     * is a stable sort, which orders ties differently).
     */
    public static <T> List<T> objectsSortedByValues(List<T> objects, double[] values) {
        if (objects.size() != values.length) throw new IllegalArgumentException("objects and values differ in size");
        final Integer[] indices = new Integer[values.length];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        sort(indices, 0, indices.length, (a, b) -> values[a] < values[b] ? -1 : 0);
        final List<T> out = new java.util.ArrayList<>(objects.size());
        for (Integer i : indices) out.add(objects.get(i));
        return out;
    }

    /** fastjet::sorted_by_pt: by decreasing kt2, FastJet's tie order. */
    public static List<com.sphere.core.fastjet.PseudoJet> sortedByPt(List<com.sphere.core.fastjet.PseudoJet> jets) {
        final double[] minusKt2 = new double[jets.size()];
        for (int i = 0; i < minusKt2.length; i++) minusKt2[i] = -jets.get(i).kt2();
        return objectsSortedByValues(jets, minusKt2);
    }

    /** fastjet::sorted_by_E: by decreasing energy, FastJet's tie order. */
    public static List<com.sphere.core.fastjet.PseudoJet> sortedByE(List<com.sphere.core.fastjet.PseudoJet> jets) {
        final double[] minusE = new double[jets.size()];
        for (int i = 0; i < minusE.length; i++) minusE[i] = -jets.get(i).E();
        return objectsSortedByValues(jets, minusE);
    }
}
