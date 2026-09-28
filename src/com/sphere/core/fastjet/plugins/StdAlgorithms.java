package com.sphere.core.fastjet.plugins;

import java.util.ArrayList;
import java.util.List;

/**
 * The C++ standard library's sorting algorithms, step for step as libstdc++
 * implements them.
 *
 * Several experiments' cone codes sort with comparators that are not strict
 * weak orderings (energies compared up to an epsilon, say), and then the
 * order obtained depends on the sorting algorithm itself; replaying the
 * library's algorithm keeps their jets those of the C++ code.
 */
public final class StdAlgorithms {

    private StdAlgorithms() {
    }

    /** a "comes before" b: the C++ comp(a, b). */
    @FunctionalInterface
    public interface Less<T> {
        boolean test(T a, T b);
    }

    /**
     * std::list::sort: the bottom-up merge sort with 64 bins of libstdc++,
     * which merges runs of 1, 2, 4... elements.
     */
    public static <T> void listSort(List<T> list, Less<? super T> comp) {
        if (list.size() < 2) return;
        final List<ArrayList<T>> tmp = new ArrayList<>(64);
        for (int i = 0; i < 64; i++) tmp.add(new ArrayList<>());
        int fill = 0;
        int pos = 0;
        final int n = list.size();
        ArrayList<T> carry = new ArrayList<>();
        do {
            carry.clear();
            carry.add(list.get(pos++));
            int counter;
            for (counter = 0; counter != fill && !tmp.get(counter).isEmpty(); counter++) {
                // counter->merge(carry); carry.swap(*counter)
                final ArrayList<T> merged = merge(tmp.get(counter), carry, comp);
                tmp.set(counter, new ArrayList<>());
                carry = merged;
            }
            // carry.swap(*counter)
            final ArrayList<T> old = tmp.get(counter);
            tmp.set(counter, carry);
            carry = old;
            if (counter == fill) fill++;
        } while (pos < n);
        for (int counter = 1; counter < fill; counter++) {
            tmp.set(counter, merge(tmp.get(counter), tmp.get(counter - 1), comp));
            tmp.set(counter - 1, new ArrayList<>());
        }
        final ArrayList<T> result = tmp.get(fill - 1);
        for (int i = 0; i < n; i++) list.set(i, result.get(i));
    }

    /**
     * std::stable_sort on a vector, as libstdc++ does it when the temporary
     * buffer of half the length is granted (the normal case): each half is
     * insertion-sorted in chunks of 7 and merged up through the buffer, and
     * the two halves are then merged.
     */
    public static <T> void stableSort(List<T> list, Less<? super T> comp) {
        final int n = list.size();
        if (n == 0) return;
        final Object[] a = list.toArray();
        final int half = (n + 1) / 2;
        final Object[] buf = new Object[half];
        mergeSortWithBuffer(a, 0, half, buf, comp);
        mergeSortWithBuffer(a, half, n, buf, comp);
        mergeAdaptive(a, 0, half, n, half, n - half, buf, comp);
        for (int i = 0; i < n; i++) list.set(i, cast(a[i]));
    }

    /**
     * std::sort, libstdc++'s introsort: median-of-three quicksort down to
     * runs of 16, heapsort past a depth of 2 log2(n), and a final insertion
     * sort. Not stable, so that equal elements end up where libstdc++ puts
     * them only if its steps are replayed exactly, as here.
     */
    public static <T> void sort(List<T> list, Less<? super T> comp) {
        final int n = list.size();
        if (n == 0) return;
        final Object[] a = list.toArray();
        introsortLoop(a, 0, n, 2 * (31 - Integer.numberOfLeadingZeros(n)), comp);
        finalInsertionSort(a, 0, n, comp);
        for (int i = 0; i < n; i++) list.set(i, cast(a[i]));
    }

    private static <T> void introsortLoop(Object[] a, int first, int last, int depthLimit, Less<? super T> comp) {
        while (last - first > 16) {
            if (depthLimit == 0) {
                heapSelect(a, first, last, last, comp);
                sortHeap(a, first, last, comp);
                return;
            }
            --depthLimit;
            final int cut = unguardedPartitionPivot(a, first, last, comp);
            introsortLoop(a, cut, last, depthLimit, comp);
            last = cut;
        }
    }

