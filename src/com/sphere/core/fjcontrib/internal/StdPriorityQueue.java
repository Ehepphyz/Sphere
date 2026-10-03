package com.sphere.core.fjcontrib.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * std::priority_queue as libstdc++ implements it: a binary max-heap in a
 * vector, sifted by std::push_heap and std::pop_heap step for step. The
 * contribs that pop elements of equal priority take them in the order this
 * heap gives, so reproducing its sifts keeps their results those of the C++
 * when priorities tie; java.util.PriorityQueue would not.
 *
 * @param <T> the elements
 */
public final class StdPriorityQueue<T> {

    private final List<T> c = new ArrayList<>();
    /** less(a, b) is true when a has a lower priority than b. */
    private final Comparator<? super T> less;

    /** @param less a comparator whose "a &lt; b" means a has lower priority */
    public StdPriorityQueue(Comparator<? super T> less) {
        this.less = less;
    }

    /**
     * The range constructor, priority_queue(first, last): the elements are
     * copied and arranged by std::make_heap, which leaves them in another
     * order than pushing them one by one would.
     */
    public StdPriorityQueue(Comparator<? super T> less, java.util.Collection<? extends T> init) {
        this.less = less;
        c.addAll(init);
        final int len = c.size();
        if (len < 2) return;
        int parent = (len - 2) / 2;
        while (true) {
            adjustHeap(parent, len, c.get(parent));
            if (parent == 0) return;
            parent--;
        }
    }

    private boolean comp(T a, T b) {
        return less.compare(a, b) < 0;
    }

    public int size() {
        return c.size();
    }

    public boolean isEmpty() {
        return c.isEmpty();
    }

    public T top() {
        return c.get(0);
    }

    public void push(T value) {
        c.add(value);
        pushHeap(c.size() - 1, 0, value);
    }

    public T pop() {
        final T top = c.get(0);
        final int len = c.size();
        if (len > 1) {
            final int last = len - 1;
            final T value = c.get(last);
            c.set(last, c.get(0));
            adjustHeap(0, last, value);
        }
        c.remove(len - 1);
        return top;
    }

    /** std::__push_heap. */
    private void pushHeap(int holeIndex, int topIndex, T value) {
        int parent = (holeIndex - 1) / 2;
        while (holeIndex > topIndex && comp(c.get(parent), value)) {
            c.set(holeIndex, c.get(parent));
            holeIndex = parent;
            parent = (holeIndex - 1) / 2;
        }
        c.set(holeIndex, value);
    }

    /** std::__adjust_heap on the first len elements. */
    private void adjustHeap(int holeIndex, int len, T value) {
        final int topIndex = holeIndex;
        int secondChild = holeIndex;
        while (secondChild < (len - 1) / 2) {
            secondChild = 2 * (secondChild + 1);
            if (comp(c.get(secondChild), c.get(secondChild - 1))) secondChild--;
            c.set(holeIndex, c.get(secondChild));
            holeIndex = secondChild;
        }
        if ((len & 1) == 0 && secondChild == (len - 2) / 2) {
            secondChild = 2 * (secondChild + 1);
            c.set(holeIndex, c.get(secondChild - 1));
            holeIndex = secondChild - 1;
        }
        pushHeap(holeIndex, topIndex, value);
    }
}
