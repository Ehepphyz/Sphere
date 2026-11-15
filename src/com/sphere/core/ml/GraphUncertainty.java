package com.sphere.core.ml;

import com.sphere.core.ml.NeuralGraph.GraphException;
import com.sphere.core.ml.NeuralGraph.Tensor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A tagger that knows when it does not know: the uncertainty of any graph's predictions, in Java, from its curvature.
 *
 * <ul>
 * <li>Curvature: the generalized Gauss-Newton matrix of the loss, G = J^T H_out J, applied to a vector without ever
 * being formed -- J v by central differences of the forward (the compiled graph, reading the weights where they are),
 * H_out the loss's own Hessian in the outputs (diag(p) - p p^T for softmax), J^T u by the exact backward of
 * {@link GraphAutograd}. The Hessian itself (differences of exact gradients) is there too: ':torch grad' holds both
 * to PyTorch's double backward.</li>
 * <li>Laplace's approximation: the posterior of the weights a Gaussian about the trained ones, its precision N G +
 * delta I -- G's leading eigenpairs found by Lanczos (full reorthogonalization), the prior delta elsewhere -- and each
 * prediction's variance from the linearized network, sigma^2 = J Sigma J^T, turned into a probability by the probit
 * approximation p = sigmoid(mu / sqrt(1 + pi sigma^2 / 8)). delta is chosen as the evidence a held-out set gives:
 * the one of least negative log-likelihood (MacKay; Daxberger et al. 2021).</li>
 * <li>Conformal prediction: sets of classes that contain the truth with probability 1 - alpha whatever the network,
 * from the scores of a calibration set (Vovk; Angelopoulos and Bates 2021): coverage guaranteed, measured.</li>
 * <li>Out of distribution: jets unlike the training's -- told apart by the softmax's confidence, by the energy
 * -logsumexp(z), by Laplace's variance -- each judged by its AUROC.</li>
 * </ul>
 */
public final class GraphUncertainty {

    private final NeuralGraph g;
    private final GraphAutograd ad;
    private final List<String> leaves;
    private final int[] offset;
    /** The number of values trained. */
    public final int dim;
    private GraphCompiler.CompiledGraph compiled;

    public GraphUncertainty(NeuralGraph g) {
        this.g = g;
        this.ad = new GraphAutograd(g);
        this.leaves = new ArrayList<>(ad.leaves());
        this.offset = new int[leaves.size() + 1];
        for (int i = 0; i < leaves.size(); i++) {
            offset[i + 1] = offset[i] + g.consts.get(leaves.get(i)).numel();
        }
        this.dim = offset[leaves.size()];
    }

    private double[] flat(Map<String, double[]> m) {
        final double[] v = new double[dim];
        for (int i = 0; i < leaves.size(); i++) {
            final double[] a = m.get(leaves.get(i));
            System.arraycopy(a, 0, v, offset[i], a.length);
        }
        return v;
    }

    /** The trained weights moved by s v, in place: what every runtime of the graph reads. */
    private void move(double[] v, double s) {
        for (int i = 0; i < leaves.size(); i++) {
            final double[] w = g.consts.get(leaves.get(i)).data;
            for (int k = 0; k < w.length; k++) {
                w[k] += s * v[offset[i] + k];
            }
        }
    }

