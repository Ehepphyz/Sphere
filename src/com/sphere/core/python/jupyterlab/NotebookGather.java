package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.PySemantics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gather: the smallest notebook that still computes one cell. A notebook grows by
 * trial -- cells tried and dropped, values recomputed three ways -- and the one result
 * worth keeping hides among them. Following the dataflow back from that cell (the
 * names it takes, from which cells, through any chain, objects changed in place
 * included) gives the cells it needs and no others; ordered by the graph, with the
 * page's order for ties, they run top to bottom in a fresh kernel even when the page
 * defined a name below its use.
 */
final class NotebookGather {

    private NotebookGather() {
    }

    /** The code cells (by code index) the target needs, with it, in an order that runs. */
    static List<Integer> slice(int cells, List<NotebookDataflow.Edge> edges, int target) {
        if (target < 0 || target >= cells) {
            return List.of();
        }
        Set<Integer> keep = new LinkedHashSet<>();
        keep.add(target);
        Deque<Integer> todo = new ArrayDeque<>();
        todo.add(target);
        while (!todo.isEmpty()) {
            int at = todo.poll();
            for (NotebookDataflow.Edge e : edges) {
                // every edge, a backward one too: the gathered notebook puts the producer first
                if (e.to() == at && e.from() != at && keep.add(e.from())) {
                    todo.add(e.from());
                }
            }
        }
        // the graph's order among the kept cells; the page's order breaks ties, so a notebook already
        // in order keeps its order (a cell reading x before a later cell redefines it stays first)
        Map<Integer, List<Integer>> out = new HashMap<>();
        Map<Integer, Integer> indegree = new HashMap<>();
        for (int k : keep) {
            indegree.put(k, 0);
        }
        Set<Long> seen = new java.util.HashSet<>();
        for (NotebookDataflow.Edge e : edges) {
            if (keep.contains(e.from()) && keep.contains(e.to()) && e.from() != e.to()
                && seen.add(((long) e.from() << 32) | e.to())) {
                out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
                indegree.merge(e.to(), 1, Integer::sum);
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (Map.Entry<Integer, Integer> d : indegree.entrySet()) {
            if (d.getValue() == 0) {
                ready.add(d.getKey());
            }
        }
        List<Integer> order = new ArrayList<>(keep.size());
        while (!ready.isEmpty()) {
            int at = ready.poll();
            order.add(at);
            for (int to : out.getOrDefault(at, List.of())) {
                if (indegree.merge(to, -1, Integer::sum) == 0) {
                    ready.add(to);
                }
            }
        }
        if (order.size() < keep.size()) {
            // cells feeding on one another: what is left, in the page's order
            for (int k : new TreeSet<>(keep)) {
                if (!order.contains(k)) {
                    order.add(k);
                }
            }
        }
        return order;
    }

    /**
     * The names the gathered cells need that none of them defines and Python does not provide:
     * values that lived only in the kernel, which a fresh kernel will not have.
     */
    static Set<String> unresolved(List<Integer> cells, List<PySemantics.Summary> summaries) {
        Set<String> bound = new java.util.HashSet<>();
        for (int i : cells) {
            PySemantics.Summary s = i < summaries.size() ? summaries.get(i) : null;
            if (s != null) {
                bound.addAll(s.bound().keySet());
            }
        }
        Set<String> out = new TreeSet<>();
        for (int i : cells) {
            PySemantics.Summary s = i < summaries.size() ? summaries.get(i) : null;
            if (s == null) {
                continue;
            }
            for (String n : s.needs()) {
                if (!bound.contains(n) && !BUILTINS.contains(n) && !n.startsWith("__")) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /**
     * The cells as a Python script in the percent format (cells start at "# %%"), which VS Code,
     * Spyder and Jupytext read as cells and Python runs as is: magics and shell lines become comments.
     */
    static String script(String header, List<String> sources) {
        StringBuilder sb = new StringBuilder();
        for (String line : header.split("\n")) {
            sb.append("# ").append(line).append('\n');
        }
        for (String src : sources) {
            sb.append("\n# %%\n");
            for (String line : src.split("\n", -1)) {
                String t = line.stripLeading();
                sb.append(t.startsWith("%") || t.startsWith("!") ? "# " + line : line).append('\n');
            }
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n");
    }

    /** What Python itself defines: never a missing input. */
    static final Set<String> BUILTINS = Set.of(
        "abs", "aiter", "all", "anext", "any", "ascii", "bin", "bool", "breakpoint", "bytearray", "bytes", "callable",
        "chr", "classmethod", "compile", "complex", "copyright", "credits", "delattr", "dict", "dir", "divmod",
        "enumerate", "eval", "exec", "exit", "filter", "float", "format", "frozenset", "getattr", "globals", "hasattr",
        "hash", "help", "hex", "id", "input", "int", "isinstance", "issubclass", "iter", "len", "license", "list",
        "locals", "map", "max", "memoryview", "min", "next", "object", "oct", "open", "ord", "pow", "print",
        "property", "quit", "range", "repr", "reversed", "round", "set", "setattr", "slice", "sorted",
        "staticmethod", "str", "sum", "super", "tuple", "type", "vars", "zip", "True", "False", "None",
        "Ellipsis", "NotImplemented", "BaseException", "Exception", "ArithmeticError", "AssertionError",
        "AttributeError", "BlockingIOError", "BrokenPipeError", "BufferError", "ChildProcessError",
        "ConnectionError", "EOFError", "EnvironmentError", "FileExistsError", "FileNotFoundError",
        "FloatingPointError", "GeneratorExit", "IOError", "ImportError", "IndentationError", "IndexError",
        "InterruptedError", "IsADirectoryError", "KeyError", "KeyboardInterrupt", "LookupError", "MemoryError",
        "ModuleNotFoundError", "NameError", "NotADirectoryError", "NotImplementedError", "OSError",
        "OverflowError", "PermissionError", "ProcessLookupError", "RecursionError", "ReferenceError",
        "RuntimeError", "StopAsyncIteration", "StopIteration", "SyntaxError", "SystemError", "SystemExit",
        "TabError", "TimeoutError", "TypeError", "UnboundLocalError", "UnicodeDecodeError", "UnicodeEncodeError",
        "UnicodeError", "ValueError", "Warning", "UserWarning", "DeprecationWarning", "RuntimeWarning",
        "FutureWarning", "ZeroDivisionError", "display", "get_ipython", "In", "Out");
}
