package com.sphere.core.ml;

import com.sphere.core.bridge.Spx;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntConsumer;

/**
 * A model of PyTorch -- or of Keras, on its torch backend -- run by Sphere itself, with no framework: the
 * portable graph sphere_graph.export wrote (models/name.sng.spx), core ATen operations one a line, evaluated
 * in double precision with numpy's formulas, as ':torch check' holds it to numpy and numpy to PyTorch.
 *
 * <p>Its memory is planned rather than hoped for: the graph is run on batches of 1 and 2 jets, the bytes its
 * values hold after every node counted (each array once, however many views share it), so that node t holds
 * a_t + b_t B bytes for a batch B (checked at 4). A batch whose peak would not fit what the JVM and the
 * machine spare now runs in chunks -- the graph's rows being independent, which the export checked -- on as
 * many threads as the memory affords, each value freed after its last use: the chunks change nothing, to the
 * bit. {@link #runFile} streams a dataset of any size through it, file to file.
 */
public final class NeuralGraph {

    /** A tensor: row-major doubles and a shape (integers and booleans are doubles too, as indices are). */
    public static final class Tensor {
        public final double[] data;
        public final int[] shape;

        public Tensor(double[] data, int... shape) {
            this.data = data;
            this.shape = shape;
            if (NeuralGraph.numel(shape) != data.length) {
                throw new IllegalArgumentException("a shape " + Arrays.toString(shape) + " for " + data.length + " values");
            }
        }

        public static Tensor scalar(double v) {
            return new Tensor(new double[] {v});
        }

        public int rank() {
            return shape.length;
        }

        public int numel() {
            return data.length;
        }

        @Override
        public String toString() {
            return "Tensor" + Arrays.toString(shape);
        }
    }

