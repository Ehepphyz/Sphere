package com.sphere.core.ml;

import com.sphere.core.ml.NeuralGraph.GraphException;
import com.sphere.core.ml.NeuralGraph.Node;
import com.sphere.core.ml.NeuralGraph.Ref;
import com.sphere.core.ml.NeuralGraph.Tensor;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
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
import java.util.TreeMap;
import java.util.function.Consumer;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;

/**
 * A graph compiled to the JVM's bytecode: an XLA in Java, with nothing but the JDK (java.lang.classfile, Java 24+).
 *
 * <p>The graph is specialized to the shapes of one batch: run once by the interpreter on inputs of those shapes, every
 * size it computes becomes a constant, and what depends on the weights alone -- a Linear's weight transposed -- is
 * computed then and kept (folding; off for a graph being trained, whose weights move). What remains is lowered:
 * <ul>
 * <li>the views (permute, expand, select, slice, squeeze, unsqueeze, view, the identities) become affine maps into
 * their storage -- offsets and strides -- and copy nothing;</li>
 * <li>the elementwise operations are fused: a chain over one shape is one loop nest, its operands read through their
 * views (broadcasting by zero strides), each intermediate value a local variable, only what is read elsewhere
 * written;</li>
 * <li>mm, bmm, matmul become a product kernel over strided operands, addmm and baddbmm that product and their bias as
 * one more elementwise operation -- fused with the activation that follows;</li>
 * <li>any other operation is the interpreter's own kernel, called on its arrays (a fallback, never a failure).</li>
 * </ul>
 * The buffers are planned from the values' lifetimes, reused, and allocated once per thread: a run allocates nothing
 * but its fallbacks' results. The kernels' arithmetic is the interpreter's -- the same scalar functions
 * ({@link GraphKernels}, copied from its lambdas), the same orders of summation -- so a compiled graph gives the
 * interpreter's results to the bit, which {@link CompiledGraph#check} holds it to.
 */
public final class GraphCompiler {

    private GraphCompiler() {
    }

