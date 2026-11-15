package com.sphere.core.ml;

import com.sphere.core.bridge.Spx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A network trained by PyTorch or by Keras, run by Sphere itself: the seven networks of sphere_ml's zoo (an
 * MLP, Deep Sets, an Energy Flow Network, ParticleNet, a Particle Transformer, and LorentzNet and PELICAN, Lorentz
 * invariant, which see a jet through its particles' Minkowski products) read from their export (models/name.snn.spx) and
 * evaluated in double precision, in the order and with the formulas of sphere_ml's numpy reference -- so a
 * FastJet loop, a command or a plot runs a trained tagger with neither Python nor a framework, and
 * ':ml crosscheck' holds this to that reference as it holds PyTorch and Keras.
 *
 * A jet goes in as sphere_ml's datasets hold it: its particles' raw features (log pt, log E, log pt/ptJ,
 * log E/EJ, delta eta, delta phi, delta R, by default), a mask, and its high-level features; the network's
 * preprocessing (the training set's means and spreads) is applied here, as Python applies it, and its
 * probabilities go through the calibration ':ml calibrate' wrote into it (a temperature, or an isotonic map).
 */
public final class NeuralNet {

    private static final double BIG = 1.0e9;

    public final String model;
    public final String name;
    public final List<String> classes;
    public final String trainedWith;
    final int nClasses, features, points, highLevel, ptIndex;
    final int[] coords;
    private final Map<String, double[]> w = new HashMap<>();
    /** The calibration ':ml calibrate' wrote: "", "temperature" or "isotonic". */
    public final String calibration;
    private final double temperature;
    private double[] calibX, calibY;
    private double[] xMean, xStd, hlMean, hlStd;
    private int[] widths, phi, rho;
    private int k, fc, dim, heads, layers, ffn, pair;
    private int msg, channels, hidden;
    private double coef, scale;
    private int[][] blocks;

    private NeuralNet(Map<String, String> meta) {
        model = meta.getOrDefault("Model", "");
        name = meta.getOrDefault("Name", "");
        trainedWith = meta.getOrDefault("TrainedWith", "");
        classes = List.of(meta.getOrDefault("Classes", "background,signal").split(","));
        nClasses = Integer.parseInt(meta.getOrDefault("NClasses", String.valueOf(classes.size())));
        features = Integer.parseInt(meta.getOrDefault("Features", "0"));
        points = Integer.parseInt(meta.getOrDefault("Points", "0"));
        highLevel = Integer.parseInt(meta.getOrDefault("HighLevel", "0"));
        ptIndex = Integer.parseInt(meta.getOrDefault("PtIndex", "0"));
        coords = ints(meta.getOrDefault("Coords", "4,5"));
        calibration = meta.getOrDefault("Calibration", "");
        temperature = Double.parseDouble(meta.getOrDefault("Temperature", "1"));
    }

    private static int[] ints(String s) {
        if (s == null || s.isBlank()) {
            return new int[0];
        }
        return Arrays.stream(s.split(",")).mapToInt(v -> Integer.parseInt(v.strip())).toArray();
    }

    /** A network as sphere_ml exported it. */
    public static NeuralNet load(Path file) throws IOException {
        try (Spx.Reader r = new Spx.Reader(file)) {
            final Map<String, String> meta = r.meta();
            if (!"sphere-nn".equals(meta.get("Kind"))) {
                throw new IOException(file + " is not a network exported by sphere_ml");
            }
            final NeuralNet n = new NeuralNet(meta);
            switch (n.model) {
                case "mlp" -> n.widths = ints(meta.get("Widths"));
                case "deepsets", "efn" -> {
                    n.phi = ints(meta.get("Phi"));
                    n.rho = ints(meta.get("Rho"));
                }
                case "particlenet" -> {
                    n.k = Integer.parseInt(meta.get("K"));
                    n.fc = Integer.parseInt(meta.get("Fc"));
                    final String[] bs = meta.get("Blocks").split(";");
                    n.blocks = new int[bs.length][];
                    for (int b = 0; b < bs.length; b++) {
                        n.blocks[b] = ints(bs[b]);
                    }
                }
                case "transformer" -> {
                    n.dim = Integer.parseInt(meta.get("Dim"));
                    n.heads = Integer.parseInt(meta.get("Heads"));
                    n.layers = Integer.parseInt(meta.get("Layers"));
                    n.ffn = Integer.parseInt(meta.get("Ffn"));
                    n.pair = Integer.parseInt(meta.get("Pair"));
                }
                case "lorentznet" -> {
                    n.dim = Integer.parseInt(meta.get("Dim"));
                    n.msg = Integer.parseInt(meta.get("Msg"));
                    n.layers = Integer.parseInt(meta.get("Layers"));
                    n.coef = Double.parseDouble(meta.get("Coef"));
                    n.scale = Double.parseDouble(meta.get("Scale"));
                }
                case "pelican" -> {
                    n.channels = Integer.parseInt(meta.get("Channels"));
                    n.layers = Integer.parseInt(meta.get("Layers"));
                    n.hidden = Integer.parseInt(meta.get("Hidden"));
                    n.scale = Double.parseDouble(meta.get("Scale"));
                }
                default -> throw new IOException("no network " + n.model + " in this Sphere (mlp, deepsets, efn,"
                    + " particlenet, transformer, lorentznet, pelican)");
            }
            for (Spx.Section s : r.sections()) {
                if (s.type() == Spx.F64) {
                    n.w.put(s.name(), r.f64(s.name()));
                }
            }
            n.xMean = n.w.get("norm.x_mean");
            n.xStd = n.w.get("norm.x_std");
            n.hlMean = n.w.get("norm.hl_mean");
            n.hlStd = n.w.get("norm.hl_std");
            n.calibX = n.w.get("calib.x");
            n.calibY = n.w.get("calib.y");
            if (n.calibration.equals("isotonic") && (n.calibX == null || n.calibY == null || n.calibX.length != n.calibY.length)) {
                throw new IOException(file + ": an isotonic calibration without its knots");
            }
            return n;
        }
    }

    public int points() {
        return points;
    }

    public int features() {
        return features;
    }

    /**
     * The logits of one jet. {@code x} holds its particles' raw features, {@code x[i][f]}, {@code mask[i]} 1 for
     * a particle, 0 for padding; {@code hl} its raw high-level features (the MLP's only input).
     */
    public double[] logits(double[][] x, double[] mask, double[] hl) {
        if (model.equals("mlp")) {
            final double[] h = new double[highLevel];
            for (int f = 0; f < highLevel; f++) {
                h[f] = (hl[f] - hlMean[f]) / hlStd[f];
            }
            return mlp("mlp", h, widths.length + 1, true);
        }
        // the particles as the network sees them: standardized, padded or cut to its points
        final int n = points;
        final double[][] xs = new double[n][features];
        final double[][] pts = new double[n][coords.length];
        final double[] m = new double[n], pt = new double[n];
        for (int i = 0; i < n && i < x.length; i++) {
            m[i] = mask[i];
            for (int f = 0; f < features; f++) {
                xs[i][f] = (x[i][f] - xMean[f]) / xStd[f] * m[i];
            }
            for (int c = 0; c < coords.length; c++) {
                pts[i][c] = x[i][coords[c]];
            }
            pt[i] = Math.exp(x[i][ptIndex]) * m[i];
        }
        return switch (model) {
            case "deepsets" -> deepsets(xs, m);
            case "efn" -> efn(pts, pt);
            case "particlenet" -> particleNet(xs, pts, m);
            case "lorentznet" -> lorentzNet(gram(pts, pt, m), m);
            case "pelican" -> pelican(gram(pts, pt, m), m);
            default -> transformer(xs, pts, pt, m);
        };
    }

    /** The classes' probabilities of one jet, through the network's calibration if it has one. */
    public double[] probabilities(double[][] x, double[] mask, double[] hl) {
        return calibrated(logits(x, mask, hl));
    }

    /**
     * Logits made probabilities as sphere_ml.calibrated does: softmax(logits / T) for a temperature; P(signal)
     * through the isotonic map for isotonic -- linear between its knots, its end levels beyond, with numpy's
     * np.interp formula -- and plain softmax for a network not calibrated.
     */
    public double[] calibrated(double[] logits) {
        if (calibration.equals("temperature")) {
            final double[] z = new double[logits.length];
            for (int c = 0; c < z.length; c++) {
                z[c] = logits[c] / temperature;
            }
            return softmax(z);
        }
        final double[] p = softmax(logits);
        if (calibration.equals("isotonic") && p.length == 2) {
            final double p1 = Math.min(1.0, Math.max(0.0, interp(p[1], calibX, calibY)));
            return new double[] {1.0 - p1, p1};
        }
        return p;
    }

    /** np.interp's value at v: the end levels beyond the knots, the knot's level on one, else slope*(v - x_j) + y_j. */
    static double interp(double v, double[] xs, double[] ys) {
        final int n = xs.length;
        if (v >= xs[n - 1]) {
            return ys[n - 1];
        }
        if (v < xs[0]) {
            return ys[0];
        }
        int lo = 0, hi = n - 1;   // xs[lo] <= v < xs[hi]
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (xs[mid] <= v) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        if (xs[lo] == v) {
            return ys[lo];
        }
        final double slope = (ys[lo + 1] - ys[lo]) / (xs[lo + 1] - xs[lo]);
        return slope * (v - xs[lo]) + ys[lo];
    }

    /** The probabilities of a dataset's first rows (all if negative), through the calibration. */
    public double[][] probabilities(Path dataset, int rows) throws IOException {
        final double[][] z = logits(dataset, rows);
        for (int i = 0; i < z.length; i++) {
            z[i] = calibrated(z[i]);
        }
        return z;
    }

    static double[] softmax(double[] z) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : z) {
            max = Math.max(max, v);
        }
        final double[] out = new double[z.length];
        double sum = 0;
        for (int i = 0; i < z.length; i++) {
            out[i] = Math.exp(z[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < z.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Layers
    // ------------------------------------------------------------------

    /** x W + b, W stored as numpy's (in, out) row-major. */
    private double[] dense(String name, double[] x) {
        final double[] W = w.get(name + ".W"), b = w.get(name + ".b");
        final int in = x.length, out = b.length;
        final double[] y = b.clone();
        for (int i = 0; i < in; i++) {
            final double xi = x[i];
            if (xi == 0) {
                continue;
            }
            final int row = i * out;
            for (int o = 0; o < out; o++) {
                y[o] += xi * W[row + o];
            }
        }
        return y;
    }

    private static double[] relu(double[] x) {
        for (int i = 0; i < x.length; i++) {
            x[i] = Math.max(x[i], 0);
        }
        return x;
    }

    private double[] mlp(String prefix, double[] x, int n, boolean lastLinear) {
        for (int i = 0; i < n; i++) {
            x = dense(prefix + "." + i, x);
            if (i < n - 1 || !lastLinear) {
                relu(x);
            }
        }
        return x;
    }

    private double[] norm(String name, double[] x) {
        final double[] g = w.get(name + ".g"), b = w.get(name + ".b");
        double m = 0;
        for (double v : x) {
            m += v;
        }
        m /= x.length;
        double var = 0;
        for (double v : x) {
            var += (v - m) * (v - m);
        }
        var /= x.length;
        final double s = Math.sqrt(var + 1e-5);
        final double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = (x[i] - m) / s * g[i] + b[i];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Deep Sets
    // ------------------------------------------------------------------

    private double[] deepsets(double[][] xs, double[] m) {
        double[] sum = null;
        for (int i = 0; i < xs.length; i++) {
            final double[] h = mlp("phi", xs[i], phi.length, false);
            if (sum == null) {
                sum = new double[h.length];
            }
            for (int c = 0; c < h.length; c++) {
                sum[c] += h[c] * m[i];
            }
        }
        return mlp("rho", sum, rho.length + 1, true);
    }

    // ------------------------------------------------------------------
    // Energy Flow Network
    // ------------------------------------------------------------------

    /**
     * F(sum_i z_i Phi(deta_i, dphi_i)), z_i the particle's share of the jet's pt: a soft particle adds nothing,
     * a collinear splitting changes nothing -- infrared and collinear safe, as ':ml irc' shows. Padding has no
     * pt, hence no share.
     */
    private double[] efn(double[][] pts, double[] pt) {
        double total = 0;
        for (double v : pt) {
            total += v;
        }
        total = Math.max(total, 1e-30);
        double[] sum = null;
        for (int i = 0; i < pts.length; i++) {
            final double z = pt[i] / total;
            final double[] h = mlp("phi", pts[i], phi.length, false);
            if (sum == null) {
                sum = new double[h.length];
            }
            for (int c = 0; c < h.length; c++) {
                sum[c] += h[c] * z;
            }
        }
        return mlp("rho", sum, rho.length + 1, true);
    }

    // ------------------------------------------------------------------
    // ParticleNet
    // ------------------------------------------------------------------

    /**
     * The k nearest of each particle, as numpy's stable argsort orders them: by distance, then by index.
     * Padding is never the neighbor of a particle, nor a particle its own.
     */
    private static int[][] nearest(double[][] p, double[] m, int k) {
        final int n = p.length;
        final int[][] out = new int[n][k];
        final Integer[] order = new Integer[n];
        final double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int c = 0; c < p[i].length; c++) {
                    final double t = p[i][c] - p[j][c];
                    s += t * t;
                }
                d[j] = s + (1.0 - m[j]) * BIG + (i == j ? BIG : 0);
                order[j] = j;
            }
            Arrays.sort(order, (a, b) -> d[a] != d[b] ? Double.compare(d[a], d[b]) : Integer.compare(a, b));
            for (int t = 0; t < k; t++) {
                out[i][t] = order[t];
            }
        }
        return out;
    }

    private double[][] edgeConv(int b, double[][] pts, double[][] x, double[] m, int kk, int[] widths) {
        final int n = x.length, c = x[0].length, c0 = widths[0];
        final int[][] nb = nearest(pts, m, kk);
        // the first layer split as sphere_ml splits it: x_i (W_i - W_j) + b, then x_j W_j
        final double[] W0 = w.get("ec" + b + ".0.W"), b0 = w.get("ec" + b + ".0.b");
        final double[][] own = new double[n][c0], their = new double[n][c0];
        for (int i = 0; i < n; i++) {
            for (int o = 0; o < c0; o++) {
                double a = 0, t = 0;
                for (int f = 0; f < c; f++) {
                    final double wi = W0[f * c0 + o], wj = W0[(c + f) * c0 + o];
                    a += x[i][f] * (wi - wj);
                    t += x[i][f] * wj;
                }
                own[i][o] = a + b0[o];
                their[i][o] = t;
            }
        }
        final double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] agg = null;
            for (int t = 0; t < kk; t++) {
                final int j = nb[i][t];
                double[] e = new double[c0];
                for (int o = 0; o < c0; o++) {
                    e[o] = Math.max(their[j][o] + own[i][o], 0);
                }
                for (int l = 1; l < widths.length; l++) {
                    e = relu(dense("ec" + b + "." + l, e));
                }
                if (agg == null) {
                    agg = new double[e.length];
                }
                for (int o = 0; o < e.length; o++) {
                    agg[o] += e[o];
                }
            }
            final double[] sc = dense("ec" + b + ".s", x[i]);
            for (int o = 0; o < sc.length; o++) {
                sc[o] = Math.max(agg[o] / kk + sc[o], 0) * m[i];
            }
            out[i] = sc;
        }
        return out;
    }

    private double[] particleNet(double[][] xs, double[][] pts, double[] m) {
        final int kk = Math.min(k, points - 1);
        double[][] h = xs;
        for (int b = 0; b < blocks.length; b++) {
            h = edgeConv(b, b == 0 ? pts : h, h, m, kk, blocks[b]);
        }
        final double[] g = new double[h[0].length];
        double count = 0;
        for (int i = 0; i < h.length; i++) {
            count += m[i];
            for (int c = 0; c < g.length; c++) {
                g[c] += h[i][c] * m[i];
            }
        }
        for (int c = 0; c < g.length; c++) {
            g[c] /= Math.max(count, 1.0);
        }
        return dense("fc.1", relu(dense("fc.0", g)));
    }

    // ------------------------------------------------------------------
    // Particle Transformer
    // ------------------------------------------------------------------

    /** ln Delta, ln kT, ln z, ln m^2 of a pair, m^2 written without cancellation, as sphere_ml writes it. */
    private static double[] pairFeatures(double pti, double ptj, double deta, double dphi, double mm) {
        final double eps = 1e-8;
        final double delta = Math.sqrt(deta * deta + dphi * dphi + eps);
        final double ptmin = Math.min(pti, ptj);
        final double kt = ptmin * delta, z = ptmin / (pti + ptj + eps);
        final double sh = Math.sinh(0.5 * deta), sn = Math.sin(0.5 * dphi);
        final double m2 = 4.0 * pti * ptj * (sh * sh + sn * sn);
        return new double[] {Math.log(delta + eps) * mm, Math.log(kt + eps) * mm, Math.log(z + eps) * mm,
            Math.log(Math.max(m2, 0) + eps) * mm};
    }

    /** Multi-head attention of the rows q over the rows kv, masked keys left out, a bias per head if given. */
    private double[][] attention(String name, double[][] q, double[][] kv, double[] keyMask, double[][][] bias) {
        final int nq = q.length, nk = kv.length, dh = dim / heads;
        final double[][] Q = new double[nq][], K = new double[nk][], V = new double[nk][];
        for (int i = 0; i < nq; i++) {
            Q[i] = dense(name + ".q", q[i]);
        }
        for (int j = 0; j < nk; j++) {
            K[j] = dense(name + ".k", kv[j]);
            V[j] = dense(name + ".v", kv[j]);
        }
        final double scale = Math.sqrt(dh);
        final double[][] out = new double[nq][];
        for (int i = 0; i < nq; i++) {
            final double[] o = new double[dim];
            for (int h = 0; h < heads; h++) {
                final double[] s = new double[nk];
                double max = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < nk; j++) {
                    double dot = 0;
                    for (int t = h * dh; t < (h + 1) * dh; t++) {
                        dot += Q[i][t] * K[j][t];
                    }
                    s[j] = dot / scale + (bias == null ? 0 : bias[h][i][j]) - (1.0 - keyMask[j]) * BIG;
                    max = Math.max(max, s[j]);
                }
                double sum = 0;
                for (int j = 0; j < nk; j++) {
                    s[j] = Math.exp(s[j] - max);
                    sum += s[j];
                }
                for (int j = 0; j < nk; j++) {
                    final double a = s[j] / sum;
                    for (int t = h * dh; t < (h + 1) * dh; t++) {
                        o[t] += a * V[j][t];
                    }
                }
            }
            out[i] = dense(name + ".o", o);
        }
        return out;
    }

    /** A pre-norm block: x + attention(norm x), then + feed-forward(norm x). */
    private double[][] block(String name, double[][] x, double[][] kv, double[] keyMask, double[][][] bias) {
        final double[][] h = new double[x.length][];
        for (int i = 0; i < x.length; i++) {
            h[i] = norm(name + ".n1", x[i]);
        }
        double[][] hk = h;
        if (kv != null) {
            hk = new double[kv.length][];
            for (int j = 0; j < kv.length; j++) {
                hk[j] = norm(name + ".n1", kv[j]);
            }
        }
        final double[][] a = attention(name, h, hk, keyMask, bias);
        final double[][] out = new double[x.length][];
        for (int i = 0; i < x.length; i++) {
            final double[] r = new double[dim];
            for (int t = 0; t < dim; t++) {
                r[t] = x[i][t] + a[i][t];
            }
            final double[] f = dense(name + ".f1", relu(dense(name + ".f0", norm(name + ".n2", r))));
            for (int t = 0; t < dim; t++) {
                r[t] += f[t];
            }
            out[i] = r;
        }
        return out;
    }

    private double[] transformer(double[][] xs, double[][] pts, double[] pt, double[] m) {
        final int n = xs.length;
        double[][] h = new double[n][];
        for (int i = 0; i < n; i++) {
            h[i] = mlp("emb", xs[i], 2, false);
            for (int t = 0; t < dim; t++) {
                h[i][t] *= m[i];
            }
        }
        // the pairs' bias, one matrix per head
        final double[][][] bias = new double[heads][n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double[] u = pairFeatures(pt[i], pt[j], pts[i][0] - pts[j][0], pts[i][1] - pts[j][1], m[i] * m[j]);
                final double[] b = mlp("pair", u, 2, true);
                for (int hh = 0; hh < heads; hh++) {
                    bias[hh][i][j] = b[hh];
                }
            }
        }
        for (int l = 0; l < layers; l++) {
            h = block("blk" + l, h, null, m, bias);
            for (int i = 0; i < n; i++) {
                for (int t = 0; t < dim; t++) {
                    h[i][t] *= m[i];
                }
            }
        }
        // the class token attends to itself and to the particles
        final double[] token = w.get("cls.token");
        final double[][] kv = new double[n + 1][];
        kv[0] = token.clone();
        System.arraycopy(h, 0, kv, 1, n);
        final double[] km = new double[n + 1];
        km[0] = 1;
        System.arraycopy(m, 0, km, 1, n);
        final double[][] cls = block("cls", new double[][] {token.clone()}, kv, km, null);
        return dense("head", norm("head.n", cls[0]));
    }

    // ------------------------------------------------------------------
    // Datasets
    // ------------------------------------------------------------------

    /** The logits of the first rows of a dataset written by MlData or sphere_ml (all of them for rows < 0). */
    // ------------------------------------------------------------------
    // The Lorentz invariant networks: LorentzNet and PELICAN, on the jet's Minkowski products
    // ------------------------------------------------------------------

    /** sgn(x) log(1 + |x|), as sphere_ml's _psi. */
    private static double psi(double x) {
        return Math.signum(x) * Math.log(1.0 + Math.abs(x));
    }

    /**
     * The Minkowski products of the jet's (massless) particles in units of scale^2, as sphere_ml.gram computes
     * them: 2 pt_i pt_j (sinh^2(deta/2) + sin^2(dphi/2)), with no cancellation for close particles.
     */
    private double[][] gram(double[][] pts, double[] pt, double[] m) {
        final int n = points;
        final double[][] g = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double sh = Math.sinh(0.5 * (pts[i][0] - pts[j][0])), sn = Math.sin(0.5 * (pts[i][1] - pts[j][1]));
                g[i][j] = 2.0 * (pt[i] / scale) * (pt[j] / scale) * (sh * sh + sn * sn) * (m[i] * m[j]);
            }
        }
        return g;
    }

    /** One row x times the rows [from, from + x.length) of a weight (in, out) of name, no bias. */
    private double[] project(String name, double[] x, int from) {
        final double[] W = w.get(name + ".W");
        final int out = w.get(name + ".b").length;
        final double[] y = new double[out];
        for (int i = 0; i < x.length; i++) {
            final double xi = x[i];
            if (xi == 0) {
                continue;
            }
            final int row = (from + i) * out;
            for (int o = 0; o < out; o++) {
                y[o] += xi * W[row + o];
            }
        }
        return y;
    }

    /**
     * LorentzNet on the Gram matrix, as sphere_ml's forward: the particles' scalars from their invariants
     * (psi of their product with the jet and of the jet's mass squared), equivariant blocks moving G to A G A^T,
     * a masked mean.
     */
    private double[] lorentzNet(double[][] g0, double[] m) {
        final int n = points;
        double[][] g = g0;
        final double[] jdot = new double[n];
        double mj = 0;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) {
                s += g[i][j];
            }
            jdot[i] = s;
        }
        for (int i = 0; i < n; i++) {
            mj += jdot[i];
        }
        double[][] h = new double[n][];
        for (int i = 0; i < n; i++) {
            h[i] = relu(dense("lz.emb", new double[] {psi(jdot[i]), psi(mj)}));
            for (int c = 0; c < h[i].length; c++) {
                h[i][c] *= m[i];
            }
        }
        for (int l = 0; l < layers; l++) {
            final Object[] hg = lgeb("lz" + l, h, g, m, l == layers - 1);
            h = (double[][]) hg[0];
            g = (double[][]) hg[1];
        }
        final double[] mean = new double[dim];
        double count = 0;
        for (int i = 0; i < n; i++) {
            count += m[i];
            for (int c = 0; c < dim; c++) {
                mean[c] += h[i][c] * m[i];
            }
        }
        for (int c = 0; c < dim; c++) {
            mean[c] /= Math.max(count, 1.0);
        }
        return dense("lz.out.1", relu(dense("lz.out.0", mean)));
    }

    private Object[] lgeb(String name, double[][] h, double[][] g, double[] m, boolean last) {
        final int n = points, d = dim, dm = msg;
        final double[] W0 = w.get(name + ".e0.W"), b0 = w.get(name + ".e0.b");
        final double[][] hi = new double[n][], hj = new double[n][];
        for (int i = 0; i < n; i++) {
            hi[i] = project(name + ".e0", h[i], 0);
            hj[i] = project(name + ".e0", h[i], d);
        }
        final double[][] a = new double[n][n];
        final double[][] agg = new double[n][dm];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double norm = g[i][i] + g[j][j] - 2.0 * g[i][j];
                final double pn = psi(norm), pg = psi(g[i][j]);
                final double[] e = new double[dm];
                for (int o = 0; o < dm; o++) {
                    e[o] = Math.max(hi[i][o] + hj[j][o] + pn * W0[2 * d * dm + o] + pg * W0[(2 * d + 1) * dm + o] + b0[o], 0);
                }
                final double[] mm = relu(dense(name + ".e1", e));
                final double pm = i == j ? 0 : m[i] * m[j];
                final double wij = 1.0 / (1.0 + Math.exp(-dense(name + ".m", mm)[0]));
                for (int o = 0; o < dm; o++) {
                    agg[i][o] += mm[o] * wij * pm;
                }
                if (!last) {   // the last block moves no four-vector: nothing reads them after it
                    final double phi = dense(name + ".x1", relu(dense(name + ".x0", mm)))[0];
                    a[i][j] = (i == j ? 1 : 0) + coef * phi * pm;
                }
            }
        }
        final double[][] h2 = new double[n][];
        for (int i = 0; i < n; i++) {
            final double[] cat = new double[d + dm];
            System.arraycopy(h[i], 0, cat, 0, d);
            System.arraycopy(agg[i], 0, cat, d, dm);
            final double[] up = dense(name + ".h1", relu(dense(name + ".h0", cat)));
            h2[i] = new double[d];
            for (int c = 0; c < d; c++) {
                h2[i][c] = (h[i][c] + up[c]) * m[i];
            }
        }
        if (last) {
            return new Object[] {h2, g};
        }
        final double[][] ag = new double[n][n], g2 = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int k2 = 0; k2 < n; k2++) {
                double s = 0;
                for (int j = 0; j < n; j++) {
                    s += a[i][j] * g[j][k2];
                }
                ag[i][k2] = s;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int l = 0; l < n; l++) {
                double s = 0;
                for (int k2 = 0; k2 < n; k2++) {
                    s += ag[i][k2] * a[l][k2];
                }
                g2[i][l] = s;
            }
        }
        return new Object[] {h2, g2};
    }

    /** x times the k-th of an Eq2to2 layer's 15 maps (rows k C .. (k + 1) C of its weight). */
    private static double[] map(double[] W, int k, int c, int co, double[] x) {
        final double[] y = new double[co];
        for (int i = 0; i < c; i++) {
            final double xi = x[i];
            if (xi == 0) {
                continue;
            }
            final int row = (k * c + i) * co;
            for (int o = 0; o < co; o++) {
                y[o] += xi * W[row + o];
            }
        }
        return y;
    }

    /** PELICAN, as sphere_ml's forward: the products' embedding, Eq2to2 layers, the diagonal's and the whole mean. */
    private double[] pelican(double[][] g, double[] m) {
        final int n = points, c = channels;
        double nreal = 0, mj = 0;
        for (int i = 0; i < n; i++) {
            nreal += m[i];
        }
        nreal = Math.max(nreal, 1.0);
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) {
                s += g[i][j];
            }
            mj += s;
        }
        final double rel = nreal * nreal / (mj + 1e-12);
        double[][][] t = new double[n][n][];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double[] e = relu(dense("pe.in", new double[] {psi(g[i][j]), psi(g[i][j] * rel)}));
                final double mm = m[i] * m[j];
                for (int o = 0; o < c; o++) {
                    e[o] *= mm;
                }
                t[i][j] = e;
            }
        }
        for (int l = 0; l < layers; l++) {
            t = eq2to2("pe" + l, t, nreal, m);
        }
        final double[] sd = new double[c], sa = new double[c];
        for (int i = 0; i < n; i++) {
            for (int o = 0; o < c; o++) {
                sd[o] += t[i][i][o];
            }
        }
        for (int i = 0; i < n; i++) {
            final double[] row = new double[c];
            for (int j = 0; j < n; j++) {
                for (int o = 0; o < c; o++) {
                    row[o] += t[i][j][o];
                }
            }
            for (int o = 0; o < c; o++) {
                sa[o] += row[o];
            }
        }
        final double[] cat = new double[2 * c];
        for (int o = 0; o < c; o++) {
            cat[o] = sd[o] / nreal;
            cat[c + o] = sa[o] / (nreal * nreal);
        }
        return dense("pe.out.1", relu(dense("pe.out.0", cat)));
    }

    /** One Eq2to2 layer: the 15 permutation-equivariant maps of a 2-tensor, added as sphere_ml's _eq2to2 adds them. */
    private double[][][] eq2to2(String name, double[][][] t, double nreal, double[] m) {
        final int n = points, c = channels;
        final double[] W = w.get(name + ".W"), b = w.get(name + ".b");
        final int co = b.length;
        final double[][] d = new double[n][], r = new double[n][c], col = new double[n][c];
        final double[] sd = new double[c], sa = new double[c];
        for (int i = 0; i < n; i++) {
            d[i] = t[i][i].clone();
            for (int j = 0; j < n; j++) {
                for (int o = 0; o < c; o++) {
                    r[i][o] += t[i][j][o];
                    col[j][o] += t[i][j][o];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int o = 0; o < c; o++) {
                r[i][o] /= nreal;
                col[i][o] /= nreal;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int o = 0; o < c; o++) {
                sd[o] += d[i][o];
                sa[o] += r[i][o];
            }
        }
        for (int o = 0; o < c; o++) {
            sd[o] /= nreal;
            sa[o] /= nreal;
        }
        final double[] sdW3 = map(W, 3, c, co, sd), saW6 = map(W, 6, c, co, sa);
        final double[] sdW13 = map(W, 13, c, co, sd), saW14 = map(W, 14, c, co, sa);
        final double[] every = new double[co];
        for (int o = 0; o < co; o++) {
            every[o] = sdW13[o] + saW14[o] + b[o];
        }
        final double[][] onDiag = new double[n][co], rows = new double[n][co], cols = new double[n][co];
        for (int i = 0; i < n; i++) {
            final double[] dW2 = map(W, 2, c, co, d[i]), rW4 = map(W, 4, c, co, r[i]), cW5 = map(W, 5, c, co, col[i]);
            final double[] cW7 = map(W, 7, c, co, col[i]), rW8 = map(W, 8, c, co, r[i]), dW11 = map(W, 11, c, co, d[i]);
            final double[] cW9 = map(W, 9, c, co, col[i]), rW10 = map(W, 10, c, co, r[i]), dW12 = map(W, 12, c, co, d[i]);
            for (int o = 0; o < co; o++) {
                onDiag[i][o] = dW2[o] + rW4[o] + cW5[o] + (sdW3[o] + saW6[o]);
                rows[i][o] = cW7[o] + rW8[o] + dW11[o];
                cols[i][o] = cW9[o] + rW10[o] + dW12[o];
            }
        }
        final double[][][] out = new double[n][n][];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double[] a0 = map(W, 0, c, co, t[i][j]), a1 = map(W, 1, c, co, t[j][i]);
                final double mm = m[i] * m[j];
                final double[] y = new double[co];
                for (int o = 0; o < co; o++) {
                    final double v = a0[o] + a1[o] + onDiag[i][o] * (i == j ? 1 : 0) + rows[i][o] + cols[j][o] + every[o];
                    y[o] = Math.max(v, 0) * mm;
                }
                out[i][j] = y;
            }
        }
        return out;
    }

    public double[][] logits(Path dataset, int rows) throws IOException {
        try (Spx.Reader r = new Spx.Reader(dataset)) {
            final Map<String, String> meta = r.meta();
            final int n = Integer.parseInt(meta.getOrDefault("Jets", "0"));
            final int P = Integer.parseInt(meta.getOrDefault("Points", "0"));
            final int F = meta.getOrDefault("Features", "").isEmpty() ? 0 : meta.get("Features").split(",").length;
            final int H = meta.getOrDefault("HighLevel", "").isEmpty() ? 0 : meta.get("HighLevel").split(",").length;
            final double[] x = P > 0 && r.has("x") ? r.f64("x") : null, mask = r.has("mask") ? r.f64("mask") : null;
            final double[] hl = H > 0 && r.has("hl") ? r.f64("hl") : null;
            final int count = Math.min(n, rows < 0 ? n : rows);
            final List<double[]> out = new ArrayList<>(count);
            for (int e = 0; e < count; e++) {
                double[][] xi = new double[P][F];
                final double[] mi = new double[P];
                for (int p = 0; p < P; p++) {
                    if (x != null) {
                        System.arraycopy(x, (e * P + p) * F, xi[p], 0, F);
                    }
                    mi[p] = mask == null ? 0 : mask[e * P + p];
                }
                final double[] h = hl == null ? null : Arrays.copyOfRange(hl, e * H, (e + 1) * H);
                out.add(logits(xi, mi, h));
            }
            return out.toArray(double[][]::new);
        }
    }

    @Override
    public String toString() {
        return name + " (" + model + ", " + nClasses + " classes, " + (points > 0 ? points + " particles x " + features
            + " features" : highLevel + " high-level features") + ", trained with " + trainedWith
            + (calibration.isEmpty() ? "" : ", " + calibration + " calibration") + ")";
    }
}
