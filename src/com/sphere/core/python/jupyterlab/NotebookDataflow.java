package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.PySemantics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The notebook as a dataflow: which cell takes which variable from which, and
 * which cells no longer match what they read.
 *
 * Each code cell's semantic summary says what it binds at module level, what it
 * needs (reads as it runs before binding it) and what it changes in place
 * (x.append(..), x[i] = v). Two ways to read a notebook:
 *
 * Linear (Jupyter): a cell takes a name from the nearest cell above that binds
 * it -- the order Restart & Run All follows -- or, when none does, from one
 * below (a dependency on the order cells were run in). A cell reading an object
 * that a cell between its maker and itself changes in place takes it from that
 * cell too.
 *
 * Reactive: cells are a graph, not a list. Each variable
 * has one home, the cell defining it, wherever it sits; a cell takes its inputs
 * from their homes, so the order of the cells on the page is free and the run
 * order is the graph's (topological). Two cells defining one variable, or cells
 * feeding on one another, cannot run: the notebook would depend on which ran
 * last. Objects changed in place are tracked too: the
 * readers of an object run after the cells changing it, and running a cell that
 * changes an object rebuilds the object first, so a change is never applied twice.
 *
 * With the execution counts, a cell that ran is:
 *   edited  -- its text changed since it ran: its outputs describe other code;
 *   stale   -- a cell it takes a name from ran again after it, or is itself
 *              edited or stale: its outputs and the values it made describe
 *              inputs that are gone;
 *   fresh   -- otherwise.
 * Reactive notebooks add conflict (cannot run, and why) and blocked (takes from
 * a cell that cannot run); a run under way adds queued and running.
 */
final class NotebookDataflow {

    enum Status { NOT_RUN, FRESH, FAILED, EDITED, STALE, QUEUED, RUNNING, CONFLICT, BLOCKED }

    /**
     * One code cell: its status and why, what it defines, needs and changes in
     * place, and its place in the run order.
     *
     * @param changes the objects it changes in place, each with the cell defining it
     * @param rank    its position in the run order (notebook order when linear)
     */
    record Node(int index, Status status, String why, Integer count, Set<String> binds, Set<String> needs,
                Map<String, Integer> changes, int rank) {
        boolean outdated() {
            return status == Status.STALE || status == Status.EDITED;
        }

        /** Cannot run: a conflict of its own, or one upstream. */
        boolean broken() {
            return status == Status.CONFLICT || status == Status.BLOCKED;
        }
    }

    /**
     * Names a cell takes from another. backward: the producer sits below the
     * consumer (linear notebooks only); mutation: the consumer reads an object
     * after the producer changed it in place.
     */
    record Edge(int from, int to, List<String> names, boolean backward, boolean stale, boolean mutation) {
    }

    /** What running some cells means in a reactive notebook: which cells, in which order, and why each. */
    record Plan(List<Integer> cells, Map<Integer, String> reasons, Map<Integer, String> refused) {
        boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    record Graph(List<Node> nodes, List<Edge> edges, boolean reactive, List<Integer> order) {

        static final Graph EMPTY = new Graph(List.of(), List.of(), false, List.of());

        Node node(int index) {
            return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
        }

        private boolean follows(Edge e) {
            return reactive || !e.backward();
        }

        /** The edited and stale cells, in run order. */
        List<Integer> outdated() {
            List<Integer> out = new ArrayList<>();
            for (int i : order) {
                Node n = node(i);
                if (n != null && n.outdated()) {
                    out.add(i);
                }
            }
            return out;
        }

        int count(Status s) {
            int k = 0;
            for (Node n : nodes) {
                k += n.status() == s ? 1 : 0;
            }
            return k;
        }

        /** The cells that take, directly or through others, from a cell, in run order. */
        List<Integer> downstream(int index) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> todo = new ArrayDeque<>();
            todo.add(index);
            while (!todo.isEmpty()) {
                int at = todo.poll();
                for (Edge e : edges) {
                    if (e.from() == at && follows(e) && seen.add(e.to())) {
                        todo.add(e.to());
                    }
                }
            }
            seen.remove(index);
            return inRunOrder(seen);
        }

