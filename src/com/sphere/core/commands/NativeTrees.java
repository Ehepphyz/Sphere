package com.sphere.core.commands;

import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;
import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The trees ":root tree attach" binds when the ROOT backend is not running:
 * read by Sphere's own ROOT reader, so that the commands that only read a
 * tree (its branches, entries, columns, statistics, an entry) answer without
 * ROOT. The commands that run ROOT code on a tree (draw, scan, project,
 * process, filters, RDataFrame) still need the backend.
 */
final class NativeTrees {

    private record Bound(RootIO file, RTree tree, String label) {
    }

    private static final Map<Integer, Bound> TREES = new ConcurrentHashMap<>();

    private NativeTrees() {
    }

    /** True when the ROOT backend is there to answer. */
    static boolean backendRunning(CommandExecutionContext c) {
        if (c == null || c.ctx == null || c.ctx.router == null) return false;
        return c.ctx.router.getRootBackend() instanceof com.sphere.core.rootbackend.RootBackend b && b.isAvailable();
    }

    static RTree get(int id) {
        final Bound b = TREES.get(id);
        return b == null ? null : b.tree();
    }

    /** Binds the tree of a file to an id, the file named by its path. */
    static void attach(int id, String fileToken, String treePath) {
        final File f = Handlers.resolve(fileToken);
        if (!f.isFile()) {
            AppLogger.error("The ROOT backend is not running, and Sphere's own reader binds a tree of a file named by its path: no file "
                + f + ".");
            return;
        }
        RootIO io = null;
        try {
            io = RootIO.open(f.toPath());
            final RTree tree = io.tree(treePath);
            final Bound old = TREES.put(id, new Bound(io, tree, treePath + " of " + f.getName()));
            if (old != null) old.file().close();
            AppLogger.result(String.format(Locale.ROOT,
                "Tree %s of %s bound to id %d: %,d entries, %d branches (read by Sphere; the ROOT backend is not running).",
                treePath, f.getName(), id, tree.entries(), tree.allBranches().size()));
        } catch (IOException | RuntimeException e) {
            if (io != null) {
                try {
                    io.close();
                } catch (IOException ignored) {
                    // the error is reported below
                }
            }
            AppLogger.error("Cannot read tree " + treePath + " of " + f.getName() + ": " + e.getMessage());
        }
    }

    /** The branches, indented under their parents, with what each holds. */
    static void branches(RTree t) {
        AppLogger.result(String.format(Locale.ROOT, "%s: %,d entries, %d branches", t.name(), t.entries(), t.allBranches().size()));
        for (RTree.RBranch b : t.branches()) listBranch(b, "  ");
    }

    private static void listBranch(RTree.RBranch b, String indent) {
        final String type = b.typeName();
        AppLogger.raw(indent + b.name() + (type == null || type.isEmpty() ? "" : "  (" + type + ")")
            + (b.title().equals(b.name()) || b.title().isEmpty() ? "" : "  \"" + b.title() + "\""));
        for (RTree.RBranch c : b.children()) listBranch(c, indent + "  ");
    }

    /** TTree::Print in short: the tree, then each branch with its entries and bytes. */
    static void print(RTree t) {
        final RObject o = t.object();
        AppLogger.result(String.format(Locale.ROOT, "Tree %s \"%s\": %,d entries, %,d bytes, %,d once compressed",
            t.name(), t.title(), t.entries(), o.number("fTotBytes"), o.number("fZipBytes")));
        for (RTree.RBranch b : t.allBranches()) {
            final RObject r = b.object();
            AppLogger.raw(String.format(Locale.ROOT, "  %-32s %-28s %,10d entries %,12d bytes %,6d baskets",
                b.name(), b.typeName(), r.number("fEntries"), r.number("fTotBytes"), r.integer("fWriteBasket")
                    + (r.list("fBaskets") != null && !r.list("fBaskets").isEmpty() ? 1 : 0)));
        }
    }

    static RTree.RBranch branch(RTree t, String name) {
        final RTree.RBranch b = t.branch(name);
        if (b == null) {
            final List<String> names = new ArrayList<>();
            for (RTree.RBranch x : t.allBranches()) names.add(x.name());
            AppLogger.error("No branch named \"" + name + "\" in " + t.name() + ". It holds: " + String.join(", ", names));
        }
        return b;
    }

    /** Every number of the branch, or an error said and null. */
    static double[] column(RTree t, String name) {
        final RTree.RBranch b = branch(t, name);
        if (b == null) return null;
        try {
            final double[] v = b.column();
            if (v.length == 0) {
                AppLogger.error(name + " (" + b.typeName() + ") holds no numbers.");
                return null;
            }
            return v;
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Cannot read " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** Count, mean, standard deviation, minimum and maximum of a branch's numbers. */
    static void stats(RTree t, String name) {
        final double[] v = column(t, name);
        if (v == null) return;
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            sum += x;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        final double mean = sum / v.length;
        double sq = 0;
        for (double x : v) sq += (x - mean) * (x - mean);
        AppLogger.result(String.format(Locale.ROOT, "%s: %,d values, mean %.6g, std dev %.6g, min %.6g, max %.6g",
            name, v.length, mean, Math.sqrt(sq / v.length), min, max));
    }

    /** The values of every branch holding data at one entry. */
    static void entry(RTree t, long entry) {
        if (entry < 0 || entry >= t.entries()) {
            AppLogger.error(t.name() + " has entries 0 to " + (t.entries() - 1) + ".");
            return;
        }
        AppLogger.result(t.name() + ", entry " + entry + ":");
        for (RTree.RBranch b : t.allBranches()) {
            if (!b.hasData()) continue;
            String shown;
            try {
                shown = show(b.value(entry));
            } catch (IOException | RuntimeException e) {
                shown = "(" + e.getMessage() + ")";
            }
            AppLogger.raw("  " + b.name() + " = " + shown);
        }
    }

    private static String show(Object v) {
        if (v == null) return "null";
        if (v instanceof double[] a) return java.util.Arrays.toString(a.length > 12 ? java.util.Arrays.copyOf(a, 12) : a) + more(a.length);
        if (v instanceof float[] a) return java.util.Arrays.toString(a.length > 12 ? java.util.Arrays.copyOf(a, 12) : a) + more(a.length);
        if (v instanceof int[] a) return java.util.Arrays.toString(a.length > 12 ? java.util.Arrays.copyOf(a, 12) : a) + more(a.length);
        if (v instanceof long[] a) return java.util.Arrays.toString(a.length > 12 ? java.util.Arrays.copyOf(a, 12) : a) + more(a.length);
        if (v instanceof boolean[] a) return java.util.Arrays.toString(a.length > 12 ? java.util.Arrays.copyOf(a, 12) : a) + more(a.length);
        if (v instanceof byte[] a) return java.util.Arrays.toString(a.length > 12 ? java.util.Arrays.copyOf(a, 12) : a) + more(a.length);
        if (v instanceof List<?> l) return (l.size() > 12 ? l.subList(0, 12) : l) + more(l.size());
        if (v instanceof RObject r) return r.className + " " + r.members.keySet();
        return String.valueOf(v);
    }

    private static String more(int n) {
        return n > 12 ? " ... (" + n + ")" : "";
    }
}
