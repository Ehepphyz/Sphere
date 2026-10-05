package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minuit2's logging (MnPrint): a message at a verbosity level is written when
 * the print level of its MnPrint reaches it, as "[Info] Prefix arg arg...",
 * each argument after a space, numbers at the default precision of an
 * ostringstream, Minuit2 objects as their operator&lt;&lt; writes them. The global
 * level is per thread as in the C++ (0: errors only); the lines go to standard
 * error unless a sink is set.
 */
public final class MnPrint {

    /** Something Minuit2 prints with operator&lt;&lt;. */
    public interface Printable {
        void print(COStream os);
    }

    public enum Verbosity { ERROR, WARN, INFO, DEBUG, TRACE }

    private static final ThreadLocal<int[]> GLOBAL_LEVEL = ThreadLocal.withInitial(() -> new int[] {0});
    private static final List<String> FILTERS = new CopyOnWriteArrayList<>();
    private static volatile Consumer<String> sink;
    private static volatile PrintStream stream = System.err;

    private final String prefix;
    private int level;

    public MnPrint(String prefix) {
        this(prefix, globalLevel());
    }

    public MnPrint(String prefix, int level) {
        this.prefix = prefix;
        this.level = level;
    }

    /** Sets the level of this thread; returns the previous one. */
    public static int setGlobalLevel(int level) {
        final int[] g = GLOBAL_LEVEL.get();
        final int prev = g[0];
        g[0] = level;
        return prev;
    }

    public static int globalLevel() {
        return GLOBAL_LEVEL.get()[0];
    }

    /** Only messages whose prefix contains one of the filters are written (none: all). */
    public static void addFilter(String prefix) {
        FILTERS.add(prefix);
    }

    public static void clearFilter() {
        FILTERS.clear();
    }

    /** Where the lines go instead of standard error (null: back to standard error). */
    public static void setSink(Consumer<String> lines) {
        sink = lines;
    }

    /** The stream lines go to when no sink is set. */
    public static void setStream(PrintStream s) {
        stream = s == null ? System.err : s;
    }

    public int setLevel(int l) {
        final int prev = level;
        level = l;
        return prev;
    }

    public int level() {
        return level;
    }

    public boolean shows(Verbosity v) {
        return level >= v.ordinal();
    }

    public void error(Object... args) {
        log(Verbosity.ERROR, args);
    }

    public void warn(Object... args) {
        log(Verbosity.WARN, args);
    }

    public void info(Object... args) {
        log(Verbosity.INFO, args);
    }

    public void debug(Object... args) {
        log(Verbosity.DEBUG, args);
    }

    public void trace(Object... args) {
        log(Verbosity.TRACE, args);
    }

    private void log(Verbosity v, Object[] args) {
        if (level < v.ordinal()) return;
        if (hidden()) return;
        final COStream os = new COStream();
        os.put(prefix);
        for (Object a : args) {
            os.put(" ");
            stream(os, a);
        }
        impl(v, os.str());
    }

    private boolean hidden() {
        if (FILTERS.isEmpty()) return false;
        final String full = "^" + prefix + ":";
        for (String f : FILTERS) {
            if (full.contains(f)) return false;
        }
        return true;
    }

    private static final String[] LABEL = {"[Error]", "[Warn]", "[Info]", "[Debug]", "[Trace]"};

    private static void impl(Verbosity v, String s) {
        final String line = LABEL[v.ordinal()] + " " + s;
        final Consumer<String> k = sink;
        if (k != null) {
            k.accept(line);
        } else {
            stream.println(line);
            stream.flush();
        }
    }

    /** os &lt;&lt; a, for what Minuit2 passes to its log functions. */
    @SuppressWarnings("unchecked")
    static void stream(COStream os, Object a) {
        if (a instanceof Printable p) {
            p.print(os);
        } else if (a instanceof Double d) {
            os.put(d.doubleValue());
        } else if (a instanceof Float f) {
            os.put(f.doubleValue());
        } else if (a instanceof Integer i) {
            os.put(i.intValue());
        } else if (a instanceof Long l) {
            os.put(l.longValue());
        } else if (a instanceof Character c) {
            os.put(c.charValue());
        } else if (a instanceof Boolean b) {
            os.put(b.booleanValue());
        } else if (a instanceof Consumer<?> c) {
            ((Consumer<COStream>) c).accept(os);
        } else if (a instanceof double[] arr) {
            MnMatrix.print(os, new LAVector(arr));
        } else {
            os.put(String.valueOf(a));
        }
    }

    /** One line about a state: "FCN = ... Edm = ... NCalls = ...", with the iteration first when known. */
    public record Oneline(double fcn, double edm, int ncalls, int iter) implements Printable {
        public Oneline(double fcn, double edm, int ncalls) {
            this(fcn, edm, ncalls, -1);
        }

        public Oneline(MinimumState state, int iter) {
            this(state.fval(), state.edm(), state.nfcn(), iter);
        }

        public Oneline(MinimumState state) {
            this(state, -1);
        }

        public Oneline(FunctionMinimum fmin) {
            this(fmin.state(), -1);
        }

        @Override
        public void print(COStream os) {
            if (iter >= 0) {
                os.setw(4).put(iter).put(" - ");
            }
            final int pr = os.precision();
            os.precision(MnMatrix.PRECISION);
            os.put("FCN = ").setw(MnMatrix.WIDTH).put(fcn).put(" Edm = ").setw(MnMatrix.WIDTH).put(edm)
                .put(" NCalls = ").setw(6).put(ncalls);
            os.precision(pr);
        }

        @Override
        public String toString() {
            final COStream os = new COStream();
            print(os);
            return os.str();
        }
    }

    /** A std::pair&lt;double, double&gt; as Minuit2 prints it: "\t x = .. y = ..". */
    public record Point(double x, double y) implements Printable {
        @Override
        public void print(COStream os) {
            os.put("\t x = ").put(x).put("  y = ").put(y).endl();
        }
    }
}