    private static double maxAbs(double[] v) {
        double m = 0;
        for (double x : v) {
            m = Math.max(m, Math.abs(x));
        }
        return m;
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** The graph's first output: the compiled graph when it compiles (weights read live: fold off), the interpreter else. */
    private Tensor forward(List<Tensor> in) {
        final int rows = GraphTrainer.rowsOf(in);
        if (compiled == null || compiled.batch != rows) {
            try {
                compiled = GraphCompiler.compile(g, in, false);
            } catch (RuntimeException e) {
                compiled = null;
            }
        }
        return compiled != null ? compiled.run(in).get(0) : g.run(in, Double.POSITIVE_INFINITY, 1, null).get(0);
    }

    /** The gradient of the mean loss (cross entropy, or binary for a single logit) over these rows. */
    public double[] gradient(List<Tensor> in, double[] y) {
        final Map<String, double[]> grads = new LinkedHashMap<>();
        for (String l : leaves) {
            grads.put(l, new double[g.consts.get(l).numel()]);
        }
        final Tensor probe = forward(GraphTrainer.rows(in, 0, 1));
        GraphTrainer.gradients(ad, in, GraphTrainer.Loss.forOutput(probe.shape), y, null, null, grads, null, Double.POSITIVE_INFINITY, 1,
            null);
        return flat(grads);
    }

    /** The loss's Hessian times v, by central differences of exact gradients: (grad(w + e v) - grad(w - e v)) / 2e. */
    public double[] hvp(List<Tensor> in, double[] y, double[] v) {
        final Map<String, double[]> keep = ad.snapshot();
        final double e = 1e-5 * (1 + maxAbs(flat(keep))) / Math.max(maxAbs(v), 1e-300);
        try {
            move(v, e);
            final double[] up = gradient(in, y);
            ad.restore(keep);
            move(v, -e);
            final double[] down = gradient(in, y);
            final double[] out = new double[dim];
            for (int i = 0; i < dim; i++) {
                out[i] = (up[i] - down[i]) / (2 * e);
            }
            return out;
        } finally {
            ad.restore(keep);
        }
    }

    /** The loss's Hessian in the outputs times u, row by row: softmax's diag(p) - p p^T, or sigmoid's p (1 - p); over N rows. */
    private static double[] outputHessian(Tensor z, double[] jv) {
        final int n = z.shape[0], c = z.numel() / n;
        final double[] u = new double[jv.length];
        for (int r = 0; r < n; r++) {
            if (c == 1) {
                final double p = 1.0 / (1.0 + Math.exp(-z.data[r]));
                u[r] = p * (1 - p) * jv[r] / n;
                continue;
            }
            double m = Double.NEGATIVE_INFINITY, s = 0;
            for (int k = 0; k < c; k++) {
                m = Math.max(m, z.data[r * c + k]);
            }
            final double[] p = new double[c];
            for (int k = 0; k < c; k++) {
                p[k] = Math.exp(z.data[r * c + k] - m);
                s += p[k];
            }
            double pj = 0;
            for (int k = 0; k < c; k++) {
                p[k] /= s;
                pj += p[k] * jv[r * c + k];
            }
            for (int k = 0; k < c; k++) {
                u[r * c + k] = (p[k] * jv[r * c + k] - p[k] * pj) / n;
            }
        }
        return u;
    }

    /** The generalized Gauss-Newton matrix of the mean loss times v: J^T (H_out (J v)), J v by differences of the forward. */
    public double[] ggnv(List<Tensor> in, double[] v) {
        final Map<String, double[]> keep = ad.snapshot();
        final double e = 1e-6 * (1 + maxAbs(flat(keep))) / Math.max(maxAbs(v), 1e-300);
        final Tensor up, down;
        try {
            move(v, e);
            up = forward(in);
            ad.restore(keep);
            move(v, -e);
            down = forward(in);
        } finally {
            ad.restore(keep);
        }
        final double[] jv = new double[up.numel()];
        for (int i = 0; i < jv.length; i++) {
            jv[i] = (up.data[i] - down.data[i]) / (2 * e);
        }
        final GraphAutograd.Frame f = ad.forward(in, false);
        final Tensor z = f.outputs().get(0);
        final List<Tensor> seeds = new ArrayList<>();
        seeds.add(new Tensor(outputHessian(z, jv), z.shape.clone()));
        for (int i = 1; i < f.outputs().size(); i++) {
            seeds.add(null);
        }
        return flat(ad.backward(f, seeds).weights());
    }

    // ------------------------------------------------------------------
    // Lanczos, and the small symmetric eigenproblem it leaves
    // ------------------------------------------------------------------

    /** Eigenvalues (descending) and their eigenvectors (rows). */
    public record Eigen(double[] values, double[][] vectors, int steps) {
    }

    /** The k leading eigenpairs of a symmetric operator on R^n: Lanczos with full reorthogonalization, then Jacobi. */
    public static Eigen lanczos(UnaryOperator<double[]> op, int n, int k, long seed) {
        final int m0 = Math.min(k, n);
        final SplittableRandom rng = new SplittableRandom(seed);
        final double[][] q = new double[m0 + 1][];
        final double[] alpha = new double[m0], beta = new double[m0];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = rng.nextGaussian();
        }
        double nv = Math.sqrt(dot(v, v));
        for (int i = 0; i < n; i++) {
            v[i] /= nv;
        }
        q[0] = v;
        int m = m0;
        for (int j = 0; j < m0; j++) {
            final double[] w = op.apply(q[j]);
            alpha[j] = dot(q[j], w);
            for (int pass = 0; pass < 2; pass++) {   // twice: Kahan's "twice is enough"
                for (int i = 0; i <= j; i++) {
                    final double c = dot(q[i], w);
                    for (int t = 0; t < n; t++) {
                        w[t] -= c * q[i][t];
                    }
                }
            }
            beta[j] = Math.sqrt(dot(w, w));
            if (beta[j] < 1e-12 * Math.max(1, Math.abs(alpha[j])) || j == m0 - 1) {
                m = j + 1;
                break;
            }
            for (int t = 0; t < n; t++) {
                w[t] /= beta[j];
            }
            q[j + 1] = w;
        }
        final double[][] tri = new double[m][m];
        for (int j = 0; j < m; j++) {
            tri[j][j] = alpha[j];
            if (j + 1 < m) {
                tri[j][j + 1] = tri[j + 1][j] = beta[j];
            }
        }
        final double[][][] ev = jacobi(tri);
        final double[] vals = ev[0][0];
        final double[][] s = ev[1];
        final Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(vals[b], vals[a]));
        final double[] outVals = new double[m];
        final double[][] outVecs = new double[m][n];
        for (int r = 0; r < m; r++) {
            final int i = order[r];
            outVals[r] = vals[i];
            for (int j = 0; j < m; j++) {
                final double sj = s[j][i];
                for (int t = 0; t < n; t++) {
                    outVecs[r][t] += sj * q[j][t];
                }
            }
        }
        return new Eigen(outVals, outVecs, m);
    }

    /** Cyclic Jacobi rotations on a small symmetric matrix: {{eigenvalues}}, and the eigenvectors as columns. */
    static double[][][] jacobi(double[][] a0) {
        final int n = a0.length;
        final double[][] a = new double[n][];
        final double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            a[i] = a0[i].clone();
            v[i][i] = 1;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    off += a[i][j] * a[i][j];
                }
            }
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int r = p + 1; r < n; r++) {
                    if (Math.abs(a[p][r]) < 1e-300) {
                        continue;
                    }
                    final double theta = (a[r][r] - a[p][p]) / (2 * a[p][r]);
                    final double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    final double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < n; k++) {
                        final double akp = a[k][p], akr = a[k][r];
                        a[k][p] = c * akp - s * akr;
                        a[k][r] = s * akp + c * akr;
                    }
                    for (int k = 0; k < n; k++) {
                        final double apk = a[p][k], ark = a[r][k];
                        a[p][k] = c * apk - s * ark;
                        a[r][k] = s * apk + c * ark;
                    }
                    for (int k = 0; k < n; k++) {
                        final double vkp = v[k][p], vkr = v[k][r];
                        v[k][p] = c * vkp - s * vkr;
                        v[k][r] = s * vkp + c * vkr;
                    }
                }
            }
        }
        final double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = a[i][i];
        }
        return new double[][][] {{d}, v};
    }

    // ------------------------------------------------------------------
    // Predictions: their gradients, Laplace's variance, conformal sets
    // ------------------------------------------------------------------

    /** d(logit_last - logit_first)/dw for one row (the single logit for a binary output): the linearized network's J. */
    public double[] logitGradient(List<Tensor> one) {
        final GraphAutograd.Frame f = ad.forward(one, false);
        final Tensor z = f.outputs().get(0);
        final double[] seed = new double[z.numel()];
        if (seed.length == 1) {
            seed[0] = 1;
        } else {
            seed[0] = -1;
            seed[seed.length - 1] = 1;
        }
        final List<Tensor> seeds = new ArrayList<>();
        seeds.add(new Tensor(seed, z.shape.clone()));
        for (int i = 1; i < f.outputs().size(); i++) {
            seeds.add(null);
        }
        return flat(ad.backward(f, seeds).weights());
    }

    /** sigma^2 = J Sigma J^T with Sigma = (I - U U^T) / delta + U diag(1 / (N lambda + delta)) U^T. */
    static double variance(double[] j, Eigen e, double n, double delta) {
        double proj = 0, inSpace = 0;
        for (int k = 0; k < e.values().length; k++) {
            final double c = dot(e.vectors()[k], j);
            proj += c * c;
            inSpace += c * c / (n * Math.max(e.values()[k], 0) + delta);
        }
        return Math.max(dot(j, j) - proj, 0) / delta + inSpace;
    }

    static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** P(last class) of each row of logits (the sigmoid of a single logit). */
    static double[] signal(Tensor z) {
        final int n = z.shape[0], c = z.numel() / n;
        final double[] p = new double[n];
        for (int r = 0; r < n; r++) {
            if (c == 1) {
                p[r] = sigmoid(z.data[r]);
                continue;
            }
            double m = Double.NEGATIVE_INFINITY, s = 0;
            for (int k = 0; k < c; k++) {
                m = Math.max(m, z.data[r * c + k]);
            }
            for (int k = 0; k < c; k++) {
                s += Math.exp(z.data[r * c + k] - m);
            }
            p[r] = Math.exp(z.data[r * c + c - 1] - m) / s;
        }
        return p;
    }

    /** The logit difference of each row (last minus first; the logit of a binary output). */
    static double[] margin(Tensor z) {
        final int n = z.shape[0], c = z.numel() / n;
        final double[] d = new double[n];
        for (int r = 0; r < n; r++) {
            d[r] = c == 1 ? z.data[r] : z.data[r * c + c - 1] - z.data[r * c];
        }
        return d;
    }

    static double nll(double[] p, double[] y) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            final double q = Math.min(Math.max(y[i] == 1 ? p[i] : 1 - p[i], 1e-15), 1);
            s -= Math.log(q);
        }
        return s / p.length;
    }

    static double brier(double[] p, double[] y) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            s += (p[i] - y[i]) * (p[i] - y[i]);
        }
        return s / p.length;
    }

    /** Expected calibration error of P(signal) in 10 equal bins. */
    static double ece(double[] p, double[] y) {
        final double[] sp = new double[10], sy = new double[10];
        final int[] n = new int[10];
        for (int i = 0; i < p.length; i++) {
            final int b = Math.min(9, (int) (p[i] * 10));
            sp[b] += p[i];
            sy[b] += y[i];
            n[b]++;
        }
        double e = 0;
        for (int b = 0; b < 10; b++) {
            if (n[b] > 0) {
                e += Math.abs(sp[b] - sy[b]) / p.length;
            }
        }
        return e;
    }

    /** The area under the ROC curve of a score that should be higher for the positives (Mann-Whitney, ties half). */
    static double auroc(double[] pos, double[] neg) {
        final double[] all = new double[pos.length + neg.length];
        final boolean[] isPos = new boolean[all.length];
        System.arraycopy(pos, 0, all, 0, pos.length);
        System.arraycopy(neg, 0, all, pos.length, neg.length);
        Arrays.fill(isPos, 0, pos.length, true);
        final Integer[] order = new Integer[all.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(all[a], all[b]));
        double rankSum = 0;
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j < order.length && all[order[j]] == all[order[i]]) {
                j++;
            }
            final double rank = (i + j + 1) / 2.0;   // ties: their mean rank
            for (int k = i; k < j; k++) {
                if (isPos[order[k]]) {
                    rankSum += rank;
                }
            }
            i = j;
        }
        return (rankSum - pos.length * (pos.length + 1) / 2.0) / ((double) pos.length * neg.length);
    }

    // ------------------------------------------------------------------
    // The analysis
    // ------------------------------------------------------------------

    /** How to analyze. */
    public static final class Options {
        public int rank = 20, hessianRows = 256, laplaceRows = 300, maxRows = -1;
        public double alpha = 0.1;
        public long seed = 3;
        public Path ood;
    }

    /** What the analysis found. */
    public record Report(int weights, int rows, int fit, int calibration, int test, double[] eigenvalues, int lanczos, double delta,
                         double effectiveParameters, double nllBefore, double nllAfter, double eceBefore, double eceAfter, double brierBefore,
                         double brierAfter, double alpha, double coverage, double coverageSignal, double coverageBackground, double setSize,
                         double singletons, double empty, double threshold, Map<String, Double> ood, double seconds) {
    }

    private static List<Tensor> take(List<Tensor> in, int[] idx) {
        final List<Tensor> out = new ArrayList<>();
        for (Tensor x : in) {
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

    private Tensor logits(List<Tensor> in) {
        final int n = GraphTrainer.rowsOf(in);
        final List<Tensor> parts = new ArrayList<>();
        final int chunk = 256;
        for (int lo = 0; lo < n; lo += chunk) {
            parts.add(forward(GraphTrainer.rows(in, lo, Math.min(n, lo + chunk))));
        }
        final int per = parts.get(0).numel() / parts.get(0).shape[0];
        final double[] d = new double[n * per];
        int at = 0;
        for (Tensor t : parts) {
            System.arraycopy(t.data, 0, d, at, t.numel());
            at += t.numel();
        }
        final int[] s = parts.get(0).shape.clone();
        s[0] = n;
        return new Tensor(d, s);
    }

    private double[][] gradients(List<Tensor> in, int rows) {
        final double[][] j = new double[rows][];
        for (int r = 0; r < rows; r++) {
            j[r] = logitGradient(GraphTrainer.rows(in, r, r + 1));
        }
        return j;
    }

    /** The whole analysis of a graph on a labeled dataset (and, if given, jets of another distribution). */
    public static Report analyze(NeuralGraph g, Path dataset, Options o, Consumer<String> log) throws java.io.IOException {
        final long t0 = System.nanoTime();
        final GraphUncertainty u = new GraphUncertainty(g);
        final GraphTrainer.Data data = GraphTrainer.data(g, dataset, o.maxRows);
        if (data.y() == null) {
            throw new GraphException(dataset.getFileName() + " has no labels");
        }
        final int n = data.rows();
        final SplittableRandom rng = new SplittableRandom(o.seed);
        final int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            final int j = rng.nextInt(i + 1), t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        final int nf = (int) (0.4 * n), nc = (int) (0.3 * n);
        final int[] fit = Arrays.copyOfRange(perm, 0, nf), cal = Arrays.copyOfRange(perm, nf, nf + nc), test = Arrays.copyOfRange(perm, nf + nc, n);
        final List<Tensor> inCal = take(data.inputs(), cal), inTest = take(data.inputs(), test);
        final double[] yCal = new double[cal.length], yTest = new double[test.length];
        final double top = Arrays.stream(data.y()).max().orElse(1);
        for (int i = 0; i < cal.length; i++) {
            yCal[i] = data.y()[cal[i]] == top ? 1 : 0;
        }
        for (int i = 0; i < test.length; i++) {
            yTest[i] = data.y()[test[i]] == top ? 1 : 0;
        }
        // the curvature: Lanczos on the Gauss-Newton matrix of the loss over the fit rows
        final int[] hRows = Arrays.copyOf(fit, Math.min(fit.length, o.hessianRows));
        final List<Tensor> inH = take(data.inputs(), hRows);
        log.accept(String.format(Locale.ROOT, "  %d weights; Lanczos on the Gauss-Newton matrix over %d jets, %d steps...", u.dim, hRows.length,
            o.rank));
        final Eigen e = lanczos(v -> u.ggnv(inH, v), u.dim, o.rank, o.seed);
        // the prior: the evidence of the calibration rows
        final int nl = Math.min(o.laplaceRows, cal.length), nt = Math.min(o.laplaceRows, test.length);
        final double[][] jCal = u.gradients(inCal, nl), jTest = u.gradients(inTest, nt);
        final Tensor zCal = u.logits(inCal), zTest = u.logits(inTest);
        final double[] mCal = margin(zCal), mTest = margin(zTest), pCal = signal(zCal), pTest = signal(zTest);
        double best = Double.POSITIVE_INFINITY, delta = 1;
        for (int k = -6; k <= 8; k++) {
            final double d = Math.pow(10, k * 0.5) * u.dim / 100.0;
            final double[] p = new double[nl];
            for (int i = 0; i < nl; i++) {
                p[i] = sigmoid(mCal[i] / Math.sqrt(1 + Math.PI * variance(jCal[i], e, fit.length, d) / 8));
            }
            final double l = nll(p, Arrays.copyOf(yCal, nl));
            if (l < best) {
                best = l;
                delta = d;
            }
        }
        double eff = 0;
        for (double lam : e.values()) {
            eff += fit.length * Math.max(lam, 0) / (fit.length * Math.max(lam, 0) + delta);
        }
        final double[] pBefore = Arrays.copyOf(pTest, nt), pAfter = new double[nt], y = Arrays.copyOf(yTest, nt), varTest = new double[nt];
        for (int i = 0; i < nt; i++) {
            varTest[i] = variance(jTest[i], e, fit.length, delta);
            pAfter[i] = sigmoid(mTest[i] / Math.sqrt(1 + Math.PI * varTest[i] / 8));
        }
        // conformal sets from the calibration rows, judged on all the test rows
        final double[] scores = new double[cal.length];
        for (int i = 0; i < cal.length; i++) {
            scores[i] = 1 - (yCal[i] == 1 ? pCal[i] : 1 - pCal[i]);
        }
        Arrays.sort(scores);
        final int kq = (int) Math.ceil((cal.length + 1) * (1 - o.alpha));
        final double q = kq > cal.length ? 1.0 : scores[kq - 1];
        int covered = 0, size = 0, single = 0, empty = 0, sig = 0, sigCov = 0;
        for (int i = 0; i < test.length; i++) {
            final boolean has1 = 1 - pTest[i] <= q, has0 = pTest[i] <= q;
            final int sz = (has1 ? 1 : 0) + (has0 ? 1 : 0);
            size += sz;
            single += sz == 1 ? 1 : 0;
            empty += sz == 0 ? 1 : 0;
            final boolean ok = yTest[i] == 1 ? has1 : has0;
            covered += ok ? 1 : 0;
            if (yTest[i] == 1) {
                sig++;
                sigCov += ok ? 1 : 0;
            }
        }
        final Map<String, Double> ood = new LinkedHashMap<>();
        if (o.ood != null) {
            final GraphTrainer.Data od = GraphTrainer.data(g, o.ood, Math.max(test.length, 1));
            final Tensor zo = u.logits(od.inputs());
            final double[] po = signal(zo);
            final int no = Math.min(od.rows(), test.length);
            final double[] mspIn = new double[test.length], mspOut = new double[no], enIn = new double[test.length], enOut = new double[no];
            for (int i = 0; i < test.length; i++) {
                mspIn[i] = 1 - Math.max(pTest[i], 1 - pTest[i]);
                enIn[i] = -logsumexp(zTest, i);
            }
            for (int i = 0; i < no; i++) {
                mspOut[i] = 1 - Math.max(po[i], 1 - po[i]);
                enOut[i] = -logsumexp(zo, i);
            }
            ood.put("1 - max softmax", auroc(mspOut, mspIn));
            ood.put("energy", auroc(enOut, enIn));
            final int nlo = Math.min(nt, no);
            final double[][] jo = u.gradients(od.inputs(), nlo);
            final double[] vo = new double[nlo];
            for (int i = 0; i < nlo; i++) {
                vo[i] = variance(jo[i], e, fit.length, delta);
            }
            ood.put("Laplace variance", auroc(vo, Arrays.copyOf(varTest, nlo)));
        }
        return new Report(u.dim, n, fit.length, cal.length, test.length, e.values(), e.steps(), delta, eff, nll(pBefore, y), nll(pAfter, y),
            ece(pBefore, y), ece(pAfter, y), brier(pBefore, y), brier(pAfter, y), o.alpha, covered / (double) test.length,
            sig == 0 ? Double.NaN : sigCov / (double) sig, test.length == sig ? Double.NaN : (covered - sigCov) / (double) (test.length - sig),
            size / (double) test.length, single / (double) test.length, empty / (double) test.length, q, ood, (System.nanoTime() - t0) / 1e9);
    }

    private static double logsumexp(Tensor z, int r) {
        final int c = z.numel() / z.shape[0];
        if (c == 1) {
            final double v = z.data[r];
            return Math.max(v, 0) + Math.log1p(Math.exp(-Math.abs(v)));   // log(1 + e^v): the binary logit's two classes
        }
        double m = Double.NEGATIVE_INFINITY, s = 0;
        for (int k = 0; k < c; k++) {
            m = Math.max(m, z.data[r * c + k]);
        }
        for (int k = 0; k < c; k++) {
            s += Math.exp(z.data[r * c + k] - m);
        }
        return m + Math.log(s);
    }
}