    private static <T> void finalInsertionSort(Object[] a, int first, int last, Less<? super T> comp) {
        if (last - first > 16) {
            insertionSort(a, first, first + 16, comp);
            for (int i = first + 16; i != last; i++) {
                final Object val = a[i];
                int at = i;
                int next = i - 1;
                while (less(comp, val, a[next])) {
                    a[at] = a[next];
                    at = next;
                    next--;
                }
                a[at] = val;
            }
        } else {
            insertionSort(a, first, last, comp);
        }
    }

    private static void swap(Object[] a, int i, int j) {
        final Object t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static <T> int unguardedPartitionPivot(Object[] a, int first, int last, Less<? super T> comp) {
        final int mid = first + (last - first) / 2;
        final int second = first + 1;
        // __move_median_to_first(first, second, mid, last - 1)
        final int x = second;
        final int b = mid;
        final int c = last - 1;
        if (less(comp, a[x], a[b])) {
            if (less(comp, a[b], a[c])) swap(a, first, b);
            else if (less(comp, a[x], a[c])) swap(a, first, c);
            else swap(a, first, x);
        } else if (less(comp, a[x], a[c])) {
            swap(a, first, x);
        } else if (less(comp, a[b], a[c])) {
            swap(a, first, c);
        } else {
            swap(a, first, b);
        }
        // __unguarded_partition(second, last, pivot = first)
        int f = second;
        int l = last;
        while (true) {
            while (less(comp, a[f], a[first])) ++f;
            --l;
            while (less(comp, a[first], a[l])) --l;
            if (!(f < l)) return f;
            swap(a, f, l);
            ++f;
        }
    }

    private static <T> void heapSelect(Object[] a, int first, int middle, int last, Less<? super T> comp) {
        makeHeap(a, first, middle, comp);
        for (int i = middle; i < last; ++i) {
            if (less(comp, a[i], a[first])) popHeap(a, first, middle, i, comp);
        }
    }

    private static <T> void sortHeap(Object[] a, int first, int last, Less<? super T> comp) {
        while (last - first > 1) {
            --last;
            popHeap(a, first, last, last, comp);
        }
    }

    private static <T> void makeHeap(Object[] a, int first, int last, Less<? super T> comp) {
        if (last - first < 2) return;
        final int len = last - first;
        int parent = (len - 2) / 2;
        while (true) {
            final Object value = a[first + parent];
            adjustHeap(a, first, parent, len, value, comp);
            if (parent == 0) return;
            parent--;
        }
    }

    private static <T> void popHeap(Object[] a, int first, int last, int result, Less<? super T> comp) {
        final Object value = a[result];
        a[result] = a[first];
        adjustHeap(a, first, 0, last - first, value, comp);
    }

    private static <T> void adjustHeap(Object[] a, int first, int holeIndex, int len, Object value,
                                       Less<? super T> comp) {
        final int topIndex = holeIndex;
        int secondChild = holeIndex;
        while (secondChild < (len - 1) / 2) {
            secondChild = 2 * (secondChild + 1);
            if (less(comp, a[first + secondChild], a[first + secondChild - 1])) secondChild--;
            a[first + holeIndex] = a[first + secondChild];
            holeIndex = secondChild;
        }
        if ((len & 1) == 0 && secondChild == (len - 2) / 2) {
            secondChild = 2 * (secondChild + 1);
            a[first + holeIndex] = a[first + secondChild - 1];
            holeIndex = secondChild - 1;
        }
        // __push_heap
        int parent = (holeIndex - 1) / 2;
        while (holeIndex > topIndex && less(comp, a[first + parent], value)) {
            a[first + holeIndex] = a[first + parent];
            holeIndex = parent;
            parent = (holeIndex - 1) / 2;
        }
        a[first + holeIndex] = value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    private static <T> boolean less(Less<? super T> comp, Object x, Object y) {
        return comp.test(StdAlgorithms.<T>cast(x), StdAlgorithms.<T>cast(y));
    }

    private static <T> void insertionSort(Object[] a, int first, int last, Less<? super T> comp) {
        if (first == last) return;
        for (int i = first + 1; i != last; i++) {
            if (less(comp, a[i], a[first])) {
                final Object val = a[i];
                System.arraycopy(a, first, a, first + 1, i - first);
                a[first] = val;
            } else {
                // __unguarded_linear_insert
                final Object val = a[i];
                int at = i;
                int next = i - 1;
                while (less(comp, val, a[next])) {
                    a[at] = a[next];
                    at = next;
                    next--;
                }
                a[at] = val;
            }
        }
    }

    private static <T> void mergeSortWithBuffer(Object[] a, int first, int last, Object[] buf, Less<? super T> comp) {
        final int len = last - first;
        int step = 7;
        int f = first;
        while (last - f >= step) {
            insertionSort(a, f, f + step, comp);
            f += step;
        }
        insertionSort(a, f, last, comp);
        while (step < len) {
            mergeSortLoop(a, first, last, buf, 0, step, comp);
            step *= 2;
            mergeSortLoop(buf, 0, len, a, first, step, comp);
            step *= 2;
        }
    }

    private static <T> void mergeSortLoop(Object[] src, int first, int last, Object[] dst, int result, int step,
                                          Less<? super T> comp) {
        final int twoStep = 2 * step;
        while (last - first >= twoStep) {
            result = moveMerge(src, first, first + step, first + step, first + twoStep, dst, result, comp);
            first += twoStep;
        }
        step = Math.min(last - first, step);
        moveMerge(src, first, first + step, first + step, last, dst, result, comp);
    }

    private static <T> int moveMerge(Object[] src, int f1, int l1, int f2, int l2, Object[] dst, int r,
                                     Less<? super T> comp) {
        while (f1 != l1 && f2 != l2) {
            if (less(comp, src[f2], src[f1])) {
                dst[r++] = src[f2++];
            } else {
                dst[r++] = src[f1++];
            }
        }
        while (f1 != l1) dst[r++] = src[f1++];
        while (f2 != l2) dst[r++] = src[f2++];
        return r;
    }

    private static <T> void mergeAdaptive(Object[] a, int first, int middle, int last, int len1, int len2,
                                          Object[] buf, Less<? super T> comp) {
        if (len1 <= len2) {
            System.arraycopy(a, first, buf, 0, len1);
            // __move_merge_adaptive(buffer, buffer_end, middle, last, first)
            int f1 = 0;
            int f2 = middle;
            int r = first;
            while (f1 != len1 && f2 != last) {
                if (less(comp, a[f2], buf[f1])) {
                    a[r++] = a[f2++];
                } else {
                    a[r++] = buf[f1++];
                }
            }
            while (f1 != len1) a[r++] = buf[f1++];
        } else {
            System.arraycopy(a, middle, buf, 0, len2);
            // __move_merge_adaptive_backward(first, middle, buffer, buffer_end, last)
            int result = last;
            if (first == middle) {
                System.arraycopy(buf, 0, a, result - len2, len2);
                return;
            }
            if (len2 == 0) return;
            int l1 = middle - 1;
            int l2 = len2 - 1;
            while (true) {
                if (less(comp, buf[l2], a[l1])) {
                    a[--result] = a[l1];
                    if (first == l1) {
                        System.arraycopy(buf, 0, a, result - (l2 + 1), l2 + 1);
                        return;
                    }
                    l1--;
                } else {
                    a[--result] = buf[l2];
                    if (l2 == 0) return;
                    l2--;
                }
            }
        }
    }

    /**
     * std::list::merge of x into first: an element of x goes before the
     * current one of first only when comp(x_elem, first_elem).
     */
    private static <T> ArrayList<T> merge(List<T> first, List<T> x, Less<? super T> comp) {
        final ArrayList<T> out = new ArrayList<>(first.size() + x.size());
        int i = 0;
        int j = 0;
        while (i < first.size() && j < x.size()) {
            if (comp.test(x.get(j), first.get(i))) {
                out.add(x.get(j++));
            } else {
                out.add(first.get(i++));
            }
        }
        while (i < first.size()) out.add(first.get(i++));
        while (j < x.size()) out.add(x.get(j++));
        return out;
    }
}
