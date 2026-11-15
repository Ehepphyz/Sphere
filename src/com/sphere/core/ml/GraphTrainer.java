package com.sphere.core.ml;

import com.sphere.core.bridge.Spx;
import com.sphere.core.ml.NeuralGraph.GraphException;
import com.sphere.core.ml.NeuralGraph.Tensor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Training in Java: a graph's weights moved by SGD, Adam or AdamW as torch.optim moves them, on the gradients
 * of {@link GraphAutograd} -- a model of PyTorch, Keras or TensorFlow fine-tuned by Sphere with no framework.
 *
 * <p>The optimizers are torch.optim's single-tensor implementations (what PyTorch runs on a CPU), operation for
 * operation: Adam's first moment by lerp, its second by mul then addcmul, the step by addcdiv, the bias
 * corrections by Python's own powers; AdamW's decay applied to the weight first; SGD's momentum buffer started
 * from the first gradient, Nesterov's look-ahead. The losses are F.cross_entropy, F.binary_cross_entropy_with_logits
 * and F.mse_loss with their mean reductions, their gradients as PyTorch's backward computes them.
 * {@link #checkReference} holds all of it to PyTorch itself (':torch grad').
 *
 * <p>A batch is split into micro-batches that fit the memory Sphere spares now -- the tape's bytes measured per
 * row, as NeuralGraph plans its runs -- run on as many threads as fit, their gradients added in a fixed order:
 * the batch's gradient, whatever the memory, to the rounding of the sum.
 */
public final class GraphTrainer {

    private GraphTrainer() {
    }

    // ------------------------------------------------------------------
    // Losses
    // ------------------------------------------------------------------

    /** The loss a graph's first output takes: decided by its shape, as sphere_graph.loss_kind decides it. */
    public enum Loss {
        CROSS_ENTROPY("cross_entropy"), BCE("bce"), MSE("mse");

        public final String key;

        Loss(String key) {
            this.key = key;
        }

        public static Loss of(String key) {
            for (Loss l : values()) {
                if (l.key.equalsIgnoreCase(key) || l.name().equalsIgnoreCase(key)) {
                    return l;
                }
            }
            throw new IllegalArgumentException("no loss " + key + " (cross_entropy, bce, mse)");
        }

        /** Logits of two classes or more [B, C]: cross entropy; one a row ([B], [B, 1]): binary; else squares. */
        public static Loss forOutput(int[] shape) {
            if (shape.length == 2 && shape[1] >= 2) {
                return CROSS_ENTROPY;
            }
            if (shape.length == 1 || shape.length == 2 && shape[1] == 1) {
                return BCE;
            }
            return MSE;
        }
    }

    /**
     * The loss of a micro-batch's outputs -- its share of the batch's weighted mean, total the batch's total weight
     * (its rows, unweighted) -- and its gradient with respect to the outputs, written into seed.
     */
    static double loss(Loss kind, Tensor out, double[] y, double[] t, double[] w, double total, double[] seed) {
        final int n = out.rank() == 0 ? 1 : out.shape[0];
        switch (kind) {
            case CROSS_ENTROPY -> {
                final int c = out.shape[1];
                double l = 0;
                final double[] lsm = new double[c];
                for (int i = 0; i < n; i++) {
                    double m = Double.NEGATIVE_INFINITY;
                    for (int j = 0; j < c; j++) {
                        m = Math.max(m, out.data[i * c + j]);
                    }
                    double s = 0;
                    for (int j = 0; j < c; j++) {
                        s += Math.exp(out.data[i * c + j] - m);
                    }
                    final double ls = Math.log(s);
                    for (int j = 0; j < c; j++) {
                        lsm[j] = (out.data[i * c + j] - m) - ls;
                    }
                    final int yi = (int) y[i];
                    if (yi < 0 || yi >= c) {
                        throw new GraphException("a label " + yi + " for " + c + " classes");
                    }
                    final double gy = w == null ? -1.0 / total : -w[i] / total;   // nll_loss's backward, mean
                    l += (w == null ? -lsm[yi] : -lsm[yi] * w[i]);
                    for (int j = 0; j < c; j++) {   // log_softmax's backward: g - exp(out) * sum(g)
                        seed[i * c + j] = (j == yi ? gy : 0.0) - Math.exp(lsm[j]) * gy;
                    }
                }
                return l / total;
            }
            case BCE -> {
                double l = 0;
                for (int i = 0; i < n; i++) {
                    final double z = out.data[i], yi = y[i];
                    final double mv = Math.max(-z, 0);
                    final double li = (1 - yi) * z + mv + Math.log(Math.exp(-mv) + Math.exp(-z - mv));
                    final double s = 1.0 / (1.0 + Math.exp(-z));
                    l += w == null ? li : li * w[i];
                    seed[i] = w == null ? (s - yi) / total : (s - yi) * w[i] / total;
                }
                return l / total;
            }
            default -> {
                final int per = out.numel() / Math.max(1, n);
                final double norm = 2.0 / (total * per);
                double l = 0;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < per; j++) {
                        final int k = i * per + j;
                        final double d = out.data[k] - t[k];
                        l += w == null ? d * d : d * d * w[i];
                        seed[k] = w == null ? d * norm : d * norm * w[i];
                    }
                }
                return l / (total * per);
            }
        }
    }

    // ------------------------------------------------------------------
    // Optimizers: torch.optim's single-tensor steps
    // ------------------------------------------------------------------

    /** An optimizer of a graph's weights (the arrays themselves, moved in place). */
    public interface Optimizer {
        void step(Map<String, double[]> weights, Map<String, double[]> grads);

        String describe();
    }

    /** ATen's lerp: self + w (end - self) for |w| < 0.5, end - (end - self)(1 - w) otherwise. */
    static double lerp(double self, double end, double w) {
        return Math.abs(w) < 0.5 ? self + w * (end - self) : end - (end - self) * (1 - w);
    }

    /** Adam, and AdamW (decoupled decay), as torch.optim's _single_tensor_adam. */
    public static final class Adam implements Optimizer {
        final double lr, beta1, beta2, eps, decay;
        final boolean decoupled;
        /** The learning rate of the next step (a schedule moves it; lr otherwise). */
        double lrNow;
        private final Map<String, double[]> m = new HashMap<>(), v = new HashMap<>();
        private int t;

        public Adam(double lr, double beta1, double beta2, double eps, double decay, boolean decoupled) {
            this.lr = lr;
            this.lrNow = lr;
            this.beta1 = beta1;
            this.beta2 = beta2;
            this.eps = eps;
            this.decay = decay;
            this.decoupled = decoupled;
        }

        @Override
        public void step(Map<String, double[]> weights, Map<String, double[]> grads) {
            t++;
            final double bc1 = 1 - StrictMath.pow(beta1, t), bc2 = 1 - StrictMath.pow(beta2, t);
            final double stepSize = lrNow / bc1, bc2Sqrt = StrictMath.pow(bc2, 0.5);
            final double w1 = 1 - beta1, c2 = 1 - beta2, shrink = 1 - lrNow * decay;
            for (Map.Entry<String, double[]> e : weights.entrySet()) {
                final double[] p = e.getValue(), g = grads.get(e.getKey());
                final double[] mm = m.computeIfAbsent(e.getKey(), k -> new double[p.length]);
                final double[] vv = v.computeIfAbsent(e.getKey(), k -> new double[p.length]);
                for (int k = 0; k < p.length; k++) {
                    double gk = g[k];
                    if (decay != 0) {
                        if (decoupled) {
                            p[k] = p[k] * shrink;
                        } else {
                            gk = gk + decay * p[k];
                        }
                    }
                    mm[k] = lerp(mm[k], gk, w1);
                    vv[k] = vv[k] * beta2 + c2 * gk * gk;
                    final double denom = Math.sqrt(vv[k]) / bc2Sqrt + eps;
                    p[k] = p[k] + -stepSize * mm[k] / denom;
                }
            }
        }

        @Override
        public String describe() {
            return String.format(Locale.ROOT, "%s lr=%g betas=(%g, %g) eps=%g weight_decay=%g", decoupled ? "adamw" : "adam", lr,
                beta1, beta2, eps, decay);
        }
    }

    /** SGD with momentum, dampening, Nesterov and L2 decay, as torch.optim's _single_tensor_sgd. */
    public static final class Sgd implements Optimizer {
        final double lr, momentum, dampening, decay;
        final boolean nesterov;
        /** The learning rate of the next step (a schedule moves it; lr otherwise). */
        double lrNow;
        private final Map<String, double[]> buf = new HashMap<>();

        public Sgd(double lr, double momentum, double dampening, double decay, boolean nesterov) {
            this.lr = lr;
            this.lrNow = lr;
            this.momentum = momentum;
            this.dampening = dampening;
            this.decay = decay;
            this.nesterov = nesterov;
        }

        @Override
        public void step(Map<String, double[]> weights, Map<String, double[]> grads) {
            for (Map.Entry<String, double[]> e : weights.entrySet()) {
                final double[] p = e.getValue(), g = grads.get(e.getKey());
                double[] b = buf.get(e.getKey());
                final boolean first = b == null;
                if (momentum != 0 && first) {
                    b = new double[p.length];
                    buf.put(e.getKey(), b);
                }
                for (int k = 0; k < p.length; k++) {
                    double gk = decay != 0 ? g[k] + decay * p[k] : g[k];
                    if (momentum != 0) {
                        b[k] = first ? gk : b[k] * momentum + (1 - dampening) * gk;
                        gk = nesterov ? gk + momentum * b[k] : b[k];
                    }
                    p[k] = p[k] + -lrNow * gk;
                }
            }
        }

        @Override
        public String describe() {
            return String.format(Locale.ROOT, "sgd lr=%g momentum=%g dampening=%g weight_decay=%g%s", lr, momentum, dampening, decay,
                nesterov ? " nesterov" : "");
        }
    }

    /** An optimizer by name (adam, adamw, sgd), its hyperparameters PyTorch's defaults where not given. */
    public static Optimizer optimizer(String name, Map<String, Double> hp) {
        final double lr = hp.getOrDefault("lr", name.equals("sgd") ? 1e-2 : 1e-3);
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "adam" -> new Adam(lr, hp.getOrDefault("beta1", 0.9), hp.getOrDefault("beta2", 0.999), hp.getOrDefault("eps", 1e-8),
                hp.getOrDefault("weight_decay", 0.0), false);
            case "adamw" -> new Adam(lr, hp.getOrDefault("beta1", 0.9), hp.getOrDefault("beta2", 0.999), hp.getOrDefault("eps", 1e-8),
                hp.getOrDefault("weight_decay", 1e-2), true);
            case "sgd" -> new Sgd(lr, hp.getOrDefault("momentum", 0.0), hp.getOrDefault("dampening", 0.0),
                hp.getOrDefault("weight_decay", 0.0), hp.getOrDefault("nesterov", 0.0) != 0);
            default -> throw new IllegalArgumentException("no optimizer " + name + " (adam, adamw, sgd)");
        };
    }

    /** torch.nn.utils.clip_grad_norm_: the gradients scaled so that their norm is at most max; their norm before. */
    static double clip(Map<String, double[]> grads, double max) {
        double s = 0;
        for (double[] g : grads.values()) {
            for (double v : g) {
                s += v * v;
            }
        }
        final double norm = Math.sqrt(s);
        if (max > 0) {
            final double c = Math.min(max / (norm + 1e-6), 1.0);
            if (c < 1.0) {
                for (double[] g : grads.values()) {
                    for (int k = 0; k < g.length; k++) {
                        g[k] *= c;
                    }
                }
            }
        }
        return norm;
    }

    // ------------------------------------------------------------------
    // A batch's gradient, in micro-batches that fit, on threads
    // ------------------------------------------------------------------

    /** The bytes a tape takes, as a + b rows: measured on one and two rows. */
    public record TapePlan(double base, double perRow) {
        public long rowsFor(double budget) {
            if (perRow <= 0) {
                return Long.MAX_VALUE;
            }
            return Math.max(1, (long) Math.floor((budget - base) / perRow));
        }
    }

    /** The tape's bytes per row (twice the forward's: the gradients live beside the values). */
    public static TapePlan plan(GraphAutograd ad, List<Tensor> sample) {
        final long b1 = ad.bytes(ad.forward(rows(sample, 0, 1), false));
        final long b2 = ad.bytes(ad.forward(rows(sample, 0, Math.min(2, rowsOf(sample))), false));
        final double per = Math.max(0, b2 - b1) * 2.0;
        return new TapePlan(Math.max(0, 2.0 * b1 - per), per);
    }

    public static int rowsOf(List<Tensor> in) {
        for (Tensor t : in) {
            if (t.rank() > 0) {
                return t.shape[0];
            }
        }
        return 1;
    }

    public static List<Tensor> rows(List<Tensor> in, int lo, int hi) {
        final List<Tensor> out = new ArrayList<>(in.size());
        for (Tensor t : in) {
            out.add(t.rank() == 0 ? t : NeuralGraph.rows(t, lo, hi));
        }
        return out;
    }

    /** What one batch's gradient took: its loss, the micro-batches and threads, the bytes each micro-batch held. */
    public record Step(double loss, int chunk, int chunks, int threads, double chunkBytes) {
    }

    /**
     * The gradient of the batch's loss (weighted mean over its rows) into grads (zeroed first): the batch cut into
     * micro-batches of the tape's planned size within memory bytes, run on up to threads threads, their gradients
     * added in order. y the labels, t the targets (MSE), w the rows' weights (null: 1).
     */
    public static Step gradients(GraphAutograd ad, List<Tensor> in, Loss kind, double[] y, double[] t, double[] w,
                                 Map<String, double[]> grads, TapePlan plan, double memory, int threads, ExecutorService pool) {
        final int n = rowsOf(in);
        double total = 0;
        if (w == null) {
            total = n;
        } else {
            for (int i = 0; i < n; i++) {
                total += w[i];
            }
        }
        final long fit = plan == null ? n : plan.rowsFor(memory / Math.max(1, threads));
        // as many micro-batches as threads at least (of 8 rows or more: smaller ones cost more than they share)
        final int share = pool == null || threads <= 1 ? n : Math.max(8, (n + threads - 1) / threads);
        final int chunk = (int) Math.max(1, Math.min(Math.min(n, share), fit));
        final int chunks = (n + chunk - 1) / chunk;
        final int th = Math.max(1, Math.min(Math.min(threads, chunks), plan == null ? 1
            : (int) Math.max(1, Math.floor(memory / Math.max(1, plan.base + plan.perRow * chunk)))));
        for (double[] g : grads.values()) {
            Arrays.fill(g, 0);
        }
        final double tot = total;
        final List<Map<String, double[]>> parts = new ArrayList<>();
        final double[] losses = new double[chunks];
        final int perT = t == null ? 0 : t.length / Math.max(1, n);
        final java.util.function.IntFunction<Map<String, double[]>> one = k -> {
            final int lo = k * chunk, hi = Math.min(n, lo + chunk);
            final List<Tensor> part = rows(in, lo, hi);
            final GraphAutograd.Frame f = ad.forward(part, false);
            final List<Tensor> outs = f.outputs();
            final Tensor out = outs.get(0);
            final double[] seed = new double[out.numel()];
            losses[k] = loss(kind, out, y == null ? null : Arrays.copyOfRange(y, lo, hi),
                t == null ? null : Arrays.copyOfRange(t, lo * perT, hi * perT), w == null ? null : Arrays.copyOfRange(w, lo, hi),
                tot, seed);
            final List<Tensor> seeds = new ArrayList<>();
            seeds.add(new Tensor(seed, out.shape.clone()));
            for (int i = 1; i < outs.size(); i++) {
                seeds.add(null);
            }
            return ad.backward(f, seeds).weights();
        };
        if (th <= 1 || pool == null) {
            for (int k = 0; k < chunks; k++) {
                parts.add(one.apply(k));
                if (parts.size() > 1) {   // add as we go: one micro-batch's gradients alive at a time
                    addInto(grads, parts.remove(0));
                }
            }
        } else {
            final List<Future<Map<String, double[]>>> futures = new ArrayList<>();
            for (int k = 0; k < chunks; k++) {
                final int kk = k;
                futures.add(pool.submit(() -> one.apply(kk)));
            }
            for (Future<Map<String, double[]>> fu : futures) {   // in order: the same sum whatever the threads
                try {
                    parts.add(fu.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GraphException("interrupted");
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof RuntimeException r ? r : new GraphException(String.valueOf(e.getCause()));
                }
                addInto(grads, parts.remove(0));
            }
        }
        for (Map<String, double[]> p : parts) {
            addInto(grads, p);
        }
        double l = 0;
        for (double v : losses) {
            l += v;
        }
        return new Step(l, chunk, chunks, th, plan == null ? 0 : plan.base + plan.perRow * chunk);
    }

    private static void addInto(Map<String, double[]> into, Map<String, double[]> part) {
        for (Map.Entry<String, double[]> e : part.entrySet()) {
            final double[] a = into.get(e.getKey()), b = e.getValue();
            for (int k = 0; k < a.length; k++) {
                a[k] += b[k];
            }
        }
    }

    // ------------------------------------------------------------------
    // Held to PyTorch: ':torch grad'
    // ------------------------------------------------------------------

    /** How one optimizer's steps compare: the largest difference of a weight, relative to the weights' scale. */
    public record OptimizerCheck(String name, String describe, double relative, String where) {
    }

    /** How Java's autograd and optimizers compare with PyTorch's own on the reference's batch. */
    public record ReferenceCheck(String loss, double javaLoss, double torchLoss, double outputs, double gradients, String gradWhere,
                                 List<OptimizerCheck> optimizers, int steps, int rows, long weights, double millis,
                                 List<String> zeroBySymmetry, double hessian, double gaussNewton) {
        /** The worst difference, in units of double's epsilon (2.2e-16) relative to the scale of what is compared. */
        public double epsUnits() {
            double worst = Math.max(gradients, outputs);
            for (OptimizerCheck o : optimizers) {
                worst = Math.max(worst, o.relative);
            }
            return worst / 0x1.0p-52;
        }
    }

    private static final Pattern HP = Pattern.compile("(\\w+)=([-+0-9.eEinfINFaN]+)");

    /** The graph's gradients and optimizer steps held to a reference sphere_graph.gradients wrote. */
    public static ReferenceCheck checkReference(NeuralGraph g, Path ref) throws IOException {
        if (!g.trainable()) {
            throw new GraphException(g.name + " was exported frozen: export it again for its weights to be the model's");
        }
        final long t0 = System.nanoTime();
        final GraphAutograd ad = new GraphAutograd(g);
        try (Spx.Reader r = new Spx.Reader(ref)) {
            final Map<String, String> meta = r.meta();
            if (!"sphere-grad-reference".equals(meta.get("Kind"))) {
                throw new IOException(ref + " is not a gradient reference");
            }
            final int ninputs = Integer.parseInt(meta.getOrDefault("Inputs", "1"));
            final List<Tensor> in = new ArrayList<>();
            for (int k = 0; k < ninputs; k++) {
                in.add(new Tensor(r.f64("in." + k), ints(meta.getOrDefault("Shape.in." + k, ""))));
            }
            final Loss kind = Loss.of(meta.getOrDefault("Loss", "cross_entropy"));
            final double[] y = r.f64("y"), t = r.has("t") ? r.f64("t") : null;
            final int steps = Integer.parseInt(meta.getOrDefault("Steps", "3"));
            final Map<String, double[]> start = ad.snapshot();
            final Map<String, double[]> grads = new LinkedHashMap<>();
            for (String l : ad.leaves()) {
                grads.put(l, new double[start.get(l).length]);
            }
            // the gradient, on the whole batch at once
            final Step st = gradients(ad, in, kind, y, t, null, grads, null, Double.POSITIVE_INFINITY, 1, null);
            final double torchLoss = r.f64("loss")[0];
            final List<Tensor> outs = g.run(in);
            final double outGap = relGap(outs.get(0).data, r.f64("out.0"));
            // each difference against the scale of the whole gradient: a gradient that is zero by symmetry (a key's
            // bias under softmax) is rounding noise in both engines, and noise against noise means nothing
            final Map<String, double[]> torchGrads = new LinkedHashMap<>();
            for (String l : ad.leaves()) {
                torchGrads.put(l, r.f64("g." + l));
            }
            final Gap gg = gap(grads, torchGrads, g);
            final double worst = gg.relative();
            final String where = gg.where();
            // weights whose gradient is zero by a symmetry of the model (PyTorch's a few 1e-19s of rounding): Adam
            // divides that noise by its own size, so their steps follow each engine's noise -- compared apart
            double gscale = 0;
            for (double[] v : torchGrads.values()) {
                for (double x : v) {
                    gscale = Math.max(gscale, Math.abs(x));
                }
            }
            final List<String> symmetric = new ArrayList<>();
            for (Map.Entry<String, double[]> e : torchGrads.entrySet()) {
                double m = 0;
                for (double x : e.getValue()) {
                    m = Math.max(m, Math.abs(x));
                }
                if (m <= 1e-12 * gscale) {
                    symmetric.add(e.getKey());
                }
            }
            // each optimizer's steps from the same start
            final List<OptimizerCheck> opts = new ArrayList<>();
            for (String spec : meta.getOrDefault("Optimizers", "").split(";")) {
                if (spec.isBlank()) {
                    continue;
                }
                final String name = spec.substring(0, spec.indexOf(':'));
                final Map<String, Double> hp = new HashMap<>();
                final Matcher m = HP.matcher(spec.substring(spec.indexOf(':') + 1));
                while (m.find()) {
                    hp.put(m.group(1), Double.parseDouble(m.group(2)));
                }
                ad.restore(start);
                final Optimizer o = optimizer(name, hp);
                final Map<String, double[]> weights = new LinkedHashMap<>();
                for (String l : ad.leaves()) {
                    weights.put(l, g.consts.get(l).data);
                }
                for (int s = 0; s < steps; s++) {
                    gradients(ad, in, kind, y, t, null, grads, null, Double.POSITIVE_INFINITY, 1, null);
                    o.step(weights, grads);
                }
                final Map<String, double[]> torchWeights = new LinkedHashMap<>();
                for (String l : ad.leaves()) {
                    if (!symmetric.contains(l)) {
                        torchWeights.put(l, r.f64(name + "." + l));
                    }
                }
                final Gap og = gap(weights, torchWeights, g);
                opts.add(new OptimizerCheck(name, o.describe(), og.relative(), og.where()));
            }
            ad.restore(start);
            final List<String> named = new ArrayList<>();
            for (String s : symmetric) {
                named.add(g.params.getOrDefault(s, s));
            }
            // second order: H v by differences of exact gradients, G v = J^T H_out J v -- against PyTorch's double backward
            double hess = Double.NaN, ggn = Double.NaN;
            if ("1".equals(meta.get("SecondOrder"))) {
                final GraphUncertainty un = new GraphUncertainty(g);
                final double[] v = concat(r, "v.", ad.leaves());
                hess = relGap(un.hvp(in, y, v), concat(r, "hv.", ad.leaves()));
                if (r.has("ggn." + ad.leaves().iterator().next())) {
                    ggn = relGap(un.ggnv(in, v), concat(r, "ggn.", ad.leaves()));
                }
                ad.restore(start);
            }
            return new ReferenceCheck(kind.key, st.loss(), torchLoss, outGap, worst, where, opts, steps, rowsOf(in), ad.trainable(),
                (System.nanoTime() - t0) / 1e6, named, hess, ggn);
        }
    }

    /** The largest difference between two sets of arrays, relative to the largest value of the reference's set. */
    record Gap(double relative, String where) {
    }

    static Gap gap(Map<String, double[]> mine, Map<String, double[]> ref, NeuralGraph g) {
        double scale = 0, worst = 0;
        String where = "";
        for (double[] v : ref.values()) {
            for (double x : v) {
                scale = Math.max(scale, Math.abs(x));
            }
        }
        for (Map.Entry<String, double[]> e : ref.entrySet()) {
            final double[] a = mine.get(e.getKey()), b = e.getValue();
            if (a == null || a.length != b.length) {
                return new Gap(Double.POSITIVE_INFINITY, e.getKey());
            }
            for (int k = 0; k < b.length; k++) {
                final double d = Math.abs(a[k] - b[k]);
                if (d > worst) {
                    worst = d;
                    where = e.getKey() + " (" + g.params.getOrDefault(e.getKey(), e.getKey()) + ")";
                }
            }
        }
        return new Gap(scale == 0 ? worst : worst / scale, where);
    }

    /** The sections prefix + leaf, laid end to end in the leaves' order. */
    static double[] concat(Spx.Reader r, String prefix, java.util.Collection<String> leaves) throws IOException {
        final List<double[]> parts = new ArrayList<>();
        int n = 0;
        for (String l : leaves) {
            final double[] d = r.f64(prefix + l);
            parts.add(d);
            n += d.length;
        }
        final double[] out = new double[n];
        int at = 0;
        for (double[] d : parts) {
            System.arraycopy(d, 0, out, at, d.length);
            at += d.length;
        }
        return out;
    }

    /** max |a - b| over max |b|: a difference relative to the scale of what is compared. */
    static double relGap(double[] a, double[] b) {
        if (a.length != b.length) {
            return Double.POSITIVE_INFINITY;
        }
        double d = 0, s = 0;
        for (int k = 0; k < a.length; k++) {
            d = Math.max(d, Math.abs(a[k] - b[k]));
            s = Math.max(s, Math.abs(b[k]));
        }
        return s == 0 ? d : d / s;
    }

    static int[] ints(String s) {
        return s == null || s.isBlank() ? new int[0] : Arrays.stream(s.split(",")).mapToInt(v -> Integer.parseInt(v.strip())).toArray();
    }

    // ------------------------------------------------------------------
    // Training on a dataset
    // ------------------------------------------------------------------

    /** How to train. */
    public static final class Options {
        public String optimizer = "adam";
        public final Map<String, Double> hyper = new HashMap<>();
        public int epochs = 5, batch = 128, threads = 0, maxRows = -1;
        public double val = 0.2, clip = 0, memory = 0;
        public long seed = 7;
        public String schedule = "constant";
        public String name;
        public Loss loss;
    }

    /** One epoch's account. */
    public record Epoch(int epoch, double loss, double valLoss, double valAuc, double seconds, double lr) {
    }

    /** What a training did and where it left the graph. */
    public record Result(Path path, List<Epoch> epochs, int best, long weights, int rows, int trainRows, int valRows,
                         String optimizer, Loss loss, Step lastStep, boolean guessedLeaves) {
    }

    /** A dataset's rows for a graph: its inputs (by the graph's feeds, as runFile reads them), labels, weights. */
    public record Data(List<Tensor> inputs, double[] y, double[] t, double[] w, int rows) {
    }

    /** The rows of a dataset the graph takes, first maxRows only if positive. */
    public static Data data(NeuralGraph g, Path file, int maxRows) throws IOException {
        try (Spx.Reader r = new Spx.Reader(file)) {
            final Map<String, String> meta = r.meta();
            final int[][] ex = g.exampleShapes();
            final List<Tensor> in = new ArrayList<>();
            int n = -1;
            for (int k = 0; k < g.inputNames.size(); k++) {
                final List<String> cand = new ArrayList<>(List.of(g.inputNames.get(k), "in." + k));
                if (g.feeds.equals("high_level") && k == 0 || g.feeds.equals("particles") && k == 2) {
                    cand.add("hl");
                }
                final String sec = cand.stream().filter(r::has).findFirst().orElseThrow(() -> new GraphException(
                    file.getFileName() + " has no section for the input " + cand.get(0) + " of " + g.name));
                final double[] d = r.f64(sec);
                final int[] row = k < ex.length && ex[k].length > 0 ? Arrays.copyOfRange(ex[k], 1, ex[k].length) : new int[0];
                final int per = Math.max(1, NeuralGraph.numel(row));
                int rows = d.length / per;
                if (maxRows > 0) {
                    rows = Math.min(rows, maxRows);
                }
                if (n >= 0 && rows != n) {
                    throw new GraphException("inputs of " + n + " and " + rows + " rows in " + file.getFileName());
                }
                n = rows;
                final int[] shape = new int[row.length + 1];
                shape[0] = rows;
                System.arraycopy(row, 0, shape, 1, row.length);
                in.add(new Tensor(rows * per == d.length ? d : Arrays.copyOf(d, rows * per), shape));
            }
            final int rows = Math.max(n, 0);
            double[] y = null, t = null, w = null;
            if (r.has("y")) {
                final Spx.Section s = r.sections().stream().filter(x -> x.name().equals("y")).findFirst().orElseThrow();
                y = s.type() == Spx.I64 ? Arrays.stream(r.i64("y")).limit(rows).asDoubleStream().toArray()
                    : Arrays.copyOf(r.f64("y"), rows);
            }
            if (r.has("t")) {
                final double[] all = r.f64("t");
                t = Arrays.copyOf(all, all.length / Math.max(1, Integer.parseInt(meta.getOrDefault("Jets", meta.getOrDefault("Rows",
                    String.valueOf(rows))))) * rows);
            }
            if (r.has("w")) {
                w = Arrays.copyOf(r.f64("w"), rows);
            }
            return new Data(in, y, t, w, rows);
        }
    }

    private static List<Tensor> take(List<Tensor> in, int[] idx) {
        final List<Tensor> out = new ArrayList<>(in.size());
        for (Tensor x : in) {
            if (x.rank() == 0) {
                out.add(x);
                continue;
            }
            final int per = x.numel() / x.shape[0];
            final double[] d = new double[idx.length * per];
            for (int i = 0; i < idx.length; i++) {
                System.arraycopy(x.data, idx[i] * per, d, i * per, per);
            }
            final int[] s = x.shape.clone();
            s[0] = idx.length;
            out.add(new Tensor(d, s));
        }
        return out;
    }

    private static double[] take(double[] v, int[] idx, int per) {
        if (v == null) {
            return null;
        }
        final double[] o = new double[idx.length * per];
        for (int i = 0; i < idx.length; i++) {
            System.arraycopy(v, idx[i] * per, o, i * per, per);
        }
        return o;
    }

    /** The probability of the signal class from a row's outputs: softmax's last class, or a single logit's sigmoid. */
    static double[] scores(Tensor out, Loss kind) {
        final int n = out.shape[0];
        final double[] s = new double[n];
        if (kind == Loss.CROSS_ENTROPY) {
            final int c = out.shape[1];
            for (int i = 0; i < n; i++) {
                double m = Double.NEGATIVE_INFINITY, z = 0;
                for (int j = 0; j < c; j++) {
                    m = Math.max(m, out.data[i * c + j]);
                }
                for (int j = 0; j < c; j++) {
                    z += Math.exp(out.data[i * c + j] - m);
                }
                s[i] = Math.exp(out.data[i * c + c - 1] - m) / z;
            }
        } else {
            for (int i = 0; i < n; i++) {
                s[i] = 1.0 / (1.0 + Math.exp(-out.data[i]));
            }
        }
        return s;
    }

    /** The weighted area under the ROC curve of scores separating label 1 from the others, ties counted half. */
    static double auc(double[] score, double[] y, double[] w) {
        final int n = score.length;
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        double sig = 0, bkg = 0, area = 0, tp = 0, fp = 0;
        for (int i = 0; i < n; i++) {
            final double wi = w == null ? 1 : w[i];
            if (y[i] == 1) {
                sig += wi;
            } else {
                bkg += wi;
            }
        }
        if (sig == 0 || bkg == 0) {
            return Double.NaN;
        }
        int i = 0;
        while (i < n) {
            int j = i;
            double dtp = 0, dfp = 0;
            while (j < n && score[order[j]] == score[order[i]]) {
                final int k = order[j];
                final double wk = w == null ? 1 : w[k];
                if (y[k] == 1) {
                    dtp += wk;
                } else {
                    dfp += wk;
                }
                j++;
            }
            area += dfp * (tp + dtp / 2);
            tp += dtp;
            fp += dfp;
            i = j;
        }
        return area / (sig * bkg);
    }

    /**
     * The graph trained on a dataset: its rows split (val held out, by seed), each epoch the training rows
     * shuffled and cut into batches, each batch's gradient (micro-batched to the memory) taken by the optimizer;
     * the epoch of least validation loss kept, the graph written with its weights (models/name.sng.spx) for Java,
     * C++ and numpy to run as they ran the first.
     */
    public static Result train(NeuralGraph g, Path dataset, Options o, Consumer<String> log, BooleanSupplier canceled) throws IOException {
        final GraphAutograd ad = new GraphAutograd(g);
        final GraphAutograd.Coverage cov = ad.coverage();
        if (!cov.complete()) {
            throw new GraphException(g.name + " has operations without a gradient here: " + String.join(", ", cov.missing()));
        }
        final double memory = o.memory > 0 ? o.memory : NeuralGraph.budget();
        int maxRows = o.maxRows;
        final Data all = data(g, dataset, maxRows);
        if (all.y == null && (o.loss == null || o.loss != Loss.MSE)) {
            throw new GraphException(dataset.getFileName() + " has no labels (y) to train on");
        }
        final int n = all.rows;
        final SplittableRandom rng = new SplittableRandom(o.seed);
        final int[] perm = permutation(n, rng);
        final int nv = (int) Math.round(n * o.val);
        final int[] valIdx = Arrays.copyOfRange(perm, 0, nv), trIdx = Arrays.copyOfRange(perm, nv, n);
        final int perT = all.t == null ? 0 : all.t.length / Math.max(1, n);
        final List<Tensor> valIn = take(all.inputs, valIdx);
        final double[] valY = take(all.y, valIdx, 1), valW = take(all.w, valIdx, 1), valT = take(all.t, valIdx, perT);
        final Tensor probe = g.run(rows(valIn.isEmpty() || nv == 0 ? take(all.inputs, new int[] {0}) : valIn, 0, 1)).get(0);
        final Loss kind = o.loss != null ? o.loss : Loss.forOutput(probe.shape);
        final Optimizer opt = optimizer(o.optimizer, o.hyper);
        final double lr0 = o.hyper.getOrDefault("lr", o.optimizer.equals("sgd") ? 1e-2 : 1e-3);
        final TapePlan plan = plan(ad, take(all.inputs, new int[] {trIdx[0], trIdx[Math.min(1, trIdx.length - 1)]}));
        final int threads = o.threads > 0 ? o.threads : Runtime.getRuntime().availableProcessors();
        final Map<String, double[]> grads = new LinkedHashMap<>(), weights = new LinkedHashMap<>();
        for (String l : ad.leaves()) {
            grads.put(l, new double[g.consts.get(l).numel()]);
            weights.put(l, g.consts.get(l).data);
        }
        log.accept(String.format(Locale.ROOT, "%s: %d weights trained%s, %d rows (%d to train, %d held out), loss %s, %s; tape %.2f MB a row,"
                + " %.0f MB to spend", g.name, ad.trainable(), ad.guessed ? " (guessed: exported frozen)" : "", n, trIdx.length, nv, kind.key,
            opt.describe(), plan.perRow / 1048576.0, memory / 1048576.0));
        final List<Epoch> epochs = new ArrayList<>();
        Map<String, double[]> best = ad.snapshot();
        double bestLoss = Double.POSITIVE_INFINITY;
        int bestEpoch = 0;
        Step last = null;
        final ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads, r -> {
            final Thread t = new Thread(r, "sphere-autograd");
            t.setDaemon(true);
            return t;
        }) : null;
        try {
            final int batches = (trIdx.length + o.batch - 1) / o.batch;
            final int totalSteps = Math.max(1, batches * o.epochs);
            int stepNo = 0;
            for (int e = 1; e <= o.epochs; e++) {
                final long t0 = System.nanoTime();
                shuffle(trIdx, rng);
                double sum = 0;
                int seen = 0;
                for (int b = 0; b < batches; b++) {
                    if (canceled != null && canceled.getAsBoolean()) {
                        log.accept("canceled: the best epoch so far kept");
                        e = o.epochs + 1;
                        break;
                    }
                    final int[] idx = Arrays.copyOfRange(trIdx, b * o.batch, Math.min(trIdx.length, (b + 1) * o.batch));
                    setLr(opt, scheduled(o.schedule, lr0, stepNo++, totalSteps));
                    last = gradients(ad, take(all.inputs, idx), kind, take(all.y, idx, 1), take(all.t, idx, perT), take(all.w, idx, 1),
                        grads, plan, memory, threads, pool);
                    if (o.clip > 0) {
                        clip(grads, o.clip);
                    }
                    opt.step(weights, grads);
                    sum += last.loss * idx.length;
                    seen += idx.length;
                }
                if (seen == 0) {
                    break;
                }
                double vl = Double.NaN, va = Double.NaN;
                if (nv > 0) {
                    final Tensor out = g.run(valIn).get(0);
                    final double[] seed = new double[out.numel()];
                    double tw = nv;
                    if (valW != null) {
                        tw = 0;
                        for (double v : valW) {
                            tw += v;
                        }
                    }
                    vl = loss(kind, out, valY, valT, valW, tw, seed);
                    if (kind != Loss.MSE && valY != null) {
                        final double[] yb = new double[valY.length];
                        final int top = kind == Loss.CROSS_ENTROPY ? out.shape[1] - 1 : 1;
                        for (int i = 0; i < yb.length; i++) {
                            yb[i] = valY[i] == top ? 1 : 0;
                        }
                        va = auc(scores(out, kind), yb, valW);
                    }
                }
                final double secs = (System.nanoTime() - t0) / 1e9;
                final Epoch ep = new Epoch(e, sum / seen, vl, va, secs, currentLr(opt));
                epochs.add(ep);
                log.accept(String.format(Locale.ROOT, "  epoch %d: loss %.5f, val_loss %.5f, val_auc %.4f  (%.1f s, %d micro-batch%s of %d on %d"
                        + " thread%s)", e, ep.loss, vl, va, secs, last.chunks(), last.chunks() == 1 ? "" : "es", last.chunk(), last.threads(),
                    last.threads() == 1 ? "" : "s"));
                final double score = Double.isNaN(vl) ? ep.loss : vl;
                if (score < bestLoss) {
                    bestLoss = score;
                    bestEpoch = e;
                    best = ad.snapshot();
                }
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        ad.restore(best);
        final String name = o.name != null ? o.name : g.name + "_ft";
        final Path out = g.source().resolveSibling(name + ".sng.spx");
        final Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Name", name);
        extra.put("TrainedFrom", g.name);
        extra.put("TrainedWith", "Sphere GraphTrainer (Java autograd): " + opt.describe() + ", " + epochs.size() + " epochs of batches of "
            + o.batch + ", loss " + kind.key + ", on " + dataset.getFileName());
        extra.put("BestEpoch", String.valueOf(bestEpoch));
        if (bestEpoch > 0) {
            final Epoch be = epochs.get(bestEpoch - 1);
            extra.put("ValLoss", String.valueOf(be.valLoss()));
            extra.put("ValAuc", String.valueOf(be.valAuc()));
        }
        final Map<String, double[]> trained = new LinkedHashMap<>();
        for (String l : ad.leaves()) {
            trained.put(l, g.consts.get(l).data);
        }
        final Path written = g.save(out, trained, extra);
        return new Result(written, epochs, bestEpoch, ad.trainable(), n, trIdx.length, nv, opt.describe(), kind, last, ad.guessed);
    }

    static double scheduled(String schedule, double lr0, int step, int total) {
        if ("cosine".equals(schedule)) {
            return 0.5 * lr0 * (1 + Math.cos(Math.PI * step / total));
        }
        if ("linear".equals(schedule)) {
            return lr0 * (1 - (double) step / total);
        }
        return lr0;
    }

    private static void setLr(Optimizer o, double lr) {
        if (o instanceof Adam a) {
            a.lrNow = lr;
        } else if (o instanceof Sgd s) {
            s.lrNow = lr;
        }
    }

    private static double currentLr(Optimizer o) {
        return o instanceof Adam a ? a.lrNow : o instanceof Sgd s ? s.lrNow : Double.NaN;
    }

    private static int[] permutation(int n, SplittableRandom rng) {
        final int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = i;
        }
        shuffle(p, rng);
        return p;
    }

    private static void shuffle(int[] p, SplittableRandom rng) {
        for (int i = p.length - 1; i > 0; i--) {
            final int j = rng.nextInt(i + 1);
            final int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
    }
}
