package com.sphere.core.ml;

import com.sphere.core.ml.NeuralGraph.GraphException;
import com.sphere.core.ml.NeuralGraph.Node;
import com.sphere.core.ml.NeuralGraph.Ref;
import com.sphere.core.ml.NeuralGraph.Tensor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * PyTorch's autograd without PyTorch: reverse-mode differentiation of a portable graph (sphere_graph's core ATen
 * operations, as {@link NeuralGraph} runs them), in double precision, so that Sphere trains and fine-tunes a
 * model of PyTorch, of Keras or of TensorFlow with neither -- in a FastJet loop, on a machine with no Python.
 *
 * <p>The forward pass is NeuralGraph's own: the same {@code apply} on the same nodes, its values kept on a tape
 * (each array once, however many views share it). The backward pass walks the tape in reverse with one rule a
 * node -- the formulas of PyTorch's derivatives.yaml and of its CPU kernels, in their order of operations where
 * that decides the last bit. What depends on no trained weight is never differentiated: a mask, an index, a size.
 * The operations that only move their input's elements -- a slice, a gather, a cat, a pad, an advanced index --
 * share one rule, exact by construction: the operation run once more on the indices of its input's elements says
 * where each element of its output came from, and the gradient goes back the way it came. Loops and conditions
 * (sphere.while, sphere.case) are differentiated through the iterations and the branch that ran, each a tape of
 * its own.
 *
 * <p>Every operation NeuralGraph runs has its rule here, or carries no gradient (comparisons, indices, sizes,
 * what is made from nothing): {@link #coverage} says which a graph meets. ':torch grad' holds the gradients to
 * PyTorch's own and {@link #gradcheck} to finite differences, with no Python at all.
 */
public final class GraphAutograd {

    /** Operations whose values carry no gradient: sizes, indices, booleans, what is created from nothing. */
    static final Set<String> CONSTANT = Set.of(
        "_assert_tensor_metadata.default", "_assert_async.msg", "_assert_scalar.default", "_functional_assert_async.msg",
        "sym_constrain_range_for_size.default", "sym_constrain_range.default", "sym_size.int", "sym_numel.default",
        "py.mul", "py.add", "py.sub", "py.floordiv", "py.truediv", "py.neg", "py.mod", "py.pow",
        "full.default", "full_like.default", "zeros_like.default", "ones_like.default", "zeros.default", "ones.default",
        "empty.memory_format", "scalar_tensor.default", "arange.start_step", "arange.default", "arange.start",
        "sign.default", "floor.default", "ceil.default", "round.default", "trunc.default",
        "logical_not.default", "isnan.default", "isinf.default", "logical_xor.default", "logical_and.default",
        "logical_or.default", "eq.Scalar", "eq.Tensor", "ne.Scalar", "ne.Tensor", "lt.Scalar", "lt.Tensor", "le.Scalar",
        "le.Tensor", "gt.Scalar", "gt.Tensor", "ge.Scalar", "ge.Tensor", "argmax.default", "argmin.default", "any.dim",
        "any.default", "detach.default");

    /** Operations differentiated here. */
    static final Set<String> DIFFERENTIABLE = Set.of(
        "py.getitem", "view.default", "_unsafe_view.default", "reshape.default", "permute.default", "expand.default",
        "clone.default", "alias.default", "lift_fresh_copy.default", "contiguous.default", "squeeze.dims", "squeeze.dim",
        "squeeze.default", "unsqueeze.default", "select.int", "slice.Tensor", "cat.default", "stack.default",
        "split_with_sizes.default", "split.Tensor", "unbind.int", "flip.default", "roll.default", "repeat.default",
        "constant_pad_nd.default", "_to_copy.default", "to.dtype",
        "mm.default", "addmm.default", "bmm.default", "matmul.default", "baddbmm.default", "dot.default",
        "relu.default", "gelu.default", "sigmoid.default", "tanh.default", "exp.default", "log.default", "sqrt.default",
        "rsqrt.default", "neg.default", "abs.default", "sin.default", "cos.default", "sinh.default", "cosh.default",
        "atan.default", "asin.default", "acos.default", "log1p.default", "expm1.default", "log2.default", "log10.default",
        "erf.default", "reciprocal.default", "silu.default", "leaky_relu.default", "hardtanh.default", "elu.default",
        "softplus.default", "clamp.default", "clamp.Tensor", "clamp_min.default", "clamp_max.default",
        "pow.Tensor_Scalar", "pow.Tensor_Tensor", "pow.Scalar",
        "add.Tensor", "add.Scalar", "sub.Tensor", "sub.Scalar", "rsub.Scalar", "rsub.Tensor", "mul.Tensor", "mul.Scalar",
        "div.Tensor", "div.Scalar", "div.Tensor_mode", "remainder.Scalar", "remainder.Tensor", "fmod.Scalar",
        "maximum.default", "minimum.default", "atan2.default", "where.self",
        "sum.dim_IntList", "sum.default", "mean.dim", "mean.default", "amax.default", "amin.default", "prod.dim_int",
        "max.dim", "min.dim", "max.default", "min.default", "var.correction", "cumsum.default",
        "_softmax.default", "_log_softmax.default", "native_layer_norm.default",
        "_native_batch_norm_legit_no_training.default",
        "gather.default", "index_select.default", "embedding.default", "index.Tensor", "topk.default", "sort.default",
        "convolution.default", "max_pool2d_with_indices.default", "avg_pool2d.default", "_adaptive_avg_pool2d.default");

    /** Operations that only move their source's elements: differentiated by their own indices (see the class). */
    private static final Set<String> MOVES = Set.of("select.int", "slice.Tensor", "split_with_sizes.default", "split.Tensor",
        "unbind.int", "flip.default", "roll.default", "repeat.default", "constant_pad_nd.default", "gather.default",
        "index_select.default", "embedding.default", "index.Tensor");

    private final NeuralGraph graph;
    private final Set<String> leaves;
    /** Whether the leaves were guessed (a graph exported frozen), not the model's own parameters. */
    public final boolean guessed;

    /** The graph's autograd, its leaves the model's parameters (or, for a graph exported frozen, its float weights). */
    public GraphAutograd(NeuralGraph graph) {
        this(graph, graph.params.isEmpty() ? floatWeights(graph) : graph.params.keySet(), graph.params.isEmpty());
    }

    /** The graph's autograd with these weights (by graph name) as the leaves differentiated. */
    public GraphAutograd(NeuralGraph graph, Collection<String> leaves) {
        this(graph, leaves, false);
    }

    private GraphAutograd(NeuralGraph graph, Collection<String> leaves, boolean guessed) {
        this.graph = graph;
        this.leaves = Collections.unmodifiableSet(new LinkedHashSet<>(leaves));
        this.guessed = guessed;
        for (String l : this.leaves) {
            if (!graph.consts.containsKey(l)) {
                throw new GraphException(graph.name + " has no weight " + l);
            }
        }
    }

    /**
     * The weights of a graph exported frozen that look trained: of two values or more, one of them not a whole
     * number (an index table, a shape, a mask is whole). What folding made of the parameters -- a weight
     * transposed -- is then what is trained: the same function, another parametrization than the model's.
     */
    static Set<String> floatWeights(NeuralGraph g) {
        final Set<String> out = new TreeSet<String>();
        for (Map.Entry<String, Tensor> e : g.consts.entrySet()) {
            final double[] d = e.getValue().data;
            if (d.length < 2) {
                continue;
            }
            for (double v : d) {
                if (v != Math.rint(v)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    public Set<String> leaves() {
        return leaves;
    }

    public NeuralGraph graph() {
        return graph;
    }

    /** The number of values trained. */
    public long trainable() {
        long n = 0;
        for (String l : leaves) {
            n += graph.consts.get(l).numel();
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Coverage: what a graph needs of this autograd
    // ------------------------------------------------------------------

    /**
     * The operations of a graph on the way from its trained weights to its outputs, by kind: differentiated,
     * carrying no gradient, and missing (which would stop a training: none, for any graph NeuralGraph runs).
     */
    public record Coverage(Map<String, Integer> differentiated, Map<String, Integer> constant, Set<String> missing,
                           int control, long weights) {
        public boolean complete() {
            return missing.isEmpty();
        }
    }

    public Coverage coverage() {
        final Map<String, Integer> diff = new TreeMap<>(), cons = new TreeMap<>();
        final Set<String> missing = new TreeSet<>();
        final int[] control = {0};
        final Set<String> live = new HashSet<>(leaves);
        live.addAll(graph.inputs);   // what the inputs feed may be differentiated too (saliencies)
        scan(graph.nodes, live, diff, cons, missing, control, new HashSet<>());
        return new Coverage(diff, cons, missing, control[0], trainable());
    }

    private void scan(List<Node> nodes, Set<String> live, Map<String, Integer> diff, Map<String, Integer> cons, Set<String> missing,
                      int[] control, Set<String> seenBlocks) {
        for (Node nd : nodes) {
            boolean any = false;
            for (String r : nd.refs()) {
                any |= live.contains(r);
            }
            if (NeuralGraph.CONTROL.contains(nd.op())) {
                control[0]++;
                for (Object a : nd.args()) {
                    final List<String> blocks = new ArrayList<>();
                    if (a instanceof String s && graph.blocks.containsKey(s)) {
                        blocks.add(s);
                    } else if (a instanceof List<?> l) {
                        for (Object e : l) {
                            if (e instanceof String s && graph.blocks.containsKey(s)) {
                                blocks.add(s);
                            }
                        }
                    }
                    for (String b : blocks) {
                        if (seenBlocks.add(b)) {
                            final NeuralGraph.Block blk = graph.blocks.get(b);
                            final Set<String> inner = new HashSet<>(live);
                            inner.addAll(blk.inputs());
                            scan(blk.nodes(), inner, diff, cons, missing, control, seenBlocks);
                        }
                    }
                }
                live.add(nd.name());
                continue;
            }
            if (!any) {
                continue;
            }
            if (CONSTANT.contains(nd.op())) {
                cons.merge(nd.op(), 1, Integer::sum);
            } else if (DIFFERENTIABLE.contains(nd.op())) {
                diff.merge(nd.op(), 1, Integer::sum);
                live.add(nd.name());
            } else {
                missing.add(nd.op());
                live.add(nd.name());
            }
        }
    }

    // ------------------------------------------------------------------
    // The tape
    // ------------------------------------------------------------------

    /** One node run: its arguments' values, its value, whether it depends on a leaf, the frames a control node ran. */
    record Step(Node node, Object[] args, Object out, boolean live, List<Frame> subs) {
    }

    /** One run of a list of nodes -- the graph's, or a block's -- and what its backward needs. */
    public static final class Frame {
        final List<String> inputs, outputs;
        final Map<String, Object> env;
        final Set<String> live = new HashSet<>();
        final List<Step> steps = new ArrayList<>();

        Frame(List<String> inputs, List<String> outputs, Map<String, Object> env) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.env = env;
        }

        /** The frame's output values, in order. */
        public List<Object> values() {
            final List<Object> out = new ArrayList<>(outputs.size());
            for (String o : outputs) {
                out.add(env.get(o));
            }
            return out;
        }

        /** Its outputs as tensors (the graph's outputs are). */
        public List<Tensor> outputs() {
            final List<Tensor> out = new ArrayList<>(outputs.size());
            for (String o : outputs) {
                out.add(NeuralGraph.tensor(env.get(o)));
            }
            return out;
        }

        boolean liveOutput(int i) {
            return live.contains(outputs.get(i));
        }
    }

    /** The graph run on its inputs with its tape kept; withInputs makes the inputs differentiable too. */
    public Frame forward(List<Tensor> in, boolean withInputs) {
        if (in.size() != graph.inputs.size()) {
            throw new GraphException(graph.name + " takes " + graph.inputs.size() + " inputs, not " + in.size());
        }
        final boolean[] live = new boolean[in.size()];
        Arrays.fill(live, withInputs);
        return run(graph.nodes, graph.inputs, new ArrayList<Object>(in), live, graph.outputs);
    }

    private Frame run(List<Node> nodes, List<String> inputNames, List<?> values, boolean[] liveIn, List<String> outputNames) {
        final Frame f = new Frame(inputNames, outputNames, new HashMap<>(graph.consts));
        f.live.addAll(leaves);
        for (int i = 0; i < inputNames.size(); i++) {
            f.env.put(inputNames.get(i), values.get(i));
            if (liveIn[i]) {
                f.live.add(inputNames.get(i));
            } else {
                f.live.remove(inputNames.get(i));
            }
        }
        for (Node nd : nodes) {
            final Object[] a = new Object[nd.args().length];
            for (int i = 0; i < a.length; i++) {
                a[i] = NeuralGraph.resolve(nd.args()[i], f.env);
            }
            final Object v;
            boolean live;
            List<Frame> subs = null;
            try {
                if (NeuralGraph.CONTROL.contains(nd.op())) {
                    subs = new ArrayList<>();
                    final boolean[] outLive = new boolean[1];
                    v = control(nd, a, f.live, subs, outLive);
                    live = outLive[0];
                } else {
                    v = NeuralGraph.apply(nd.op(), a);
                    live = carries(nd, a, f.live);
                }
            } catch (GraphException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new GraphException(nd.name() + " (" + nd.op() + "): " + e);
            }
            if (live && subs == null && !DIFFERENTIABLE.contains(nd.op())) {
                throw new GraphException("no gradient for " + nd.op() + " (" + nd.name() + "), on the way from a trained weight");
            }
            f.steps.add(new Step(nd, a, v, live, subs));
            f.env.put(nd.name(), v);
            if (live) {
                f.live.add(nd.name());
            }
        }
        return f;
    }

    /** Whether a node's value depends on a leaf through operations that carry a gradient. */
    private static boolean carries(Node nd, Object[] a, Set<String> live) {
        final String op = nd.op();
        if (CONSTANT.contains(op)) {
            return false;
        }
        if (op.equals("div.Tensor_mode") && a.length > 2 && a[2] != null) {
            return false;   // floor and trunc division: no gradient, as PyTorch's
        }
        if ((op.equals("_to_copy.default") || op.equals("to.dtype")) && a.length > 1 && a[1] != null && !floating(a[1].toString())) {
            return false;   // to integers or booleans: no gradient
        }
        for (String r : nd.refs()) {
            if (live.contains(r)) {
                return true;
            }
        }
        return false;
    }

    private static boolean floating(String dtype) {
        return dtype.startsWith("float") || dtype.equals("bfloat16") || dtype.equals("none") || dtype.isEmpty();
    }

    private static boolean truth(Object v) {
        final Tensor t = NeuralGraph.tensor(v);
        if (t.numel() != 1) {
            throw new GraphException("a condition of " + t.numel() + " values, not one");
        }
        return t.data[0] != 0;
    }

    /** sphere.while and sphere.case run as NeuralGraph runs them, each block run a frame of the tape. */
    private Object control(Node nd, Object[] a, Set<String> live, List<Frame> subs, boolean[] outLive) {
        final List<?> tokens = (List<?>) nd.args()[2];
        final List<Object> vals = new ArrayList<>((List<?>) a[2]);
        boolean[] lv = new boolean[vals.size()];
        for (int i = 0; i < lv.length; i++) {
            lv[i] = tokens.get(i) instanceof Ref r && live.contains(r.name());
        }
        if (nd.op().equals("sphere.while")) {
            final NeuralGraph.Block cond = block((String) a[0]), body = block((String) a[1]);
            final long limit = a.length > 3 && a[3] != null ? NeuralGraph.lng(a[3]) : NeuralGraph.LOOP_LIMIT;
            List<Object> cur = vals;
            for (long n = 0; ; ) {
                final Frame c = run(cond.nodes(), cond.inputs(), cur, new boolean[cur.size()], cond.outputs());
                if (!truth(c.values().get(0))) {
                    break;
                }
                final Frame b = run(body.nodes(), body.inputs(), cur, lv, body.outputs());
                subs.add(b);
                cur = b.values();
                lv = new boolean[cur.size()];
                for (int i = 0; i < lv.length; i++) {
                    lv[i] = b.liveOutput(i);
                }
                if (++n >= limit) {
                    throw new GraphException("a loop past " + limit + " iterations");
                }
            }
            boolean any = false;
            for (boolean b : lv) {
                any |= b;
            }
            outLive[0] = any;
            return cur;
        }
        final int k = (int) NeuralGraph.num(a[0]);
        final List<?> branches = (List<?>) a[1];
        final NeuralGraph.Block b = block((String) branches.get(k >= 0 && k < branches.size() ? k : branches.size() - 1));
        final Frame f = run(b.nodes(), b.inputs(), vals, lv, b.outputs());
        subs.add(f);
        boolean any = false;
        for (int i = 0; i < b.outputs().size(); i++) {
            any |= f.liveOutput(i);
        }
        outLive[0] = any;
        return f.values();
    }

    private NeuralGraph.Block block(String name) {
        final NeuralGraph.Block b = graph.blocks.get(name);
        if (b == null) {
            throw new GraphException(graph.name + " has no block " + name);
        }
        return b;
    }

    /** The bytes a tape holds: each array once, the weights apart. */
    public long bytes(Frame f) {
        final IdentityHashMap<double[], Boolean> seen = new IdentityHashMap<>();
        for (Tensor t : graph.consts.values()) {
            seen.put(t.data, Boolean.FALSE);
        }
        final long[] total = {0};
        count(f, seen, total);
        return total[0];
    }

    private static void count(Frame f, IdentityHashMap<double[], Boolean> seen, long[] total) {
        for (String i : f.inputs) {
            countValue(f.env.get(i), seen, total);
        }
        for (Step s : f.steps) {
            countValue(s.out, seen, total);
            if (s.subs != null) {
                for (Frame sub : s.subs) {
                    count(sub, seen, total);
                }
            }
        }
    }

    private static void countValue(Object v, IdentityHashMap<double[], Boolean> seen, long[] total) {
        if (v instanceof Tensor t) {
            if (!seen.containsKey(t.data)) {
                seen.put(t.data, Boolean.TRUE);
                total[0] += 8L * t.data.length;
            }
        } else if (v instanceof Object[] tuple) {
            for (Object e : tuple) {
                countValue(e, seen, total);
            }
        } else if (v instanceof List<?> list) {
            for (Object e : list) {
                countValue(e, seen, total);
            }
        }
    }

    // ------------------------------------------------------------------
    // Backward
    // ------------------------------------------------------------------

    /** The gradients of one backward pass: of each leaf (by graph name), and of each input if asked. */
    public record Gradients(Map<String, double[]> weights, List<Tensor> inputs) {
    }

    /**
     * The gradient of sum_k (seeds_k . outputs_k) -- seeds_k the loss's gradient with respect to output k, null for
     * an output the loss does not take -- with respect to every leaf, and to the inputs if the forward made them
     * differentiable.
     */
    public Gradients backward(Frame top, List<Tensor> seeds) {
        if (seeds.size() != top.outputs.size()) {
            throw new GraphException(seeds.size() + " seeds for " + top.outputs.size() + " outputs");
        }
        final Map<String, double[]> grads = new LinkedHashMap<>();
        for (String l : leaves) {
            grads.put(l, new double[graph.consts.get(l).numel()]);
        }
        final List<Object> outGrads = new ArrayList<>(seeds);
        final List<Object> in = back(top, outGrads, grads);
        final List<Tensor> inputs = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            final Object g = in.get(i);
            final Tensor x = NeuralGraph.tensor(top.env.get(top.inputs.get(i)));
            inputs.add(g instanceof Tensor t ? t : new Tensor(new double[x.numel()], x.shape.clone()));
        }
        return new Gradients(grads, inputs);
    }

    /** Forward and backward at once. */
    public Gradients gradients(List<Tensor> in, List<Tensor> seeds, boolean withInputs) {
        return backward(forward(in, withInputs), seeds);
    }

    /** The gradients flowing into a frame's inputs; its leaves' added into grads. */
    private List<Object> back(Frame f, List<Object> outGrads, Map<String, double[]> grads) {
        final Map<String, Object> gr = new HashMap<>();
        for (int i = 0; i < f.outputs.size(); i++) {
            final Object g = outGrads.get(i);
            if (g != null && f.live.contains(f.outputs.get(i))) {
                gr.merge(f.outputs.get(i), g, GraphAutograd::plus);
            }
        }
        for (int s = f.steps.size() - 1; s >= 0; s--) {
            final Step st = f.steps.get(s);
            if (!st.live) {
                continue;
            }
            final Object gy = gr.remove(st.node.name());
            if (gy == null) {
                continue;
            }
            final Object[] tok = st.node.args();
            final boolean[] need = new boolean[tok.length];
            for (int i = 0; i < tok.length; i++) {
                if (tok[i] instanceof Ref r) {
                    need[i] = f.live.contains(r.name());
                } else if (tok[i] instanceof List<?> l) {
                    for (Object e : l) {
                        need[i] |= e instanceof Ref r && f.live.contains(r.name());
                    }
                }
            }
            final Object[] gin;
            try {
                gin = st.subs != null ? controlVjp(st, gy, grads) : vjp(st, gy, need);
            } catch (GraphException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new GraphException("the gradient of " + st.node.name() + " (" + st.node.op() + "): " + e);
            }
            for (int i = 0; i < tok.length && i < gin.length; i++) {
                if (gin[i] == null || !need[i]) {
                    continue;
                }
                if (tok[i] instanceof Ref r) {
                    gr.merge(r.name(), gin[i], GraphAutograd::plus);
                } else if (tok[i] instanceof List<?> l && gin[i] instanceof List<?> gl) {
                    for (int j = 0; j < l.size() && j < gl.size(); j++) {
                        if (l.get(j) instanceof Ref r && gl.get(j) != null && f.live.contains(r.name())) {
                            gr.merge(r.name(), gl.get(j), GraphAutograd::plus);
                        }
                    }
                }
            }
        }
        for (String l : leaves) {
            final Object g = gr.remove(l);
            if (g instanceof Tensor t) {
                final double[] acc = grads.get(l);
                if (t.numel() != acc.length) {
                    throw new GraphException("a gradient of " + t.numel() + " values for the weight " + l + " of " + acc.length);
                }
                for (int k = 0; k < acc.length; k++) {
                    acc[k] += t.data[k];
                }
            }
        }
        final List<Object> in = new ArrayList<>(f.inputs.size());
        for (String i : f.inputs) {
            in.add(gr.get(i));
        }
        return in;
    }

    private Object[] controlVjp(Step st, Object gy, Map<String, double[]> grads) {
        final List<?> outs = (List<?>) st.out;
        List<Object> g = new ArrayList<>(Collections.nCopies(outs.size(), null));
        if (gy instanceof List<?> l) {
            for (int i = 0; i < l.size() && i < g.size(); i++) {
                g.set(i, l.get(i));
            }
        } else {
            throw new GraphException("a gradient of " + gy.getClass().getSimpleName() + " for the values of " + st.node.op());
        }
        for (int k = st.subs.size() - 1; k >= 0; k--) {
            g = back(st.subs.get(k), g, grads);
        }
        final Object[] gin = new Object[st.args.length];
        gin[2] = g;
        return gin;
    }

    /** a + b for gradients: tensors of one shape, tuples and lists elementwise; never in place. */
    static Object plus(Object a, Object b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a instanceof Tensor x && b instanceof Tensor y) {
            if (x.numel() != y.numel()) {
                throw new GraphException("gradients of " + Arrays.toString(x.shape) + " and " + Arrays.toString(y.shape) + " added");
            }
            final double[] o = new double[x.numel()];
            for (int k = 0; k < o.length; k++) {
                o[k] = x.data[k] + y.data[k];
            }
            return new Tensor(o, x.shape.clone());
        }
        if (a instanceof Object[] x && b instanceof Object[] y) {
            final Object[] o = new Object[Math.max(x.length, y.length)];
            for (int i = 0; i < o.length; i++) {
                o[i] = plus(i < x.length ? x[i] : null, i < y.length ? y[i] : null);
            }
            return o;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            final List<Object> o = new ArrayList<>();
            for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
                o.add(plus(i < x.size() ? x.get(i) : null, i < y.size() ? y.get(i) : null));
            }
            return o;
        }
        throw new GraphException("gradients of " + a.getClass().getSimpleName() + " and " + b.getClass().getSimpleName() + " added");
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    private static Tensor T(Object v) {
        return NeuralGraph.tensor(v);
    }

    private static int numel(int[] s) {
        return NeuralGraph.numel(s);
    }

    private static Tensor zeros(int[] shape) {
        return new Tensor(new double[numel(shape)], shape.clone());
    }

    /** g reshaped (its data shared: gradients are never written in place). */
    private static Tensor as(Tensor g, int[] shape) {
        return new Tensor(g.data, shape.clone());
    }

    private static Tensor scaled(Tensor g, double s) {
        final double[] o = new double[g.numel()];
        for (int k = 0; k < o.length; k++) {
            o[k] = g.data[k] * s;
        }
        return new Tensor(o, g.shape.clone());
    }

    /** g summed down to shape, as broadcasting made it bigger: PyTorch's sum_to. */
    static Tensor sumTo(Tensor g, int[] shape) {
        if (Arrays.equals(g.shape, shape)) {
            return g;
        }
        final double[] o = new double[numel(shape)];
        if (g.numel() == 0) {
            return new Tensor(o, shape.clone());
        }
        final int[] st = NeuralGraph.bstrides(shape, g.shape), gs = g.shape;
        final int rank = gs.length;
        final int[] idx = new int[rank];
        int io = 0;
        for (int k = 0; k < g.data.length; k++) {
            o[io] += g.data[k];
            for (int d = rank - 1; d >= 0; d--) {
                idx[d]++;
                io += st[d];
                if (idx[d] < gs[d]) {
                    break;
                }
                io -= st[d] * gs[d];
                idx[d] = 0;
            }
        }
        return new Tensor(o, shape.clone());
    }

    @FunctionalInterface
    private interface F3 {
        double at(double g, double a, double b);
    }

    /** f(g, a, b) at every element of g, a and b broadcast to g's shape (b may be null). */
    private static Tensor zip(Tensor g, Tensor a, Tensor b, F3 f) {
        final double[] o = new double[g.numel()];
        final int[] gs = g.shape;
        final boolean sameA = Arrays.equals(a.shape, gs), sameB = b == null || Arrays.equals(b.shape, gs);
        if (sameA && sameB) {
            for (int k = 0; k < o.length; k++) {
                o[k] = f.at(g.data[k], a.data[k], b == null ? 0 : b.data[k]);
            }
            return new Tensor(o, gs.clone());
        }
        final int[] sa = NeuralGraph.bstrides(a.shape, gs), sb = b == null ? new int[gs.length] : NeuralGraph.bstrides(b.shape, gs);
        final int rank = gs.length;
        final int[] idx = new int[rank];
        int ia = 0, ib = 0;
        for (int k = 0; k < o.length; k++) {
            o[k] = f.at(g.data[k], a.data[ia], b == null ? 0 : b.data[ib]);
            for (int d = rank - 1; d >= 0; d--) {
                idx[d]++;
                ia += sa[d];
                ib += sb[d];
                if (idx[d] < gs[d]) {
                    break;
                }
                ia -= sa[d] * gs[d];
                ib -= sb[d] * gs[d];
                idx[d] = 0;
            }
        }
        return new Tensor(o, gs.clone());
    }

    /** The gradient of an elementwise operation of two arguments, to each argument's own shape. */
    private static Tensor part(Tensor g, Tensor a, Tensor b, F3 f, int[] shape) {
        return sumTo(zip(g, a, b, f), shape);
    }

    /** A reduced gradient spread back over the input's shape (the reduced dims restored, then broadcast). */
    private static Tensor restore(Tensor g, int[] xs, int[] dims) {
        final boolean[] red = reduced(xs.length, dims);
        final int[] kept = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            kept[i] = red[i] ? 1 : xs[i];
        }
        return NeuralGraph.broadcastTo(new Tensor(g.data, kept), xs);
    }

    private static boolean[] reduced(int rank, int[] dims) {
        final boolean[] red = new boolean[rank];
        if (dims == null || dims.length == 0) {
            Arrays.fill(red, true);
        } else {
            for (int d : dims) {
                red[d] = true;
            }
        }
        return red;
    }

    private static int count(int[] xs, int[] dims) {
        final boolean[] red = reduced(xs.length, dims);
        int n = 1;
        for (int i = 0; i < xs.length; i++) {
            if (red[i]) {
                n *= xs[i];
            }
        }
        return n;
    }

    /** The last two dims swapped. */
    private static Tensor tr(Tensor t) {
        final int r = t.rank();
        final int[] p = new int[r];
        for (int i = 0; i < r; i++) {
            p[i] = i;
        }
        p[r - 2] = r - 1;
        p[r - 1] = r - 2;
        return NeuralGraph.permute(t, p);
    }

    /** Tensor of the flat indices of an element of shape, plus offset: the source of the moves' rule. */
    private static Tensor iota(int[] shape, int offset) {
        final double[] d = new double[numel(shape)];
        for (int k = 0; k < d.length; k++) {
            d[k] = offset + k;
        }
        return new Tensor(d, shape.clone());
    }

    /** The gradient of an operation that moves its source's elements: scattered back where they came from. */
    private static Tensor moved(String op, Object[] a, int src, Object gy) {
        final Tensor x = T(a[src]);
        Object[] b = a.clone();
        b[src] = iota(x.shape, 0);
        if (op.equals("constant_pad_nd.default")) {
            b = padFromNowhere(b);
        }
        final Object where = NeuralGraph.apply(op, b);
        final double[] dx = new double[x.numel()];
        scatter(where, gy, dx);
        return new Tensor(dx, x.shape.clone());
    }

    private static Object[] padFromNowhere(Object[] b) {
        final Object[] c = Arrays.copyOf(b, Math.max(b.length, 3));
        c[2] = -1.0;   // padding came from no element
        return c;
    }

    private static void scatter(Object where, Object gy, double[] dx) {
        if (gy == null || where == null) {
            return;
        }
        if (where instanceof Tensor w) {
            final Tensor g = T(gy);
            for (int k = 0; k < w.data.length; k++) {
                final int i = (int) w.data[k];
                if (i >= 0) {
                    dx[i] += g.data[k];
                }
            }
        } else if (where instanceof List<?> wl) {
            final List<?> gl = (List<?>) gy;
            for (int j = 0; j < wl.size() && j < gl.size(); j++) {
                scatter(wl.get(j), gl.get(j), dx);
            }
        } else if (where instanceof Object[] wa) {
            final Object[] ga = (Object[]) gy;
            for (int j = 0; j < wa.length && j < ga.length; j++) {
                scatter(wa[j], ga[j], dx);
            }
        }
    }

    /** cat and stack: each element's indices offset by those before it, the gradient split back by them. */
    private static List<Object> movedList(String op, Object[] a, Object gy) {
        final List<?> xs = (List<?>) a[0];
        final List<Object> iotas = new ArrayList<>(xs.size());
        final int[] offset = new int[xs.size() + 1];
        for (int j = 0; j < xs.size(); j++) {
            final Tensor x = T(xs.get(j));
            iotas.add(iota(x.shape, offset[j]));
            offset[j + 1] = offset[j] + x.numel();
        }
        final Object[] b = a.clone();
        b[0] = iotas;
        final double[] all = new double[offset[xs.size()]];
        scatter(NeuralGraph.apply(op, b), gy, all);
        final List<Object> out = new ArrayList<>(xs.size());
        for (int j = 0; j < xs.size(); j++) {
            final Tensor x = T(xs.get(j));
            out.add(new Tensor(Arrays.copyOfRange(all, offset[j], offset[j + 1]), x.shape.clone()));
        }
        return out;
    }

    private static double alpha(Object[] a, int i) {
        return a.length > i && a[i] != null ? NeuralGraph.num(a[i]) : 1;
    }

    /** The gradients of a node's arguments (null where none, or not needed), given its value's gradient gy. */
    private static Object[] vjp(Step st, Object gy, boolean[] need) {
        final String op = st.node.op();
        final Object[] a = st.args;
        final Object[] gin = new Object[a.length];
        if (MOVES.contains(op)) {
            gin[0] = moved(op, a, 0, gy);
            if (op.equals("embedding.default") && a.length > 2 && a[2] != null && NeuralGraph.lng(a[2]) >= 0) {
                final Tensor dw = (Tensor) gin[0];   // the padding row learns nothing, as PyTorch's
                final int row = (int) NeuralGraph.lng(a[2]), width = dw.shape.length > 1 ? dw.numel() / dw.shape[0] : 1;
                Arrays.fill(dw.data, row * width, (row + 1) * width, 0.0);
            }
            return gin;
        }
        switch (op) {
            case "py.getitem" -> {
                final int i = (int) NeuralGraph.lng(a[1]);
                if (a[0] instanceof Object[] t) {
                    final Object[] o = new Object[t.length];
                    o[i < 0 ? i + t.length : i] = gy;
                    gin[0] = o;
                } else {
                    final List<?> l = (List<?>) a[0];
                    final List<Object> o = new ArrayList<>(Collections.nCopies(l.size(), null));
                    o.set(i < 0 ? i + l.size() : i, gy);
                    gin[0] = o;
                }
            }
            case "view.default", "_unsafe_view.default", "reshape.default", "squeeze.dims", "squeeze.dim", "squeeze.default",
                 "unsqueeze.default" -> gin[0] = as(T(gy), T(a[0]).shape);
            case "permute.default" -> {
                final Tensor x = T(a[0]);
                final int[] p = NeuralGraph.dimsOf(a[1], x.rank()), inv = new int[p.length];
                for (int i = 0; i < p.length; i++) {
                    inv[p[i]] = i;
                }
                gin[0] = NeuralGraph.permute(T(gy), inv);
            }
            case "expand.default" -> gin[0] = sumTo(T(gy), T(a[0]).shape);
            case "clone.default", "alias.default", "lift_fresh_copy.default", "contiguous.default", "_to_copy.default", "to.dtype" ->
                gin[0] = as(T(gy), T(a[0]).shape);
            case "cat.default", "stack.default" -> gin[0] = movedList(op, a, gy);
            // ---- products
            case "mm.default", "bmm.default" -> {
                final Tensor g = T(gy), x = T(a[0]), w = T(a[1]);
                if (need[0]) {
                    gin[0] = NeuralGraph.matmul(g, tr(w));
                }
                if (need[1]) {
                    gin[1] = NeuralGraph.matmul(tr(x), g);
                }
            }
            case "matmul.default" -> {
                final Tensor x0 = T(a[0]), w0 = T(a[1]);
                final Tensor x = x0.rank() == 1 ? new Tensor(x0.data, 1, x0.shape[0]) : x0;
                final Tensor w = w0.rank() == 1 ? new Tensor(w0.data, w0.shape[0], 1) : w0;
                Tensor g = T(gy);
                final int[] bx = Arrays.copyOf(x.shape, x.rank() - 2), bw = Arrays.copyOf(w.shape, w.rank() - 2);
                final int[] batch = NeuralGraph.broadcast(bx, bw);
                final int[] gs = Arrays.copyOf(batch, batch.length + 2);
                gs[batch.length] = x.shape[x.rank() - 2];
                gs[batch.length + 1] = w.shape[w.rank() - 1];
                g = as(g, gs);
                if (need[0]) {
                    final Tensor dx = NeuralGraph.matmul(g, tr(w));
                    gin[0] = as(sumTo(dx, x.shape), x0.shape);
                }
                if (need[1]) {
                    final Tensor dw = NeuralGraph.matmul(tr(x), g);
                    gin[1] = as(sumTo(dw, w.shape), w0.shape);
                }
            }
            case "addmm.default", "baddbmm.default" -> {
                final Tensor g = T(gy), x = T(a[1]), w = T(a[2]);
                final double beta = alpha(a, 3), al = alpha(a, 4);
                if (need[0]) {
                    gin[0] = sumTo(beta == 1 ? g : scaled(g, beta), T(a[0]).shape);
                }
                if (need[1]) {
                    final Tensor d = NeuralGraph.matmul(g, tr(w));
                    gin[1] = al == 1 ? d : scaled(d, al);
                }
                if (need[2]) {
                    final Tensor d = NeuralGraph.matmul(tr(x), g);
                    gin[2] = al == 1 ? d : scaled(d, al);
                }
            }
            case "dot.default" -> {
                final double g = T(gy).data[0];
                if (need[0]) {
                    gin[0] = scaled(T(a[1]), g);
                }
                if (need[1]) {
                    gin[1] = scaled(T(a[0]), g);
                }
            }
            // ---- elementwise, one argument
            case "relu.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> y > 0 ? g : 0);
            case "gelu.default" -> {
                final boolean tanh = a.length > 1 && "tanh".equals(a[1]);
                gin[0] = zip(T(gy), T(a[0]), null, tanh ? GraphAutograd::geluTanh : GraphAutograd::gelu);
            }
            case "sigmoid.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> g * (1 - y) * y);
            case "tanh.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> g * (1 - y * y));
            case "exp.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> g * y);
            case "log.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g / x);
            case "sqrt.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> g / (2 * y));
            case "rsqrt.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> -0.5 * g * (y * y * y));
            case "neg.default" -> gin[0] = scaled(T(gy), -1);
            case "abs.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * (x > 0 ? 1 : x < 0 ? -1 : 0));
            case "sin.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * Math.cos(x));
            case "cos.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * -Math.sin(x));
            case "sinh.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * Math.cosh(x));
            case "cosh.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * Math.sinh(x));
            case "atan.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g / (x * x + 1));
            case "asin.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * (1 / Math.sqrt(-x * x + 1)));
            case "acos.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g * -(1 / Math.sqrt(-x * x + 1)));
            case "log1p.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g / (x + 1));
            case "expm1.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> g * (y + 1));
            case "log2.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g / (x * 0.6931471805599453));
            case "log10.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> g / (x * 2.3025850929940456));
            case "erf.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> 2.0 / Math.sqrt(Math.PI) * Math.exp(-(x * x)) * g);
            case "reciprocal.default" -> gin[0] = zip(T(gy), T(st.out), null, (g, y, u) -> -g * (y * y));
            case "silu.default" -> gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> {
                final double s = 1.0 / (1.0 + Math.exp(-x));
                return g * s * (1 + x * (1 - s));
            });
            case "leaky_relu.default" -> {
                final double slope = a.length > 1 && a[1] != null ? NeuralGraph.num(a[1]) : 0.01;
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> x > 0 ? g : g * slope);
            }
            case "hardtanh.default" -> {
                final double lo = a.length > 1 && a[1] != null ? NeuralGraph.num(a[1]) : -1, hi = a.length > 2 && a[2] != null
                    ? NeuralGraph.num(a[2]) : 1;
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> x > lo && x < hi ? g : 0);
            }
            case "elu.default" -> {
                final double al = alpha(a, 1), scale = alpha(a, 2), in = alpha(a, 3), neg = al * scale;
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> x <= 0 ? g * in * neg * Math.exp(x * in) : g * scale);
            }
            case "softplus.default" -> {
                final double beta = alpha(a, 1), th = a.length > 2 && a[2] != null ? NeuralGraph.num(a[2]) : 20;
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> {
                    if (x * beta > th) {
                        return g;
                    }
                    final double z = Math.exp(x * beta);
                    return g * z / (z + 1);
                });
            }
            case "clamp.default" -> {
                final boolean hasLo = a.length > 1 && a[1] != null, hasHi = a.length > 2 && a[2] != null;
                final double lo = hasLo ? NeuralGraph.num(a[1]) : Double.NEGATIVE_INFINITY;
                final double hi = hasHi ? NeuralGraph.num(a[2]) : Double.POSITIVE_INFINITY;
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> x >= lo && x <= hi ? g : 0);
            }
            case "clamp.Tensor" -> {
                final Tensor g = T(gy), x = T(a[0]);
                final Tensor lo = a.length > 1 && a[1] != null ? T(a[1]) : null, hi = a.length > 2 && a[2] != null ? T(a[2]) : null;
                final Tensor lob = lo == null ? null : NeuralGraph.broadcastTo(lo, g.shape);
                final Tensor hib = hi == null ? null : NeuralGraph.broadcastTo(hi, g.shape);
                final Tensor xb = NeuralGraph.broadcastTo(x, g.shape);
                final double[] dx = new double[g.numel()], dl = new double[g.numel()], dh = new double[g.numel()];
                for (int k = 0; k < dx.length; k++) {
                    final boolean ge = lob == null || xb.data[k] >= lob.data[k], le = hib == null || xb.data[k] <= hib.data[k];
                    dx[k] = ge && le ? g.data[k] : 0;
                    dl[k] = ge ? 0 : g.data[k];
                    dh[k] = le ? 0 : g.data[k];
                }
                if (need[0]) {
                    gin[0] = sumTo(new Tensor(dx, g.shape.clone()), x.shape);
                }
                if (lo != null && need[1]) {
                    gin[1] = sumTo(new Tensor(dl, g.shape.clone()), lo.shape);
                }
                if (hi != null && need.length > 2 && need[2]) {
                    gin[2] = sumTo(new Tensor(dh, g.shape.clone()), hi.shape);
                }
            }
            case "clamp_min.default" -> {
                final double lo = NeuralGraph.num(a[1]);
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> x >= lo ? g : 0);
            }
            case "clamp_max.default" -> {
                final double hi = NeuralGraph.num(a[1]);
                gin[0] = zip(T(gy), T(a[0]), null, (g, x, u) -> x <= hi ? g : 0);
            }
            case "pow.Tensor_Scalar" -> {
                final double e = NeuralGraph.num(a[1]);
                gin[0] = e == 0 ? zeros(T(a[0]).shape) : zip(T(gy), T(a[0]), null, (g, x, u) -> g * (e * Math.pow(x, e - 1)));
            }
            case "pow.Tensor_Tensor" -> {
                final Tensor g = T(gy), x = T(a[0]), e = T(a[1]);
                if (need[0]) {
                    gin[0] = part(g, x, e, (gv, xv, ev) -> ev == 0 ? 0 : gv * (ev * Math.pow(xv, ev - 1)), x.shape);
                }
                if (need[1]) {
                    gin[1] = part(g, x, e, (gv, xv, ev) -> xv == 0 && ev >= 0 ? 0 : gv * (Math.pow(xv, ev) * Math.log(xv)), e.shape);
                }
            }
            case "pow.Scalar" -> {
                final double base = NeuralGraph.num(a[0]);
                gin[1] = zip(T(gy), T(st.out), T(a[1]), (g, y, e) -> base == 0 && e >= 0 ? 0 : g * (y * Math.log(base)));
            }
            // ---- elementwise, two arguments, broadcast
            case "add.Tensor", "add.Scalar" -> {
                final Tensor g = T(gy);
                if (need[0]) {
                    gin[0] = sumTo(g, T(a[0]).shape);
                }
                if (need.length > 1 && need[1]) {
                    final double al = alpha(a, 2);
                    gin[1] = sumTo(al == 1 ? g : scaled(g, al), T(a[1]).shape);
                }
            }
            case "sub.Tensor", "sub.Scalar" -> {
                final Tensor g = T(gy);
                if (need[0]) {
                    gin[0] = sumTo(g, T(a[0]).shape);
                }
                if (need.length > 1 && need[1]) {
                    gin[1] = sumTo(scaled(g, -alpha(a, 2)), T(a[1]).shape);
                }
            }
            case "rsub.Scalar", "rsub.Tensor" -> {
                final Tensor g = T(gy);
                if (need[0]) {
                    gin[0] = sumTo(scaled(g, -alpha(a, 2)), T(a[0]).shape);
                }
                if (need.length > 1 && need[1]) {
                    gin[1] = sumTo(g, T(a[1]).shape);
                }
            }
            case "mul.Tensor", "mul.Scalar" -> {
                final Tensor g = T(gy), x = T(a[0]), y = T(a[1]);
                if (need[0]) {
                    gin[0] = part(g, y, null, (gv, yv, u) -> gv * yv, x.shape);
                }
                if (need.length > 1 && need[1]) {
                    gin[1] = part(g, x, null, (gv, xv, u) -> gv * xv, y.shape);
                }
            }
            case "div.Tensor", "div.Scalar", "div.Tensor_mode" -> {
                final Tensor g = T(gy), x = T(a[0]), y = T(a[1]);
                if (need[0]) {
                    gin[0] = part(g, y, null, (gv, yv, u) -> gv / yv, x.shape);
                }
                if (need.length > 1 && need[1]) {
                    gin[1] = part(g, x, y, (gv, xv, yv) -> -gv * ((xv / yv) / yv), y.shape);
                }
            }
            case "remainder.Scalar", "remainder.Tensor" -> {
                final Tensor g = T(gy), x = T(a[0]), y = T(a[1]);
                if (need[0]) {
                    gin[0] = sumTo(g, x.shape);
                }
                if (need.length > 1 && need[1]) {
                    gin[1] = part(g, x, y, (gv, xv, yv) -> -gv * Math.floor(xv / yv), y.shape);
                }
            }
            case "fmod.Scalar" -> gin[0] = sumTo(T(gy), T(a[0]).shape);
            case "maximum.default", "minimum.default" -> {
                final Tensor g = T(gy), x = T(a[0]), y = T(a[1]);
                final boolean max = op.startsWith("max");
                if (need[0]) {   // PyTorch's: a tie halves the gradient between the two
                    gin[0] = part(g, x, y, (gv, xv, yv) -> xv == yv ? gv / 2 : (max ? xv > yv : xv < yv) ? gv : 0, x.shape);
                }
                if (need[1]) {
                    gin[1] = part(g, x, y, (gv, xv, yv) -> xv == yv ? gv / 2 : (max ? yv > xv : yv < xv) ? gv : 0, y.shape);
                }
            }
            case "atan2.default" -> {
                final Tensor g = T(gy), x = T(a[0]), y = T(a[1]);
                if (need[0]) {
                    gin[0] = part(g, x, y, (gv, xv, yv) -> gv * yv * (1 / (xv * xv + yv * yv)), x.shape);
                }
                if (need[1]) {
                    gin[1] = part(g, x, y, (gv, xv, yv) -> gv * -xv * (1 / (xv * xv + yv * yv)), y.shape);
                }
            }
            case "where.self" -> {
                final Tensor g = T(gy), c = T(a[0]);
                if (need[1]) {
                    gin[1] = part(g, c, null, (gv, cv, u) -> cv != 0 ? gv : 0, T(a[1]).shape);
                }
                if (need[2]) {
                    gin[2] = part(g, c, null, (gv, cv, u) -> cv != 0 ? 0 : gv, T(a[2]).shape);
                }
            }
            // ---- reductions
            case "sum.dim_IntList", "sum.default" -> {
                final Tensor x = T(a[0]);
                final int[] dims = op.equals("sum.default") ? null : NeuralGraph.dimsOf(a.length > 1 ? a[1] : null, x.rank());
                gin[0] = restore(T(gy), x.shape, dims);
            }
            case "mean.dim", "mean.default" -> {
                final Tensor x = T(a[0]);
                final int[] dims = op.equals("mean.default") ? null : NeuralGraph.dimsOf(a.length > 1 ? a[1] : null, x.rank());
                final Tensor r = restore(T(gy), x.shape, dims);
                final double n = count(x.shape, dims);
                final double[] o = new double[r.numel()];
                for (int k = 0; k < o.length; k++) {
                    o[k] = r.data[k] / n;
                }
                gin[0] = new Tensor(o, x.shape.clone());
            }
            case "amax.default", "amin.default", "max.default", "min.default" -> {
                final Tensor x = T(a[0]);
                final int[] dims = op.endsWith(".default") && (op.startsWith("max") || op.startsWith("min")) ? null
                    : NeuralGraph.dimsOf(a.length > 1 ? a[1] : null, x.rank());
                final Tensor y = restore(T(st.out), x.shape, dims), g = restore(T(gy), x.shape, dims);
                final double[] mask = new double[x.numel()];
                for (int k = 0; k < mask.length; k++) {
                    mask[k] = x.data[k] == y.data[k] || Double.isNaN(x.data[k]) && Double.isNaN(y.data[k]) ? 1 : 0;
                }
                final Tensor cnt = restore(NeuralGraph.reduce(new Tensor(mask, x.shape.clone()), dims, true, NeuralGraph.SUM), x.shape, dims);
                final double[] o = new double[x.numel()];
                for (int k = 0; k < o.length; k++) {   // ties share the gradient evenly, as PyTorch's
                    o[k] = mask[k] == 0 ? 0 : g.data[k] / cnt.data[k];
                }
                gin[0] = new Tensor(o, x.shape.clone());
            }
            case "max.dim", "min.dim" -> {
                final Tensor x = T(a[0]);
                final int d = NeuralGraph.dim(NeuralGraph.lng(a[1]), x.rank());
                final Object[] out = (Object[]) st.out, g = (Object[]) gy;
                if (g[0] != null) {
                    gin[0] = picked(x.shape, d, T(out[1]), T(g[0]), 1);
                }
            }
            case "topk.default", "sort.default" -> {
                final Tensor x = T(a[0]);
                final int d = NeuralGraph.dim(op.startsWith("topk") ? (a.length > 2 && a[2] != null ? NeuralGraph.lng(a[2]) : -1)
                    : (a.length > 1 && a[1] != null ? NeuralGraph.lng(a[1]) : -1), x.rank());
                final Object[] out = (Object[]) st.out, g = (Object[]) gy;
                if (g[0] != null) {
                    final Tensor idx = T(out[1]);
                    gin[0] = picked(x.shape, d, idx, T(g[0]), x.rank() == 0 ? 1 : idx.shape[d]);
                }
            }
            case "prod.dim_int" -> gin[0] = prodVjp(T(a[0]), NeuralGraph.dim(NeuralGraph.lng(a[1]), T(a[0]).rank()), T(st.out), T(gy));
            case "var.correction" -> {
                final Tensor x = T(a[0]);
                final int[] dims = NeuralGraph.dimsOf(a.length > 1 ? a[1] : null, x.rank());
                final double corr = a.length > 2 && a[2] != null ? NeuralGraph.num(a[2]) : 1;
                final double n = count(x.shape, dims), c = 2.0 / (n - corr);
                final Tensor mean = restore(NeuralGraph.reduce(x, dims, true, NeuralGraph.MEAN), x.shape, dims);
                final Tensor g = restore(T(gy), x.shape, dims);
                final double[] o = new double[x.numel()];
                for (int k = 0; k < o.length; k++) {
                    o[k] = c * g.data[k] * (x.data[k] - mean.data[k]);
                }
                gin[0] = new Tensor(o, x.shape.clone());
            }
            case "cumsum.default" -> {
                final Tensor x = T(a[0]), g = T(gy);
                final NeuralGraph.Around ar = NeuralGraph.Around.of(x.shape, NeuralGraph.dim(NeuralGraph.lng(a[1]), x.rank()));
                final double[] o = new double[x.numel()];
                for (int p = 0; p < ar.outer(); p++) {
                    for (int q = 0; q < ar.inner(); q++) {
                        double acc = 0;
                        for (int i = ar.n() - 1; i >= 0; i--) {
                            final int at = (p * ar.n() + i) * ar.inner() + q;
                            acc += g.data[at];
                            o[at] = acc;
                        }
                    }
                }
                gin[0] = new Tensor(o, x.shape.clone());
            }
            // ---- softmax, normalizations
            case "_softmax.default", "_log_softmax.default" -> {
                final Tensor y = T(st.out), g = T(gy);
                final NeuralGraph.Around ar = NeuralGraph.Around.of(y.shape, NeuralGraph.dim(NeuralGraph.lng(a[1]), y.rank()));
                final boolean log = op.startsWith("_log");
                final double[] o = new double[y.numel()];
                for (int p = 0; p < ar.outer(); p++) {
                    for (int q = 0; q < ar.inner(); q++) {
                        final int base = p * ar.n() * ar.inner() + q;
                        double s = 0;
                        for (int i = 0; i < ar.n(); i++) {
                            final int at = base + i * ar.inner();
                            s += log ? g.data[at] : g.data[at] * y.data[at];
                        }
                        for (int i = 0; i < ar.n(); i++) {
                            final int at = base + i * ar.inner();
                            o[at] = log ? g.data[at] - Math.exp(y.data[at]) * s : y.data[at] * (g.data[at] - s);
                        }
                    }
                }
                gin[0] = new Tensor(o, y.shape.clone());
            }
            case "native_layer_norm.default" -> layerNormVjp(a, (Object[]) st.out, gy, need, gin);
            case "_native_batch_norm_legit_no_training.default" -> batchNormVjp(a, gy, need, gin);
            // ---- images
            case "convolution.default" -> convVjp(a, T(gy instanceof Object[] t ? t[0] : gy), need, gin);
            case "max_pool2d_with_indices.default" -> {
                final Object[] g = (Object[]) gy;
                if (g[0] != null) {
                    gin[0] = poolVjp(a, T(g[0]), true);
                }
            }
            case "avg_pool2d.default" -> gin[0] = poolVjp(a, T(gy), false);
            case "_adaptive_avg_pool2d.default" -> {
                final Tensor x = T(a[0]);
                gin[0] = restoreMean(T(gy), x.shape, new int[] {x.rank() - 2, x.rank() - 1});
            }
            default -> throw new GraphException("no gradient for " + op);
        }
        return gin;
    }

    private static Tensor restoreMean(Tensor g, int[] xs, int[] dims) {
        final Tensor r = restore(g, xs, dims);
        final double n = count(xs, dims);
        final double[] o = new double[r.numel()];
        for (int k = 0; k < o.length; k++) {
            o[k] = r.data[k] / n;
        }
        return new Tensor(o, xs.clone());
    }

    /** The gradient of values picked along d at these indices (max.dim, topk, sort): back to the picked elements. */
    private static Tensor picked(int[] xs, int d, Tensor idx, Tensor g, int k) {
        final double[] dx = new double[numel(xs)];
        if (xs.length == 0) {
            dx[0] = g.data[0];
            return new Tensor(dx, xs.clone());
        }
        final NeuralGraph.Around ar = NeuralGraph.Around.of(xs, d);
        for (int p = 0; p < ar.outer(); p++) {
            for (int q = 0; q < ar.inner(); q++) {
                for (int i = 0; i < k; i++) {
                    final int at = (p * k + i) * ar.inner() + q;
                    dx[(p * ar.n() + (int) idx.data[at]) * ar.inner() + q] += g.data[at];
                }
            }
        }
        return new Tensor(dx, xs.clone());
    }

    private static Tensor prodVjp(Tensor x, int d, Tensor y, Tensor gy) {
        final NeuralGraph.Around ar = NeuralGraph.Around.of(x.shape, d);
        final double[] dx = new double[x.numel()];
        for (int p = 0; p < ar.outer(); p++) {
            for (int q = 0; q < ar.inner(); q++) {
                final int base = p * ar.n() * ar.inner() + q, out = p * ar.inner() + q;
                final double g = gy.data[out], prod = y.data[out];
                int zeros = 0, zeroAt = -1;
                for (int i = 0; i < ar.n(); i++) {
                    if (x.data[base + i * ar.inner()] == 0) {
                        zeros++;
                        zeroAt = i;
                    }
                }
                if (zeros == 0) {
                    for (int i = 0; i < ar.n(); i++) {
                        final int at = base + i * ar.inner();
                        dx[at] = g * (prod / x.data[at]);
                    }
                } else if (zeros == 1) {
                    double others = 1;
                    for (int i = 0; i < ar.n(); i++) {
                        if (i != zeroAt) {
                            others *= x.data[base + i * ar.inner()];
                        }
                    }
                    dx[base + zeroAt * ar.inner()] = g * others;
                }
            }
        }
        return new Tensor(dx, x.shape.clone());
    }

    /** gelu's derivative, as PyTorch's CPU kernel computes it: cdf + x pdf. */
    private static double gelu(double g, double x, double unused) {
        final double kAlpha = 0.70710678118654752440, kBeta = 1.12837916709551257390 * 0.70710678118654752440 * 0.5;
        final double cdf = 0.5 * (1 + NeuralGraph.Erf.erf(x * kAlpha));
        final double pdf = kBeta * Math.exp(x * x * -0.5);
        return g * (cdf + x * pdf);
    }

    private static double geluTanh(double g, double x, double unused) {
        final double kBeta = 1.41421356237309504880 * 1.12837916709551257390 * 0.5, kKappa = 0.044715;
        final double x2 = x * x, x3 = x2 * x, inner = kBeta * (x + kKappa * x3), t = Math.tanh(inner);
        final double left = 0.5 * x, right = 1 + t, dleft = 0.5 * right;
        final double dright = left * (1 - t * t) * kBeta * (1 + 3 * kKappa * x2);
        return g * (dleft + dright);
    }

    /**
     * native_layer_norm's backward, as PyTorch's CPU kernel: per row, ds = sum dy gamma x, db = sum dy gamma,
     * b = (db mean - ds) rstd^3 / N, c = -b mean - db rstd / N, dx = rstd dy gamma + b x + c.
     */
    private static void layerNormVjp(Object[] a, Object[] out, Object gy, boolean[] need, Object[] gin) {
        final Object[] g3 = gy instanceof Object[] t ? t : new Object[] {gy};
        for (int i = 1; i < g3.length; i++) {
            if (g3[i] != null) {
                throw new GraphException("a gradient through layer_norm's mean or rstd");
            }
        }
        if (g3[0] == null) {
            return;
        }
        final Tensor x = T(a[0]), g = T(g3[0]), mean = T(out[1]), rstd = T(out[2]);
        final int inner = numel(NeuralGraph.shapeOf(a[1])), outer = x.numel() / Math.max(1, inner);
        final Tensor w = a[2] == null ? null : T(a[2]);
        final double[] dx = need[0] ? new double[x.numel()] : null;
        final double[] dw = w != null && need[2] ? new double[inner] : null;
        final double[] db = a.length > 3 && a[3] != null && need[3] ? new double[inner] : null;
        for (int p = 0; p < outer; p++) {
            final double m = mean.data[p], r = rstd.data[p];
            double ds = 0, dbs = 0;
            for (int j = 0; j < inner; j++) {
                final int at = p * inner + j;
                final double gam = w == null ? 1 : w.data[j];
                ds += g.data[at] * gam * x.data[at];
                dbs += g.data[at] * gam;
            }
            final double b = (dbs * m - ds) * r * r * r / inner, c = -b * m - dbs * r / inner;
            for (int j = 0; j < inner; j++) {
                final int at = p * inner + j;
                final double gam = w == null ? 1 : w.data[j];
                if (dx != null) {
                    dx[at] = r * g.data[at] * gam + b * x.data[at] + c;
                }
                if (dw != null) {
                    dw[j] += g.data[at] * (x.data[at] - m) * r;
                }
                if (db != null) {
                    db[j] += g.data[at];
                }
            }
        }
        if (dx != null) {
            gin[0] = new Tensor(dx, x.shape.clone());
        }
        if (dw != null) {
            gin[2] = new Tensor(dw, w.shape.clone());
        }
        if (db != null) {
            gin[3] = new Tensor(db, T(a[3]).shape.clone());
        }
    }

    /** _native_batch_norm_legit_no_training's backward: the running statistics fixed, as in evaluation. */
    private static void batchNormVjp(Object[] a, Object gy, boolean[] need, Object[] gin) {
        final Object[] g3 = gy instanceof Object[] t ? t : new Object[] {gy};
        if (g3[0] == null) {
            return;
        }
        final Tensor x = T(a[0]), g = T(g3[0]), mean = T(a[3]), var = T(a[4]);
        final double eps = NeuralGraph.num(a[6]);
        final Tensor w = a[1] == null ? null : T(a[1]);
        final int c = x.shape[1], n = x.shape[0], inner = numel(Arrays.copyOfRange(x.shape, 2, x.rank()));
        final double[] dx = need[0] ? new double[x.numel()] : null;
        final double[] dw = w != null && need[1] ? new double[c] : null, db = a[2] != null && need[2] ? new double[c] : null;
        for (int s = 0; s < n; s++) {
            for (int ch = 0; ch < c; ch++) {
                final double sd = Math.sqrt(var.data[ch] + eps), wv = w == null ? 1 : w.data[ch];
                for (int i = 0; i < inner; i++) {
                    final int at = (s * c + ch) * inner + i;
                    if (dx != null) {
                        dx[at] = g.data[at] * wv / sd;
                    }
                    if (dw != null) {
                        dw[ch] += g.data[at] * (x.data[at] - mean.data[ch]) / sd;
                    }
                    if (db != null) {
                        db[ch] += g.data[at];
                    }
                }
            }
        }
        if (dx != null) {
            gin[0] = new Tensor(dx, x.shape.clone());
        }
        if (dw != null) {
            gin[1] = new Tensor(dw, w.shape.clone());
        }
        if (db != null) {
            gin[2] = new Tensor(db, T(a[2]).shape.clone());
        }
    }

    /** convolution's backward: the forward's loops, each product's two factors given the other's share. */
    private static void convVjp(Object[] a, Tensor gy, boolean[] need, Object[] gin) {
        final Tensor x = T(a[0]), w = T(a[1]);
        final int[] stride = NeuralGraph.shapeOf(a[3]), pad = NeuralGraph.shapeOf(a[4]), dil = NeuralGraph.shapeOf(a[5]);
        final int groups = (int) NeuralGraph.lng(a[8]);
        final int nd = w.rank() - 2, n = x.shape[0], cin = x.shape[1], cout = w.shape[0], cg = w.shape[1];
        final int[] in = Arrays.copyOfRange(x.shape, 2, x.rank()), ks = Arrays.copyOfRange(w.shape, 2, w.rank());
        final int[] st = NeuralGraph.rep(stride, nd), pd = NeuralGraph.rep(pad, nd), dl = NeuralGraph.rep(dil, nd);
        final int[] out = Arrays.copyOfRange(gy.shape, 2, gy.rank());
        final int og = cout / groups, osz = numel(out), isz = numel(in), ksz = numel(ks);
        final int[] ost = NeuralGraph.strides(out), ist = NeuralGraph.strides(in), kst = NeuralGraph.strides(ks);
        final double[] dx = need[0] ? new double[x.numel()] : null, dw = need[1] ? new double[w.numel()] : null;
        final double[] db = a[2] != null && need[2] ? new double[cout] : null;
        // the input position of each (output position, kernel position), -1 outside: computed once for all channels
        final int[] at = new int[osz * ksz];
        for (int op = 0; op < osz; op++) {
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
                at[op * ksz + kp] = inside ? xi : -1;
            }
        }
        for (int s = 0; s < n; s++) {
            for (int co = 0; co < cout; co++) {
                final int grp = co / og;
                for (int op = 0; op < osz; op++) {
                    final double g = gy.data[(s * cout + co) * osz + op];
                    if (db != null) {
                        db[co] += g;
                    }
                    if (g == 0) {
                        continue;
                    }
                    for (int ci = 0; ci < cg; ci++) {
                        final int xc = (s * cin + grp * cg + ci) * isz, wc = (co * cg + ci) * ksz;
                        for (int kp = 0; kp < ksz; kp++) {
                            final int xi = at[op * ksz + kp];
                            if (xi < 0) {
                                continue;
                            }
                            if (dx != null) {
                                dx[xc + xi] += g * w.data[wc + kp];
                            }
                            if (dw != null) {
                                dw[wc + kp] += g * x.data[xc + xi];
                            }
                        }
                    }
                }
            }
        }
        if (dx != null) {
            gin[0] = new Tensor(dx, x.shape.clone());
        }
        if (dw != null) {
            gin[1] = new Tensor(dw, w.shape.clone());
        }
        if (db != null) {
            gin[2] = new Tensor(db, T(a[2]).shape.clone());
        }
    }

    /**
     * Pooling's backward: max pooling gives each window's gradient to its maximum -- the first met, as PyTorch's
     * CPU kernel picks it (a NaN taken over); average pooling spreads it over the window, by the forward's divisor.
     */
    private static Tensor poolVjp(Object[] a, Tensor gy, boolean max) {
        final Tensor x = T(a[0]);
        final int nd = 2, lead = x.rank() - nd;
        final int[] kernel = NeuralGraph.rep(NeuralGraph.shapeOf(a[1]), nd);
        final int[] stride0 = a.length > 2 && a[2] != null ? NeuralGraph.shapeOf(a[2]) : new int[0];
        final int[] stride = NeuralGraph.rep(stride0.length == 0 ? kernel : stride0, nd);
        final int[] pad = NeuralGraph.rep(a.length > 3 && a[3] != null ? NeuralGraph.shapeOf(a[3]) : new int[] {0}, nd);
        final int[] dil = max ? NeuralGraph.rep(a.length > 4 && a[4] != null ? NeuralGraph.shapeOf(a[4]) : new int[] {1}, nd)
            : new int[] {1, 1};
        final boolean countPad = max || a.length <= 5 || a[5] == null || Boolean.TRUE.equals(a[5]);
        final double divisor = !max && a.length > 6 && a[6] != null ? NeuralGraph.num(a[6]) : 0;
        final int[] in = Arrays.copyOfRange(x.shape, lead, x.rank()), out = Arrays.copyOfRange(gy.shape, lead, gy.rank());
        int planes = 1;
        for (int i = 0; i < lead; i++) {
            planes *= x.shape[i];
        }
        final int isz = numel(in), osz = numel(out), ksz = numel(kernel);
        final int[] ost = NeuralGraph.strides(out), ist = NeuralGraph.strides(in), kst = NeuralGraph.strides(kernel);
        final double[] dx = new double[x.numel()];
        final int[] cover = new int[ksz];
        for (int p = 0; p < planes; p++) {
            for (int op = 0; op < osz; op++) {
                int covered = 0, best = -1;
                double bestVal = Double.NEGATIVE_INFINITY;
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
                    if (!inside) {
                        continue;
                    }
                    cover[covered++] = xi;
                    if (best < 0) {
                        best = xi;   // PyTorch's maxindex starts at the window's first position inside the input
                    }
                    final double v = x.data[p * isz + xi];
                    if (v > bestVal || Double.isNaN(v)) {
                        best = xi;
                        bestVal = v;
                    }
                }
                final double g = gy.data[p * osz + op];
                if (max) {
                    if (best >= 0) {
                        dx[p * isz + best] += g;
                    }
                } else {
                    final double share = g / (divisor > 0 ? divisor : countPad ? ksz : covered);
                    for (int c = 0; c < covered; c++) {
                        dx[p * isz + cover[c]] += share;
                    }
                }
            }
        }
        return new Tensor(dx, x.shape.clone());
    }

    // ------------------------------------------------------------------
    // Finite differences: the autograd held to its own forward, no framework needed
    // ------------------------------------------------------------------

    /** What gradcheck found: the largest relative difference, and where. */
    public record Check(double worst, String where, int compared, double autograd, double numeric) {
        public boolean passed(double tolerance) {
            return worst <= tolerance;
        }
    }

    /**
     * The autograd's gradient of sum_k (s_k . out_k) -- random seeds s -- against central differences of the graph's
     * own forward, at samples weights of each leaf: the largest relative difference. Differences of step h =
     * 1e-6 (1 + |w|) are good to some 1e-8 where the function is smooth; a kink crossed (a ReLU's) shows as one
     * large difference, at one weight.
     */
    public Check gradcheck(List<Tensor> in, int samples, long seed) {
        final SplittableRandom rng = new SplittableRandom(seed);
        final Frame f = forward(in, false);
        final List<Tensor> outs = f.outputs();
        final List<Tensor> seeds = new ArrayList<>();
        for (Tensor o : outs) {
            final double[] s = new double[o.numel()];
            for (int k = 0; k < s.length; k++) {
                s[k] = rng.nextDouble(-1, 1);
            }
            seeds.add(new Tensor(s, o.shape.clone()));
        }
        final Gradients gr = backward(f, seeds);
        double gscale = 0;   // the gradient's own scale: a gradient zero by symmetry is judged against it, not against 0
        for (double[] g : gr.weights().values()) {
            for (double v : g) {
                gscale = Math.max(gscale, Math.abs(v));
            }
        }
        double worst = 0, ad = 0, nm = 0;
        String where = "";
        int compared = 0;
        for (String l : leaves) {
            final double[] w = graph.consts.get(l).data, g = gr.weights().get(l);
            final int m = Math.min(samples, w.length);
            for (int s = 0; s < m; s++) {
                final int k = m == w.length ? s : rng.nextInt(w.length);
                final double keep = w[k], h = 1e-6 * (1 + Math.abs(keep));
                w[k] = keep + h;
                final double up = dotOutputs(in, seeds);
                w[k] = keep - h;
                final double down = dotOutputs(in, seeds);
                w[k] = keep;
                final double num = (up - down) / (2 * h);
                final double scale = Math.max(Math.max(Math.abs(num), Math.abs(g[k])), Math.max(1e-3 * gscale, 1e-12));
                final double rel = Math.abs(num - g[k]) / scale;
                compared++;
                if (rel > worst) {
                    worst = rel;
                    where = l + "[" + k + "]";
                    ad = g[k];
                    nm = num;
                }
            }
        }
        return new Check(worst, where, compared, ad, nm);
    }

    private double dotOutputs(List<Tensor> in, List<Tensor> seeds) {
        final List<Tensor> out = forward(in, false).outputs();
        double s = 0;
        for (int i = 0; i < out.size(); i++) {
            final double[] o = out.get(i).data, w = seeds.get(i).data;
            for (int k = 0; k < o.length; k++) {
                s += o[k] * w[k];
            }
        }
        return s;
    }

    /** The trained weights' current values, copied (to restore a best epoch). */
    public Map<String, double[]> snapshot() {
        final Map<String, double[]> m = new LinkedHashMap<>();
        for (String l : leaves) {
            m.put(l, graph.consts.get(l).data.clone());
        }
        return m;
    }

    /** Weights put back in place (the graph's own arrays written, so every runtime of it sees them). */
    public void restore(Map<String, double[]> values) {
        for (Map.Entry<String, double[]> e : values.entrySet()) {
            final double[] w = graph.consts.get(e.getKey()).data;
            System.arraycopy(e.getValue(), 0, w, 0, w.length);
        }
    }
}