    /** A graph's operation this runtime has not, or one that failed on its values. */
    public static final class GraphException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GraphException(String m) {
            super(m);
        }
    }

    private record Ref(String name) {
    }

    private record Node(String name, String op, Object[] args, List<String> refs, String[] distinct) {
    }

    /** A block: a loop's condition or body, a branch of a condition -- a function of its inputs and the weights. */
    private record Block(List<String> inputs, List<Node> nodes, List<String> outputs) {
    }

    /** The control operations, which run blocks: sphere.while (condition, body, values[, limit]), sphere.case (index, branches, values). */
    static final Set<String> CONTROL = Set.of("sphere.while", "sphere.case");
    static final long LOOP_LIMIT = 10_000_000L;   // a loop past this many iterations is taken for one that does not end
    private final Map<String, Block> blocks = new HashMap<>();

    /** batch: how the graph's batch was left free -- "symbolic" (torch.export's), "generalized ..." (from two traces), "fixed". */
    public final String name, feeds, framework, precision, batch;
    public final List<String> inputNames = new ArrayList<>();
    private final List<String> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private final Set<String> outputSet = new HashSet<>();
    private final Map<String, Tensor> consts = new HashMap<>();
    private final IdentityHashMap<double[], Boolean> constArrays = new IdentityHashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Integer> last = new HashMap<>();
    private final boolean rowsIndependent;
    private final int[][] exampleShapes;
    private double[] planBase, planSlope;
    private int[][] outRows;
    private boolean planLinear;

    private NeuralGraph(Map<String, String> meta) {
        name = meta.getOrDefault("Name", "");
        feeds = meta.getOrDefault("Feeds", "");
        framework = meta.getOrDefault("Framework", "");
        precision = meta.getOrDefault("Precision", "float64");
        batch = meta.getOrDefault("Batch", "symbolic");
        rowsIndependent = "1".equals(meta.get("RowsIndependent"));
        final String ex = meta.getOrDefault("ExampleShapes", "");
        // split(";", -1): a scalar input's shape is empty, and kept -- two of them are ";"
        exampleShapes = ex.isEmpty() ? new int[0][] : Arrays.stream(ex.split(";", -1)).map(NeuralGraph::ints).toArray(int[][]::new);
    }

    private static int[] ints(String s) {
        return s.isBlank() ? new int[0] : Arrays.stream(s.split(",")).mapToInt(v -> Integer.parseInt(v.strip())).toArray();
    }

    /** A graph as sphere_graph exported it; its operations are all checked to be this runtime's first. */
    public static NeuralGraph load(Path file) throws IOException {
        try (Spx.Reader r = new Spx.Reader(file)) {
            final Map<String, String> meta = r.meta();
            if (!"sphere-graph".equals(meta.get("Kind"))) {
                throw new IOException(file + " is not a graph exported by sphere_graph");
            }
            final NeuralGraph g = new NeuralGraph(meta);
            final Set<String> missing = new TreeSet<>();
            Block block = null;   // the block being read, between begin and end
            for (String line : r.text("graph").split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] p = line.split("\t", -1);
                switch (p[0]) {
                    case "begin" -> {
                        block = new Block(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                        g.blocks.put(p[1], block);
                    }
                    case "end" -> block = null;
                    case "input" -> {
                        if (block != null) {
                            block.inputs.add(p[1]);
                        } else {
                            g.inputs.add(p[1]);
                            g.inputNames.add(p[2]);
                        }
                    }
                    case "const" -> g.consts.put(p[1], new Tensor(r.f64("w." + p[1]), ints(p[2])));
                    case "node" -> {
                        final List<String> refs = new ArrayList<>();
                        final String[] toks = p.length > 3 && !p[3].isEmpty() ? p[3].split(" ") : new String[0];
                        final Object[] args = new Object[toks.length];
                        for (int i = 0; i < toks.length; i++) {
                            args[i] = token(toks[i], refs);
                        }
                        if (!SUPPORTED.contains(p[2]) && !CONTROL.contains(p[2])) {
                            missing.add(p[2]);
                        }
                        final Node nd = new Node(p[1], p[2], args, refs, new TreeSet<>(refs).toArray(String[]::new));
                        (block != null ? block.nodes : g.nodes).add(nd);
                    }
                    case "output" -> (block != null ? block.outputs : g.outputs).add(p[1]);
                    default -> throw new IOException("a graph line of kind " + p[0]);
                }
            }
            if (!missing.isEmpty()) {
                throw new IOException(g.name + " uses operations this runtime has not: " + String.join(", ", missing));
            }
            for (int i = 0; i < g.nodes.size(); i++) {
                for (String ref : g.nodes.get(i).refs) {
                    g.last.put(ref, i);
                }
            }
            g.outputSet.addAll(g.outputs);
            for (Tensor t : g.consts.values()) {
                g.constArrays.put(t.data, Boolean.TRUE);
            }
            return g;
        }
    }

    private static Object token(String t, List<String> refs) {
        if (t.equals("none")) {
            return null;
        }
        final String kind = t.substring(0, 1), v = t.length() > 2 ? t.substring(2) : "";
        return switch (kind) {
            case "n" -> {
                refs.add(v);
                yield new Ref(v);
            }
            case "i" -> Long.parseLong(v);
            case "f" -> switch (v) {
                case "inf" -> Double.POSITIVE_INFINITY;
                case "-inf" -> Double.NEGATIVE_INFINITY;
                case "nan" -> Double.NaN;
                default -> Double.parseDouble(v);
            };
            case "b" -> v.equals("1");
            case "s" -> v;
            case "T" -> {
                final List<Object> list = new ArrayList<>();
                for (String e : v.split(",")) {
                    refs.add(e);
                    list.add(new Ref(e));
                }
                yield list;
            }
            case "L" -> {
                final List<Object> list = new ArrayList<>();
                if (!v.isEmpty()) {
                    for (String e : v.split(",")) {
                        list.add(token(e, refs));
                    }
                }
                yield list;
            }
            default -> throw new IllegalArgumentException("a graph token " + t);
        };
    }

    // ------------------------------------------------------------------
    // Running, its memory planned
    // ------------------------------------------------------------------

    /** What a run did: its rows, the chunks and threads it took, the memory it was held to and held at most. */
    public static final class RunInfo {
        public long rows, chunk, chunks;
        public int threads = 1;
        public double memory, peak;

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d rows in %d chunk%s of %d on %d thread%s, %.2f MB held at most of the %.1f MB allowed",
                rows, chunks, chunks == 1 ? "" : "s", chunk, threads, threads == 1 ? "" : "s", peak / 1048576.0, memory / 1048576.0);
        }
    }

    /** The outputs for these inputs, in the graph's input order, held to what Sphere spares now. */
    public List<Tensor> run(List<Tensor> in) {
        return run(in, budget(), 0, null);
    }

    /** The outputs, the run held to memory bytes (its weights apart). */
    public List<Tensor> run(List<Tensor> in, double memory) {
        return run(in, memory, 0, null);
    }

    /**
     * The outputs, the run held to memory bytes on up to threads threads (0: as many as the machine has): a batch
     * too large runs in chunks, on as many threads as the memory affords; info (if not null) says how it went.
     */
    public List<Tensor> run(List<Tensor> in, double memory, int threads, RunInfo info) {
        final int n = rowsOf(in);
        if (rowsIndependent) {   // a plan only to chunk by rows: a graph whose rows are not independent runs whole
            plan();
        }
        final RunInfo r = info != null ? info : new RunInfo();
        r.rows = n;
        r.memory = memory;
        r.chunk = n;
        r.chunks = 1;
        r.threads = 1;
        if (!rowsIndependent || planBase == null || !planLinear) {
            final Holdings h = new Holdings();
            final List<Tensor> out = execute(in, null, h);
            r.peak = h.peak;
            return out;
        }
        final int[] split = split(n, memory - outBytes(n), threads);
        final int c = split[0];
        r.chunk = c;
        r.threads = split[1];
        r.chunks = (n + c - 1) / c;
        if (r.chunks <= 1) {
            final Holdings h = new Holdings();
            final List<Tensor> out = execute(in, null, h);
            r.peak = h.peak;
            return out;
        }
        final List<Tensor> outs = new ArrayList<>();
        for (int[] row : outRows) {
            final int[] s = new int[row.length + 1];
            s[0] = n;
            System.arraycopy(row, 0, s, 1, row.length);
            outs.add(new Tensor(new double[numel(s)], s));
        }
        parallel((int) r.chunks, r.threads, r, k -> {
            final int lo = k * c, hi = Math.min(n, lo + c);
            final Holdings h = new Holdings();
            final List<Tensor> got = execute(in.stream().map(x -> rows(x, lo, hi)).toList(), null, h);
            for (int i = 0; i < got.size(); i++) {
                final int per = numel(outRows[i]);
                System.arraycopy(got.get(i).data, 0, outs.get(i).data, lo * per, (hi - lo) * per);
            }
            return h.peak;
        });
        return outs;
    }

    /** The rows of these inputs, their count and their rows' shapes checked against the trace's. */
    private int rowsOf(List<Tensor> in) {
        if (in.size() != inputs.size()) {
            throw new GraphException(name + " takes " + inputs.size() + " inputs (" + String.join(", ", inputNames) + "), not " + in.size());
        }
        int n = -1;
        for (int k = 0; k < in.size(); k++) {
            final Tensor x = in.get(k);
            if (k < exampleShapes.length && x.rank() == exampleShapes[k].length && x.rank() > 0) {
                final int[] a = Arrays.copyOfRange(x.shape, 1, x.rank()), b = Arrays.copyOfRange(exampleShapes[k], 1, x.rank());
                if (!Arrays.equals(a, b)) {
                    throw new GraphException("input " + inputNames.get(k) + ": " + name + " was traced on rows of " + Arrays.toString(b)
                        + ", not " + Arrays.toString(a));
                }
            }
            final int rows = x.rank() > 0 ? x.shape[0] : 1;
            if (rowsIndependent && n >= 0 && x.rank() > 0 && rows != n) {   // rows only where the graph has a batch
                throw new GraphException("inputs of " + n + " and " + rows + " rows");
            }
            n = Math.max(n, rows);
        }
        return Math.max(n, 0);
    }

    private double outBytes(long n) {
        double b = 0;
        for (int[] row : outRows) {
            b += 8.0 * n * numel(row);
        }
        return b;
    }

    /** The chunk and the threads the memory affords: the threads halved until one row fits each. */
    private int[] split(int n, double room, int threads) {
        int t = Math.max(1, Math.min(threads > 0 ? threads : Runtime.getRuntime().availableProcessors(), n));
        long c;
        while (true) {
            c = batchFor(Math.max(0, room) / t);
            if (c >= 1 || t == 1) {
                break;
            }
            t = Math.max(1, t / 2);
        }
        if (c < 1) {
            throw new GraphException(String.format(Locale.ROOT, "one row of %s needs %.1f MB, where Sphere spares %.1f MB now;"
                + " sphere_graph.hpp runs it in C++, putting what will not fit in temporary files", name, perRowPeak() / 1048576.0,
                room / 1048576.0));
        }
        c = Math.min(c, Math.max(1, (n + t - 1) / t));
        final long chunks = (n + c - 1) / c;
        return new int[] {(int) c, (int) Math.max(1, Math.min(t, chunks))};
    }

    private interface Chunk {
        double run(int k) throws Exception;
    }

    /** chunks tasks on t threads (the caller's one of them), each taking the next; the first failure stops them all. */
    private static void parallel(int chunks, int t, RunInfo info, Chunk task) {
        final AtomicInteger next = new AtomicInteger();
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final double[] peaks = new double[t];
        final IntConsumer work = me -> {
            try {
                for (int k; failed.get() == null && (k = next.getAndIncrement()) < chunks; ) {
                    peaks[me] = Math.max(peaks[me], task.run(k));
                }
            } catch (Throwable e) {
                failed.compareAndSet(null, e);
            }
        };
        final List<Thread> pool = new ArrayList<>();
        for (int i = 1; i < t; i++) {
            final int me = i;
            pool.add(Thread.ofPlatform().daemon().name("sphere-graph-" + i).start(() -> work.accept(me)));
        }
        work.accept(0);
        for (Thread th : pool) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.compareAndSet(null, e);
            }
        }
        final Throwable e = failed.get();
        if (e instanceof RuntimeException re) {
            throw re;
        }
        if (e instanceof Error er) {
            throw er;
        }
        if (e != null) {
            throw new GraphException(e.toString());
        }
        double sum = 0;
        for (double p : peaks) {
            sum += p;
        }
        info.peak = sum;
    }

    /** The bytes a run's values hold: each array once, however many values share it; the weights apart. */
    private final class Holdings {
        private final IdentityHashMap<double[], int[]> count = new IdentityHashMap<>();
        double held, peak;

        void take(Object v) {
            if (v instanceof Tensor t) {
                if (!constArrays.containsKey(t.data) && count.computeIfAbsent(t.data, k -> new int[1])[0]++ == 0) {
                    held += 8.0 * t.data.length;
                    peak = Math.max(peak, held);
                }
            } else if (v instanceof Object[] tuple) {
                for (Object e : tuple) {
                    take(e);
                }
            } else if (v instanceof List<?> list) {
                for (Object e : list) {
                    take(e);
                }
            }
        }

        void drop(Object v) {
            if (v instanceof Tensor t) {
                final int[] c = count.get(t.data);
                if (c != null && --c[0] == 0) {
                    count.remove(t.data);
                    held -= 8.0 * t.data.length;
                }
            } else if (v instanceof Object[] tuple) {
                for (Object e : tuple) {
                    drop(e);
                }
            } else if (v instanceof List<?> list) {
                for (Object e : list) {
                    drop(e);
                }
            }
        }
    }

    private List<Tensor> execute(List<Tensor> in, List<Double> live, Holdings h) {
        final Map<String, Object> env = new HashMap<>(consts);
        for (int i = 0; i < inputs.size(); i++) {
            env.put(inputs.get(i), in.get(i));
            h.take(in.get(i));
        }
        for (int at = 0; at < nodes.size(); at++) {
            final Node nd = nodes.get(at);
            final Object[] a = new Object[nd.args.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = resolve(nd.args[i], env);
            }
            final Object v;
            try {
                v = CONTROL.contains(nd.op) ? control(nd.op, a) : apply(nd.op, a);
            } catch (GraphException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new GraphException(nd.name + " (" + nd.op + "): " + e);
            }
            env.put(nd.name, v);
            h.take(v);
            if (live != null) {
                live.add(h.held);
            }
            for (String r : nd.distinct) {
                if (last.get(r) == at && !outputSet.contains(r) && !consts.containsKey(r)) {
                    h.drop(env.remove(r));
                }
            }
        }
        final List<Tensor> out = new ArrayList<>();
        for (String o : outputs) {
            out.add(tensor(env.get(o)));
        }
        return out;
    }

    /** A block run on its inputs' values: its outputs. */
    private List<Object> runBlock(String name, List<?> values) {
        final Block b = blocks.get(name);
        if (b == null) {
            throw new GraphException(this.name + " has no block " + name);
        }
        if (values.size() != b.inputs.size()) {
            throw new GraphException("block " + name + " takes " + b.inputs.size() + " inputs, not " + values.size());
        }
        final Map<String, Object> env = new HashMap<>(consts);
        for (int i = 0; i < values.size(); i++) {
            env.put(b.inputs.get(i), values.get(i));
        }
        for (Node nd : b.nodes) {
            final Object[] a = new Object[nd.args.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = resolve(nd.args[i], env);
            }
            try {
                env.put(nd.name, CONTROL.contains(nd.op) ? control(nd.op, a) : apply(nd.op, a));
            } catch (GraphException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new GraphException(nd.name + " (" + nd.op + "): " + e);
            }
        }
        final List<Object> out = new ArrayList<>(b.outputs.size());
        for (String o : b.outputs) {
            out.add(env.get(o));
        }
        return out;
    }

    private static boolean truth(Object v) {
        final Tensor t = tensor(v);
        if (t.numel() != 1) {
            throw new GraphException("a condition of " + t.numel() + " values, not one");
        }
        return t.data[0] != 0;
    }

    /** sphere.while runs its body while its condition holds; sphere.case the branch its index picks (the last if out of range). */
    private Object control(String op, Object[] a) {
        if (op.equals("sphere.while")) {
            final String cond = (String) a[0], body = (String) a[1];
            List<Object> vals = new ArrayList<>((List<?>) a[2]);
            final long limit = a.length > 3 && a[3] != null ? lng(a[3]) : LOOP_LIMIT;
            for (long n = 0; truth(runBlock(cond, vals).get(0)); ) {
                vals = runBlock(body, vals);
                if (++n >= limit) {
                    throw new GraphException("a loop past " + limit + " iterations");
                }
            }
            return vals;
        }
        final int k = (int) num(a[0]);
        final List<?> branches = (List<?>) a[1];
        return runBlock((String) branches.get(k >= 0 && k < branches.size() ? k : branches.size() - 1), (List<?>) a[2]);
    }

    // ------------------------------------------------------------------
    // A dataset streamed, file to file
    // ------------------------------------------------------------------

    private static final int IO = 1 << 20;   // the bytes a thread reads or writes at a time

    /**
     * The graph over a dataset of any size, as sphere_graph.hpp's run_file does: its rows read chunk by chunk
     * (sections named as the graph's inputs -- x, mask, input2 -- or in.0, in.1; for a graph that feeds on
     * particles, ':ml jets' datasets' x, mask and hl; on high-level features, hl), the outputs written chunk by
     * chunk into the result (out.0, out.1..., the labels y copied): neither is ever whole in memory.
     */
    public RunInfo runFile(Path input, Path output, double memory, int threads) throws IOException {
        plan();
        if (planBase == null || outRows == null) {
            throw new GraphException(name + " has no example shapes to plan its memory: run it with run()");
        }
        final RunInfo info = new RunInfo();
        try (FileChannel src = FileChannel.open(input, StandardOpenOption.READ)) {
            final Map<String, long[]> dir = directory(src, input);
            final Map<String, String> meta = meta(src, dir);
            final long[][] at = new long[inputs.size()][];
            final int[][] rowShape = new int[inputs.size()][];
            long n = -1;
            for (int k = 0; k < inputs.size(); k++) {
                final List<String> candidates = new ArrayList<>(List.of(inputNames.get(k), "in." + k));
                if (feeds.equals("high_level") && k == 0 || feeds.equals("particles") && k == 2) {
                    candidates.add("hl");
                }
                final String wanted = inputNames.get(k);
                final String c = candidates.stream().filter(dir::containsKey).findFirst().orElseThrow(() -> new GraphException(
                    input.getFileName() + " has no section for the input " + wanted + " of " + name));
                at[k] = dir.get(c);
                if (at[k][0] != Spx.F64) {
                    throw new GraphException("section " + c + " of " + input.getFileName() + " is not float64");
                }
                rowShape[k] = k < exampleShapes.length && exampleShapes[k].length > 0
                    ? Arrays.copyOfRange(exampleShapes[k], 1, exampleShapes[k].length) : new int[0];
                final int per = numel(rowShape[k]);
                final String declared = meta.get("Shape." + c);
                if (declared != null && ints(declared).length > 0 && !Arrays.equals(Arrays.copyOfRange(ints(declared), 1,
                    ints(declared).length), rowShape[k])) {
                    throw new GraphException("input " + inputNames.get(k) + ": " + name + " was traced on rows of "
                        + Arrays.toString(rowShape[k]) + ", " + c + " holds rows of " + declared);
                }
                if (per == 0 || at[k][2] % per != 0) {
                    throw new GraphException("section " + c + " holds " + at[k][2] + " values, not rows of " + Arrays.toString(rowShape[k]));
                }
                final long rows = at[k][2] / per;
                if (n >= 0 && rows != n) {
                    throw new GraphException("inputs of " + n + " and " + rows + " rows");
                }
                n = rows;
            }
            if (n > Integer.MAX_VALUE) {
                throw new GraphException(n + " rows: more than Java's arrays index; sphere_graph.hpp streams them");
            }
            final int rows = (int) Math.max(n, 0);
            // the result's layout: meta, each output, the labels
            final List<String> names = new ArrayList<>(List.of("meta"));
            final List<Long> counts = new ArrayList<>();
            final StringBuilder text = new StringBuilder("Kind: sphere-graph-output\nGraph: " + name + "\nRows: " + rows + "\nSource: "
                + input.getFileName() + "\n");
            for (int i = 0; i < outRows.length; i++) {
                final int[] s = new int[outRows[i].length + 1];
                s[0] = rows;
                System.arraycopy(outRows[i], 0, s, 1, outRows[i].length);
                text.append("Shape.out.").append(i).append(": ").append(join(s)).append('\n');
                names.add("out." + i);
                counts.add((long) rows * numel(outRows[i]));
            }
            final long[] y = dir.get("y");
            if (y != null) {
                names.add("y");
                counts.add(y[2]);
            }
            final byte[] metaBytes = text.toString().getBytes(StandardCharsets.UTF_8);
            counts.add(0, (long) metaBytes.length);
            final long[] offsets = new long[names.size()];
            long end = aligned(64 + 64L * names.size());
            for (int k = 0; k < names.size(); k++) {
                offsets[k] = end;
                end = aligned(end + (k == 0 ? counts.get(0) : 8 * counts.get(k)));
            }
            final Path part = output.resolveSibling(output.getFileName() + ".part");
            try (FileChannel dst = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ)) {
                writeFully(dst, header(names, counts, offsets, end), 0);
                writeFully(dst, ByteBuffer.wrap(metaBytes), offsets[0]);
                if (end > 0) {
                    writeFully(dst, ByteBuffer.wrap(new byte[1]), end - 1);
                }
                final int[] split = split(Math.max(rows, 1), memory - 2.0 * IO * Math.max(1, threads > 0 ? threads
                    : Runtime.getRuntime().availableProcessors()), threads);
                final int c = split[0];
                info.rows = rows;
                info.memory = memory;
                info.chunk = c;
                info.threads = split[1];
                info.chunks = rows == 0 ? 0 : (rows + c - 1) / c;
                final ThreadLocal<ByteBuffer> io = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(IO).order(ByteOrder.LITTLE_ENDIAN));
                parallel((int) info.chunks, info.threads, info, k -> {
                    final int lo = k * c, hi = Math.min(rows, lo + c);
                    final List<Tensor> part0 = new ArrayList<>();
                    for (int q = 0; q < inputs.size(); q++) {
                        final int per = numel(rowShape[q]);
                        final double[] v = new double[(hi - lo) * per];
                        readDoubles(src, at[q][1] + 8L * lo * per, v, io.get(), false);
                        final int[] s = new int[rowShape[q].length + 1];
                        s[0] = hi - lo;
                        System.arraycopy(rowShape[q], 0, s, 1, rowShape[q].length);
                        part0.add(new Tensor(v, s));
                    }
                    final Holdings h = new Holdings();
                    final List<Tensor> got = execute(part0, null, h);
                    for (int i = 0; i < got.size(); i++) {
                        final int per = numel(outRows[i]);
                        writeDoubles(dst, offsets[i + 1] + 8L * lo * per, got.get(i).data, (hi - lo) * per, io.get());
                    }
                    return h.peak;
                });
                if (y != null) {   // the labels, as doubles, a megabyte at a time
                    final ByteBuffer b = io.get();
                    final double[] piece = new double[IO / 8];
                    for (long from = 0; from < y[2]; from += piece.length) {
                        final int m = (int) Math.min(piece.length, y[2] - from);
                        final double[] v = m == piece.length ? piece : new double[m];
                        readDoubles(src, y[1] + 8 * from, v, b, y[0] == Spx.I64);
                        writeDoubles(dst, offsets[names.size() - 1] + 8 * from, v, m, b);
                    }
                }
                dst.force(true);
            }
            try {
                Files.move(part, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(part, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return info;
    }

    private static long aligned(long v) {
        return (v + 63) / 64 * 64;
    }

    private static String join(int[] s) {
        return String.join(",", Arrays.stream(s).mapToObj(Integer::toString).toList());
    }

    private static ByteBuffer header(List<String> names, List<Long> counts, long[] offsets, long size) {
        final ByteBuffer h = ByteBuffer.allocate((int) aligned(64 + 64L * names.size())).order(ByteOrder.LITTLE_ENDIAN);
        h.put(0, "SPHRSPX1".getBytes(StandardCharsets.US_ASCII));
        h.putInt(8, 1).putInt(12, 3).putInt(16, names.size()).putInt(20, 0).putLong(24, 64).putLong(32, size);
        h.put(40, padded("graph output"));
        for (int k = 0; k < names.size(); k++) {
            final int d = 64 + 64 * k;
            h.put(d, padded(names.get(k)));
            h.putInt(d + 24, k == 0 ? Spx.TEXT : Spx.F64).putInt(d + 28, 0).putLong(d + 32, offsets[k]).putLong(d + 40, counts.get(k));
        }
        return h;
    }

    private static byte[] padded(String s) {
        final byte[] b = new byte[24];
        Arrays.fill(b, (byte) ' ');
        final byte[] v = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(v, 0, b, 0, Math.min(24, v.length));
        return b;
    }

    /** An SPX file's sections: name to {type, offset, count}, read from its directory alone. */
    private static Map<String, long[]> directory(FileChannel ch, Path file) throws IOException {
        final ByteBuffer h = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, h, 0);
        final byte[] magic = new byte[8];
        h.get(0, magic);
        if (!"SPHRSPX1".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException(file.getFileName() + " is not an SPX file");
        }
        final int count = h.getInt(16);
        final ByteBuffer d = ByteBuffer.allocate(64 * count).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, d, h.getLong(24));
        final Map<String, long[]> out = new LinkedHashMap<>();
        for (int k = 0; k < count; k++) {
            final byte[] nm = new byte[24];
            d.get(64 * k, nm);
            out.put(new String(nm, StandardCharsets.US_ASCII).strip(), new long[] {d.getInt(64 * k + 24), d.getLong(64 * k + 32),
                d.getLong(64 * k + 40)});
        }
        return out;
    }

    private static Map<String, String> meta(FileChannel ch, Map<String, long[]> dir) throws IOException {
        final Map<String, String> out = new LinkedHashMap<>();
        final long[] m = dir.get("meta");
        if (m == null || m[0] != Spx.TEXT) {
            return out;
        }
        final ByteBuffer b = ByteBuffer.allocate((int) m[2]);
        readFully(ch, b, m[1]);
        for (String line : new String(b.array(), StandardCharsets.UTF_8).split("\n")) {
            final int colon = line.indexOf(':');
            if (colon > 0) {
                out.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return out;
    }

    private static void readFully(FileChannel ch, ByteBuffer b, long position) throws IOException {
        while (b.hasRemaining()) {
            if (ch.read(b, position + b.position()) < 0) {
                throw new EOFException("an SPX file shorter than its directory says");
            }
        }
        b.flip();
    }

    private static void writeFully(FileChannel ch, ByteBuffer b, long position) throws IOException {
        while (b.hasRemaining()) {
            ch.write(b, position + b.position());
        }
    }

    /** into.length doubles (int64 values, if longs) from position, through buf. */
    private static void readDoubles(FileChannel ch, long position, double[] into, ByteBuffer buf, boolean longs) throws IOException {
        for (int at = 0; at < into.length; ) {
            final int m = Math.min(into.length - at, buf.capacity() / 8);
            buf.clear().limit(8 * m);
            readFully(ch, buf, position + 8L * at);
            if (longs) {
                for (int i = 0; i < m; i++) {
                    into[at + i] = buf.getLong(8 * i);
                }
            } else {
                buf.asDoubleBuffer().get(into, at, m);
            }
            at += m;
        }
    }

    private static void writeDoubles(FileChannel ch, long position, double[] from, int length, ByteBuffer buf) throws IOException {
        for (int at = 0; at < length; ) {
            final int m = Math.min(length - at, buf.capacity() / 8);
            buf.clear();
            buf.asDoubleBuffer().put(from, at, m);
            buf.limit(8 * m);
            writeFully(ch, buf, position + 8L * at);
            at += m;
        }
    }

    /** P(signal) from a classifier's outputs, and what was made of them. */
    public record Score(double[] p, String reading) {
    }

    /**
     * P(signal) whatever the model ends with, the last class taken for the signal: [n, C] probabilities (rows in
     * [0, 1] summing to 1) as they are, or logits through a softmax; [n] or [n, 1] a probability as it is, or a
     * logit through a sigmoid. Null for an output of more dimensions (not a classifier's).
     */
    public static Score signal(Tensor out) {
        if (out.rank() > 2) {
            return null;
        }
        final int n = out.rank() == 0 ? 1 : out.shape[0], c = n == 0 ? 1 : out.numel() / n;
        final double[] p = new double[n], x = out.data;
        boolean prob = true;
        for (int i = 0; i < n && prob; i++) {
            double s = 0;
            for (int j = 0; j < c; j++) {
                prob &= x[i * c + j] >= 0 && x[i * c + j] <= 1;
                s += x[i * c + j];
            }
            prob &= c == 1 || Math.abs(s - 1) <= 1e-9 * c;
        }
        for (int i = 0; i < n; i++) {
            if (prob) {
                p[i] = x[i * c + c - 1];
            } else if (c == 1) {
                p[i] = 1 / (1 + Math.exp(-x[i]));
            } else {
                double m = Double.NEGATIVE_INFINITY, s = 0;
                for (int j = 0; j < c; j++) {
                    m = Math.max(m, x[i * c + j]);
                }
                for (int j = 0; j < c; j++) {
                    s += Math.exp(x[i * c + j] - m);
                }
                p[i] = Math.exp(x[i * c + c - 1] - m) / s;
            }
        }
        return new Score(p, prob ? (c == 1 ? "its output, a probability" : "its last column, of probabilities")
            : c == 1 ? "a sigmoid of its output, a logit" : "a softmax of its " + c + " logits, the last class");
    }

    private static Object resolve(Object a, Map<String, Object> env) {
        if (a instanceof Ref r) {
            return env.get(r.name);
        }
        if (a instanceof List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(resolve(e, env));
            }
            return out;
        }
        return a;
    }

    /** The bytes alive after each node, as a_t + b_t B: measured on batches of 1 and 2 (inputs of the traced shapes), checked at 4. */
    private synchronized void plan() {
        if (planBase != null || planned || exampleShapes.length != inputs.size()) {
            return;
        }
        planned = true;
        try {
            measure();
        } catch (GraphException e) {   // the graph's sizes do not follow its first dimension: it runs whole
            planBase = null;
            planSlope = null;
            outRows = null;
        }
    }

    private boolean planned;

    private void measure() {
        final double[][] live = new double[3][];
        final int[] batches = {1, 2, 4};
        for (int k = 0; k < 3; k++) {
            final List<Tensor> in = new ArrayList<>();
            for (int[] s : exampleShapes) {
                final int[] shape = s.clone();
                if (shape.length > 0) {
                    shape[0] = batches[k];
                }
                final double[] d = new double[numel(shape)];
                Arrays.fill(d, 1.0);
                in.add(new Tensor(d, shape));
            }
            final List<Double> l = new ArrayList<>();
            final List<Tensor> out = execute(in, l, new Holdings());
            live[k] = l.stream().mapToDouble(Double::doubleValue).toArray();
            if (k == 0) {
                outRows = out.stream().map(t -> t.rank() > 0 ? Arrays.copyOfRange(t.shape, 1, t.rank()) : new int[0]).toArray(int[][]::new);
            }
        }
        final int n = live[0].length;
        planBase = new double[n];
        planSlope = new double[n];
        double predicted = 0, actual = 0;
        for (int t = 0; t < n; t++) {
            planSlope[t] = live[1][t] - live[0][t];
            planBase[t] = live[0][t] - planSlope[t];
            predicted = Math.max(predicted, planBase[t] + 4 * planSlope[t]);
            actual = Math.max(actual, live[2][t]);
        }
        planLinear = Math.abs(predicted - actual) <= 0.05 * Math.max(actual, 1);
    }

    /** The largest batch whose run stays within budget bytes (its weights apart), -1 if the plan is not linear. */
    public long batchFor(double budget) {
        plan();
        if (planBase == null || !planLinear) {
            return -1;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int t = 0; t < planBase.length; t++) {
            if (planSlope[t] > 0) {
                best = Math.min(best, Math.floor((budget - planBase[t]) / planSlope[t]));
            }
        }
        return best == Double.POSITIVE_INFINITY ? Long.MAX_VALUE : (long) Math.max(0, best);
    }

    /** The bytes one row takes at the peak of a run. */
    public double perRowPeak() {
        plan();
        if (planBase == null) {
            return 0;
        }
        double p = 0;
        for (int t = 0; t < planBase.length; t++) {
            p = Math.max(p, planBase[t] + planSlope[t]);
        }
        return p;
    }

    /**
     * What a run may take now: 60 % of what the JVM's heap can still give -- what it holds already and free,
     * plus half of what the machine has beyond its reserve (12 % of the RAM, 400 MB at least) for the heap to
     * grow into, and on Windows what the commit limit leaves beyond 1 GB, as sphere_ml's governor reckons it.
     */
    public static double budget() {
        final Runtime rt = Runtime.getRuntime();
        final double used = rt.totalMemory() - rt.freeMemory();
        final double heap = rt.maxMemory() - used, held = rt.totalMemory() - used;
        double growth = Double.POSITIVE_INFINITY;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            final double total = os.getTotalMemorySize(), avail = os.getFreeMemorySize(), mb = 1048576.0;
            double spare = avail - Math.max(400 * mb, 0.12 * total);
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                spare = Math.min(spare, os.getFreeSwapSpaceSize() - 1024 * mb);   // the commit charge's limit, there
            }
            growth = 0.5 * Math.max(0, spare);
        }
        return Math.max(16.0 * 1048576, 0.6 * Math.min(heap, held + growth));
    }

    public boolean rowsIndependent() {
        return rowsIndependent;
    }

    /** The shapes of the inputs the graph was traced on, the batch first. */
    public int[][] exampleShapes() {
        return Arrays.stream(exampleShapes).map(int[]::clone).toArray(int[][]::new);
    }

    /** Whether the plan holds: the bytes held grow as a_t + b_t B (false also where nothing could be planned). */
    public boolean planned() {
        plan();
        return planBase != null && planLinear;
    }

    public int nodes() {
        return nodes.size();
    }

    public long weights() {
        long n = 0;
        for (Tensor t : consts.values()) {
            n += t.numel();
        }
        return n;
    }

    @Override
    public String toString() {
        return name + " (" + nodes.size() + " operations, " + weights() + " weights, inputs " + String.join(", ", inputNames)
            + (feeds.isEmpty() ? "" : ", feeds " + feeds) + ", from " + framework + ")";
    }

    // ------------------------------------------------------------------
    // Tensors: shapes, broadcasting, reductions
    // ------------------------------------------------------------------

    static int numel(int[] shape) {
        int n = 1;
        for (int s : shape) {
            n *= s;
        }
        return n;
    }

    private static int[] strides(int[] shape) {
        final int[] s = new int[shape.length];
        int acc = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            s[i] = acc;
            acc *= shape[i];
        }
        return s;
    }

    private static int dim(long d, int rank) {
        final int r = Math.max(rank, 1);
        return (int) (((d % r) + r) % r);
    }

    private static Tensor tensor(Object v) {
        if (v instanceof Tensor t) {
            return t;
        }
        if (v instanceof Number n) {
            return Tensor.scalar(n.doubleValue());
        }
        if (v instanceof Boolean b) {
            return Tensor.scalar(b ? 1 : 0);
        }
        throw new GraphException("a tensor expected, " + (v == null ? "none" : v.getClass().getSimpleName()) + " found");
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Tensor t && t.numel() == 1) {
            return t.data[0];
        }
        throw new GraphException("a number expected, " + v + " found");
    }

    private static long lng(Object v) {
        return (long) num(v);
    }

    private static int[] shapeOf(Object list) {
        if (!(list instanceof List<?>)) {
            return new int[] {(int) lng(list)};   // an int[2] argument given one int, as padding=0
        }
        final List<?> l = (List<?>) list;
        final int[] s = new int[l.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = (int) lng(l.get(i));
        }
        return s;
    }

    private static int[] dimsOf(Object v, int rank) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l) {
            final int[] d = new int[l.size()];
            for (int i = 0; i < d.length; i++) {
                d[i] = dim(lng(l.get(i)), rank);
            }
            return d;
        }
        return new int[] {dim(lng(v), rank)};
    }

    private static int[] broadcast(int[] a, int[] b) {
        final int n = Math.max(a.length, b.length);
        final int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            final int da = i - (n - a.length) >= 0 ? a[i - (n - a.length)] : 1;
            final int db = i - (n - b.length) >= 0 ? b[i - (n - b.length)] : 1;
            if (da != db && da != 1 && db != 1) {
                throw new GraphException("shapes " + Arrays.toString(a) + " and " + Arrays.toString(b) + " do not broadcast");
            }
            out[i] = Math.max(da, db) == 0 || da == 0 || db == 0 ? 0 : Math.max(da, db);
        }
        return out;
    }

    /** The strides of x read as shape out (broadcast: 0 where x has 1 or nothing). */
    private static int[] bstrides(int[] xs, int[] out) {
        final int[] st = strides(xs), r = new int[out.length];
        for (int i = 0; i < out.length; i++) {
            final int j = i - (out.length - xs.length);
            r[i] = j >= 0 && xs[j] != 1 ? st[j] : 0;
        }
        return r;
    }

    static Tensor binary(Object av, Object bv, DoubleBinaryOperator f) {
        final Tensor a = tensor(av), b = tensor(bv);
        if (Arrays.equals(a.shape, b.shape)) {
            final double[] o = new double[a.data.length];
            for (int i = 0; i < o.length; i++) {
                o[i] = f.applyAsDouble(a.data[i], b.data[i]);
            }
            return new Tensor(o, a.shape.clone());
        }
        final int[] shape = broadcast(a.shape, b.shape);
        final int[] sa = bstrides(a.shape, shape), sb = bstrides(b.shape, shape);
        final int n = numel(shape), rank = shape.length;
        final double[] o = new double[n];
        final int[] idx = new int[rank];
        int ia = 0, ib = 0;
        for (int k = 0; k < n; k++) {
            o[k] = f.applyAsDouble(a.data[ia], b.data[ib]);
            for (int d = rank - 1; d >= 0; d--) {
                idx[d]++;
                ia += sa[d];
                ib += sb[d];
                if (idx[d] < shape[d]) {
                    break;
                }
                ia -= sa[d] * shape[d];
                ib -= sb[d] * shape[d];
                idx[d] = 0;
            }
        }
        return new Tensor(o, shape);
    }

    static Tensor unary(Object xv, DoubleUnaryOperator f) {
        final Tensor x = tensor(xv);
        final double[] o = new double[x.data.length];
        for (int i = 0; i < o.length; i++) {
            o[i] = f.applyAsDouble(x.data[i]);
        }
        return new Tensor(o, x.shape.clone());
    }

    private static Tensor broadcastTo(Tensor x, int[] shape) {
        if (Arrays.equals(x.shape, shape)) {
            return x;
        }
        final int[] st = bstrides(x.shape, shape);
        final int n = numel(shape), rank = shape.length;
        final double[] o = new double[n];
        final int[] idx = new int[rank];
        int ix = 0;
        for (int k = 0; k < n; k++) {
            o[k] = x.data[ix];
            for (int d = rank - 1; d >= 0; d--) {
                idx[d]++;
                ix += st[d];
                if (idx[d] < shape[d]) {
                    break;
                }
                ix -= st[d] * shape[d];
                idx[d] = 0;
            }
        }
        return new Tensor(o, shape);
    }

    private static final int SUM = 0, MEAN = 1, MAX = 2, MIN = 3, PROD = 4, ANY = 5;

    /** Reduces dims (null: all), numpy's pairwise sums aside: sums in order. */
    private static Tensor reduce(Tensor x, int[] dims, boolean keep, int kind) {
        final int rank = x.rank();
        final boolean[] red = new boolean[rank];
        if (dims == null || dims.length == 0) {
            Arrays.fill(red, true);
        } else {
            for (int d : dims) {
                red[d] = true;
            }
        }
        final int[] kshape = x.shape.clone();
        int count = 1;
        for (int d = 0; d < rank; d++) {
            if (red[d]) {
                count *= kshape[d];
                kshape[d] = 1;
            }
        }
        final int[] ost = strides(kshape);
        final double[] o = new double[numel(kshape)];
        Arrays.fill(o, kind == MAX ? Double.NEGATIVE_INFINITY : kind == MIN ? Double.POSITIVE_INFINITY : kind == PROD ? 1 : 0);
        final int[] idx = new int[rank];
        for (int k = 0; k < x.data.length; k++) {
            int oi = 0;
            for (int d = 0; d < rank; d++) {
                if (!red[d]) {
                    oi += idx[d] * ost[d];
                }
            }
            final double v = x.data[k];
            switch (kind) {
                case SUM, MEAN -> o[oi] += v;
                case MAX -> o[oi] = Double.isNaN(v) || Double.isNaN(o[oi]) ? Double.NaN : Math.max(o[oi], v);
                case MIN -> o[oi] = Double.isNaN(v) || Double.isNaN(o[oi]) ? Double.NaN : Math.min(o[oi], v);
                case PROD -> o[oi] *= v;
                default -> o[oi] = (o[oi] != 0 || v != 0) ? 1 : 0;
            }
            for (int d = rank - 1; d >= 0; d--) {
                if (++idx[d] < x.shape[d]) {
                    break;
                }
                idx[d] = 0;
            }
        }
        if (kind == MEAN) {
            for (int i = 0; i < o.length; i++) {
                o[i] /= count;
            }
        }
        if (keep) {
            return new Tensor(o, kshape);
        }
        final List<Integer> s = new ArrayList<>();
        for (int d = 0; d < rank; d++) {
            if (!red[d]) {
                s.add(x.shape[d]);
            }
        }
        return new Tensor(o, s.stream().mapToInt(Integer::intValue).toArray());
    }

    /** x seen as (outer, n, inner) around one dim. */
    private record Around(int outer, int n, int inner) {
        static Around of(int[] shape, int d) {
            int o = 1, in = 1;
            for (int i = 0; i < d; i++) {
                o *= shape[i];
            }
            for (int i = d + 1; i < shape.length; i++) {
                in *= shape[i];
            }
            return new Around(o, shape.length == 0 ? 1 : shape[d], in);
        }
    }

    private static Tensor softmax(Tensor x, int d, boolean log) {
        final Around a = Around.of(x.shape, d);
        final double[] o = new double[x.data.length];
        for (int p = 0; p < a.outer; p++) {
            for (int q = 0; q < a.inner; q++) {
                final int base = p * a.n * a.inner + q;
                double m = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < a.n; i++) {
                    m = Math.max(m, x.data[base + i * a.inner]);
                }
                double s = 0;
                for (int i = 0; i < a.n; i++) {
                    final double e = Math.exp(x.data[base + i * a.inner] - m);
                    o[base + i * a.inner] = e;
                    s += e;
                }
                for (int i = 0; i < a.n; i++) {
                    final int at = base + i * a.inner;
                    o[at] = log ? (x.data[at] - m) - Math.log(s) : o[at] / s;
                }
            }
        }
        return new Tensor(o, x.shape.clone());
    }

    private static Tensor permute(Tensor x, int[] perm) {
        final int rank = x.rank();
        final int[] shape = new int[rank], st = strides(x.shape), pst = new int[rank];
        for (int i = 0; i < rank; i++) {
            shape[i] = x.shape[perm[i]];
            pst[i] = st[perm[i]];
        }
        final double[] o = new double[x.data.length];
        final int[] idx = new int[rank];
        int ix = 0;
        for (int k = 0; k < o.length; k++) {
            o[k] = x.data[ix];
            for (int d = rank - 1; d >= 0; d--) {
                idx[d]++;
                ix += pst[d];
                if (idx[d] < shape[d]) {
                    break;
                }
                ix -= pst[d] * shape[d];
                idx[d] = 0;
            }
        }
        return new Tensor(o, shape);
    }

    private static Tensor view(Tensor x, int[] sizes) {
        final int[] s = sizes.clone();
        int known = 1, free = -1;
        for (int i = 0; i < s.length; i++) {
            if (s[i] == -1) {
                free = i;
            } else {
                known *= s[i];
            }
        }
        if (free >= 0) {
            s[free] = known == 0 ? 0 : x.numel() / known;
        }
        return new Tensor(x.data, s);
    }

    private static Tensor expand(Tensor x, int[] sizes) {
        final int lead = sizes.length - x.rank();
        final int[] target = new int[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            target[i] = sizes[i] == -1 && i >= lead ? x.shape[i - lead] : sizes[i];
        }
        return broadcastTo(x, target);
    }

    /** Elements [lo, hi) step `step` along a dim. */
    private static Tensor narrow(Tensor x, int d, int lo, int hi, int step) {
        final Around a = Around.of(x.shape, d);
        final int m = hi <= lo ? 0 : (hi - lo + step - 1) / step;
        final double[] o = new double[a.outer * m * a.inner];
        for (int p = 0; p < a.outer; p++) {
            for (int i = 0; i < m; i++) {
                System.arraycopy(x.data, (p * a.n + lo + i * step) * a.inner, o, (p * m + i) * a.inner, a.inner);
            }
        }
        final int[] s = x.shape.clone();
        s[d] = m;
        return new Tensor(o, s);
    }

    private static Tensor rows(Tensor x, int lo, int hi) {
        return x.rank() == 0 ? x : narrow(x, 0, lo, hi, 1);
    }

    private static Tensor slice(Tensor x, Object dv, Object start, Object end, Object step) {
        final int d = dim(dv == null ? 0 : lng(dv), x.rank()), n = x.shape[d];
        long lo = start == null ? 0 : lng(start), hi = end == null ? n : lng(end);
        lo = lo < 0 ? Math.max(0, lo + n) : Math.min(lo, n);
        hi = hi < 0 ? Math.max(0, hi + n) : Math.min(hi, n);
        return narrow(x, d, (int) lo, (int) Math.max(lo, hi), step == null ? 1 : (int) lng(step));
    }

    private static Tensor select(Tensor x, int d, long i) {
        final int n = x.shape[d];
        final int at = (int) (i < 0 ? i + n : i);
        final Tensor t = narrow(x, d, at, at + 1, 1);
        final int[] s = new int[x.rank() - 1];
        for (int k = 0, j = 0; k < x.rank(); k++) {
            if (k != d) {
                s[j++] = x.shape[k];
            }
        }
        return new Tensor(t.data, s);
    }

    static Tensor cat(List<Tensor> ts, int dv) {
        final List<Tensor> parts = ts.stream().filter(t -> !(t.rank() == 1 && t.numel() == 0)).toList();
        if (parts.isEmpty()) {
            return ts.get(0);
        }
        final int rank = parts.get(0).rank(), d = dim(dv, rank);
        final int[] s = parts.get(0).shape.clone();
        int total = 0;
        for (Tensor t : parts) {
            total += t.shape[d];
        }
        s[d] = total;
        final Around a = Around.of(s, d);
        final double[] o = new double[numel(s)];
        int at = 0;
        for (Tensor t : parts) {
            final int m = t.shape[d];
            for (int p = 0; p < a.outer; p++) {
                System.arraycopy(t.data, p * m * a.inner, o, (p * total + at) * a.inner, m * a.inner);
            }
            at += m;
        }
        return new Tensor(o, s);
    }

    private static Tensor squeeze(Tensor x, int[] dims) {
        final List<Integer> s = new ArrayList<>();
        for (int d = 0; d < x.rank(); d++) {
            boolean drop = x.shape[d] == 1;
            if (dims != null) {
                final int dd = d;
                drop &= Arrays.stream(dims).anyMatch(v -> v == dd);
            }
            if (!drop) {
                s.add(x.shape[d]);
            }
        }
        return new Tensor(x.data, s.stream().mapToInt(Integer::intValue).toArray());
    }

    private static Tensor unsqueeze(Tensor x, long dv) {
        final int d = dim(dv, x.rank() + 1);
        final int[] s = new int[x.rank() + 1];
        for (int i = 0, j = 0; i < s.length; i++) {
            s[i] = i == d ? 1 : x.shape[j++];
        }
        return new Tensor(x.data, s);
    }

    // ---- products

    /**
     * a @ b with numpy's matmul rules: batch dimensions broadcast -- read in place, a weight shared by a batch
     * of a million rows is not copied a million times -- a vector's dimension added and removed.
     */
    static Tensor matmul(Tensor a0, Tensor b0) {
        final Tensor a = a0.rank() == 1 ? new Tensor(a0.data, 1, a0.shape[0]) : a0;
        final Tensor b = b0.rank() == 1 ? new Tensor(b0.data, b0.shape[0], 1) : b0;
        final int n = a.shape[a.rank() - 2], k = a.shape[a.rank() - 1], m = b.shape[b.rank() - 1];
        if (b.shape[b.rank() - 2] != k) {
            throw new GraphException("matmul of " + Arrays.toString(a.shape) + " and " + Arrays.toString(b.shape));
        }
        final int[] ba = Arrays.copyOf(a.shape, a.rank() - 2), bb = Arrays.copyOf(b.shape, b.rank() - 2);
        final int[] batch = broadcast(ba, bb), bst = strides(batch);
        final int[] sa = bstrides(ba, batch), sb = bstrides(bb, batch);
        final int nb = numel(batch);
        final double[] o = new double[nb * n * m];
        for (int z = 0; z < nb; z++) {
            int za = 0, zb = 0;
            for (int d = 0; d < batch.length; d++) {
                final int i = (z / bst[d]) % batch[d];
                za += i * sa[d];
                zb += i * sb[d];
            }
            final int ao = za * n * k, bo = zb * k * m, oo = z * n * m;
            for (int i = 0; i < n; i++) {
                for (int p = 0; p < k; p++) {
                    final double av = a.data[ao + i * k + p];
                    if (av == 0) {
                        continue;
                    }
                    final int brow = bo + p * m, orow = oo + i * m;
                    for (int j = 0; j < m; j++) {
                        o[orow + j] += av * b.data[brow + j];
                    }
                }
            }
        }
        int[] s = Arrays.copyOf(batch, batch.length + 2);
        s[batch.length] = n;
        s[batch.length + 1] = m;
        if (a0.rank() == 1) {
            s = squeezeAt(s, s.length - 2);
        }
        if (b0.rank() == 1) {
            s = squeezeAt(s, s.length - 1);
        }
        return new Tensor(o, s);
    }

    private static int[] squeezeAt(int[] s, int d) {
        final int[] r = new int[s.length - 1];
        for (int i = 0, j = 0; i < s.length; i++) {
            if (i != d) {
                r[j++] = s[i];
            }
        }
        return r;
    }

    /** beta c + alpha prod, the product's fresh values combined in place: no second tensor of its size. */
    private static Tensor scaleAdd(Object c, Tensor prod, Object beta, Object alpha) {
        final double be = beta == null ? 1 : num(beta), al = alpha == null ? 1 : num(alpha);
        final Tensor ct = tensor(c);
        if (!Arrays.equals(broadcast(ct.shape, prod.shape), prod.shape)) {
            return binary(ct, prod, (x, y) -> be * x + al * y);
        }
        final int[] sc = bstrides(ct.shape, prod.shape), shape = prod.shape;
        final int rank = shape.length;
        final int[] idx = new int[rank];
        int ic = 0;
        for (int k = 0; k < prod.data.length; k++) {
            prod.data[k] = be * ct.data[ic] + al * prod.data[k];
            for (int d = rank - 1; d >= 0; d--) {
                idx[d]++;
                ic += sc[d];
                if (idx[d] < shape[d]) {
                    break;
                }
                ic -= sc[d] * shape[d];
                idx[d] = 0;
            }
        }
        return prod;
    }

    // ---- images

    private static Tensor conv(Tensor x, Tensor w, Object bias, int[] stride, int[] pad, int[] dil, int groups) {
        final int nd = w.rank() - 2, n = x.shape[0], cin = x.shape[1], cout = w.shape[0], cg = w.shape[1];
        final int[] in = Arrays.copyOfRange(x.shape, 2, x.rank()), ks = Arrays.copyOfRange(w.shape, 2, w.rank());
        final int[] st = rep(stride, nd), pd = rep(pad, nd), dl = rep(dil, nd), out = new int[nd];
        for (int i = 0; i < nd; i++) {
            out[i] = (in[i] + 2 * pd[i] - dl[i] * (ks[i] - 1) - 1) / st[i] + 1;
        }
        final int og = cout / groups, osz = numel(out), isz = numel(in), ksz = numel(ks);
        final int[] ost = strides(out), ist = strides(in), kst = strides(ks);
        final double[] o = new double[n * cout * osz];
        final Tensor b = bias == null ? null : tensor(bias);
        for (int s = 0; s < n; s++) {
            for (int co = 0; co < cout; co++) {
                final int g = co / og;
                for (int op = 0; op < osz; op++) {
                    double acc = b == null ? 0 : b.data[co];
                    for (int ci = 0; ci < cg; ci++) {
                        final int xc = (s * cin + g * cg + ci) * isz, wc = (co * cg + ci) * ksz;
                        for (int kp = 0; kp < ksz; kp++) {
                            int xi = 0;
                            boolean inside = true;
                            for (int d = 0; d < nd; d++) {
                                final int pos = (op / ost[d]) % out[d] * st[d] - pd[d] + (kp / kst[d]) % ks[d] * dl[d];
                                if (pos < 0 || pos >= in[d]) {
                                    inside = false;
                                    break;
                                }
                                xi += pos * ist[d];
                            }
                            if (inside) {
                                acc += x.data[xc + xi] * w.data[wc + kp];
                            }
                        }
                    }
                    o[(s * cout + co) * osz + op] = acc;
                }
            }
        }
        final int[] shape = new int[nd + 2];
        shape[0] = n;
        shape[1] = cout;
        System.arraycopy(out, 0, shape, 2, nd);
        return new Tensor(o, shape);
    }

    private static int[] rep(int[] v, int nd) {
        if (v.length == nd) {
            return v;
        }
        final int[] r = new int[nd];
        Arrays.fill(r, v.length == 0 ? 0 : v[0]);
        return r;
    }

    /**
     * A 2-d pooling, its kernel, stride, padding and dilation each one int or two: the window's maximum, or its
     * sum over divisor (> 0), over the window's size (countPad), or over the input positions it covers.
     */
    private static Tensor pool(Tensor x, int[] kernel0, int[] stride0, int[] pad0, int[] dil0, boolean max, boolean countPad,
                               double divisor) {
        final int nd = 2, lead = x.rank() - nd;
        final int[] kernel = rep(kernel0, nd);
        final int[] stride = rep(stride0.length == 0 ? kernel : stride0, nd), pad = rep(pad0, nd), dil = rep(dil0.length == 0
            ? new int[] {1} : dil0, nd);
        final int[] in = Arrays.copyOfRange(x.shape, lead, x.rank()), out = new int[nd];
        for (int i = 0; i < nd; i++) {
            out[i] = (in[i] + 2 * pad[i] - dil[i] * (kernel[i] - 1) - 1) / stride[i] + 1;
        }
        int planes = 1;
        for (int i = 0; i < lead; i++) {
            planes *= x.shape[i];
        }
        final int isz = numel(in), osz = numel(out), ksz = numel(kernel);
        final int[] ost = strides(out), ist = strides(in), kst = strides(kernel);
        final double[] o = new double[planes * osz];
        for (int p = 0; p < planes; p++) {
            for (int op = 0; op < osz; op++) {
                double acc = max ? Double.NEGATIVE_INFINITY : 0;
                int covered = 0;
                for (int kp = 0; kp < ksz; kp++) {
                    int xi = 0;
                    boolean inside = true;
                    for (int d = 0; d < nd; d++) {
                        final int pos = (op / ost[d]) % out[d] * stride[d] - pad[d] + (kp / kst[d]) % kernel[d] * dil[d];
                        if (pos < 0 || pos >= in[d]) {
                            inside = false;
                            break;
                        }
                        xi += pos * ist[d];
                    }
                    final double v = inside ? x.data[p * isz + xi] : (max ? Double.NEGATIVE_INFINITY : 0);
                    covered += inside ? 1 : 0;
                    acc = max ? (Double.isNaN(v) || Double.isNaN(acc) ? Double.NaN : Math.max(acc, v)) : acc + v;
                }
                o[p * osz + op] = max ? acc : acc / (divisor > 0 ? divisor : countPad ? ksz : covered);
            }
        }
        final int[] shape = Arrays.copyOf(x.shape, x.rank());
        System.arraycopy(out, 0, shape, lead, nd);
        return new Tensor(o, shape);
    }

    // ---- gathering, ordering

    private static Tensor gather(Tensor x, int d, Tensor idx) {
        final int rank = x.rank();
        final int[] xst = strides(x.shape);
        final double[] o = new double[idx.numel()];
        final int[] i = new int[rank];
        for (int k = 0; k < o.length; k++) {
            int at = 0;
            for (int q = 0; q < rank; q++) {
                at += (q == d ? (int) idx.data[k] : i[q]) * xst[q];
            }
            o[k] = x.data[at];
            for (int q = rank - 1; q >= 0; q--) {
                if (++i[q] < idx.shape[q]) {
                    break;
                }
                i[q] = 0;
            }
        }
        return new Tensor(o, idx.shape.clone());
    }

    private static Tensor indexSelect(Tensor x, int d, Tensor idx) {
        final Around a = Around.of(x.shape, d);
        final int m = idx.numel();
        final double[] o = new double[a.outer * m * a.inner];
        for (int p = 0; p < a.outer; p++) {
            for (int j = 0; j < m; j++) {
                int at = (int) idx.data[j];
                at = at < 0 ? at + a.n : at;
                System.arraycopy(x.data, (p * a.n + at) * a.inner, o, (p * m + j) * a.inner, a.inner);
            }
        }
        final int[] s = x.shape.clone();
        s[d] = m;
        final Tensor t = new Tensor(o, s);
        if (idx.rank() == 1) {
            return t;
        }
        final int[] full = new int[x.rank() - 1 + idx.rank()];
        System.arraycopy(x.shape, 0, full, 0, d);
        System.arraycopy(idx.shape, 0, full, d, idx.rank());
        System.arraycopy(x.shape, d + 1, full, d + idx.rank(), x.rank() - d - 1);
        return new Tensor(t.data, full);
    }

    /** numpy's order of floats for sorting: NaN last, -0 and 0 alike. */
    private static int numpyOrder(double a, double b) {
        return a < b || (Double.isNaN(b) && !Double.isNaN(a)) ? -1 : b < a || (Double.isNaN(a) && !Double.isNaN(b)) ? 1 : 0;
    }

    /** Stable order along a dim, as numpy's argsort(kind="stable"): values and indices of the first k. */
    private static Object[] order(Tensor x, int d, int k, boolean descending) {
        final Around a = Around.of(x.shape, d);
        final double[] vals = new double[a.outer * k * a.inner], idx = new double[vals.length];
        final Integer[] perm = new Integer[a.n];
        for (int p = 0; p < a.outer; p++) {
            for (int q = 0; q < a.inner; q++) {
                final int base = p * a.n * a.inner + q;
                for (int i = 0; i < a.n; i++) {
                    perm[i] = i;
                }
                Arrays.sort(perm, (u, v) -> {
                    final double xu = x.data[base + u * a.inner], xv = x.data[base + v * a.inner];
                    return descending ? numpyOrder(-xu, -xv) : numpyOrder(xu, xv);
                });
                for (int i = 0; i < k; i++) {
                    final int at = (p * k + i) * a.inner + q;
                    vals[at] = x.data[base + perm[i] * a.inner];
                    idx[at] = perm[i];
                }
            }
        }
        final int[] s = x.shape.clone();
        s[d] = k;
        return new Object[] {new Tensor(vals, s), new Tensor(idx, s.clone())};
    }

    private static Object[] argReduce(Tensor x, Object dv, boolean keep, boolean max) {
        final int d = dim(lng(dv), x.rank());
        final Around a = Around.of(x.shape, d);
        final double[] v = new double[a.outer * a.inner], ix = new double[v.length];
        for (int p = 0; p < a.outer; p++) {
            for (int q = 0; q < a.inner; q++) {
                final int base = p * a.n * a.inner + q;
                double best = x.data[base];
                int at = 0;
                for (int i = 1; i < a.n; i++) {
                    final double c = x.data[base + i * a.inner];
                    if (Double.isNaN(best)) {
                        break;
                    }
                    if (Double.isNaN(c) || (max ? c > best : c < best)) {
                        best = c;
                        at = i;
                    }
                }
                v[p * a.inner + q] = best;
                ix[p * a.inner + q] = at;
            }
        }
        final int[] s = x.shape.clone();
        s[d] = 1;
        final int[] shape = keep ? s : squeezeAt(s, d);
        return new Object[] {new Tensor(v, shape), new Tensor(ix, shape.clone())};
    }

    private static Tensor index(Tensor x, List<?> indices) {
        int first = -1, count = 0;
        final List<Tensor> ts = new ArrayList<>();
        for (int i = 0; i < indices.size(); i++) {
            if (indices.get(i) != null) {
                if (first < 0) {
                    first = i;
                } else if (i != first + count) {
                    throw new GraphException("index with index tensors apart");
                }
                count++;
                ts.add(tensor(indices.get(i)));
            }
        }
        int[] bs = ts.get(0).shape;
        for (Tensor t : ts) {
            bs = broadcast(bs, t.shape);
        }
        final List<Tensor> b = new ArrayList<>();
        for (Tensor t : ts) {
            b.add(broadcastTo(t, bs));
        }
        final int before = first, after = x.rank() - first - count;
        final int[] xst = strides(x.shape);
        final int[] pre = Arrays.copyOfRange(x.shape, 0, before), post = Arrays.copyOfRange(x.shape, first + count, x.rank());
        final int npre = numel(pre), nb = numel(bs), npost = numel(post);
        final double[] o = new double[npre * nb * npost];
        final int[] pst = strides(pre), qst = strides(post);
        for (int p = 0; p < npre; p++) {
            int off = 0;
            for (int d = 0; d < before; d++) {
                off += (p / pst[d]) % pre[d] * xst[d];
            }
            for (int j = 0; j < nb; j++) {
                int at = off;
                for (int c = 0; c < count; c++) {
                    int v = (int) b.get(c).data[j];
                    v = v < 0 ? v + x.shape[first + c] : v;
                    at += v * xst[first + c];
                }
                for (int q = 0; q < npost; q++) {
                    int pq = 0;
                    for (int d = 0; d < after; d++) {
                        pq += (q / qst[d]) % post[d] * xst[first + count + d];
                    }
                    o[(p * nb + j) * npost + q] = x.data[at + pq];
                }
            }
        }
        final int[] shape = new int[before + bs.length + after];
        System.arraycopy(pre, 0, shape, 0, before);
        System.arraycopy(bs, 0, shape, before, bs.length);
        System.arraycopy(post, 0, shape, before + bs.length, after);
        return new Tensor(o, shape);
    }

    // ---- normalizations

    private static Object[] layerNorm(Tensor x, int[] normalized, Object w, Object b, double eps) {
        int inner = 1;
        for (int s : normalized) {
            inner *= s;
        }
        final int outer = x.numel() / Math.max(inner, 1);
        final double[] o = new double[x.numel()], mean = new double[outer], rstd = new double[outer];
        final Tensor wt = w == null ? null : tensor(w), bt = b == null ? null : tensor(b);
        for (int p = 0; p < outer; p++) {
            double m = 0;
            for (int i = 0; i < inner; i++) {
                m += x.data[p * inner + i];
            }
            m /= inner;
            double v = 0;
            for (int i = 0; i < inner; i++) {
                final double dlt = x.data[p * inner + i] - m;
                v += dlt * dlt;
            }
            v /= inner;
            final double r = 1.0 / Math.sqrt(v + eps);
            mean[p] = m;
            rstd[p] = r;
            for (int i = 0; i < inner; i++) {
                double y = (x.data[p * inner + i] - m) * r;
                if (wt != null) {
                    y *= wt.data[i];
                }
                if (bt != null) {
                    y += bt.data[i];
                }
                o[p * inner + i] = y;
            }
        }
        final int[] ks = x.shape.clone();
        for (int i = ks.length - normalized.length; i < ks.length; i++) {
            ks[i] = 1;
        }
        return new Object[] {new Tensor(o, x.shape.clone()), new Tensor(mean, ks), new Tensor(rstd, ks.clone())};
    }

    private static Object[] batchNorm(Tensor x, Object w, Object b, Tensor mean, Tensor var, double eps) {
        final int c = x.shape[1], inner = numel(Arrays.copyOfRange(x.shape, 2, x.rank())), n = x.shape[0];
        final double[] o = new double[x.numel()];
        final Tensor wt = w == null ? null : tensor(w), bt = b == null ? null : tensor(b);
        for (int s = 0; s < n; s++) {
            for (int ch = 0; ch < c; ch++) {
                final double sd = Math.sqrt(var.data[ch] + eps);
                for (int i = 0; i < inner; i++) {
                    final int at = (s * c + ch) * inner + i;
                    double y = (x.data[at] - mean.data[ch]) / sd;
                    if (wt != null) {
                        y *= wt.data[ch];
                    }
                    if (bt != null) {
                        y += bt.data[ch];
                    }
                    o[at] = y;
                }
            }
        }
        return new Object[] {new Tensor(o, x.shape.clone()), new Tensor(new double[0], 0), new Tensor(new double[0], 0)};
    }

    private static Tensor full(int[] shape, double v) {
        final double[] d = new double[numel(shape)];
        Arrays.fill(d, v);
        return new Tensor(d, shape);
    }

    private static Tensor arange(double start, double end, double step) {
        final int n = (int) Math.max(0, Math.ceil((end - start) / step));
        final double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = start + i * step;
        }
        return new Tensor(d, n);
    }

    /** A cast, rounding as PyTorch's own does: to float32 directly, to half and bfloat16 by way of float32. */
    private static Tensor cast(Tensor x, Object dtype) {
        final String t = dtype == null ? "" : dtype.toString();
        return switch (t) {
            case "bool" -> unary(x, v -> v != 0 ? 1 : 0);
            case "int64", "int32", "int16", "int8", "uint8" -> unary(x, v -> v < 0 ? Math.ceil(v) : Math.floor(v));
            case "float32" -> unary(x, v -> (double) (float) v);
            case "float16" -> unary(x, v -> Float.float16ToFloat(Float.floatToFloat16((float) v)));
            case "bfloat16" -> unary(x, NeuralGraph::bf16);
            default -> new Tensor(x.data.clone(), x.shape.clone());
        };
    }

    /** c10::BFloat16's rounding of a float: to nearest, ties to even, on the upper 16 bits. */
    static double bf16(double v) {
        final float f = (float) v;
        if (Float.isNaN(f)) {
            return Double.NaN;
        }
        final long u = Float.floatToRawIntBits(f) & 0xffffffffL;
        return Float.intBitsToFloat((int) (((u + ((u >>> 16) & 1) + 0x7fff) >>> 16) << 16));
    }

    /** numpy's (and Python's) modulo of floats: fmod, its sign made the divisor's. */
    static double mod(double u, double w) {
        double m = u % w;
        if (m != 0) {
            if ((w < 0) != (m < 0)) {
                m += w;
            }
        } else {
            m = Math.copySign(0.0, w);
        }
        return m;
    }

    private static Tensor roll(Tensor x, int[] shifts, int[] dims) {
        if (dims == null || dims.length == 0) {
            final int n = x.numel(), s = n == 0 ? 0 : ((shifts[0] % n) + n) % n;
            final double[] o = new double[n];
            for (int i = 0; i < n; i++) {
                o[(i + s) % n] = x.data[i];
            }
            return new Tensor(o, x.shape.clone());
        }
        Tensor t = x;
        for (int k = 0; k < dims.length; k++) {
            final Around a = Around.of(t.shape, dims[k]);
            final int s = a.n == 0 ? 0 : ((shifts[k] % a.n) + a.n) % a.n;
            final double[] o = new double[t.numel()];
            for (int p = 0; p < a.outer; p++) {
                for (int i = 0; i < a.n; i++) {
                    System.arraycopy(t.data, (p * a.n + i) * a.inner, o, (p * a.n + (i + s) % a.n) * a.inner, a.inner);
                }
            }
            t = new Tensor(o, t.shape.clone());
        }
        return t;
    }

    /** F.pad's constant padding: pairs from the last dimension back, a negative one cropping. */
    private static Tensor constantPad(Tensor x, int[] pad, double value) {
        final int rank = x.rank();
        final int[] lo = new int[rank], hi = new int[rank], shape = new int[rank];
        for (int i = 0; i < pad.length / 2; i++) {
            lo[rank - 1 - i] = pad[2 * i];
            hi[rank - 1 - i] = pad[2 * i + 1];
        }
        for (int d = 0; d < rank; d++) {
            shape[d] = x.shape[d] + lo[d] + hi[d];
        }
        final double[] o = new double[numel(shape)];
        Arrays.fill(o, value);
        final int[] ost = strides(shape), idx = new int[rank];
        for (int k = 0; k < x.data.length; k++) {
            int at = 0;
            boolean inside = true;
            for (int d = 0; d < rank; d++) {
                final int pos = idx[d] + lo[d];
                if (pos < 0 || pos >= shape[d]) {
                    inside = false;
                    break;
                }
                at += pos * ost[d];
            }
            if (inside) {
                o[at] = x.data[k];
            }
            for (int d = rank - 1; d >= 0; d--) {
                if (++idx[d] < x.shape[d]) {
                    break;
                }
                idx[d] = 0;
            }
        }
        return new Tensor(o, shape);
    }

    private static Tensor cumsum(Tensor x, int d) {
        final Around a = Around.of(x.shape, d);
        final double[] o = new double[x.numel()];
        for (int p = 0; p < a.outer; p++) {
            for (int q = 0; q < a.inner; q++) {
                double s = 0;
                for (int i = 0; i < a.n; i++) {
                    final int at = (p * a.n + i) * a.inner + q;
                    s += x.data[at];
                    o[at] = s;
                }
            }
        }
        return new Tensor(o, x.shape.clone());
    }

    private static Tensor flip(Tensor x, int[] dims) {
        Tensor t = x;
        for (int d : dims) {
            final Around a = Around.of(t.shape, d);
            final double[] o = new double[t.numel()];
            for (int p = 0; p < a.outer; p++) {
                for (int i = 0; i < a.n; i++) {
                    System.arraycopy(t.data, (p * a.n + i) * a.inner, o, (p * a.n + (a.n - 1 - i)) * a.inner, a.inner);
                }
            }
            t = new Tensor(o, t.shape.clone());
        }
        return t;
    }

    private static Tensor repeat(Tensor x, int[] reps) {
        Tensor t = x;
        if (reps.length > t.rank()) {
            final int[] s = new int[reps.length];
            Arrays.fill(s, 1);
            System.arraycopy(t.shape, 0, s, reps.length - t.rank(), t.rank());
            t = new Tensor(t.data, s);
        }
        for (int d = 0; d < reps.length; d++) {
            final List<Tensor> copies = new ArrayList<>();
            for (int r = 0; r < reps[d]; r++) {
                copies.add(t);
            }
            t = cat(copies, d);
        }
        return t;
    }

    private static Tensor var(Tensor x, int[] dims, double ddof, boolean keep) {
        final Tensor mean = reduce(x, dims, true, MEAN);
        final Tensor sq = binary(x, mean, (a, b) -> (a - b) * (a - b));
        final Tensor s = reduce(sq, dims, keep, SUM);
        final int count = x.numel() / Math.max(1, mean.numel());
        return unary(s, v -> v / (count - ddof));
    }

    // ------------------------------------------------------------------
    // The operations
    // ------------------------------------------------------------------

    static final Set<String> SUPPORTED = new HashSet<>(List.of(
        "_assert_tensor_metadata.default", "_assert_async.msg", "_assert_scalar.default", "_functional_assert_async.msg",
        "sym_constrain_range_for_size.default", "sym_constrain_range.default",
        "sym_size.int", "sym_numel.default", "py.mul", "py.add", "py.sub", "py.floordiv", "py.truediv", "py.neg", "py.mod",
        "py.pow", "py.getitem",
        "view.default", "_unsafe_view.default", "reshape.default", "permute.default", "expand.default", "clone.default",
        "alias.default", "detach.default", "lift_fresh_copy.default", "contiguous.default", "squeeze.dims", "squeeze.dim",
        "squeeze.default", "unsqueeze.default", "select.int", "slice.Tensor", "cat.default", "stack.default",
        "split_with_sizes.default", "split.Tensor", "unbind.int", "flip.default", "roll.default", "repeat.default",
        "constant_pad_nd.default", "_to_copy.default", "to.dtype",
        "full.default", "full_like.default", "zeros_like.default", "ones_like.default", "zeros.default", "ones.default",
        "empty.memory_format", "scalar_tensor.default", "arange.start_step", "arange.default", "arange.start",
        "mm.default", "addmm.default", "bmm.default", "matmul.default", "baddbmm.default", "dot.default",
        "relu.default", "gelu.default", "sigmoid.default", "tanh.default", "exp.default", "log.default", "sqrt.default",
        "rsqrt.default", "neg.default", "abs.default", "sin.default", "cos.default", "sinh.default", "cosh.default", "atan.default",
        "asin.default", "acos.default", "log1p.default", "expm1.default", "log2.default", "log10.default", "erf.default",
        "reciprocal.default", "sign.default", "floor.default", "ceil.default", "round.default", "trunc.default", "silu.default",
        "leaky_relu.default", "hardtanh.default", "elu.default", "softplus.default", "clamp.default", "clamp.Tensor",
        "clamp_min.default", "clamp_max.default", "pow.Tensor_Scalar", "pow.Tensor_Tensor", "pow.Scalar",
        "logical_not.default", "isnan.default", "isinf.default", "logical_xor.default",
        "add.Tensor", "add.Scalar", "sub.Tensor", "sub.Scalar", "rsub.Scalar", "rsub.Tensor", "mul.Tensor", "mul.Scalar",
        "div.Tensor", "div.Scalar", "div.Tensor_mode", "remainder.Scalar", "remainder.Tensor", "fmod.Scalar",
        "maximum.default", "minimum.default", "atan2.default",
        "eq.Scalar", "eq.Tensor", "ne.Scalar", "ne.Tensor", "lt.Scalar", "lt.Tensor", "le.Scalar", "le.Tensor", "gt.Scalar",
        "gt.Tensor", "ge.Scalar", "ge.Tensor", "logical_and.default", "logical_or.default", "where.self",
        "sum.dim_IntList", "sum.default", "mean.dim", "mean.default", "amax.default", "amin.default", "prod.dim_int",
        "max.dim", "min.dim", "max.default", "min.default", "argmax.default", "argmin.default", "var.correction", "any.dim",
        "any.default", "cumsum.default",
        "_softmax.default", "_log_softmax.default", "native_layer_norm.default", "_native_batch_norm_legit_no_training.default",
        "gather.default", "index_select.default", "embedding.default", "index.Tensor", "topk.default", "sort.default",
        "convolution.default", "max_pool2d_with_indices.default", "avg_pool2d.default", "_adaptive_avg_pool2d.default"));

    private static double sgn(double v) {
        return v > 0 ? 1 : v < 0 ? -1 : v;
    }

    @SuppressWarnings("unchecked")
    private static Object apply(String op, Object[] a) {
        switch (op) {
            case "_assert_tensor_metadata.default", "_assert_async.msg", "_assert_scalar.default", "_functional_assert_async.msg",
                 "sym_constrain_range_for_size.default", "sym_constrain_range.default" -> {
                return null;
            }
            case "sym_size.int" -> {
                final Tensor x = tensor(a[0]);
                return (long) x.shape[dim(lng(a[1]), x.rank())];
            }
            case "sym_numel.default" -> {
                return (long) tensor(a[0]).numel();
            }
            case "py.mul", "py.add", "py.sub", "py.floordiv", "py.truediv", "py.mod", "py.pow" -> {
                if (a[0] instanceof Long x && a[1] instanceof Long y && !op.equals("py.truediv")) {
                    return switch (op) {
                        case "py.mul" -> x * y;
                        case "py.add" -> x + y;
                        case "py.sub" -> x - y;
                        case "py.floordiv" -> Math.floorDiv(x, y);
                        case "py.mod" -> Math.floorMod(x, y);
                        default -> (long) Math.pow(x, y);
                    };
                }
                final double x = num(a[0]), y = num(a[1]);
                return switch (op) {
                    case "py.mul" -> x * y;
                    case "py.add" -> x + y;
                    case "py.sub" -> x - y;
                    case "py.floordiv" -> Math.floor(x / y);
                    case "py.mod" -> mod(x, y);
                    case "py.pow" -> Math.pow(x, y);
                    default -> x / y;
                };
            }
            case "py.neg" -> {
                return a[0] instanceof Long x ? (Object) (-x) : (Object) (-num(a[0]));
            }
            case "py.getitem" -> {
                final int i = (int) lng(a[1]);
                if (a[0] instanceof Object[] t) {
                    return t[i < 0 ? i + t.length : i];
                }
                final List<Object> l = (List<Object>) a[0];
                return l.get(i < 0 ? i + l.size() : i);
            }
            case "view.default", "_unsafe_view.default", "reshape.default" -> {
                return view(tensor(a[0]), shapeOf(a[1]));
            }
            case "permute.default" -> {
                final Tensor x = tensor(a[0]);
                return permute(x, dimsOf(a[1], x.rank()));
            }
            case "expand.default" -> {
                return expand(tensor(a[0]), shapeOf(a[1]));
            }
            case "clone.default", "lift_fresh_copy.default", "contiguous.default" -> {
                final Tensor x = tensor(a[0]);
                return new Tensor(x.data.clone(), x.shape.clone());
            }
            case "alias.default", "detach.default" -> {
                return a[0];
            }
            case "squeeze.dims", "squeeze.dim" -> {
                final Tensor x = tensor(a[0]);
                return squeeze(x, dimsOf(a[1], x.rank()));
            }
            case "squeeze.default" -> {
                return squeeze(tensor(a[0]), null);
            }
            case "unsqueeze.default" -> {
                return unsqueeze(tensor(a[0]), lng(a[1]));
            }
            case "select.int" -> {
                final Tensor x = tensor(a[0]);
                return select(x, dim(lng(a[1]), x.rank()), lng(a[2]));
            }
            case "slice.Tensor" -> {
                return slice(tensor(a[0]), a[1], a[2], a[3], a.length > 4 ? a[4] : null);
            }
            case "cat.default" -> {
                final List<Tensor> ts = ((List<Object>) a[0]).stream().map(NeuralGraph::tensor).toList();
                return cat(ts, a.length > 1 && a[1] != null ? (int) lng(a[1]) : 0);
            }
            case "stack.default" -> {
                final List<Tensor> ts = ((List<Object>) a[0]).stream().map(NeuralGraph::tensor).toList();
                final int d = dim(a.length > 1 && a[1] != null ? lng(a[1]) : 0, ts.get(0).rank() + 1);
                return cat(ts.stream().map(t -> unsqueeze(t, d)).toList(), d);
            }
            case "split_with_sizes.default", "split.Tensor" -> {
                final Tensor x = tensor(a[0]);
                final int d = dim(a.length > 2 && a[2] != null ? lng(a[2]) : 0, x.rank());
                final List<Integer> sizes = new ArrayList<>();
                if (op.equals("split.Tensor")) {
                    final int size = (int) lng(a[1]);
                    for (int at = 0; at < x.shape[d]; at += size) {
                        sizes.add(Math.min(size, x.shape[d] - at));
                    }
                } else {
                    for (int s : shapeOf(a[1])) {
                        sizes.add(s);
                    }
                }
                final List<Object> out = new ArrayList<>();
                int at = 0;
                for (int s : sizes) {
                    out.add(narrow(x, d, at, at + s, 1));
                    at += s;
                }
                return out;
            }
            case "unbind.int" -> {
                final Tensor x = tensor(a[0]);
                final int d = dim(a.length > 1 && a[1] != null ? lng(a[1]) : 0, x.rank());
                final List<Object> out = new ArrayList<>();
                for (int i = 0; i < x.shape[d]; i++) {
                    out.add(select(x, d, i));
                }
                return out;
            }
            case "flip.default" -> {
                final Tensor x = tensor(a[0]);
                return flip(x, dimsOf(a[1], x.rank()));
            }
            case "repeat.default" -> {
                return repeat(tensor(a[0]), shapeOf(a[1]));
            }
            case "roll.default" -> {
                final Tensor x = tensor(a[0]);
                final int[] dims = a.length > 2 && a[2] != null ? dimsOf(a[2], x.rank()) : null;
                return roll(x, shapeOf(a[1]), dims);
            }
            case "constant_pad_nd.default" -> {
                return constantPad(tensor(a[0]), shapeOf(a[1]), a.length > 2 && a[2] != null ? num(a[2]) : 0);
            }
            case "_to_copy.default", "to.dtype" -> {
                return cast(tensor(a[0]), a.length > 1 ? a[1] : null);
            }
            case "full.default" -> {
                return full(shapeOf(a[0]), num(a[1]));
            }
            case "full_like.default" -> {
                return full(tensor(a[0]).shape.clone(), num(a[1]));
            }
            case "zeros_like.default" -> {
                return full(tensor(a[0]).shape.clone(), 0);
            }
            case "ones_like.default" -> {
                return full(tensor(a[0]).shape.clone(), 1);
            }
            case "zeros.default", "empty.memory_format" -> {
                return full(shapeOf(a[0]), 0);
            }
            case "ones.default" -> {
                return full(shapeOf(a[0]), 1);
            }
            case "scalar_tensor.default" -> {
                return new Tensor(new double[] {num(a[0])});
            }
            case "arange.start_step" -> {
                return arange(num(a[0]), num(a[1]), a.length > 2 && a[2] != null ? num(a[2]) : 1);
            }
            case "arange.default" -> {
                return arange(0, num(a[0]), 1);
            }
            case "arange.start" -> {
                return arange(num(a[0]), num(a[1]), 1);
            }
            case "mm.default", "bmm.default", "matmul.default" -> {
                return matmul(tensor(a[0]), tensor(a[1]));
            }
            case "addmm.default", "baddbmm.default" -> {
                return scaleAdd(a[0], matmul(tensor(a[1]), tensor(a[2])), a.length > 3 ? a[3] : null, a.length > 4 ? a[4] : null);
            }
            case "dot.default" -> {
                final Tensor x = tensor(a[0]), y = tensor(a[1]);
                double s = 0;
                for (int i = 0; i < x.numel(); i++) {
                    s += x.data[i] * y.data[i];
                }
                return new Tensor(new double[] {s});
            }
            case "relu.default" -> {
                return unary(a[0], v -> Double.isNaN(v) ? v : Math.max(v, 0.0));
            }
            case "gelu.default" -> {
                final boolean tanh = "tanh".equals(a.length > 1 ? a[1] : null);
                final double c = Math.sqrt(2.0 / Math.PI), r2 = Math.sqrt(2.0);
                return unary(a[0], tanh ? v -> 0.5 * v * (1.0 + Math.tanh(c * (v + 0.044715 * v * v * v)))
                    : v -> 0.5 * v * (1.0 + Erf.erf(v / r2)));
            }
            case "sigmoid.default" -> {
                return unary(a[0], v -> 1.0 / (1.0 + Math.exp(-v)));
            }
            case "tanh.default" -> {
                return unary(a[0], Math::tanh);
            }
            case "exp.default" -> {
                return unary(a[0], Math::exp);
            }
            case "log.default" -> {
                return unary(a[0], Math::log);
            }
            case "sqrt.default" -> {
                return unary(a[0], Math::sqrt);
            }
            case "rsqrt.default" -> {
                return unary(a[0], v -> 1.0 / Math.sqrt(v));
            }
            case "neg.default" -> {
                return unary(a[0], v -> -v);
            }
            case "abs.default" -> {
                return unary(a[0], Math::abs);
            }
            case "sin.default" -> {
                return unary(a[0], Math::sin);
            }
            case "cos.default" -> {
                return unary(a[0], Math::cos);
            }
            case "sinh.default" -> {
                return unary(a[0], Math::sinh);
            }
            case "cosh.default" -> {
                return unary(a[0], Math::cosh);
            }
            case "atan.default" -> {
                return unary(a[0], Math::atan);
            }
            case "asin.default" -> {
                return unary(a[0], Math::asin);
            }
            case "acos.default" -> {
                return unary(a[0], Math::acos);
            }
            case "log1p.default" -> {
                return unary(a[0], Math::log1p);
            }
            case "expm1.default" -> {
                return unary(a[0], Math::expm1);
            }
            case "log2.default" -> {
                return unary(a[0], v -> Math.log(v) / Math.log(2.0));
            }
            case "log10.default" -> {
                return unary(a[0], Math::log10);
            }
            case "erf.default" -> {
                return unary(a[0], Erf::erf);
            }
            case "reciprocal.default" -> {
                return unary(a[0], v -> 1.0 / v);
            }
            case "sign.default" -> {
                return unary(a[0], NeuralGraph::sgn);
            }
            case "floor.default" -> {
                return unary(a[0], Math::floor);
            }
            case "ceil.default" -> {
                return unary(a[0], Math::ceil);
            }
            case "round.default" -> {
                return unary(a[0], Math::rint);
            }
            case "trunc.default" -> {
                return unary(a[0], v -> v < 0 ? Math.ceil(v) : Math.floor(v));
            }
            case "silu.default" -> {
                return unary(a[0], v -> v / (1.0 + Math.exp(-v)));
            }
            case "leaky_relu.default" -> {
                final double s = a.length > 1 && a[1] != null ? num(a[1]) : 0.01;
                return unary(a[0], v -> v > 0 ? v : s * v);
            }
            case "hardtanh.default" -> {
                final double lo = a.length > 1 && a[1] != null ? num(a[1]) : -1, hi = a.length > 2 && a[2] != null ? num(a[2]) : 1;
                return unary(a[0], v -> Math.min(Math.max(v, lo), hi));
            }
            case "elu.default" -> {
                final double al = a.length > 1 && a[1] != null ? num(a[1]) : 1, sc = a.length > 2 && a[2] != null ? num(a[2]) : 1;
                final double is = a.length > 3 && a[3] != null ? num(a[3]) : 1;
                return unary(a[0], v -> sc * (v > 0 ? v : al * (Math.exp(is * v) - 1.0)));
            }
            case "softplus.default" -> {
                final double be = a.length > 1 && a[1] != null ? num(a[1]) : 1, th = a.length > 2 && a[2] != null ? num(a[2]) : 20;
                return unary(a[0], v -> be * v > th ? v : Math.log1p(Math.exp(be * v)) / be);
            }
            case "clamp.default", "clamp.Tensor" -> {
                Tensor x = tensor(a[0]);
                if (a.length > 1 && a[1] != null) {
                    x = binary(x, a[1], (u, w) -> Double.isNaN(u) ? u : Math.max(u, w));
                }
                if (a.length > 2 && a[2] != null) {
                    x = binary(x, a[2], (u, w) -> Double.isNaN(u) ? u : Math.min(u, w));
                }
                return x;
            }
            case "clamp_min.default" -> {
                return binary(a[0], a[1], (u, w) -> Double.isNaN(u) ? u : Math.max(u, w));
            }
            case "clamp_max.default" -> {
                return binary(a[0], a[1], (u, w) -> Double.isNaN(u) ? u : Math.min(u, w));
            }
            case "pow.Tensor_Scalar", "pow.Tensor_Tensor", "pow.Scalar" -> {
                return binary(a[0], a[1], Math::pow);
            }
            case "logical_not.default" -> {
                return unary(a[0], v -> v == 0 ? 1 : 0);
            }
            case "isnan.default" -> {
                return unary(a[0], v -> Double.isNaN(v) ? 1 : 0);
            }
            case "isinf.default" -> {
                return unary(a[0], v -> Double.isInfinite(v) ? 1 : 0);
            }
            case "logical_xor.default" -> {
                return binary(a[0], a[1], (u, w) -> (u != 0) != (w != 0) ? 1 : 0);
            }
            case "add.Tensor", "add.Scalar" -> {
                final double al = a.length > 2 && a[2] != null ? num(a[2]) : 1;
                return binary(a[0], a[1], al == 1 ? Double::sum : (u, w) -> u + al * w);
            }
            case "sub.Tensor", "sub.Scalar" -> {
                final double al = a.length > 2 && a[2] != null ? num(a[2]) : 1;
                return binary(a[0], a[1], (u, w) -> u - al * w);
            }
            case "rsub.Scalar", "rsub.Tensor" -> {
                final double al = a.length > 2 && a[2] != null ? num(a[2]) : 1;
                return binary(a[0], a[1], (u, w) -> w - al * u);
            }
            case "mul.Tensor", "mul.Scalar" -> {
                return binary(a[0], a[1], (u, w) -> u * w);
            }
            case "div.Tensor", "div.Scalar" -> {
                return binary(a[0], a[1], (u, w) -> u / w);
            }
            case "div.Tensor_mode" -> {
                final Object mode = a.length > 2 ? a[2] : null;
                return binary(a[0], a[1], "floor".equals(mode) ? (u, w) -> Math.floor(u / w)
                    : "trunc".equals(mode) ? (u, w) -> { final double q = u / w; return q < 0 ? Math.ceil(q) : Math.floor(q); }
                    : (u, w) -> u / w);
            }
            case "remainder.Scalar", "remainder.Tensor" -> {
                return binary(a[0], a[1], NeuralGraph::mod);
            }
            case "fmod.Scalar" -> {
                return binary(a[0], a[1], (u, w) -> u % w);
            }
            case "maximum.default" -> {
                return binary(a[0], a[1], (u, w) -> Double.isNaN(u) || Double.isNaN(w) ? Double.NaN : Math.max(u, w));
            }
            case "minimum.default" -> {
                return binary(a[0], a[1], (u, w) -> Double.isNaN(u) || Double.isNaN(w) ? Double.NaN : Math.min(u, w));
            }
            case "atan2.default" -> {
                return binary(a[0], a[1], Math::atan2);
            }
            case "eq.Scalar", "eq.Tensor" -> {
                return binary(a[0], a[1], (u, w) -> u == w ? 1 : 0);
            }
            case "ne.Scalar", "ne.Tensor" -> {
                return binary(a[0], a[1], (u, w) -> u != w ? 1 : 0);
            }
            case "lt.Scalar", "lt.Tensor" -> {
                return binary(a[0], a[1], (u, w) -> u < w ? 1 : 0);
            }
            case "le.Scalar", "le.Tensor" -> {
                return binary(a[0], a[1], (u, w) -> u <= w ? 1 : 0);
            }
            case "gt.Scalar", "gt.Tensor" -> {
                return binary(a[0], a[1], (u, w) -> u > w ? 1 : 0);
            }
            case "ge.Scalar", "ge.Tensor" -> {
                return binary(a[0], a[1], (u, w) -> u >= w ? 1 : 0);
            }
            case "logical_and.default" -> {
                return binary(a[0], a[1], (u, w) -> u != 0 && w != 0 ? 1 : 0);
            }
            case "logical_or.default" -> {
                return binary(a[0], a[1], (u, w) -> u != 0 || w != 0 ? 1 : 0);
            }
            case "where.self" -> {
                final Tensor c = tensor(a[0]), x = tensor(a[1]), y = tensor(a[2]);
                final int[] shape = broadcast(broadcast(c.shape, x.shape), y.shape);
                final Tensor cb = broadcastTo(c, shape), xb = broadcastTo(x, shape), yb = broadcastTo(y, shape);
                final double[] o = new double[numel(shape)];
                for (int i = 0; i < o.length; i++) {
                    o[i] = cb.data[i] != 0 ? xb.data[i] : yb.data[i];
                }
                return new Tensor(o, shape);
            }
            case "sum.dim_IntList", "mean.dim", "amax.default", "amin.default" -> {
                final Tensor x = tensor(a[0]);
                final int kind = op.startsWith("sum") ? SUM : op.startsWith("mean") ? MEAN : op.startsWith("amax") ? MAX : MIN;
                return reduce(x, dimsOf(a.length > 1 ? a[1] : null, x.rank()), a.length > 2 && Boolean.TRUE.equals(a[2]), kind);
            }
            case "sum.default", "mean.default", "max.default", "min.default" -> {
                final int kind = op.startsWith("sum") ? SUM : op.startsWith("mean") ? MEAN : op.startsWith("max") ? MAX : MIN;
                return reduce(tensor(a[0]), null, false, kind);
            }
            case "prod.dim_int" -> {
                final Tensor x = tensor(a[0]);
                return reduce(x, dimsOf(a[1], x.rank()), a.length > 2 && Boolean.TRUE.equals(a[2]), PROD);
            }
            case "any.dim" -> {
                final Tensor x = tensor(a[0]);
                return reduce(x, dimsOf(a[1], x.rank()), a.length > 2 && Boolean.TRUE.equals(a[2]), ANY);
            }
            case "any.default" -> {
                return reduce(tensor(a[0]), null, false, ANY);
            }
            case "max.dim", "min.dim" -> {
                return argReduce(tensor(a[0]), a[1], a.length > 2 && Boolean.TRUE.equals(a[2]), op.startsWith("max"));
            }
            case "argmax.default", "argmin.default" -> {
                final Tensor x = tensor(a[0]);
                if (a.length < 2 || a[1] == null) {
                    return argReduce(new Tensor(x.data, x.numel()), 0L, false, op.startsWith("argmax"))[1];
                }
                return argReduce(x, a[1], a.length > 2 && Boolean.TRUE.equals(a[2]), op.startsWith("argmax"))[1];
            }
            case "var.correction" -> {
                final Tensor x = tensor(a[0]);
                final double ddof = a.length > 2 && a[2] != null ? num(a[2]) : 1;
                return var(x, dimsOf(a.length > 1 ? a[1] : null, x.rank()), ddof, a.length > 3 && Boolean.TRUE.equals(a[3]));
            }
            case "cumsum.default" -> {
                final Tensor x = tensor(a[0]);
                return cumsum(x, dim(lng(a[1]), x.rank()));
            }
            case "_softmax.default", "_log_softmax.default" -> {
                final Tensor x = tensor(a[0]);
                return softmax(x, dim(lng(a[1]), x.rank()), op.startsWith("_log"));
            }
            case "native_layer_norm.default" -> {
                return layerNorm(tensor(a[0]), shapeOf(a[1]), a[2], a[3], num(a[4]));
            }
            case "_native_batch_norm_legit_no_training.default" -> {
                return batchNorm(tensor(a[0]), a[1], a[2], tensor(a[3]), tensor(a[4]), num(a[6]));
            }
            case "gather.default" -> {
                final Tensor x = tensor(a[0]);
                return gather(x, dim(lng(a[1]), x.rank()), tensor(a[2]));
            }
            case "index_select.default" -> {
                final Tensor x = tensor(a[0]);
                return indexSelect(x, dim(lng(a[1]), x.rank()), tensor(a[2]));
            }
            case "embedding.default" -> {
                return indexSelect(tensor(a[0]), 0, tensor(a[1]));
            }
            case "index.Tensor" -> {
                return index(tensor(a[0]), (List<?>) a[1]);
            }
            case "topk.default" -> {
                final Tensor x = tensor(a[0]);
                final int d = dim(a.length > 2 && a[2] != null ? lng(a[2]) : -1, x.rank());
                return order(x, d, (int) lng(a[1]), a.length <= 3 || a[3] == null || Boolean.TRUE.equals(a[3]));
            }
            case "sort.default" -> {
                final Tensor x = tensor(a[0]);
                final int d = dim(a.length > 1 && a[1] != null ? lng(a[1]) : -1, x.rank());
                return order(x, d, x.shape[d], a.length > 2 && Boolean.TRUE.equals(a[2]));
            }
            case "convolution.default" -> {
                if (Boolean.TRUE.equals(a[6])) {
                    throw new GraphException("transposed convolutions");
                }
                return conv(tensor(a[0]), tensor(a[1]), a[2], shapeOf(a[3]), shapeOf(a[4]), shapeOf(a[5]), (int) lng(a[8]));
            }
            case "max_pool2d_with_indices.default" -> {
                if (a.length > 5 && Boolean.TRUE.equals(a[5])) {
                    throw new GraphException("pooling with ceil_mode");
                }
                final Tensor x = tensor(a[0]);
                return new Object[] {pool(x, shapeOf(a[1]), a.length > 2 && a[2] != null ? shapeOf(a[2]) : new int[0],
                    a.length > 3 && a[3] != null ? shapeOf(a[3]) : new int[] {0}, a.length > 4 && a[4] != null ? shapeOf(a[4])
                    : new int[] {1}, true, true, 0), new Tensor(new double[0], 0)};
            }
            case "avg_pool2d.default" -> {
                if (a.length > 4 && Boolean.TRUE.equals(a[4])) {
                    throw new GraphException("pooling with ceil_mode");
                }
                final boolean countPad = a.length <= 5 || a[5] == null || Boolean.TRUE.equals(a[5]);
                final double divisor = a.length > 6 && a[6] != null ? num(a[6]) : 0;
                return pool(tensor(a[0]), shapeOf(a[1]), a.length > 2 && a[2] != null ? shapeOf(a[2]) : new int[0],
                    a.length > 3 && a[3] != null ? shapeOf(a[3]) : new int[] {0}, new int[] {1}, false, countPad, divisor);
            }
            case "_adaptive_avg_pool2d.default" -> {
                final int[] out = shapeOf(a[1]);
                if (out.length != 2 || out[0] != 1 || out[1] != 1) {
                    throw new GraphException("adaptive pooling to " + Arrays.toString(out));
                }
                final Tensor x = tensor(a[0]);
                return reduce(x, new int[] {x.rank() - 2, x.rank() - 1}, true, MEAN);
            }
            default -> throw new GraphException("no operation " + op);
        }
    }

    // ------------------------------------------------------------------
    // erf, as fdlibm computes it (s_erf.c): what C libraries, hence numpy's math.erf, compute too
    // ------------------------------------------------------------------

    static final class Erf {
        private Erf() {
        }

        private static final double ERX = 8.45062911510467529297e-01, EFX = 1.28379167095512586316e-01,
            EFX8 = 1.02703333676410069053e+00,
            PP0 = 1.28379167095512558561e-01, PP1 = -3.25042107247001499370e-01, PP2 = -2.84817495755985104766e-02,
            PP3 = -5.77027029648944159157e-03, PP4 = -2.37630166566501626084e-05,
            QQ1 = 3.97917223959155352819e-01, QQ2 = 6.50222499887672944485e-02, QQ3 = 5.08130628187576562776e-03,
            QQ4 = 1.32494738004321644526e-04, QQ5 = -3.96022827877536812320e-06,
            PA0 = -2.36211856075265944077e-03, PA1 = 4.14856118683748331666e-01, PA2 = -3.72207876035701323847e-01,
            PA3 = 3.18346619901161753674e-01, PA4 = -1.10894694282396677476e-01, PA5 = 3.54783043256182359371e-02,
            PA6 = -2.16637559486879084300e-03,
            QA1 = 1.06420880400844228286e-01, QA2 = 5.40397917702171048937e-01, QA3 = 7.18286544141962662868e-02,
            QA4 = 1.26171219808761642112e-01, QA5 = 1.36370839120290507362e-02, QA6 = 1.19844998467991074170e-02,
            RA0 = -9.86494403484714822705e-03, RA1 = -6.93858572707181764372e-01, RA2 = -1.05586262253232909814e+01,
            RA3 = -6.23753324503260060396e+01, RA4 = -1.62396669462573470355e+02, RA5 = -1.84605092906711035994e+02,
            RA6 = -8.12874355063065934246e+01, RA7 = -9.81432934416914548592e+00,
            SA1 = 1.96512716674392571292e+01, SA2 = 1.37657754143519042600e+02, SA3 = 4.34565877475229228821e+02,
            SA4 = 6.45387271733267880336e+02, SA5 = 4.29008140027567833386e+02, SA6 = 1.08635005541779435134e+02,
            SA7 = 6.57024977031928170135e+00, SA8 = -6.04244152148580987438e-02,
            RB0 = -9.86494292470009928597e-03, RB1 = -7.99283237680523006574e-01, RB2 = -1.77579549177547519889e+01,
            RB3 = -1.60636384855821916062e+02, RB4 = -6.37566443368389627722e+02, RB5 = -1.02509513161107724954e+03,
            RB6 = -4.83519191608651397019e+02,
            SB1 = 3.03380607434824582924e+01, SB2 = 3.25792512996573918826e+02, SB3 = 1.53672958608443695994e+03,
            SB4 = 3.19985821950859553908e+03, SB5 = 2.55305040643316442583e+03, SB6 = 4.74528541206955367215e+02,
            SB7 = -2.24409524465858183362e+01,
            ERFC_SPLIT = Double.longBitsToDouble(0x4006DB6E00000000L);   // fdlibm's 1/0.35, by its high word

        static double erf(double x) {
            if (Double.isNaN(x)) {
                return x;
            }
            if (Double.isInfinite(x)) {
                return x > 0 ? 1 : -1;
            }
            final double ax = Math.abs(x);
            if (ax < 0.84375) {
                if (ax < 0x1.0p-28) {
                    return ax < 0x1.0p-1015 ? 0.125 * (8.0 * x + EFX8 * x) : x + EFX * x;
                }
                final double z = x * x;
                final double r = PP0 + z * (PP1 + z * (PP2 + z * (PP3 + z * PP4)));
                final double s = 1.0 + z * (QQ1 + z * (QQ2 + z * (QQ3 + z * (QQ4 + z * QQ5))));
                return x + x * (r / s);
            }
            if (ax < 1.25) {
                final double s = ax - 1.0;
                final double p = PA0 + s * (PA1 + s * (PA2 + s * (PA3 + s * (PA4 + s * (PA5 + s * PA6)))));
                final double q = 1.0 + s * (QA1 + s * (QA2 + s * (QA3 + s * (QA4 + s * (QA5 + s * QA6)))));
                return x >= 0 ? ERX + p / q : -ERX - p / q;
            }
            if (ax >= 6) {
                return x >= 0 ? 1.0 - 1e-300 : 1e-300 - 1.0;
            }
            final double s = 1.0 / (ax * ax);
            final double r, ss;
            if (ax < ERFC_SPLIT) {
                r = RA0 + s * (RA1 + s * (RA2 + s * (RA3 + s * (RA4 + s * (RA5 + s * (RA6 + s * RA7))))));
                ss = 1.0 + s * (SA1 + s * (SA2 + s * (SA3 + s * (SA4 + s * (SA5 + s * (SA6 + s * (SA7 + s * SA8)))))));
            } else {
                r = RB0 + s * (RB1 + s * (RB2 + s * (RB3 + s * (RB4 + s * (RB5 + s * RB6)))));
                ss = 1.0 + s * (SB1 + s * (SB2 + s * (SB3 + s * (SB4 + s * (SB5 + s * (SB6 + s * SB7))))));
            }
            final double z = Double.longBitsToDouble(Double.doubleToRawLongBits(ax) & 0xffffffff00000000L);
            final double rr = Math.exp(-z * z - 0.5625) * Math.exp((z - ax) * (z + ax) + r / ss);
            return x >= 0 ? 1.0 - rr / ax : rr / ax - 1.0;
        }
    }

    /** The graph's inputs and outputs of a reference sphere_graph wrote, against this runtime: the largest difference. */
    public double check(Path reference) throws IOException {
        try (Spx.Reader r = new Spx.Reader(reference)) {
            final Map<String, String> meta = r.meta();
            final List<Tensor> in = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                in.add(new Tensor(r.f64("in." + i), ints(meta.getOrDefault("Shape.in." + i, ""))));
            }
            final List<Tensor> out = run(in);
            double worst = 0;
            for (int i = 0; i < out.size(); i++) {
                final double[] ref = r.f64("out." + i);
                for (int k = 0; k < ref.length; k++) {
                    worst = Math.max(worst, Math.abs(out.get(i).data[k] - ref[k]));
                }
            }
            return worst;
        }
    }

    /** How this runtime agrees with numpy on a reference, and with itself however it is run. */
    public record Agreement(double vsNumpy, double chunked, double threaded, RunInfo chunks, RunInfo threads, double millis) {
        /** Whether chunks and threads changed nothing, to the bit. */
        public boolean bitwise() {
            return chunked == 0 && threaded == 0;
        }
    }

    /** The reference's inputs run whole, in chunks of a few rows' memory, and on four threads: each against numpy's outputs and the whole run. */
    public Agreement agree(Path reference) throws IOException {
        final List<Tensor> in = new ArrayList<>();
        final List<double[]> want = new ArrayList<>();
        try (Spx.Reader r = new Spx.Reader(reference)) {
            final Map<String, String> meta = r.meta();
            for (int i = 0; i < inputs.size(); i++) {
                in.add(new Tensor(r.f64("in." + i), ints(meta.getOrDefault("Shape.in." + i, ""))));
            }
            for (int i = 0; i < outputs.size(); i++) {
                want.add(r.f64("out." + i));
            }
        }
        final long t0 = System.nanoTime();
        final List<Tensor> whole = run(in, Double.POSITIVE_INFINITY, 1, null);
        final double ms = (System.nanoTime() - t0) / 1e6;
        double vs = 0;
        for (int i = 0; i < whole.size(); i++) {
            vs = Math.max(vs, gap(whole.get(i).data, want.get(i)));
        }
        final RunInfo c = new RunInfo(), t = new RunInfo();
        final List<Tensor> chunked = run(in, 3.5 * perRowPeak(), 1, c), threaded = run(in, Double.POSITIVE_INFINITY, 4, t);
        double dc = 0, dt = 0;
        for (int i = 0; i < whole.size(); i++) {
            dc = Math.max(dc, gap(whole.get(i).data, chunked.get(i).data));
            dt = Math.max(dt, gap(whole.get(i).data, threaded.get(i).data));
        }
        return new Agreement(vs, dc, dt, c, t, ms);
    }

    private static double gap(double[] a, double[] b) {
        if (a.length != b.length) {
            return Double.POSITIVE_INFINITY;
        }
        double d = 0;
        for (int k = 0; k < a.length; k++) {
            d = Math.max(d, Math.abs(a[k] - b[k]));
        }
        return d;
    }

    /** The inputs of a graph by their names, as a map (insertion order kept). */
    public static Map<String, Tensor> named(NeuralGraph g, List<Tensor> values) {
        final Map<String, Tensor> m = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            m.put(g.inputNames.get(i), values.get(i));
        }
        return m;
    }
}