        /** The cells a cell takes from, directly or through others. */
        Set<Integer> upstream(int index) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> todo = new ArrayDeque<>();
            todo.add(index);
            while (!todo.isEmpty()) {
                int at = todo.poll();
                for (Edge e : edges) {
                    if (e.to() == at && follows(e) && seen.add(e.from())) {
                        todo.add(e.from());
                    }
                }
            }
            seen.remove(index);
            return seen;
        }

        List<Integer> inRunOrder(Collection<Integer> cells) {
            Set<Integer> wanted = new HashSet<>(cells);
            List<Integer> out = new ArrayList<>(wanted.size());
            for (int i : order) {
                if (wanted.contains(i)) {
                    out.add(i);
                }
            }
            return out;
        }

        /**
         * Running these cells in a reactive notebook: first the cells they take
         * from that have not run or are out of date, then the cells themselves,
         * then every cell taking from any of them, in run order. A cell changing
         * an object in place brings the cell making the object along, so its
         * change is made on a fresh object. Cells that cannot run are refused.
         *
         * @param label how to name a cell in a reason ("[3]", "cell 4")
         */
        Plan plan(Collection<Integer> roots, IntFunction<String> label) {
            Map<Integer, String> reasons = new HashMap<>();
            Set<Integer> run = new LinkedHashSet<>();
            for (int r : roots) {
                if (node(r) != null && run.add(r)) {
                    reasons.put(r, "run by you");
                }
            }
            for (int r : new ArrayList<>(run)) {
                for (Map.Entry<String, Integer> ch : node(r).changes().entrySet()) {
                    if (run.add(ch.getValue())) {
                        reasons.put(ch.getValue(), "rebuilds '" + ch.getKey() + "' first: " + label.apply(r)
                                    + " changes it in place, and must not change it twice");
                    }
                }
            }
            // What they feed on must be current: ancestors not run, edited or stale run first.
            for (int r : new ArrayList<>(run)) {
                for (int a : upstream(r)) {
                    Node up = node(a);
                    if (up != null && (up.status() == Status.NOT_RUN || up.outdated()) && run.add(a)) {
                        reasons.put(a, "runs first: " + label.apply(r) + " needs what it defines, and it "
                                    + (up.status() == Status.NOT_RUN ? "has not run"
                                       : up.status() == Status.EDITED ? "was edited since it ran" : "is stale"));
                    }
                }
            }
            // Everything taking from a cell that runs runs again.
            for (int s : new ArrayList<>(run)) {
                run.addAll(downstream(s));
            }
            for (int d : run) {
                if (reasons.containsKey(d)) {
                    continue;
                }
                Edge best = null;
                for (Edge e : edges) {
                    if (e.to() == d && follows(e) && run.contains(e.from()) && (best == null || best.mutation() && !e.mutation())) {
                        best = e;
                    }
                }
                reasons.put(d, best == null ? "takes from a cell that runs"
                    : best.mutation() ? "reads " + quote(best.names()) + " after " + label.apply(best.from()) + " changes it"
                    : "takes " + quote(best.names()) + " from " + label.apply(best.from()));
            }
            Map<Integer, String> refused = new LinkedHashMap<>();
            List<Integer> cells = new ArrayList<>();
            for (int i : inRunOrder(run)) {
                Node n = node(i);
                if (n.broken()) {
                    refused.put(i, n.why());
                } else {
                    cells.add(i);
                }
            }
            return new Plan(List.copyOf(cells), Map.copyOf(reasons), refused);
        }
    }

    private NotebookDataflow() {
    }

    /** A linear notebook, cells named by their counts: what the first version computed. */
    static Graph compute(List<PySemantics.Summary> summaries, List<Integer> counts, List<Boolean> edited, int running) {
        return compute(summaries, counts, edited, running, Map.of(), Map.of(), false, null);
    }

    /**
     * @param summaries what each code cell binds, needs and changes, in notebook order (null: not analysed yet)
     * @param counts    each cell's execution count, null when it has not run on this kernel
     * @param edited    whether each cell's text changed since it ran
     * @param running   the index of the running cell, or -1
     * @param queued    the cells waiting in the run under way, with why each runs
     * @param errors    the cells whose last run raised, with the error ("NameError: name 'x' is not defined")
     * @param reactive  read the notebook as a graph rather than a list
     * @param label     how a reason names a cell; null: by its count
     */
    static Graph compute(List<PySemantics.Summary> summaries, List<Integer> counts, List<Boolean> edited, int running,
                         Map<Integer, String> queued, Map<Integer, String> errors, boolean reactive,
                         IntFunction<String> label) {
        int n = summaries.size();
        IntFunction<String> name = label != null ? label : i -> {
            Integer c = i < counts.size() ? counts.get(i) : null;
            return c == null ? "code cell " + (i + 1) : "cell [" + c + "]";
        };
        List<Set<String>> binds = new ArrayList<>(n);
        List<Set<String>> needs = new ArrayList<>(n);
        List<Set<String>> mutates = new ArrayList<>(n);
        for (PySemantics.Summary s : summaries) {
            binds.add(s == null ? Set.of() : s.bound().keySet());
            needs.add(s == null ? Set.of() : s.needs());
            mutates.add(s == null ? Set.of() : s.mutates());
        }
        Map<String, List<Integer>> definers = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            for (String b : binds.get(i)) {
                definers.computeIfAbsent(b, k -> new ArrayList<>()).add(i);
            }
        }

        String[] problem = new String[n];
        Map<Long, List<String>> named = new LinkedHashMap<>();
        Map<Long, Boolean> backward = new HashMap<>();
        Set<Long> mutation = new HashSet<>();

        // Reactive: one home per variable. Importing a module in two cells is no conflict: both give the same.
        if (reactive) {
            for (Map.Entry<String, List<Integer>> d : definers.entrySet()) {
                List<Integer> at = d.getValue();
                if (at.size() < 2 || allImports(summaries, d.getKey(), at)) {
                    continue;
                }
                for (int i : at) {
                    List<String> others = new ArrayList<>();
                    for (int j : at) {
                        if (j != i) {
                            others.add(name.apply(j));
                        }
                    }
                    problem[i] = append(problem[i], "'" + d.getKey() + "' is also defined by " + String.join(", ", others)
                                        + ": in a reactive notebook a variable has one home (F2 renames one of them)");
                }
            }
        }

        // What each cell takes from which.
        for (int i = 0; i < n; i++) {
            for (String nm : needs.get(i)) {
                List<Integer> defs = definers.getOrDefault(nm, List.of());
                if (reactive) {
                    List<Integer> others = new ArrayList<>(defs);
                    others.remove(Integer.valueOf(i));
                    if (others.isEmpty()) {
                        if (defs.contains(i) && isData(summaries.get(i), nm)) {
                            // x = x + 1 with no other home for x: each run would build on the last one
                            problem[i] = append(problem[i], "reads '" + nm + "' before defining it, so every run would "
                                                + "start from its own last result: give the new value its own name");
                        }
                        continue; // a builtin, the kernel's, or nowhere: the kernel's analysis says which
                    }
                    if (allImports(summaries, nm, defs)) {
                        others = List.of(nearest(others, i));
                    }
                    for (int from : others) {
                        edge(named, backward, from, i, nm, false);
                    }
                } else {
                    int from = -1;
                    boolean back = false;
                    for (int k = i - 1; k >= 0; k--) {
                        if (binds.get(k).contains(nm)) {
                            from = k;
                            break;
                        }
                    }
                    if (from < 0) {
                        for (int k = i + 1; k < n; k++) {
                            if (binds.get(k).contains(nm)) {
                                from = k;
                                back = true;
                                break;
                            }
                        }
                    }
                    if (from >= 0) {
                        edge(named, backward, from, i, nm, back);
                    }
                }
            }
        }

        // Objects changed in place: which cell made each, and who must read it after the change.
        List<Map<String, Integer>> changes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Integer> ch = new TreeMap<>();
            for (String x : mutates.get(i)) {
                List<Integer> defs = definers.getOrDefault(x, List.of());
                int maker = reactive ? (defs.size() == 1 ? defs.get(0) : -1) : nearestAbove(defs, i);
                if (maker >= 0 && maker != i && isData(summaries.get(maker), x)) {
                    ch.put(x, maker);
                }
            }
            changes.add(ch);
        }
        Map<String, List<Integer>> changers = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            for (String x : changes.get(i).keySet()) {
                changers.computeIfAbsent(x, k -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<String, List<Integer>> c : changers.entrySet()) {
            String x = c.getKey();
            List<Integer> ms = c.getValue();
            if (reactive) {
                for (int k = 1; k < ms.size(); k++) {
                    mutation.add(edge(named, backward, ms.get(k - 1), ms.get(k), x, false));
                }
                int last = ms.get(ms.size() - 1);
                int maker = changes.get(last).get(x);
                for (int r = 0; r < n; r++) {
                    if (r != maker && !ms.contains(r) && needs.get(r).contains(x)) {
                        mutation.add(edge(named, backward, last, r, x, false));
                    }
                }
            } else {
                // top to bottom, a reader sees the changes made between the object's maker and itself
                for (int r = 0; r < n; r++) {
                    if (!needs.get(r).contains(x)) {
                        continue;
                    }
                    int maker = nearestAbove(definers.getOrDefault(x, List.of()), r);
                    int m = -1;
                    for (int k : ms) {
                        if (k < r && k > maker && changes.get(k).get(x) == maker) {
                            m = k;
                        }
                    }
                    if (m >= 0) {
                        mutation.add(edge(named, backward, m, r, x, false));
                    }
                }
            }
        }

        // Reactive: cells feeding on one another cannot run, nor can what takes from them.
        if (reactive) {
            for (List<Integer> cycle : cycles(n, named.keySet())) {
                List<String> members = new ArrayList<>();
                for (int i : cycle) {
                    members.add(name.apply(i));
                }
                for (int i : cycle) {
                    problem[i] = append(problem[i], "cells " + String.join(", ", members)
                                        + " take variables from one another: no order can run them");
                }
            }
        }

        // The run order: the graph's when reactive (ties go to the page's order), the page's when linear.
        List<Integer> order = new ArrayList<>(n);
        if (reactive) {
            int[] indegree = new int[n];
            List<List<Integer>> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new ArrayList<>());
            }
            for (long key : named.keySet()) {
                int from = (int) (key >>> 32);
                int to = (int) key;
                out.get(from).add(to);
                indegree[to]++;
            }
            PriorityQueue<Integer> ready = new PriorityQueue<>();
            for (int i = 0; i < n; i++) {
                if (indegree[i] == 0) {
                    ready.add(i);
                }
            }
            boolean[] placed = new boolean[n];
            while (!ready.isEmpty()) {
                int at = ready.poll();
                order.add(at);
                placed[at] = true;
                for (int to : out.get(at)) {
                    if (--indegree[to] == 0) {
                        ready.add(to);
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (!placed[i]) {
                    order.add(i); // in a cycle, or after one: refused anyway
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
        }
        int[] rank = new int[n];
        for (int k = 0; k < order.size(); k++) {
            rank[order.get(k)] = k;
        }

        // Statuses from the execution counts.
        Status[] status = new Status[n];
        String[] why = new String[n];
        for (int i = 0; i < n; i++) {
            Integer c = i < counts.size() ? counts.get(i) : null;
            if (c == null) {
                status[i] = Status.NOT_RUN;
                why[i] = "not run on this kernel";
            } else if (i < edited.size() && Boolean.TRUE.equals(edited.get(i))) {
                status[i] = Status.EDITED;
                why[i] = "edited since it ran: its outputs come from the code it had then";
            } else {
                // Stale when a cell it takes a name from ran again after it.
                status[i] = Status.FRESH;
                why[i] = "up to date";
                int latest = -1;
                String latestName = null;
                boolean latestChanged = false;
                for (Map.Entry<Long, List<String>> e : named.entrySet()) {
                    int from = (int) (e.getKey() >>> 32);
                    int to = (int) (long) e.getKey();
                    Integer cf = from < counts.size() ? counts.get(from) : null;
                    if (to == i && cf != null && cf > c && cf > latest) {
                        latest = cf;
                        latestName = e.getValue().get(0);
                        latestChanged = mutation.contains(e.getKey());
                    }
                }
                if (latestName != null) {
                    status[i] = Status.STALE;
                    why[i] = latestChanged
                        ? "reads '" + latestName + "', which cell [" + latest + "] changed in place after this cell ran"
                        : "takes '" + latestName + "' from cell [" + latest + "], which ran again after this cell";
                } else if (errors.containsKey(i)) {
                    status[i] = Status.FAILED;
                    why[i] = "failed: " + errors.get(i);
                }
            }
        }
        // Through the edges: a cell taking from an outdated or failed one is outdated too.
        for (int pass = 0; pass < n; pass++) {
            boolean changed = false;
            for (Map.Entry<Long, List<String>> e : named.entrySet()) {
                int from = (int) (e.getKey() >>> 32);
                int to = (int) (long) e.getKey();
                if (status[to] == Status.FRESH
                    && (status[from] == Status.STALE || status[from] == Status.EDITED || status[from] == Status.FAILED)) {
                    status[to] = Status.STALE;
                    Integer cf = from < counts.size() ? counts.get(from) : null;
                    why[to] = "takes '" + e.getValue().get(0) + "' from cell " + (cf == null ? "" : "[" + cf + "] ")
                              + "which " + (status[from] == Status.EDITED ? "is edited since it ran"
                                            : status[from] == Status.FAILED ? "failed" : "is stale itself");
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        // Reactive: what cannot run, and what takes from it.
        if (reactive) {
            Deque<Integer> todo = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                if (problem[i] != null) {
                    status[i] = Status.CONFLICT;
                    why[i] = problem[i];
                    todo.add(i);
                }
            }
            while (!todo.isEmpty()) {
                int at = todo.poll();
                for (long key : named.keySet()) {
                    int from = (int) (key >>> 32);
                    int to = (int) key;
                    if (from == at && status[to] != Status.CONFLICT && status[to] != Status.BLOCKED) {
                        status[to] = Status.BLOCKED;
                        why[to] = "takes " + quote(named.get(key)) + " from " + name.apply(from) + ", which cannot run";
                        todo.add(to);
                    }
                }
            }
        }
        // A run under way.
        for (Map.Entry<Integer, String> q : queued.entrySet()) {
            int i = q.getKey();
            if (i >= 0 && i < n && status[i] != Status.CONFLICT && status[i] != Status.BLOCKED) {
                status[i] = Status.QUEUED;
                why[i] = q.getValue() == null ? "waits its turn" : q.getValue();
            }
        }
        if (running >= 0 && running < n) {
            status[running] = Status.RUNNING;
            why[running] = queued.getOrDefault(running, "running");
            if (why[running] == null) {
                why[running] = "running";
            }
        }

        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nodes.add(new Node(i, status[i], why[i], i < counts.size() ? counts.get(i) : null, binds.get(i), needs.get(i),
                               Map.copyOf(changes.get(i)), rank[i]));
        }
        List<Edge> edges = new ArrayList<>(named.size());
        for (Map.Entry<Long, List<String>> e : named.entrySet()) {
            int from = (int) (e.getKey() >>> 32);
            int to = (int) (long) e.getKey();
            boolean hot = status[from] == Status.STALE || status[from] == Status.EDITED
                          || status[to] == Status.STALE && e.getValue().stream().anyMatch(nm -> why[to].contains("'" + nm + "'"));
            edges.add(new Edge(from, to, List.copyOf(e.getValue()), Boolean.TRUE.equals(backward.get(e.getKey())), hot,
                               mutation.contains(e.getKey())));
        }
        return new Graph(List.copyOf(nodes), List.copyOf(edges), reactive, List.copyOf(order));
    }

    private static long edge(Map<Long, List<String>> named, Map<Long, Boolean> backward, int from, int to, String nm,
                             boolean back) {
        long key = (long) from << 32 | to;
        List<String> names = named.computeIfAbsent(key, x -> new ArrayList<>());
        if (!names.contains(nm)) {
            names.add(nm);
        }
        backward.merge(key, back, (a, b) -> a && b);
        return key;
    }

    private static boolean isData(PySemantics.Summary s, String name) {
        PySemantics.Bind b = s == null ? null : s.bound().get(name);
        return b != null && b.kind() != null && b.kind().isData();
    }

    private static boolean allImports(List<PySemantics.Summary> summaries, String name, List<Integer> cells) {
        for (int i : cells) {
            PySemantics.Summary s = summaries.get(i);
            PySemantics.Bind b = s == null ? null : s.bound().get(name);
            if (b == null || b.kind() != PySemantics.Binding.IMPORT_MODULE && b.kind() != PySemantics.Binding.IMPORT_NAME) {
                return false;
            }
        }
        return true;
    }

    /** The cell of cells nearest above i, else the first below. */
    private static int nearest(List<Integer> cells, int i) {
        int above = nearestAbove(cells, i);
        if (above >= 0) {
            return above;
        }
        for (int c : cells) {
            if (c > i) {
                return c;
            }
        }
        return cells.get(0);
    }

    private static int nearestAbove(List<Integer> cells, int i) {
        int best = -1;
        for (int c : cells) {
            if (c < i) {
                best = c;
            }
        }
        return best;
    }

    private static String append(String to, String what) {
        return to == null ? what : to + "; " + what;
    }

    static String quote(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < names.size() && k < 3; k++) {
            sb.append(k == 0 ? "" : ", ").append('\'').append(names.get(k)).append('\'');
        }
        if (names.size() > 3) {
            sb.append(" and ").append(names.size() - 3).append(" more");
        }
        return sb.toString();
    }

    /** The strongly connected groups of more than one cell (Tarjan): cells taking from one another. */
    private static List<List<Integer>> cycles(int n, Set<Long> keys) {
        List<List<Integer>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ArrayList<>());
        }
        for (long key : keys) {
            out.get((int) (key >>> 32)).add((int) key);
        }
        int[] index = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        java.util.Arrays.fill(index, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        List<List<Integer>> found = new ArrayList<>();
        int[] counter = { 0 };
        for (int v = 0; v < n; v++) {
            if (index[v] < 0) {
                strongConnect(v, out, index, low, onStack, stack, found, counter);
            }
        }
        return found;
    }

    private static void strongConnect(int root, List<List<Integer>> out, int[] index, int[] low, boolean[] onStack,
                                      Deque<Integer> stack, List<List<Integer>> found, int[] counter) {
        // iterative Tarjan: notebooks are small, but a recursion per cell is still a recursion
        Deque<int[]> work = new ArrayDeque<>();
        work.push(new int[] { root, 0 });
        index[root] = low[root] = counter[0]++;
        stack.push(root);
        onStack[root] = true;
        while (!work.isEmpty()) {
            int[] frame = work.peek();
            int v = frame[0];
            if (frame[1] < out.get(v).size()) {
                int w = out.get(v).get(frame[1]++);
                if (index[w] < 0) {
                    index[w] = low[w] = counter[0]++;
                    stack.push(w);
                    onStack[w] = true;
                    work.push(new int[] { w, 0 });
                } else if (onStack[w]) {
                    low[v] = Math.min(low[v], index[w]);
                }
                continue;
            }
            work.pop();
            if (!work.isEmpty()) {
                int parent = work.peek()[0];
                low[parent] = Math.min(low[parent], low[v]);
            }
            if (low[v] == index[v]) {
                List<Integer> group = new ArrayList<>();
                int w;
                do {
                    w = stack.pop();
                    onStack[w] = false;
                    group.add(w);
                } while (w != v);
                if (group.size() > 1) {
                    group.sort(null);
                    found.add(group);
                }
            }
        }
    }

    /** Every name the cells define, for a rename's collision check. */
    static Set<String> defined(List<PySemantics.Summary> summaries) {
        Set<String> out = new TreeSet<>();
        for (PySemantics.Summary s : summaries) {
            if (s != null) {
                out.addAll(s.bound().keySet());
            }
        }
        return out;
    }
}
