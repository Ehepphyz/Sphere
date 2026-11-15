package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.PySemantics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * The notebook as one module for the semantic colors: a module-level name
 * belongs to every cell, so what a cell's name is depends on the others.
 *
 * Each code cell's scan gives a summary of its module level (what it binds and
 * how, what it reads, calls, uses as a module); this class keeps them in cell
 * order with what the kernel holds, and answers each cell's analysis through a
 * {@link PySemantics.Context} that sees every cell but that one. A variable
 * defined in cell 2 and read in cell 7 is then neither unresolved in 7 nor
 * unused in 2; one assigned in two cells is underlined in both; a builtin
 * hidden in one cell is flagged where another calls it.
 *
 * The state is replaced whole, never changed in place: the analyses read it on
 * the scanner thread while the event thread updates it.
 */
final class NotebookSemantics {

    private record State(List<Object> cells, List<PySemantics.Summary> summaries,
                         Map<String, PySemantics.Live> live, boolean kernelChecks) {
    }

    private volatile State state = new State(List.of(), List.of(), Map.of(), false);
    private final IntFunction<String> cellLabel;

    /** @param cellLabel describes the code cell of an index, for tooltips ("cell [3]") */
    NotebookSemantics(IntFunction<String> cellLabel) {
        this.cellLabel = cellLabel;
    }

    /** The code cells, in notebook order; summaries of cells no longer there are dropped. */
    synchronized boolean retain(List<?> cells) {
        State s = state;
        List<Object> order = new ArrayList<>(cells);
        List<PySemantics.Summary> sums = new ArrayList<>(order.size());
        for (Object c : order) {
            int i = indexOf(s.cells, c);
            sums.add(i >= 0 ? s.summaries.get(i) : null);
        }
        boolean changed = !sameCells(order, s.cells);
        state = new State(List.copyOf(order), listOf(sums), s.live, s.kernelChecks);
        return changed;
    }

    /** A cell's latest summary; true when it changed what the other cells see. */
    synchronized boolean update(Object cell, PySemantics.Summary summary) {
        State s = state;
        int i = indexOf(s.cells, cell);
        if (i < 0) {
            return false;
        }
        if (Objects.equals(s.summaries.get(i), summary)) {
            return false;
        }
        List<PySemantics.Summary> sums = new ArrayList<>(s.summaries);
        sums.set(i, summary);
        state = new State(s.cells, listOf(sums), s.live, s.kernelChecks);
        return true;
    }

    /** What the kernel holds; true when it changed. kernelChecks: the kernel underlines undefined names itself. */
    synchronized boolean setLive(Map<String, PySemantics.Live> live, boolean kernelChecks) {
        State s = state;
        Map<String, PySemantics.Live> now = Map.copyOf(live);
        if (now.equals(s.live) && kernelChecks == s.kernelChecks) {
            return false;
        }
        state = new State(s.cells, s.summaries, now, kernelChecks);
        return true;
    }

    PySemantics.Live live(String name) {
        return state.live.get(name);
    }

    boolean hasLive() {
        return !state.live.isEmpty();
    }

    /** The positions of the code cells binding name, in notebook order. */
    List<Integer> binders(String name) {
        State s = state;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < s.summaries.size(); i++) {
            PySemantics.Summary sm = s.summaries.get(i);
            if (sm != null && sm.bound().containsKey(name)) {
                out.add(i);
            }
        }
        return out;
    }

    List<Object> cells() {
        return state.cells;
    }

    /** The summaries, aligned with cells(); null for a cell not analysed yet. */
    List<PySemantics.Summary> summaries() {
        return state.summaries;
    }

    /** The context a cell's analysis reads: every other cell, and the kernel. */
    PySemantics.Context contextFor(Object cell) {
        return new PySemantics.Context() {
            @Override
            public PySemantics.Global global(String name) {
                return NotebookSemantics.this.global(state, cell, name);
            }

            @Override
            public boolean opaque() {
                for (PySemantics.Summary sm : state.summaries) {
                    if (sm != null && sm.opaque()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean checksNames() {
                return state.kernelChecks;
            }
        };
    }

    private PySemantics.Global global(State s, Object cell, String name) {
        int self = indexOf(s.cells, cell);
        PySemantics.Binding kind = null;
        String definedIn = null;
        int before = 0;
        int after = 0;
        int assigns = 0;
        int reads = 0;
        boolean called = false;
        boolean attr = false;
        for (int i = 0; i < s.summaries.size(); i++) {
            if (i == self) {
                continue;
            }
            PySemantics.Summary sm = s.summaries.get(i);
            if (sm == null) {
                continue;
            }
            PySemantics.Bind b = sm.bound().get(name);
            if (b != null) {
                if (self < 0 || i < self) {
                    before++;
                } else {
                    after++;
                }
                assigns += b.assigns();
                if (kind == null) {
                    kind = b.kind();
                    definedIn = cellLabel == null ? null : cellLabel.apply(i);
                }
                called |= b.called();
                attr |= b.attrBase();
            }
            Integer r = sm.reads().get(name);
            if (r != null) {
                reads += r;
            }
            called |= sm.called().contains(name);
            attr |= sm.attrBases().contains(name);
        }
        PySemantics.Live live = s.live.get(name);
        if (kind == null && live == null && reads == 0 && !called) {
            return null;
        }
        return new PySemantics.Global(kind, before, after, assigns, reads, called, attr, definedIn, live);
    }

    private static int indexOf(List<Object> cells, Object cell) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i) == cell) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameCells(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static List<PySemantics.Summary> listOf(List<PySemantics.Summary> sums) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(sums)); // holds nulls: not List.copyOf
    }
}
