package com.sphere.core.hepmc3.cxx;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * libstdc++'s std::sort, step for step: introsort with the median of three
 * moved to the front, heapsort past the depth limit, and the final insertion
 * sort over runs of 16. It is not stable, and the order it leaves equal
 * elements in is part of what HepMC3 writes (the HEPEVT record of a vertex
 * with two identical outgoing particles); Java's sorts would leave another.
 */
public final class StdSort {

    private static final int THRESHOLD = 16;

    private StdSort() {
    }

    /** std::sort(list.begin(), list.end(), less). */
    public static <T> void sort(List<T> list, BiPredicate<? super T, ? super T> less) {
        final int n = list.size();
        if (n < 2) return;
        @SuppressWarnings("unchecked")
        final T[] a = (T[]) list.toArray();
        introsortLoop(a, 0, n, 2 * lg(n), less);
        finalInsertionSort(a, 0, n, less);
        for (int i = 0; i < n; i++) list.set(i, a[i]);
    }

    private static int lg(int n) {
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    private static <T> void introsortLoop(T[] a, int first, int last, int depth, BiPredicate<? super T, ? super T> less) {
        while (last - first > THRESHOLD) {
            if (depth == 0) {
                partialSort(a, first, last, less);
                return;
            }
            --depth;
            final int cut = unguardedPartitionPivot(a, first, last, less);
            introsortLoop(a, cut, last, depth, less);
            last = cut;
        }
    }

    private static <T> int unguardedPartitionPivot(T[] a, int first, int last, BiPredicate<? super T, ? super T> less) {
        final int mid = first + (last - first) / 2;
        moveMedianToFirst(a, first, first + 1, mid, last - 1, less);
        return unguardedPartition(a, first + 1, last, first, less);
    }

    private static <T> void moveMedianToFirst(T[] x, int result, int a, int b, int c, BiPredicate<? super T, ? super T> less) {
        if (less.test(x[a], x[b])) {
            if (less.test(x[b], x[c])) swap(x, result, b);
            else if (less.test(x[a], x[c])) swap(x, result, c);
            else swap(x, result, a);
        } else if (less.test(x[a], x[c])) {
            swap(x, result, a);
        } else if (less.test(x[b], x[c])) {
            swap(x, result, c);
        } else {
            swap(x, result, b);
        }
    }

    private static <T> int unguardedPartition(T[] a, int first, int last, int pivot, BiPredicate<? super T, ? super T> less) {
        while (true) {
            while (less.test(a[first], a[pivot])) ++first;
            --last;
            while (less.test(a[pivot], a[last])) --last;
            if (!(first < last)) return first;
            swap(a, first, last);
            ++first;
        }
    }

    private static <T> void finalInsertionSort(T[] a, int first, int last, BiPredicate<? super T, ? super T> less) {
        if (last - first > THRESHOLD) {
            insertionSort(a, first, first + THRESHOLD, less);
            for (int i = first + THRESHOLD; i != last; ++i) unguardedLinearInsert(a, i, less);
        } else {
            insertionSort(a, first, last, less);
        }
    }

    private static <T> void insertionSort(T[] a, int first, int last, BiPredicate<? super T, ? super T> less) {
        if (first == last) return;
        for (int i = first + 1; i != last; ++i) {
            if (less.test(a[i], a[first])) {
                final T val = a[i];
                System.arraycopy(a, first, a, first + 1, i - first);
                a[first] = val;
            } else {
                unguardedLinearInsert(a, i, less);
            }
        }
    }

    private static <T> void unguardedLinearInsert(T[] a, int last, BiPredicate<? super T, ? super T> less) {
        final T val = a[last];
        int next = last - 1;
        while (less.test(val, a[next])) {
            a[last] = a[next];
            last = next;
            --next;
        }
        a[last] = val;
    }

    /* ---- the heap sort past the depth limit ------------------------------ */

    private static <T> void partialSort(T[] a, int first, int last, BiPredicate<? super T, ? super T> less) {
        makeHeap(a, first, last, less);
        while (last - first > 1) {
            --last;
            popHeap(a, first, last, last, less);
        }
    }

    private static <T> void makeHeap(T[] a, int first, int last, BiPredicate<? super T, ? super T> less) {
        final int len = last - first;
        if (len < 2) return;
        int parent = (len - 2) / 2;
        while (true) {
            final T value = a[first + parent];
            adjustHeap(a, first, parent, len, value, less);
            if (parent == 0) return;
            parent--;
        }
    }

    private static <T> void popHeap(T[] a, int first, int last, int result, BiPredicate<? super T, ? super T> less) {
        final T value = a[result];
        a[result] = a[first];
        adjustHeap(a, first, 0, last - first, value, less);
    }

    private static <T> void adjustHeap(T[] a, int first, int holeIndex, int len, T value, BiPredicate<? super T, ? super T> less) {
        final int topIndex = holeIndex;
        int secondChild = holeIndex;
        while (secondChild < (len - 1) / 2) {
            secondChild = 2 * (secondChild + 1);
            if (less.test(a[first + secondChild], a[first + (secondChild - 1)])) secondChild--;
            a[first + holeIndex] = a[first + secondChild];
            holeIndex = secondChild;
        }
        if ((len & 1) == 0 && secondChild == (len - 2) / 2) {
            secondChild = 2 * (secondChild + 1);
            a[first + holeIndex] = a[first + (secondChild - 1)];
            holeIndex = secondChild - 1;
        }
        int parent = (holeIndex - 1) / 2;
        while (holeIndex > topIndex && less.test(a[first + parent], value)) {
            a[first + holeIndex] = a[first + parent];
            holeIndex = parent;
            parent = (holeIndex - 1) / 2;
        }
        a[first + holeIndex] = value;
    }

    private static <T> void swap(T[] a, int i, int j) {
        final T t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