    /** What the compiler leaves to the interpreter, and why. */
    public static class NotCompiled extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotCompiled(String m) {
            super(m);
        }
    }

    /** A transcription found wrong after it was written: for those that check one from outside this package. */
    public static final class NotCompiledReport extends NotCompiled {
        private static final long serialVersionUID = 1L;

        public NotCompiledReport(String m) {
            super(m);
        }
    }

    // ------------------------------------------------------------------
    // Storages and views
    // ------------------------------------------------------------------

    static final int INPUT = 0, CONST = 1, KERNEL = 2, DYNAMIC = 3;

    /** An array of the compiled program: an input, a constant, a kernel's output (planned, reused), a fallback's. */
    static final class Storage {
        final int id, kind, size;
        double[] constant;
        int input = -1, first = Integer.MAX_VALUE, last = -1, phys = -1, slot = -1;
        boolean pinned;
        Storage alias;   // union-find: what a fallback's output may share with its inputs

        Storage(int id, int kind, int size) {
            this.id = id;
            this.kind = kind;
            this.size = size;
        }

        Storage root() {
            Storage s = this;
            while (s.alias != null) {
                s = s.alias;
            }
            return s;
        }
    }

    /** A tensor as an affine map into a storage: element (i_0..i_r) at offset + sum_d i_d strides_d. */
    record View(Storage storage, int offset, int[] shape, int[] strides) {
        static View contiguous(Storage s, int[] shape) {
            return new View(s, 0, shape.clone(), NeuralGraph.strides(shape));
        }

        boolean contiguous() {
            final int[] c = NeuralGraph.strides(shape);
            for (int d = 0; d < shape.length; d++) {
                if (shape[d] != 1 && strides[d] != c[d]) {
                    return false;
                }
            }
            return true;
        }

        /** The whole storage in order: what the interpreter's kernels can take as it is. */
        boolean plain() {
            return offset == 0 && contiguous() && NeuralGraph.numel(shape) == storage.size;
        }

        /** The strides that read this view at each index of an iteration space it broadcasts to. */
        int[] stridesFor(int[] space) {
            final int[] out = new int[space.length];
            for (int d = 0; d < space.length; d++) {
                final int j = d - (space.length - shape.length);
                out[d] = j >= 0 && shape[j] == space[d] && shape[j] != 1 ? strides[j] : 0;
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Instructions: fused elementwise groups, products, fallbacks
    // ------------------------------------------------------------------

    /** An argument of a micro-operation: operand k (read through its view), a constant, or micro k's value. */
    record Arg(int kind, int index, double value) {
        static final int OPERAND = 0, CONSTANT = 1, MICRO = 2;
    }

    /** One scalar function of GraphKernels applied per element. */
    record Micro(String method, List<Arg> args, double[] consts) {
    }

    /** A reduction computed in a group's loop: its value accumulated as the loop reaches it, in the interpreter's order. */
    record Reduction(int micro, int kind, boolean[] reduced, int[] kshape, Storage out, int count) {
    }

    /** The element of a tuple a fused node gives (batch norm's output): a member of the group under this name. */
    record MemberRef(String name) {
    }

    /** Elementwise nodes over one shape, fused into one loop nest. */
    static final class Group {
        final int[] shape;
        final List<View> operands = new ArrayList<>();
        final List<Micro> micros = new ArrayList<>();
        final Map<String, Integer> members = new LinkedHashMap<>();
        final Set<Integer> nodes = new HashSet<>();
        final List<Object[]> stored = new ArrayList<>();   // {micro index, View}
        final List<Reduction> reductions = new ArrayList<>();
        final Set<String> produced = new HashSet<>();      // the reductions' values: ready when the group has run

        Group(int[] shape) {
            this.shape = shape.clone();
        }

        int operand(View v) {
            for (int i = 0; i < operands.size(); i++) {
                final View o = operands.get(i);
                if (o.storage == v.storage && o.offset == v.offset && Arrays.equals(o.shape, v.shape) && Arrays.equals(o.strides, v.strides)) {
                    return i;
                }
            }
            operands.add(v);
            return operands.size() - 1;
        }
    }

    /** A product C[b, i, j] = sum_p A[b, i, p] B[b, p, j] over strided operands, b the broadcast batch. */
    record Matmul(View a, View b, Storage out, int[] batch, int n, int k, int m, int[] sa, int[] sb, int sai, int sap,
                  int sbp, int sbj) {
    }

    /** A tensor argument of a fallback: a storage and its shape (a plain view). */
    record Binding(Storage storage, int[] shape) {
    }

    /** An operation the interpreter runs on the program's arrays. */
    static final class Fallback {
        final String op;
        final Object[] template;
        final Object[] bindings;
        final Storage[] outs;
        final int kind;   // 0 a tensor, 1 a tuple, 2 a list

        Fallback(String op, Object[] template, Object[] bindings, Storage[] outs, int kind) {
            this.op = op;
            this.template = template;
            this.bindings = bindings;
            this.outs = outs;
            this.kind = kind;
        }

        private static Object bind(Object b, double[][] s) {
            if (b instanceof Binding x) {
                return new Tensor(s[x.storage.slot], x.shape);
            }
            if (b instanceof List<?> l) {
                final List<Object> out = new ArrayList<>(l.size());
                for (Object e : l) {
                    out.add(bind(e, s));
                }
                return out;
            }
            return b;
        }

        void exec(double[][] s) {
            final Object[] a = template.clone();
            for (int p = 0; p < a.length; p++) {
                if (bindings[p] != null) {
                    a[p] = bind(bindings[p], s);
                }
            }
            final Object v = NeuralGraph.apply(op, a);
            if (kind == 0) {
                s[outs[0].slot] = NeuralGraph.tensor(v).data;
                return;
            }
            final List<?> items = v instanceof Object[] t ? Arrays.asList(t) : (List<?>) v;
            for (int i = 0; i < outs.length && i < items.size(); i++) {
                if (outs[i] != null && items.get(i) instanceof Tensor t) {
                    s[outs[i].slot] = t.data;
                }
            }
        }
    }

    /** softmax or log_softmax along a dim of a contiguous array (outer x n x inner), as NeuralGraph.softmax. */
    record Softmax(Storage in, Storage out, int outer, int n, int inner, boolean log) {
    }

    /** native_layer_norm over the last dims (rows of inner values), as NeuralGraph.layerNorm: out, mean, rstd. */
    record LayerNorm(Storage in, Storage w, Storage b, Storage out, Storage mean, Storage rstd, int outer, int inner, double eps) {
    }

    /** convolution (not transposed) of a contiguous x [N, Cin, in...] by w [Cout, Cin/groups, k...], as NeuralGraph.conv. */
    record Conv(Storage x, Storage w, Storage bias, Storage out, int n, int cin, int cout, int cg, int og, int[] in, int[] ks, int[] out_,
                int[] st, int[] pd, int[] dl) {
    }

    /** 2-d pooling of a contiguous x [planes, H, W], max or average, as NeuralGraph.pool. */
    record Pool(Storage x, Storage out, int planes, int[] in, int[] out_, int[] kernel, int[] stride, int[] pad, int[] dil, boolean max,
                boolean countPad, double divisor) {
    }

    static final class Instr {
        final Group group;
        final Matmul mm;
        final Fallback fb;
        Softmax sm;
        LayerNorm ln;
        Conv cv;
        Pool pl;
        final List<Storage> reads = new ArrayList<>(), writes = new ArrayList<>();
        int fallbackIndex = -1;

        Instr(Group g, Matmul m, Fallback f) {
            group = g;
            mm = m;
            fb = f;
        }
    }

    // ------------------------------------------------------------------
    // Elementwise operations: their scalar functions and constants
    // ------------------------------------------------------------------

    /** One micro-operation of a node: the function, its tensor operands' argument positions (or -1: the previous micro's value), its constants. */
    record Spec(String method, int[] from, double[] consts) {
    }

    private static double opt(Object[] a, int i, double fallback) {
        return a.length > i && a[i] != null ? NeuralGraph.num(a[i]) : fallback;
    }

    private static final Map<String, String> UNARY = new HashMap<>();
    private static final Map<String, String> BINARY = new HashMap<>();

    static {
        for (String[] p : new String[][] {{"relu", "relu"}, {"sigmoid", "sigmoid"}, {"tanh", "tanh"}, {"exp", "exp"}, {"log", "log"},
            {"sqrt", "sqrt"}, {"rsqrt", "rsqrt"}, {"neg", "neg"}, {"abs", "abs"}, {"sin", "sin"}, {"cos", "cos"}, {"sinh", "sinh"},
            {"cosh", "cosh"}, {"atan", "atan"}, {"asin", "asin"}, {"acos", "acos"}, {"log1p", "log1p"}, {"expm1", "expm1"},
            {"log2", "log2"}, {"log10", "log10"}, {"erf", "erf"}, {"reciprocal", "reciprocal"}, {"sign", "sign"}, {"floor", "floor"},
            {"ceil", "ceil"}, {"round", "round"}, {"trunc", "trunc"}, {"silu", "silu"}, {"logical_not", "logicalNot"},
            {"isnan", "isnan"}, {"isinf", "isinf"}}) {
            UNARY.put(p[0] + ".default", p[1]);
        }
        for (String[] p : new String[][] {{"mul.Tensor", "mul"}, {"mul.Scalar", "mul"}, {"div.Tensor", "div"}, {"div.Scalar", "div"},
            {"remainder.Scalar", "remainder"}, {"remainder.Tensor", "remainder"}, {"fmod.Scalar", "fmod"},
            {"maximum.default", "maximum"}, {"minimum.default", "minimum"}, {"atan2.default", "atan2"},
            {"eq.Scalar", "eq"}, {"eq.Tensor", "eq"}, {"ne.Scalar", "ne"}, {"ne.Tensor", "ne"}, {"lt.Scalar", "lt"}, {"lt.Tensor", "lt"},
            {"le.Scalar", "le"}, {"le.Tensor", "le"}, {"gt.Scalar", "gt"}, {"gt.Tensor", "gt"}, {"ge.Scalar", "ge"}, {"ge.Tensor", "ge"},
            {"logical_and.default", "logicalAnd"}, {"logical_or.default", "logicalOr"}, {"logical_xor.default", "logicalXor"},
            {"clamp_min.default", "clampMin"}, {"clamp_max.default", "clampMax"}, {"pow.Tensor_Scalar", "pow"},
            {"pow.Tensor_Tensor", "pow"}, {"pow.Scalar", "pow"}}) {
            BINARY.put(p[0], p[1]);
        }
    }

    /** The micro-operations of an elementwise node, or null if the node is not elementwise. */
    static List<Spec> elementwise(String op, Object[] a) {
        if (UNARY.containsKey(op)) {
            return List.of(new Spec(UNARY.get(op), new int[] {0}, new double[0]));
        }
        if (BINARY.containsKey(op)) {
            return List.of(new Spec(BINARY.get(op), new int[] {0, 1}, new double[0]));
        }
        return switch (op) {
            case "gelu.default" -> List.of(new Spec("tanh".equals(a.length > 1 ? a[1] : null) ? "geluTanh" : "gelu", new int[] {0},
                new double[0]));
            case "leaky_relu.default" -> List.of(new Spec("leakyRelu", new int[] {0}, new double[] {opt(a, 1, 0.01)}));
            case "hardtanh.default" -> List.of(new Spec("hardtanh", new int[] {0}, new double[] {opt(a, 1, -1), opt(a, 2, 1)}));
            case "elu.default" -> List.of(new Spec("elu", new int[] {0}, new double[] {opt(a, 1, 1), opt(a, 2, 1), opt(a, 3, 1)}));
            case "softplus.default" -> List.of(new Spec("softplus", new int[] {0}, new double[] {opt(a, 1, 1), opt(a, 2, 20)}));
            case "add.Tensor", "add.Scalar" -> {
                final double al = opt(a, 2, 1);
                yield List.of(al == 1 ? new Spec("add", new int[] {0, 1}, new double[0]) : new Spec("addAlpha", new int[] {0, 1},
                    new double[] {al}));
            }
            case "sub.Tensor", "sub.Scalar" -> List.of(new Spec("sub", new int[] {0, 1}, new double[] {opt(a, 2, 1)}));
            case "rsub.Scalar", "rsub.Tensor" -> List.of(new Spec("rsub", new int[] {0, 1}, new double[] {opt(a, 2, 1)}));
            case "div.Tensor_mode" -> {
                final Object mode = a.length > 2 ? a[2] : null;
                yield List.of(new Spec("floor".equals(mode) ? "divFloor" : "trunc".equals(mode) ? "divTrunc" : "div", new int[] {0, 1},
                    new double[0]));
            }
            case "where.self" -> List.of(new Spec("where", new int[] {0, 1, 2}, new double[0]));
            case "clamp.default", "clamp.Tensor" -> {
                final List<Spec> chain = new ArrayList<>();
                if (a.length > 1 && a[1] != null) {
                    chain.add(new Spec("clampMin", new int[] {0, 1}, new double[0]));
                }
                if (a.length > 2 && a[2] != null) {
                    chain.add(new Spec("clampMax", new int[] {chain.isEmpty() ? 0 : -1, 2}, new double[0]));
                }
                yield chain.isEmpty() ? null : chain;
            }
            case "_to_copy.default", "to.dtype" -> {
                final String t = a.length > 1 && a[1] != null ? a[1].toString() : "";
                final String m = switch (t) {
                    case "bool" -> "toBool";
                    case "int64", "int32", "int16", "int8", "uint8" -> "toInt";
                    case "float32" -> "toFloat32";
                    case "float16" -> "toFloat16";
                    case "bfloat16" -> "toBFloat16";
                    default -> null;
                };
                yield m == null ? null : List.of(new Spec(m, new int[] {0}, new double[0]));
            }
            default -> null;
        };
    }

    /** Operations that are views: no computation, an affine map of their input. */
    static boolean isView(String op, Object[] a) {
        switch (op) {
            case "alias.default", "detach.default", "clone.default", "lift_fresh_copy.default", "contiguous.default", "view.default",
                 "_unsafe_view.default", "reshape.default", "permute.default", "expand.default", "squeeze.dims", "squeeze.dim",
                 "squeeze.default", "unsqueeze.default", "select.int", "slice.Tensor" -> {
                return true;
            }
            case "_to_copy.default", "to.dtype" -> {
                return elementwise(op, a) == null;   // to float64 (or no dtype): the interpreter's copy
            }
            case "clamp.default", "clamp.Tensor" -> {
                return !(a.length > 1 && a[1] != null) && !(a.length > 2 && a[2] != null);
            }
            default -> {
                return false;
            }
        }
    }

    /** Operations computed from their inputs' shapes alone: constants of a specialization. */
    private static final Set<String> SHAPE_ONLY = Set.of("sym_size.int", "sym_numel.default", "full_like.default", "zeros_like.default",
        "ones_like.default", "_assert_tensor_metadata.default", "_assert_async.msg", "_assert_scalar.default",
        "_functional_assert_async.msg", "sym_constrain_range_for_size.default", "sym_constrain_range.default");

    private static final Set<String> PRODUCTS = Set.of("mm.default", "bmm.default", "matmul.default", "addmm.default", "baddbmm.default");

    static final int SUM = 0, MEAN = 1, MAX = 2, MIN = 3;

    /** The reductions a fused loop accumulates, by kind. */
    private static final Map<String, Integer> REDUCTIONS = Map.of("sum.dim_IntList", SUM, "sum.default", SUM, "mean.dim", MEAN,
        "mean.default", MEAN, "amax.default", MAX, "amin.default", MIN, "max.default", MAX, "min.default", MIN);

    // ------------------------------------------------------------------
    // The compiler
    // ------------------------------------------------------------------

    /** The graph compiled for inputs whose first dimension is batch; fold computes its weights-only values once. */
    public static CompiledGraph compile(NeuralGraph g, int batch, boolean fold) {
        final int[][] ex = g.exampleShapes();
        if (ex.length != g.inputs.size()) {
            throw new NotCompiled(g.name + " has no example shapes to specialize on");
        }
        final List<Tensor> dummy = new ArrayList<>();
        for (int[] e : ex) {
            final int[] s = e.clone();
            if (s.length > 0) {
                s[0] = batch;
            }
            final double[] d = new double[NeuralGraph.numel(s)];
            Arrays.fill(d, 0.5);
            dummy.add(new Tensor(d, s));
        }
        return compile(g, dummy, fold);
    }

    /**
     * The graph compiled for inputs of these shapes, the interpreter run once on these values to find the sizes and
     * the constants: real inputs where the graph computes indices from its data (an embedding's), any otherwise.
     */
    public static CompiledGraph compile(NeuralGraph g, List<Tensor> example, boolean fold) {
        final long t0 = System.nanoTime();
        if (!g.blocks.isEmpty()) {
            throw new NotCompiled(g.name + " has loops or conditions: the interpreter runs it");
        }
        for (Node nd : g.nodes) {
            if (NeuralGraph.CONTROL.contains(nd.op())) {
                throw new NotCompiled(g.name + " has loops or conditions: the interpreter runs it");
            }
        }
        if (example.size() != g.inputs.size()) {
            throw new NotCompiled(g.name + " takes " + g.inputs.size() + " inputs, not " + example.size());
        }
        final int[][] inShapes = new int[example.size()][];
        for (int k = 0; k < example.size(); k++) {
            inShapes[k] = example.get(k).shape.clone();
        }
        final GraphAutograd.Frame frame;
        try {
            frame = new GraphAutograd(g, List.of()).forward(example, false);
        } catch (RuntimeException e) {
            throw new NotCompiled("the interpreter could not run " + g.name + " on these inputs to specialize it: " + e.getMessage());
        }
        final Builder b = new Builder(g, fold, frame, inShapes);
        b.build();
        final int batch = inShapes.length > 0 && inShapes[0].length > 0 ? inShapes[0][0] : 1;
        return b.finish(batch, inShapes, (System.nanoTime() - t0) / 1e6);
    }

    private static final class Builder {
        static final int STATIC = 0, WEIGHT = 1, RUNTIME = 2;
        final NeuralGraph g;
        final boolean fold;
        final GraphAutograd.Frame frame;
        final int[][] inShapes;
        final Map<String, Integer> kind = new HashMap<>();
        final Map<String, Object> value = new HashMap<>();   // runtime values: View, or Object[] / List of View
        final Map<String, Storage> constStorage = new HashMap<>();
        final IdentityHashMap<double[], Storage> constByArray = new IdentityHashMap<>();
        final Map<String, List<Integer>> users = new HashMap<>();
        final Set<String> outputs;
        final List<Storage> storages = new ArrayList<>();
        final List<Instr> instrs = new ArrayList<>();
        final Map<String, Integer> folded = new TreeMap<>(), fallbacks = new TreeMap<>();
        int views, fusedNodes, matmuls, nodes, softmaxes, norms, convs;
        Group open;

        Builder(NeuralGraph g, boolean fold, GraphAutograd.Frame frame, int[][] inShapes) {
            this.g = g;
            this.fold = fold;
            this.frame = frame;
            this.inShapes = inShapes;
            this.outputs = new HashSet<>(g.outputs);
        }

        Storage storage(int kind, int size) {
            final Storage s = new Storage(storages.size(), kind, size);
            storages.add(s);
            return s;
        }

        int kindOf(String name) {
            final Integer k = kind.get(name);
            if (k != null) {
                return k;
            }
            return g.consts.containsKey(name) ? WEIGHT : RUNTIME;
        }

        /** A constant array as a storage: once per array (a weight read by two nodes is one storage). */
        Storage constant(double[] data) {
            return constByArray.computeIfAbsent(data, d -> {
                final Storage s = storage(CONST, d.length);
                s.constant = d;
                return s;
            });
        }

        boolean member(String name) {
            return open != null && (open.members.containsKey(name) || open.produced.contains(name));
        }

        /** The view of a value that is not a member of the open group. */
        View view(String name) {
            final Object v = value.get(name);
            if (v instanceof View x) {
                return x;
            }
            final Object rec = frame.env.get(name);
            if (rec instanceof Tensor t && (kindOf(name) != RUNTIME)) {
                return View.contiguous(constant(t.data), t.shape);
            }
            throw new NotCompiled("the value " + name + " (" + (rec == null ? "none" : rec.getClass().getSimpleName()) + ") as a tensor");
        }

        void build() {
            for (int i = 0; i < g.inputs.size(); i++) {
                final Storage s = storage(INPUT, NeuralGraph.numel(inShapes[i]));
                s.input = i;
                value.put(g.inputs.get(i), View.contiguous(s, inShapes[i]));
                kind.put(g.inputs.get(i), RUNTIME);
            }
            final List<GraphAutograd.Step> steps = frame.steps;
            for (int t = 0; t < steps.size(); t++) {
                for (String r : steps.get(t).node().refs()) {
                    users.computeIfAbsent(r, k -> new ArrayList<>()).add(t);
                }
            }
            for (int t = 0; t < steps.size(); t++) {
                final GraphAutograd.Step st = steps.get(t);
                final Node nd = st.node();
                nodes++;
                int k = STATIC;
                if (!SHAPE_ONLY.contains(nd.op())) {
                    for (String r : nd.refs()) {
                        k = Math.max(k, kindOf(r));
                    }
                }
                if (k == STATIC || k == WEIGHT && fold) {
                    kind.put(nd.name(), k);
                    if (k == WEIGHT) {
                        folded.merge(nd.op(), 1, Integer::sum);
                    }
                    continue;
                }
                kind.put(nd.name(), RUNTIME);
                if (st.out() instanceof Number) {
                    throw new NotCompiled(nd.name() + " (" + nd.op() + ") computes a number from the data");
                }
                emit(t, st);
            }
            close();
        }

        void emit(int t, GraphAutograd.Step st) {
            final Node nd = st.node();
            final String op = nd.op();
            final Object[] a = st.args();
            if (op.equals("py.getitem")) {
                final String c = ((Ref) nd.args()[0]).name();
                if (member(c)) {
                    close();
                }
                final Object cv = value.get(c);
                final int i = (int) NeuralGraph.lng(a[1]);
                final Object item;
                if (cv instanceof Object[] tuple) {
                    item = tuple[i < 0 ? i + tuple.length : i];
                } else if (cv instanceof List<?> l) {
                    item = l.get(i < 0 ? i + l.size() : i);
                } else {
                    throw new NotCompiled("an item of " + c);
                }
                if (item instanceof MemberRef mr) {
                    if (open != null && open.members.containsKey(mr.name)) {
                        open.members.put(nd.name(), open.members.get(mr.name));   // the same value, read in the loop
                        open.nodes.add(t);
                    } else {
                        value.put(nd.name(), value.get(mr.name));
                    }
                } else {
                    value.put(nd.name(), item);
                }
                return;
            }
            if (REDUCTIONS.containsKey(op)) {
                reduction(t, nd, a, st.out());
                return;
            }
            if (op.equals("_native_batch_norm_legit_no_training.default")) {
                batchNorm(t, nd, a, st.out());
                return;
            }
            if (isView(op, a)) {
                final String src = ((Ref) nd.args()[0]).name();
                if (member(src)) {
                    close();
                }
                View v = view(src);
                View out = derive(op, a, v, NeuralGraph.tensor(st.out()).shape);
                if (out == null) {
                    v = materialize(v);
                    out = derive(op, a, v, NeuralGraph.tensor(st.out()).shape);
                }
                value.put(nd.name(), out);
                views++;
                return;
            }
            final List<Spec> specs = elementwise(op, a);
            if (specs != null) {
                final List<Object> operands = new ArrayList<>();
                for (int p = 0; p < nd.args().length; p++) {
                    operands.add(nd.args()[p] instanceof Ref r ? r.name() : a[p]);
                }
                fuse(t, nd.name(), NeuralGraph.tensor(st.out()).shape, specs, operands);
                fusedNodes++;
                return;
            }
            // what reads the open group's values needs them written first
            for (String r : nd.refs()) {
                if (member(r)) {
                    close();
                    break;
                }
            }
            if (PRODUCTS.contains(op)) {
                product(t, nd, a, st.out());
                return;
            }
            if (op.equals("_softmax.default") || op.equals("_log_softmax.default")) {
                softmax(nd, a, st.out());
                return;
            }
            if (op.equals("native_layer_norm.default")) {
                layerNorm(nd, a, st.out());
                return;
            }
            if (op.equals("convolution.default") && !Boolean.TRUE.equals(a[6])) {
                conv(nd, a, st.out());
                return;
            }
            if (op.equals("max_pool2d_with_indices.default") || op.equals("avg_pool2d.default")) {
                pool(nd, a, st.out());
                return;
            }
            fallback(nd, a, st.out());
        }

        void conv(Node nd, Object[] a, Object out) {
            final View x = plainView(((Ref) nd.args()[0]).name()), w = plainView(((Ref) nd.args()[1]).name());
            final View b = a[2] == null ? null : plainView(((Ref) nd.args()[2]).name());
            final int nd2 = w.shape.length - 2;
            final int[] in = Arrays.copyOfRange(x.shape, 2, x.shape.length), ks = Arrays.copyOfRange(w.shape, 2, w.shape.length);
            final int[] st = NeuralGraph.rep(NeuralGraph.shapeOf(a[3]), nd2), pd = NeuralGraph.rep(NeuralGraph.shapeOf(a[4]), nd2);
            final int[] dl = NeuralGraph.rep(NeuralGraph.shapeOf(a[5]), nd2);
            final int groups = (int) NeuralGraph.lng(a[8]);
            final int[] outShape = NeuralGraph.tensor(out).shape;
            final int[] o = Arrays.copyOfRange(outShape, 2, outShape.length);
            final Storage s = storage(KERNEL, NeuralGraph.numel(outShape));
            final Instr ins = new Instr(null, null, null);
            ins.cv = new Conv(x.storage, w.storage, b == null ? null : b.storage, s, x.shape[0], x.shape[1], w.shape[0], w.shape[1],
                w.shape[0] / groups, in, ks, o, st, pd, dl);
            ins.reads.add(x.storage);
            ins.reads.add(w.storage);
            if (b != null) {
                ins.reads.add(b.storage);
            }
            ins.writes.add(s);
            instrs.add(ins);
            value.put(nd.name(), View.contiguous(s, outShape));
            convs++;
        }

        void pool(Node nd, Object[] a, Object out) {
            final boolean max = nd.op().startsWith("max");
            final View x = plainView(((Ref) nd.args()[0]).name());
            final int lead = x.shape.length - 2;
            final int[] kernel = NeuralGraph.rep(NeuralGraph.shapeOf(a[1]), 2);
            final int[] stride0 = a.length > 2 && a[2] != null ? NeuralGraph.shapeOf(a[2]) : new int[0];
            final int[] stride = NeuralGraph.rep(stride0.length == 0 ? kernel : stride0, 2);
            final int[] pad = NeuralGraph.rep(a.length > 3 && a[3] != null ? NeuralGraph.shapeOf(a[3]) : new int[] {0}, 2);
            final int[] dil = max ? NeuralGraph.rep(a.length > 4 && a[4] != null ? NeuralGraph.shapeOf(a[4]) : new int[] {1}, 2)
                : new int[] {1, 1};
            final boolean countPad = max || a.length <= 5 || a[5] == null || Boolean.TRUE.equals(a[5]);
            final double divisor = !max && a.length > 6 && a[6] != null ? NeuralGraph.num(a[6]) : 0;
            int planes = 1;
            for (int i = 0; i < lead; i++) {
                planes *= x.shape[i];
            }
            final Tensor rec = NeuralGraph.tensor(max ? ((Object[]) out)[0] : out);
            final Storage s = storage(KERNEL, rec.numel());
            final Instr ins = new Instr(null, null, null);
            ins.pl = new Pool(x.storage, s, planes, Arrays.copyOfRange(x.shape, lead, x.shape.length), Arrays.copyOfRange(rec.shape, lead,
                rec.shape.length), kernel, stride, pad, dil, max, countPad, divisor);
            ins.reads.add(x.storage);
            ins.writes.add(s);
            instrs.add(ins);
            final View v = View.contiguous(s, rec.shape);
            if (max) {
                final Storage empty = constant(new double[0]);
                value.put(nd.name(), new Object[] {v, View.contiguous(empty, new int[] {0})});
            } else {
                value.put(nd.name(), v);
            }
            convs++;
        }

        // ---- reductions, softmax, normalizations

        /** sum, mean, amax, amin (over dims or all): accumulated in the loop that computes their operand. */
        void reduction(int t, Node nd, Object[] a, Object out) {
            final String src = ((Ref) nd.args()[0]).name();
            final int kindR = REDUCTIONS.get(nd.op());
            final Tensor x = NeuralGraph.tensor(frame.env.get(src));
            final boolean all = nd.op().endsWith(".default") && !nd.op().startsWith("a");
            final int[] dims = all ? null : NeuralGraph.dimsOf(a.length > 1 ? a[1] : null, x.rank());
            final boolean[] red = new boolean[x.rank()];
            if (dims == null || dims.length == 0) {
                Arrays.fill(red, true);
            } else {
                for (int d : dims) {
                    red[d] = true;
                }
            }
            final int[] kshape = x.shape.clone();
            int count = 1;
            for (int d = 0; d < kshape.length; d++) {
                if (red[d]) {
                    count *= kshape[d];
                    kshape[d] = 1;
                }
            }
            if (!(open != null && open.members.containsKey(src) && Arrays.equals(open.shape, x.shape))) {
                // its operand is not computed by the open group: a loop of its own over it, the identity its only operation
                if (member(src)) {
                    close();
                }
                final View v = view(src);
                close();
                open = new Group(x.shape);
                open.micros.add(new Micro("id", List.of(new Arg(Arg.OPERAND, open.operand(v), 0)), new double[0]));
                open.members.put(src + "#in", 0);
            }
            final int micro = open.members.containsKey(src) ? open.members.get(src) : open.members.get(src + "#in");
            final Storage s = storage(KERNEL, NeuralGraph.numel(kshape));
            open.reductions.add(new Reduction(micro, kindR, red, kshape, s, count));
            open.produced.add(nd.name());
            open.nodes.add(t);
            final int[] outShape = NeuralGraph.tensor(out).shape;
            value.put(nd.name(), new View(s, 0, outShape.clone(), NeuralGraph.strides(outShape)));
            fusedNodes++;
        }

        /** Batch normalization in evaluation as elementwise operations: its statistics broadcast along dim 1. */
        void batchNorm(int t, Node nd, Object[] a, Object out) {
            final String src = ((Ref) nd.args()[0]).name();
            final Tensor x = NeuralGraph.tensor(frame.env.get(src));
            final int r = x.rank();
            final List<Object> operands = new ArrayList<>();
            operands.add(src);
            operands.add(channel(nd.args()[3], r));
            operands.add(channel(nd.args()[4], r));
            // x alpha + beta, alpha and beta from the weight and bias, or 1 and 0: the interpreter's arithmetic, to the bit
            operands.add(a[1] != null ? channel(nd.args()[1], r) : 1.0);
            operands.add(a[2] != null ? channel(nd.args()[2], r) : 0.0);
            final List<Spec> chain = List.of(new Spec("bnAffine", new int[] {0, 1, 2, 3, 4}, new double[] {NeuralGraph.num(a[6])}));
            final String m = nd.name() + "#0";
            fuse(t, m, x.shape, chain, operands);
            final Storage empty = constant(new double[0]);
            final View none = View.contiguous(empty, new int[] {0});
            value.put(nd.name(), new Object[] {new MemberRef(m), none, none});
            fusedNodes++;
        }

        /** A per-channel tensor [C] seen as [C, 1, ..., 1]: broadcast along dim 1 of a tensor of rank r. */
        View channel(Object token, int r) {
            final View v = view(((Ref) token).name());
            final int[] s = new int[r - 1], st = new int[r - 1];
            Arrays.fill(s, 1);
            s[0] = v.shape[0];
            st[0] = v.strides[0];
            return new View(v.storage, v.offset, s, st);
        }

        View plainView(String name) {
            View v = view(name);
            return v.plain() ? v : materialize(v);
        }

        void softmax(Node nd, Object[] a, Object out) {
            final String src = ((Ref) nd.args()[0]).name();
            final View v = plainView(src);
            final int d = NeuralGraph.dim(NeuralGraph.lng(a[1]), v.shape.length);
            final NeuralGraph.Around ar = NeuralGraph.Around.of(v.shape, d);
            final Storage o = storage(KERNEL, NeuralGraph.numel(v.shape));
            final Instr in = new Instr(null, null, null);
            in.sm = new Softmax(v.storage, o, ar.outer(), ar.n(), ar.inner(), nd.op().startsWith("_log"));
            in.reads.add(v.storage);
            in.writes.add(o);
            instrs.add(in);
            value.put(nd.name(), View.contiguous(o, NeuralGraph.tensor(out).shape));
            softmaxes++;
        }

        void layerNorm(Node nd, Object[] a, Object out) {
            final View v = plainView(((Ref) nd.args()[0]).name());
            int inner = 1;
            for (int s : NeuralGraph.shapeOf(a[1])) {
                inner *= s;
            }
            final int outer = NeuralGraph.numel(v.shape) / Math.max(1, inner);
            final View w = a[2] == null ? null : plainView(((Ref) nd.args()[2]).name());
            final View b = a[3] == null ? null : plainView(((Ref) nd.args()[3]).name());
            final Object[] rec = (Object[]) out;
            final Storage o = storage(KERNEL, v.storage.size), mean = storage(KERNEL, outer), rstd = storage(KERNEL, outer);
            final Instr in = new Instr(null, null, null);
            in.ln = new LayerNorm(v.storage, w == null ? null : w.storage, b == null ? null : b.storage, o, mean, rstd, outer, inner,
                NeuralGraph.num(a[4]));
            in.reads.add(v.storage);
            if (w != null) {
                in.reads.add(w.storage);
            }
            if (b != null) {
                in.reads.add(b.storage);
            }
            in.writes.add(o);
            in.writes.add(mean);
            in.writes.add(rstd);
            instrs.add(in);
            value.put(nd.name(), new Object[] {View.contiguous(o, NeuralGraph.tensor(rec[0]).shape),
                View.contiguous(mean, NeuralGraph.tensor(rec[1]).shape), View.contiguous(rstd, NeuralGraph.tensor(rec[2]).shape)});
            norms++;
        }

        // ---- views

        View derive(String op, Object[] a, View v, int[] outShape) {
            switch (op) {
                case "alias.default", "detach.default", "clone.default", "lift_fresh_copy.default", "contiguous.default", "_to_copy.default",
                     "to.dtype", "clamp.default", "clamp.Tensor" -> {
                    return v;
                }
                case "view.default", "_unsafe_view.default", "reshape.default" -> {
                    return v.contiguous() ? new View(v.storage, v.offset, outShape.clone(), NeuralGraph.strides(outShape)) : null;
                }
                case "permute.default" -> {
                    final int[] p = NeuralGraph.dimsOf(a[1], v.shape.length);
                    final int[] s = new int[p.length], st = new int[p.length];
                    for (int i = 0; i < p.length; i++) {
                        s[i] = v.shape[p[i]];
                        st[i] = v.strides[p[i]];
                    }
                    return new View(v.storage, v.offset, s, st);
                }
                case "expand.default" -> {
                    final int lead = outShape.length - v.shape.length;
                    final int[] st = new int[outShape.length];
                    for (int d = 0; d < outShape.length; d++) {
                        final int j = d - lead;
                        st[d] = j >= 0 && v.shape[j] == outShape[d] && v.shape[j] != 1 ? v.strides[j] : 0;
                    }
                    return new View(v.storage, v.offset, outShape.clone(), st);
                }
                case "squeeze.dims", "squeeze.dim", "squeeze.default" -> {
                    final int[] dims = op.equals("squeeze.default") ? null : NeuralGraph.dimsOf(a[1], v.shape.length);
                    final List<Integer> s = new ArrayList<>(), st = new ArrayList<>();
                    for (int d = 0; d < v.shape.length; d++) {
                        boolean drop = v.shape[d] == 1;
                        if (dims != null) {
                            final int dd = d;
                            drop &= Arrays.stream(dims).anyMatch(x -> x == dd);
                        }
                        if (!drop) {
                            s.add(v.shape[d]);
                            st.add(v.strides[d]);
                        }
                    }
                    return new View(v.storage, v.offset, s.stream().mapToInt(Integer::intValue).toArray(),
                        st.stream().mapToInt(Integer::intValue).toArray());
                }
                case "unsqueeze.default" -> {
                    final int d = NeuralGraph.dim(NeuralGraph.lng(a[1]), v.shape.length + 1);
                    final int[] s = new int[v.shape.length + 1], st = new int[s.length];
                    for (int i = 0, j = 0; i < s.length; i++) {
                        if (i == d) {
                            s[i] = 1;
                            st[i] = 0;
                        } else {
                            s[i] = v.shape[j];
                            st[i] = v.strides[j++];
                        }
                    }
                    return new View(v.storage, v.offset, s, st);
                }
                case "select.int" -> {
                    final int d = NeuralGraph.dim(NeuralGraph.lng(a[1]), v.shape.length);
                    final long i = NeuralGraph.lng(a[2]);
                    final int at = (int) (i < 0 ? i + v.shape[d] : i);
                    final int[] s = new int[v.shape.length - 1], st = new int[s.length];
                    for (int k = 0, j = 0; k < v.shape.length; k++) {
                        if (k != d) {
                            s[j] = v.shape[k];
                            st[j++] = v.strides[k];
                        }
                    }
                    return new View(v.storage, v.offset + at * v.strides[d], s, st);
                }
                case "slice.Tensor" -> {
                    final int d = NeuralGraph.dim(a[1] == null ? 0 : NeuralGraph.lng(a[1]), v.shape.length), n = v.shape[d];
                    long lo = a[2] == null ? 0 : NeuralGraph.lng(a[2]);
                    lo = lo < 0 ? Math.max(0, lo + n) : Math.min(lo, n);
                    final int step = a.length > 4 && a[4] != null ? (int) NeuralGraph.lng(a[4]) : 1;
                    final int[] s = v.shape.clone(), st = v.strides.clone();
                    s[d] = outShape[d];
                    st[d] = v.strides[d] * step;
                    return new View(v.storage, v.offset + (int) lo * v.strides[d], s, st);
                }
                default -> throw new NotCompiled("a view " + op);
            }
        }

        /** A view copied into a fresh array of its own: one more fused kernel, its only operation the identity. */
        View materialize(View v) {
            final Group gr = new Group(v.shape);
            gr.micros.add(new Micro("id", List.of(new Arg(Arg.OPERAND, gr.operand(v), 0)), new double[0]));
            final Storage s = storage(KERNEL, NeuralGraph.numel(v.shape));
            final View out = View.contiguous(s, v.shape);
            gr.stored.add(new Object[] {0, out});
            final Instr in = new Instr(gr, null, null);
            in.reads.add(v.storage);
            in.writes.add(s);
            instrs.add(in);
            return out;
        }

        // ---- fusion

        void fuse(int t, String name, int[] shape, List<Spec> specs, List<Object> operands) {
            final boolean join = open != null && Arrays.equals(open.shape, shape);
            if (!join) {
                close();
                open = new Group(shape);
            }
            int prev = -1;
            for (Spec sp : specs) {
                final List<Arg> args = new ArrayList<>();
                for (int f : sp.from) {
                    args.add(f < 0 ? new Arg(Arg.MICRO, prev, 0) : arg(operands.get(f)));
                }
                open.micros.add(new Micro(sp.method, args, sp.consts));
                prev = open.micros.size() - 1;
            }
            open.members.put(name, prev);
            open.nodes.add(t);
        }

        /** fuse with the operands given as views (a product's result) or names. */
        Arg arg(Object o) {
            if (o instanceof View v) {
                return new Arg(Arg.OPERAND, open.operand(v), 0);
            }
            if (o instanceof String name) {
                if (open.members.containsKey(name)) {
                    return new Arg(Arg.MICRO, open.members.get(name), 0);
                }
                final int k = kindOf(name);
                final Object rec = frame.env.get(name);
                if ((k == STATIC || k == WEIGHT && fold) && rec instanceof Tensor t && t.numel() == 1) {
                    return new Arg(Arg.CONSTANT, 0, t.data[0]);   // a one-element constant, read as the number it is
                }
                return new Arg(Arg.OPERAND, open.operand(view(name)), 0);
            }
            if (o instanceof Boolean bo) {
                return new Arg(Arg.CONSTANT, 0, bo ? 1 : 0);
            }
            if (o instanceof Number n) {
                return new Arg(Arg.CONSTANT, 0, n.doubleValue());
            }
            throw new NotCompiled("an elementwise operand " + o);
        }

        void close() {
            if (open == null) {
                return;
            }
            final Group gr = open;
            open = null;
            final Instr in = new Instr(gr, null, null);
            for (Map.Entry<String, Integer> e : gr.members.entrySet()) {
                final String key = e.getKey();
                if (key.endsWith("#in")) {
                    continue;   // a reduction's operand read from where it already is
                }
                // a tuple's element ("node#0") is read through the tuple's items: their readers decide
                final String owner = key.endsWith("#0") ? key.substring(0, key.length() - 2) : key;
                boolean needed = outputs.contains(key);
                for (int u : users.getOrDefault(owner, List.of())) {
                    needed |= !gr.nodes.contains(u);
                }
                if (needed) {
                    final Storage s = storage(KERNEL, NeuralGraph.numel(gr.shape));
                    final View v = View.contiguous(s, gr.shape);
                    gr.stored.add(new Object[] {e.getValue(), v});
                    value.put(key, v);
                    in.writes.add(s);
                }
            }
            for (Reduction rd : gr.reductions) {
                in.writes.add(rd.out());
            }
            if (gr.stored.isEmpty() && gr.reductions.isEmpty()) {
                return;
            }
            for (View v : gr.operands) {
                in.reads.add(v.storage);
            }
            instrs.add(in);
        }

        // ---- products

        void product(int t, Node nd, Object[] a, Object out) {
            final boolean add = nd.op().equals("addmm.default") || nd.op().equals("baddbmm.default");
            final int ia = add ? 1 : 0, ib = add ? 2 : 1;
            View va = view(((Ref) nd.args()[ia]).name()), vb = view(((Ref) nd.args()[ib]).name());
            if (va.shape.length == 1) {
                va = new View(va.storage, va.offset, new int[] {1, va.shape[0]}, new int[] {0, va.strides[0]});
            }
            if (vb.shape.length == 1) {
                vb = new View(vb.storage, vb.offset, new int[] {vb.shape[0], 1}, new int[] {vb.strides[0], 0});
            }
            final int ra = va.shape.length, rb = vb.shape.length;
            final int n = va.shape[ra - 2], k = va.shape[ra - 1], m = vb.shape[rb - 1];
            if (vb.shape[rb - 2] != k) {
                throw new NotCompiled("a product of " + Arrays.toString(va.shape) + " and " + Arrays.toString(vb.shape));
            }
            final int[] ba = Arrays.copyOf(va.shape, ra - 2), bb = Arrays.copyOf(vb.shape, rb - 2);
            final int[] batch = NeuralGraph.broadcast(ba, bb);
            final int[] sa = new int[batch.length], sb = new int[batch.length];
            for (int d = 0; d < batch.length; d++) {
                final int ja = d - (batch.length - ba.length), jb = d - (batch.length - bb.length);
                sa[d] = ja >= 0 && ba[ja] != 1 ? va.strides[ja] : 0;
                sb[d] = jb >= 0 && bb[jb] != 1 ? vb.strides[jb] : 0;
            }
            final int[] prodShape = Arrays.copyOf(batch, batch.length + 2);
            prodShape[batch.length] = n;
            prodShape[batch.length + 1] = m;
            final Storage o = storage(KERNEL, NeuralGraph.numel(prodShape));
            final Matmul mm = new Matmul(va, vb, o, batch, n, k, m, sa, sb, va.strides[ra - 2], va.strides[ra - 1], vb.strides[rb - 2],
                vb.strides[rb - 1]);
            final Instr in = new Instr(null, mm, null);
            in.reads.add(va.storage);
            in.reads.add(vb.storage);
            in.writes.add(o);
            instrs.add(in);
            matmuls++;
            final int[] outShape = NeuralGraph.tensor(out).shape;
            if (!add) {
                value.put(nd.name(), new View(o, 0, outShape, NeuralGraph.strides(outShape)));
                return;
            }
            // beta c + alpha prod, as one more elementwise operation: fused with what follows
            final View prod = View.contiguous(o, prodShape);
            final List<Object> operands = new ArrayList<>();
            operands.add(nd.args()[0] instanceof Ref r ? r.name() : a[0]);
            operands.add(prod);
            fuse(t, nd.name(), outShape, List.of(new Spec("scaleAdd", new int[] {0, 1}, new double[] {opt(a, 3, 1), opt(a, 4, 1)})),
                operands);
        }

        // ---- fallbacks

        Object binding(Object token, Object recorded, Instr in) {
            if (token instanceof Ref r) {
                if (kindOf(r.name()) != RUNTIME) {
                    if (g.consts.containsKey(r.name())) {
                        in.reads.add(constant(g.consts.get(r.name()).data));
                    }
                    return null;   // the recorded value: a constant of this specialization
                }
                View v = view(r.name());
                if (!v.plain()) {
                    v = materialize(v);
                }
                in.reads.add(v.storage);
                return new Binding(v.storage, v.shape);
            }
            if (token instanceof List<?> l) {
                boolean any = false;
                final List<Object> out = new ArrayList<>();
                final List<?> rec = (List<?>) recorded;
                for (int i = 0; i < l.size(); i++) {
                    final Object b = binding(l.get(i), rec.get(i), in);
                    any |= b != null;
                    out.add(b != null ? b : rec.get(i));
                }
                return any ? out : null;
            }
            return null;
        }

        void fallback(Node nd, Object[] a, Object out) {
            final Instr in = new Instr(null, null, null);
            final Object[] bindings = new Object[a.length];
            for (int p = 0; p < a.length; p++) {
                bindings[p] = binding(nd.args()[p], a[p], in);
            }
            final int kindOut;
            final Storage[] outs;
            if (out instanceof Tensor t) {
                kindOut = 0;
                outs = new Storage[] {storage(DYNAMIC, t.numel())};
                value.put(nd.name(), View.contiguous(outs[0], t.shape));
            } else if (out instanceof Object[] || out instanceof List<?>) {
                final List<?> items = out instanceof Object[] o ? Arrays.asList(o) : (List<?>) out;
                kindOut = out instanceof Object[] ? 1 : 2;
                outs = new Storage[items.size()];
                final Object[] vs = new Object[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i) instanceof Tensor t) {
                        outs[i] = storage(DYNAMIC, t.numel());
                        vs[i] = View.contiguous(outs[i], t.shape);
                    }
                }
                value.put(nd.name(), kindOut == 1 ? vs : new ArrayList<>(Arrays.asList(vs)));
            } else {
                throw new NotCompiled(nd.name() + " (" + nd.op() + ") gives " + (out == null ? "nothing" : out.getClass().getSimpleName()));
            }
            final Fallback fb = new Fallback(nd.op(), a.clone(), bindings, outs, kindOut);
            final Instr f = new Instr(null, null, fb);
            f.reads.addAll(in.reads);
            for (Storage s : outs) {
                if (s != null) {
                    f.writes.add(s);
                }
            }
            instrs.add(f);
            fallbacks.merge(nd.op(), 1, Integer::sum);
        }

        // ---- planning and code

        CompiledGraph finish(int batch, int[][] inShapes, double millisSoFar) {
            final long t0 = System.nanoTime();
            // the outputs: views kept alive to the end
            final List<Object> outs = new ArrayList<>();
            for (String o : g.outputs) {
                final Object rec = frame.env.get(o);
                if (kindOf(o) == RUNTIME) {
                    final View v = (View) value.get(o);
                    v.storage.pinned = true;
                    outs.add(v);
                } else {
                    outs.add(NeuralGraph.tensor(rec));
                }
            }
            // lifetimes; a fallback's results may share their inputs' arrays: they live and die together
            for (int t = 0; t < instrs.size(); t++) {
                final Instr in = instrs.get(t);
                for (Storage s : in.reads) {
                    s.last = Math.max(s.last, t);
                }
                for (Storage s : in.writes) {
                    s.first = Math.min(s.first, t);
                    s.last = Math.max(s.last, t);
                }
                if (in.fb != null) {
                    for (Storage w : in.writes) {
                        for (Storage r : in.reads) {
                            final Storage a = w.root(), b = r.root();
                            if (a != b) {
                                a.alias = b;
                            }
                        }
                    }
                }
            }
            final Map<Storage, Integer> groupLast = new HashMap<>();
            final Set<Storage> pinnedRoots = new HashSet<>();
            for (Storage s : storages) {
                groupLast.merge(s.root(), s.last, Math::max);
                if (s.pinned) {
                    pinnedRoots.add(s.root());
                }
            }
            // kernel arrays: reused once all of what may share them is dead
            final List<Storage> kernels = new ArrayList<>();
            for (Storage s : storages) {
                if (s.kind == KERNEL && s.first != Integer.MAX_VALUE) {
                    kernels.add(s);
                }
            }
            kernels.sort((x, y) -> Integer.compare(x.first, y.first));
            final Map<Integer, ArrayDeque<Integer>> free = new HashMap<>();
            final List<int[]> live = new ArrayList<>();   // {phys, last, size}
            final List<Integer> physSize = new ArrayList<>();
            for (Storage s : kernels) {
                for (int i = live.size() - 1; i >= 0; i--) {
                    final int[] l = live.get(i);
                    if (l[1] < s.first) {
                        free.computeIfAbsent(l[2], z -> new ArrayDeque<>()).push(l[0]);
                        live.remove(i);
                    }
                }
                final ArrayDeque<Integer> q = free.get(s.size);
                if (q != null && !q.isEmpty()) {
                    s.phys = q.pop();
                } else {
                    s.phys = physSize.size();
                    physSize.add(s.size);
                }
                final Storage root = s.root();
                final int end = pinnedRoots.contains(root) ? Integer.MAX_VALUE : groupLast.getOrDefault(root, s.last);
                live.add(new int[] {s.phys, end, s.size});
            }
            // slots: inputs, constants, kernel arrays, fallback results
            int slot = 0;
            for (Storage s : storages) {
                if (s.kind == INPUT) {
                    s.slot = slot++;
                }
            }
            final List<double[]> constants = new ArrayList<>();
            final int constBase = slot;
            for (Storage s : storages) {
                if (s.kind == CONST) {
                    s.slot = slot++;
                    constants.add(s.constant);
                }
            }
            final int kernelBase = slot;
            slot += physSize.size();
            for (Storage s : kernels) {
                s.slot = kernelBase + s.phys;
            }
            for (Storage s : storages) {
                if (s.kind == DYNAMIC) {
                    s.slot = slot++;
                }
            }
            final double[][] template = new double[slot][];
            for (int i = 0; i < constants.size(); i++) {
                template[constBase + i] = constants.get(i);
            }
            final int[] kernelSizes = physSize.stream().mapToInt(Integer::intValue).toArray();
            final List<Fallback> fbs = new ArrayList<>();
            for (Instr in : instrs) {
                if (in.fb != null) {
                    in.fallbackIndex = fbs.size();
                    fbs.add(in.fb);
                }
            }
            final MethodHandle run = Codegen.define(instrs);
            int[] inputSlots = new int[g.inputs.size()];
            for (Storage s : storages) {
                if (s.kind == INPUT) {
                    inputSlots[s.input] = s.slot;
                }
            }
            long kernelBytes = 0;
            for (int z : kernelSizes) {
                kernelBytes += 8L * z;
            }
            int fused = 0, micros = 0;
            for (Instr in : instrs) {
                if (in.group != null) {
                    fused++;
                    micros += in.group.micros.size();
                }
            }
            int reductions = 0;
            for (Instr in : instrs) {
                if (in.group != null) {
                    reductions += in.group.reductions.size();
                }
            }
            final String report = String.format(Locale.ROOT, "%d nodes -> %d kernels (%d fused loops of %d operations and %d reductions,"
                    + " %d products, %d softmax, %d layer norms, %d convolutions and poolings), %d fallbacks %s, %d views copying nothing,"
                    + " %d values folded%s; %d arrays planned (%.2f MB, reused), compiled in %.0f ms",
                nodes, fused + matmuls + softmaxes + norms + convs, fused, micros, reductions, matmuls, softmaxes, norms, convs, fbs.size(),
                fallbacks.isEmpty() ? "" : fallbacks, views,
                folded.values().stream().mapToInt(Integer::intValue).sum(), folded.isEmpty() ? "" : " " + folded, kernelSizes.length,
                kernelBytes / 1048576.0, millisSoFar + (System.nanoTime() - t0) / 1e6);
            final CompiledGraph compiled = new CompiledGraph(g, batch, inShapes, inputSlots, template, kernelBase, kernelSizes,
                fbs.toArray(new Fallback[0]), outs, run, report);
            compiled.instrs = List.copyOf(instrs);   // what GraphCpp writes as C++
            compiled.storages = List.copyOf(storages);
            return compiled;
        }
    }

    // ------------------------------------------------------------------
    // Bytecode
    // ------------------------------------------------------------------

    private static final class Codegen {
        static final ClassDesc SELF = ClassDesc.of("com.sphere.core.ml.GraphJit");
        static final ClassDesc KERNELS = ClassDesc.of("com.sphere.core.ml.GraphKernels");
        static final ClassDesc COMPILED = ClassDesc.of("com.sphere.core.ml.GraphCompiler$CompiledGraph");
        static final ClassDesc ARRAYS = ClassDesc.of("java.util.Arrays");
        static final ClassDesc SLOTS = CD_double.arrayType(2);
        static final MethodTypeDesc KERNEL = MethodTypeDesc.of(CD_void, SLOTS);
        static final MethodTypeDesc RUN = MethodTypeDesc.of(CD_void, SLOTS, CD_Object);
        static final MethodTypeDesc FALLBACK = MethodTypeDesc.of(CD_void, CD_Object, CD_int, SLOTS);
        static final MethodTypeDesc FILL = MethodTypeDesc.of(CD_void, CD_double.arrayType(), CD_double);

        static MethodTypeDesc doubles(int n) {
            final ClassDesc[] p = new ClassDesc[n];
            Arrays.fill(p, CD_double);
            return MethodTypeDesc.of(CD_double, p);
        }

        static MethodHandle define(List<Instr> instrs) {
            final byte[] bytes = ClassFile.of().build(SELF, cb -> {
                cb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                for (int t = 0; t < instrs.size(); t++) {
                    final Instr in = instrs.get(t);
                    if (in.group != null) {
                        cb.withMethodBody("k" + t, KERNEL, ClassFile.ACC_STATIC, c -> fused(c, in.group));
                    } else if (in.mm != null) {
                        cb.withMethodBody("k" + t, KERNEL, ClassFile.ACC_STATIC, c -> product(c, in.mm));
                    } else if (in.sm != null) {
                        cb.withMethodBody("k" + t, KERNEL, ClassFile.ACC_STATIC, c -> softmax(c, in.sm));
                    } else if (in.ln != null) {
                        cb.withMethodBody("k" + t, KERNEL, ClassFile.ACC_STATIC, c -> layerNorm(c, in.ln));
                    } else if (in.cv != null) {
                        cb.withMethodBody("k" + t, KERNEL, ClassFile.ACC_STATIC, c -> conv(c, in.cv));
                    } else if (in.pl != null) {
                        cb.withMethodBody("k" + t, KERNEL, ClassFile.ACC_STATIC, c -> pool(c, in.pl));
                    }
                }
                cb.withMethodBody("run", RUN, ClassFile.ACC_STATIC, c -> {
                    for (int t = 0; t < instrs.size(); t++) {
                        final Instr in = instrs.get(t);
                        if (in.fb != null) {
                            c.aload(1).loadConstant(in.fallbackIndex).aload(0).invokestatic(COMPILED, "fallback", FALLBACK);
                        } else {
                            c.aload(0).invokestatic(SELF, "k" + t, KERNEL);
                        }
                    }
                    c.return_();
                });
            });
            try {
                final MethodHandles.Lookup lk = MethodHandles.lookup().defineHiddenClass(bytes, true);
                return lk.findStatic(lk.lookupClass(), "run", MethodType.methodType(void.class, double[][].class, Object.class));
            } catch (ReflectiveOperationException e) {
                throw new GraphException("the compiled graph could not be loaded: " + e);
            }
        }

        /** offset + sum_d var_d * stride_d, constants folded into the instructions. */
        static void index(CodeBuilder c, int offset, int[] strides, int[] vars) {
            c.loadConstant(offset);
            for (int d = 0; d < strides.length; d++) {
                if (strides[d] != 0) {
                    c.iload(vars[d]);
                    if (strides[d] != 1) {
                        c.loadConstant(strides[d]).imul();
                    }
                    c.iadd();
                }
            }
        }

        /** for (v = 0; v < size; v++) body(next): next is where a continue goes. */
        static void loop(CodeBuilder c, int var, int size, Consumer<Label> body) {
            final Label top = c.newLabel(), next = c.newLabel(), end = c.newLabel();
            c.loadConstant(0).istore(var);
            c.labelBinding(top);
            c.iload(var).loadConstant(size).if_icmpge(end);
            body.accept(next);
            c.labelBinding(next);
            c.iinc(var, 1).goto_(top);
            c.labelBinding(end);
        }

        static void nest(CodeBuilder c, int[] sizes, int[] vars, int d, Runnable body) {
            if (d == sizes.length) {
                body.run();
                return;
            }
            loop(c, vars[d], sizes[d], next -> nest(c, sizes, vars, d + 1, body));
        }

        static int array(CodeBuilder c, Storage s) {
            final int local = c.allocateLocal(TypeKind.REFERENCE);
            c.aload(0).loadConstant(s.slot).aaload().astore(local);
            return local;
        }

        static void fused(CodeBuilder c, Group gr) {
            final int r = gr.shape.length;
            final int[] arr = new int[gr.operands.size()], val = new int[gr.operands.size()];
            final int[][] st = new int[gr.operands.size()][];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = array(c, gr.operands.get(k).storage);
                val[k] = c.allocateLocal(TypeKind.DOUBLE);
                st[k] = gr.operands.get(k).stridesFor(gr.shape);
            }
            final int[] outArr = new int[gr.stored.size()];
            for (int k = 0; k < outArr.length; k++) {
                outArr[k] = array(c, ((View) gr.stored.get(k)[1]).storage);
            }
            final int[] redArr = new int[gr.reductions.size()];
            final int[][] redStr = new int[redArr.length][];
            for (int k = 0; k < redArr.length; k++) {
                final Reduction rd = gr.reductions.get(k);
                redArr[k] = array(c, rd.out());
                c.aload(redArr[k]).loadConstant(rd.kind() == MAX ? Double.NEGATIVE_INFINITY : rd.kind() == MIN ? Double.POSITIVE_INFINITY : 0.0)
                    .invokestatic(ARRAYS, "fill", FILL);
                final int[] ost = NeuralGraph.strides(rd.kshape());
                redStr[k] = new int[r];
                for (int d = 0; d < r; d++) {
                    redStr[k][d] = rd.reduced()[d] ? 0 : ost[d];
                }
            }
            final int[] micro = new int[gr.micros.size()];
            for (int j = 0; j < micro.length; j++) {
                micro[j] = c.allocateLocal(TypeKind.DOUBLE);
            }
            final int[] vars = new int[r];
            for (int d = 0; d < r; d++) {
                vars[d] = c.allocateLocal(TypeKind.INT);
            }
            final int[] contiguous = NeuralGraph.strides(gr.shape);
            nest(c, gr.shape, vars, 0, () -> {
                for (int k = 0; k < arr.length; k++) {
                    c.aload(arr[k]);
                    index(c, gr.operands.get(k).offset, st[k], vars);
                    c.daload().dstore(val[k]);
                }
                for (int j = 0; j < micro.length; j++) {
                    final Micro m = gr.micros.get(j);
                    for (Arg a : m.args) {
                        switch (a.kind) {
                            case Arg.OPERAND -> c.dload(val[a.index]);
                            case Arg.MICRO -> c.dload(micro[a.index]);
                            default -> c.loadConstant(a.value);
                        }
                    }
                    for (double k : m.consts) {
                        c.loadConstant(k);
                    }
                    c.invokestatic(KERNELS, m.method, doubles(m.args.size() + m.consts.length)).dstore(micro[j]);
                }
                for (int k = 0; k < outArr.length; k++) {
                    c.aload(outArr[k]);
                    index(c, 0, contiguous, vars);
                    c.dload(micro[(Integer) gr.stored.get(k)[0]]).dastore();
                }
                // NeuralGraph.reduce visits its operand in this same order: each sum gets its terms in the same order
                for (int k = 0; k < redArr.length; k++) {
                    final Reduction rd = gr.reductions.get(k);
                    c.aload(redArr[k]);
                    index(c, 0, redStr[k], vars);
                    c.dup2().daload().dload(micro[rd.micro()]);
                    switch (rd.kind()) {
                        case MAX -> c.invokestatic(KERNELS, "redMax", doubles(2));
                        case MIN -> c.invokestatic(KERNELS, "redMin", doubles(2));
                        default -> c.dadd();
                    }
                    c.dastore();
                }
            });
            for (int k = 0; k < redArr.length; k++) {
                final Reduction rd = gr.reductions.get(k);
                if (rd.kind() == MEAN) {   // o[i] /= count, after the sums
                    final int iv = c.allocateLocal(TypeKind.INT), arrK = redArr[k];
                    loop(c, iv, rd.out().size, next -> c.aload(arrK).iload(iv).dup2().daload().loadConstant((double) rd.count()).ddiv()
                        .dastore());
                }
            }
            c.return_();
        }

        static void math(CodeBuilder c, String name, int args) {
            c.invokestatic(ClassDesc.of("java.lang.Math"), name, doubles(args));
        }

        /** NeuralGraph.softmax: the maximum, the sum of exp(x - max), then exp / sum or (x - max) - log(sum). */
        static void softmax(CodeBuilder c, Softmax sm) {
            final int x = array(c, sm.in()), o = array(c, sm.out());
            final int pv = c.allocateLocal(TypeKind.INT), qv = c.allocateLocal(TypeKind.INT), iv = c.allocateLocal(TypeKind.INT);
            final int base = c.allocateLocal(TypeKind.INT), at = c.allocateLocal(TypeKind.INT);
            final int m = c.allocateLocal(TypeKind.DOUBLE), s = c.allocateLocal(TypeKind.DOUBLE), e = c.allocateLocal(TypeKind.DOUBLE);
            final int ls = c.allocateLocal(TypeKind.DOUBLE);
            final int n = sm.n(), inner = sm.inner();
            final Runnable atIndex = () -> c.iload(base).iload(iv).loadConstant(inner).imul().iadd().istore(at);
            loop(c, pv, sm.outer(), nextP -> loop(c, qv, inner, nextQ -> {
                c.iload(pv).loadConstant(n * inner).imul().iload(qv).iadd().istore(base);
                c.loadConstant(Double.NEGATIVE_INFINITY).dstore(m);
                loop(c, iv, n, nx -> {
                    atIndex.run();
                    c.dload(m).aload(x).iload(at).daload();
                    math(c, "max", 2);
                    c.dstore(m);
                });
                c.dconst_0().dstore(s);
                loop(c, iv, n, nx -> {
                    atIndex.run();
                    c.aload(x).iload(at).daload().dload(m).dsub();
                    math(c, "exp", 1);
                    c.dstore(e);
                    c.aload(o).iload(at).dload(e).dastore();
                    c.dload(s).dload(e).dadd().dstore(s);
                });
                if (sm.log()) {
                    c.dload(s);
                    math(c, "log", 1);
                    c.dstore(ls);
                }
                loop(c, iv, n, nx -> {
                    atIndex.run();
                    c.aload(o).iload(at);
                    if (sm.log()) {
                        c.aload(x).iload(at).daload().dload(m).dsub().dload(ls).dsub();
                    } else {
                        c.aload(o).iload(at).daload().dload(s).ddiv();
                    }
                    c.dastore();
                });
            }));
            c.return_();
        }

        /** pos_d = o_d st_d - pd_d + k_d dl_d for each spatial dim, a jump to skip when outside [0, in_d). */
        static void positions(CodeBuilder c, int[] ov, int[] kv, int[] posv, int[] st, int[] pd, int[] dl, int[] in, Label skip) {
            for (int d = 0; d < ov.length; d++) {
                c.iload(ov[d]).loadConstant(st[d]).imul().loadConstant(-pd[d]).iadd().iload(kv[d]).loadConstant(dl[d]).imul().iadd()
                    .istore(posv[d]);
                c.iload(posv[d]).iflt(skip);
                c.iload(posv[d]).loadConstant(in[d]).if_icmpge(skip);
            }
        }

        /** NeuralGraph.conv: acc = bias, then for each input channel and kernel position inside, acc += x w, in that order. */
        static void conv(CodeBuilder c, Conv cv) {
            final int nd = cv.in().length;
            final int x = array(c, cv.x()), w = array(c, cv.w()), o = array(c, cv.out());
            final int b = cv.bias() == null ? -1 : array(c, cv.bias());
            final int sv = c.allocateLocal(TypeKind.INT), cov = c.allocateLocal(TypeKind.INT), civ = c.allocateLocal(TypeKind.INT);
            final int gv = c.allocateLocal(TypeKind.INT), acc = c.allocateLocal(TypeKind.DOUBLE);
            final int[] ov = new int[nd], kv = new int[nd], posv = new int[nd];
            for (int d = 0; d < nd; d++) {
                ov[d] = c.allocateLocal(TypeKind.INT);
                kv[d] = c.allocateLocal(TypeKind.INT);
                posv[d] = c.allocateLocal(TypeKind.INT);
            }
            final int isz = NeuralGraph.numel(cv.in()), ksz = NeuralGraph.numel(cv.ks()), osz = NeuralGraph.numel(cv.out_());
            final int[] ist = NeuralGraph.strides(cv.in()), kst = NeuralGraph.strides(cv.ks()), ost = NeuralGraph.strides(cv.out_());
            loop(c, sv, cv.n(), nextS -> loop(c, cov, cv.cout(), nextCo -> {
                c.iload(cov).loadConstant(cv.og()).idiv().istore(gv);
                nest(c, cv.out_(), ov, 0, () -> {
                    if (b >= 0) {
                        c.aload(b).iload(cov).daload().dstore(acc);
                    } else {
                        c.dconst_0().dstore(acc);
                    }
                    loop(c, civ, cv.cg(), nextCi -> nest(c, cv.ks(), kv, 0, () -> {
                        // acc += (inside ? x : 0) w: the padding is multiplied too, so that an infinite weight there makes NaN
                        final Label outside = c.newLabel(), product = c.newLabel();
                        positions(c, ov, kv, posv, cv.st(), cv.pd(), cv.dl(), cv.in(), outside);
                        c.aload(x);
                        // ((s cin + g cg + ci) isz) + sum pos_d ist_d
                        c.iload(sv).loadConstant(cv.cin()).imul().iload(gv).loadConstant(cv.cg()).imul().iadd().iload(civ).iadd()
                            .loadConstant(isz).imul();
                        for (int d = 0; d < nd; d++) {
                            c.iload(posv[d]).loadConstant(ist[d]).imul().iadd();
                        }
                        c.daload().goto_(product);
                        c.labelBinding(outside);
                        c.dconst_0();
                        c.labelBinding(product);
                        c.aload(w);
                        c.iload(cov).loadConstant(cv.cg()).imul().iload(civ).iadd().loadConstant(ksz).imul();
                        for (int d = 0; d < nd; d++) {
                            c.iload(kv[d]).loadConstant(kst[d]).imul().iadd();
                        }
                        c.daload().dmul().dload(acc).dadd().dstore(acc);
                    }));
                    c.aload(o);
                    c.iload(sv).loadConstant(cv.cout()).imul().iload(cov).iadd().loadConstant(osz).imul();
                    for (int d = 0; d < nd; d++) {
                        c.iload(ov[d]).loadConstant(ost[d]).imul().iadd();
                    }
                    c.dload(acc).dastore();
                });
            }));
            c.return_();
        }

        /** NeuralGraph.pool: the window's maximum (a NaN wins), or its sum over the divisor -- padding read as 0 for the sum. */
        static void pool(CodeBuilder c, Pool pl) {
            final int x = array(c, pl.x()), o = array(c, pl.out());
            final int pv = c.allocateLocal(TypeKind.INT), acc = c.allocateLocal(TypeKind.DOUBLE), v = c.allocateLocal(TypeKind.DOUBLE);
            final int covered = c.allocateLocal(TypeKind.INT);
            final int[] ov = {c.allocateLocal(TypeKind.INT), c.allocateLocal(TypeKind.INT)};
            final int[] kv = {c.allocateLocal(TypeKind.INT), c.allocateLocal(TypeKind.INT)};
            final int[] posv = {c.allocateLocal(TypeKind.INT), c.allocateLocal(TypeKind.INT)};
            final int isz = NeuralGraph.numel(pl.in()), osz = NeuralGraph.numel(pl.out_()), ksz = NeuralGraph.numel(pl.kernel());
            final int[] ist = NeuralGraph.strides(pl.in()), ost = NeuralGraph.strides(pl.out_());
            loop(c, pv, pl.planes(), nextP -> nest(c, pl.out_(), ov, 0, () -> {
                c.loadConstant(pl.max() ? Double.NEGATIVE_INFINITY : 0.0).dstore(acc);
                c.loadConstant(0).istore(covered);
                nest(c, pl.kernel(), kv, 0, () -> {
                    final Label outside = c.newLabel(), combine = c.newLabel();
                    positions(c, ov, kv, posv, pl.stride(), pl.pad(), pl.dil(), pl.in(), outside);
                    c.aload(x).iload(pv).loadConstant(isz).imul();
                    for (int d = 0; d < 2; d++) {
                        c.iload(posv[d]).loadConstant(ist[d]).imul().iadd();
                    }
                    c.daload().dstore(v);
                    c.iinc(covered, 1);
                    c.goto_(combine);
                    c.labelBinding(outside);
                    c.loadConstant(pl.max() ? Double.NEGATIVE_INFINITY : 0.0).dstore(v);
                    c.labelBinding(combine);
                    if (pl.max()) {
                        c.dload(acc).dload(v).invokestatic(KERNELS, "redMax", doubles(2)).dstore(acc);
                    } else {
                        c.dload(acc).dload(v).dadd().dstore(acc);
                    }
                });
                c.aload(o).iload(pv).loadConstant(osz).imul();
                for (int d = 0; d < 2; d++) {
                    c.iload(ov[d]).loadConstant(ost[d]).imul().iadd();
                }
                c.dload(acc);
                if (!pl.max()) {
                    if (pl.divisor() > 0) {
                        c.loadConstant(pl.divisor());
                    } else if (pl.countPad()) {
                        c.loadConstant((double) ksz);
                    } else {
                        c.iload(covered).i2d();
                    }
                    c.ddiv();
                }
                c.dastore();
            }));
            c.return_();
        }

        /** NeuralGraph.layerNorm, row by row: the mean, the variance about it, 1 / sqrt(var + eps), (x - m) r w + b. */
        static void layerNorm(CodeBuilder c, LayerNorm ln) {
            final int x = array(c, ln.in()), o = array(c, ln.out()), mean = array(c, ln.mean()), rstd = array(c, ln.rstd());
            final int w = ln.w() == null ? -1 : array(c, ln.w()), b = ln.b() == null ? -1 : array(c, ln.b());
            final int pv = c.allocateLocal(TypeKind.INT), iv = c.allocateLocal(TypeKind.INT), at = c.allocateLocal(TypeKind.INT);
            final int m = c.allocateLocal(TypeKind.DOUBLE), v = c.allocateLocal(TypeKind.DOUBLE), d = c.allocateLocal(TypeKind.DOUBLE);
            final int rr = c.allocateLocal(TypeKind.DOUBLE), y = c.allocateLocal(TypeKind.DOUBLE);
            final int inner = ln.inner();
            final Runnable atIndex = () -> c.iload(pv).loadConstant(inner).imul().iload(iv).iadd().istore(at);
            loop(c, pv, ln.outer(), nextP -> {
                c.dconst_0().dstore(m);
                loop(c, iv, inner, nx -> {
                    atIndex.run();
                    c.dload(m).aload(x).iload(at).daload().dadd().dstore(m);
                });
                c.dload(m).loadConstant((double) inner).ddiv().dstore(m);
                c.dconst_0().dstore(v);
                loop(c, iv, inner, nx -> {
                    atIndex.run();
                    c.aload(x).iload(at).daload().dload(m).dsub().dstore(d);
                    c.dload(v).dload(d).dload(d).dmul().dadd().dstore(v);
                });
                c.dload(v).loadConstant((double) inner).ddiv().dstore(v);
                c.loadConstant(1.0).dload(v).loadConstant(ln.eps()).dadd();
                math(c, "sqrt", 1);
                c.ddiv().dstore(rr);
                c.aload(mean).iload(pv).dload(m).dastore();
                c.aload(rstd).iload(pv).dload(rr).dastore();
                loop(c, iv, inner, nx -> {
                    atIndex.run();
                    c.aload(x).iload(at).daload().dload(m).dsub().dload(rr).dmul().dstore(y);
                    if (w >= 0) {
                        c.dload(y).aload(w).iload(iv).daload().dmul().dstore(y);
                    }
                    if (b >= 0) {
                        c.dload(y).aload(b).iload(iv).daload().dadd().dstore(y);
                    }
                    c.aload(o).iload(at).dload(y).dastore();
                });
            });
            c.return_();
        }

        /** The interpreter's matmul: o[i, j] += a[i, p] b[p, j] in the order i, p, j, zeros included. */
        static void product(CodeBuilder c, Matmul mm) {
            final int a = array(c, mm.a.storage), b = array(c, mm.b.storage), o = array(c, mm.out);
            c.aload(o).dconst_0().invokestatic(ARRAYS, "fill", FILL);
            final int nb = mm.batch.length;
            final int[] zv = new int[nb];
            for (int d = 0; d < nb; d++) {
                zv[d] = c.allocateLocal(TypeKind.INT);
            }
            final int iv = c.allocateLocal(TypeKind.INT), pv = c.allocateLocal(TypeKind.INT), jv = c.allocateLocal(TypeKind.INT);
            final int av = c.allocateLocal(TypeKind.DOUBLE);
            final int[] so = new int[nb];
            int acc = mm.n * mm.m;
            for (int d = nb - 1; d >= 0; d--) {
                so[d] = acc;
                acc *= mm.batch[d];
            }
            final int[] aStr = Arrays.copyOf(mm.sa, nb + 2), bStr = Arrays.copyOf(mm.sb, nb + 2), oStr = Arrays.copyOf(so, nb + 3);
            aStr[nb] = mm.sai;
            aStr[nb + 1] = mm.sap;
            bStr[nb] = mm.sbp;
            bStr[nb + 1] = mm.sbj;
            oStr[nb] = mm.m;
            oStr[nb + 1] = 0;
            oStr[nb + 2] = 1;
            final int[] aVars = Arrays.copyOf(zv, nb + 2), bVars = Arrays.copyOf(zv, nb + 2), oVars = Arrays.copyOf(zv, nb + 3);
            aVars[nb] = iv;
            aVars[nb + 1] = pv;
            bVars[nb] = pv;
            bVars[nb + 1] = jv;
            oVars[nb] = iv;
            oVars[nb + 1] = pv;
            oVars[nb + 2] = jv;
            nest(c, mm.batch, zv, 0, () -> loop(c, iv, mm.n, nextI -> loop(c, pv, mm.k, nextP -> {
                c.aload(a);
                index(c, mm.a.offset, aStr, aVars);
                c.daload().dstore(av);
                // a zero is multiplied like the others, as the interpreter does: 0 * NaN is NaN, and a NaN must not vanish
                loop(c, jv, mm.m, nextJ -> {
                    c.aload(o);
                    index(c, 0, oStr, oVars);
                    c.dup2().daload().dload(av).aload(b);
                    index(c, mm.b.offset, bStr, bVars);
                    c.daload().dmul().dadd().dastore();
                });
            })));
            c.return_();
        }
    }

    // ------------------------------------------------------------------
    // The compiled graph
    // ------------------------------------------------------------------

    /** A graph compiled for one batch: run it, check it against the interpreter. */
    public static final class CompiledGraph {
        public final NeuralGraph graph;
        public final int batch;
        final int[][] inShapes;
        private final int[] inputSlots;
        private final double[][] template;
        private final int kernelBase;
        private final int[] kernelSizes;
        private final Fallback[] fallbacks;
        final List<Object> outs;
        private final MethodHandle run;
        private final String report;
        private final ThreadLocal<double[][]> slots;
        List<Instr> instrs = List.of();
        List<Storage> storages = List.of();

        CompiledGraph(NeuralGraph graph, int batch, int[][] inShapes, int[] inputSlots, double[][] template, int kernelBase,
                      int[] kernelSizes, Fallback[] fallbacks, List<Object> outs, MethodHandle run, String report) {
            this.graph = graph;
            this.batch = batch;
            this.inShapes = inShapes;
            this.inputSlots = inputSlots;
            this.template = template;
            this.kernelBase = kernelBase;
            this.kernelSizes = kernelSizes;
            this.fallbacks = fallbacks;
            this.outs = outs;
            this.run = run;
            this.report = report;
            this.slots = ThreadLocal.withInitial(() -> {
                final double[][] s = template.clone();
                for (int i = 0; i < kernelSizes.length; i++) {
                    s[kernelBase + i] = new double[kernelSizes[i]];
                }
                return s;
            });
        }

        /** What the compiler made of the graph. */
        public String report() {
            return report;
        }

        static void fallback(Object prog, int i, double[][] s) {
            ((CompiledGraph) prog).fallbacks[i].exec(s);
        }

        /** The outputs for these inputs (of the shapes compiled for). */
        public List<Tensor> run(List<Tensor> in) {
            if (in.size() != inputSlots.length) {
                throw new GraphException(graph.name + " takes " + inputSlots.length + " inputs, not " + in.size());
            }
            final double[][] s = slots.get();
            for (int k = 0; k < in.size(); k++) {
                if (!Arrays.equals(in.get(k).shape, inShapes[k])) {
                    throw new GraphException("input " + k + " of " + Arrays.toString(in.get(k).shape) + ": compiled for "
                        + Arrays.toString(inShapes[k]));
                }
                s[inputSlots[k]] = in.get(k).data;
            }
            try {
                run.invokeExact(s, (Object) this);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new GraphException(String.valueOf(t));
            }
            final List<Tensor> out = new ArrayList<>(outs.size());
            for (Object o : outs) {
                if (o instanceof View v) {
                    out.add(copy(s[v.storage.slot], v));
                } else {
                    final Tensor t = (Tensor) o;
                    out.add(new Tensor(t.data.clone(), t.shape.clone()));
                }
            }
            for (int k = 0; k < in.size(); k++) {
                s[inputSlots[k]] = null;   // the caller's arrays are not kept
            }
            return out;
        }

        private static Tensor copy(double[] src, View v) {
            final int n = NeuralGraph.numel(v.shape), r = v.shape.length;
            final double[] o = new double[n];
            final int[] idx = new int[r];
            int at = v.offset;
            for (int e = 0; e < n; e++) {
                o[e] = src[at];
                for (int d = r - 1; d >= 0; d--) {
                    idx[d]++;
                    at += v.strides[d];
                    if (idx[d] < v.shape[d]) {
                        break;
                    }
                    at -= v.strides[d] * v.shape[d];
                    idx[d] = 0;
                }
            }
            return new Tensor(o, v.shape.clone());
        }

        /** The compiled outputs against the interpreter's on the same inputs: the number of bits that differ (0: the same, to the bit). */
        public long check(List<Tensor> in) {
            final List<Tensor> mine = run(in), theirs = graph.run(in, Double.POSITIVE_INFINITY, 1, null);
            long differ = 0;
            for (int i = 0; i < mine.size(); i++) {
                final double[] a = mine.get(i).data, b = theirs.get(i).data;
                if (a.length != b.length || !Arrays.equals(mine.get(i).shape, theirs.get(i).shape)) {
                    return Long.MAX_VALUE;
                }
                for (int k = 0; k < a.length; k++) {
                    if (Double.doubleToRawLongBits(a[k]) != Double.doubleToRawLongBits(b[k])) {
                        differ++;
                    }
                }
            }
            return differ;
        }
    }
}
